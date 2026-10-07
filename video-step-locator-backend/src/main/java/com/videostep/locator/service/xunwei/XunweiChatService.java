package com.videostep.locator.service.xunwei;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videostep.locator.entity.XunweiMessage;
import com.videostep.locator.entity.XunweiSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「寻味」AI 对话搜索编排服务。
 *
 * 双轮 LLM + 工具：
 *   Round1 规划（区分 chat / search + 生成搜索关键词）
 *   → 多源搜索工具（B站 wbi + yt-dlp）
 *   → Round2 推荐（把候选喂回 LLM 挑选并给出推荐理由）
 *  M4 将注入跨会话记忆与滚动摘要。
 */
@Service
@Slf4j
public class XunweiChatService {

    /** 单次发给 LLM 的最大历史消息数（超出部分由 M4 滚动摘要接管） */
    private static final int HISTORY_WINDOW = 20;
    /** 最多推荐视频数 */
    private static final int MAX_RECOMMEND = 3;

    private final DeepSeekClient deepSeekClient;
    private final XunweiSessionService sessionService;
    private final XunweiSearchService searchService;
    private final XunweiMemoryService memoryService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public XunweiChatService(DeepSeekClient deepSeekClient,
                             XunweiSessionService sessionService,
                             XunweiSearchService searchService,
                             XunweiMemoryService memoryService) {
        this.deepSeekClient = deepSeekClient;
        this.sessionService = sessionService;
        this.searchService = searchService;
        this.memoryService = memoryService;
    }

    /** 「寻味」静态系统提示词（角色 + 输出契约；M4 会拼接动态用户画像） */
    static final String SYSTEM_PROMPT = """
            你是「寻味」，一位专业的中文烹饪视频搜索助手。你的职责是通过对话帮用户找到合适的做菜视频，并把可直接下载的视频地址推荐给用户。

            【你的能力】
            - 多轮对话：先理解用户想做什么菜，必要时主动追问（口味、忌口、食材、用时、人数、厨具、厨艺水平）。
            - 搜索推荐：当用户明确需要找做菜视频时，构造 1~3 组高质量中文搜索关键词（含菜名、做法风格、场景），不要编造任何视频链接。
            - 推荐把关：只推荐真实存在、可下载的做菜视频；优先清晰完整、时长适中（5~25 分钟）的教学视频。

            【输出格式（严格遵守，仅输出 JSON，不要输出任何其它文字或 Markdown）】
            情况一：用户只是闲聊或问做菜知识（不涉及找视频）：
            {"action":"chat","reply":"你的回复","queries":[]}
            情况二：用户想要找做菜视频：
            {"action":"search","reply":"简短回应（可空）","queries":[{"keyword":"搜索关键词","platform":"auto","count":5}]}
            - platform 取值为 auto / bilibili / youtube
            - 若信息不足需先追问偏好，reply 里说明，queries 可为空数组。
            """;

    /** Round2 推荐系统提示词（M3） */
    static final String ROUND2_SYSTEM_PROMPT = """
            你是「寻味」，一位专业的中文烹饪视频搜索助手。系统已经帮你搜索到了一批候选做菜视频（JSON），
            请根据用户的做菜需求，从候选中挑选最合适的 1~3 个推荐，并为每个视频写一句推荐理由（贴合口味/忌口/场景/时长等）。

            【要求】
            - 只能从候选列表里选，严禁编造列表之外的视频或链接。
            - 优先清晰完整、时长适中（5~25 分钟）的做菜教学视频。
            - 如果候选都不合适，reply 里说明原因并给出调整建议，videos 返回空数组。

            【输出格式（严格遵守，仅输出 JSON，不要输出任何其它文字）】
            {"reply":"你的推荐语","videos":[{"title":"标题","url":"视频地址","uploader":"UP主","duration":秒数,"viewCount":数字,"thumbnail":"封面","reason":"推荐理由"}]}
            """;

    /** 会话滚动摘要提示词（M4） */
    static final String SUMMARY_PROMPT = """
            你是对话摘要助手。把下面这段「寻味」烹饪助手与用户的早期对话，压缩成一段简洁的中文摘要，
            保留关键信息（用户想做的菜、口味/忌口偏好、已推荐过的视频标题等），以便后续对话保持上下文。
            只输出摘要文本，不要输出其它内容。
            """;

    /**
     * 处理一条用户消息（无状态版本，保留兼容）。
     */
    public ChatResult chat(Long userId, String userMessage) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content",
                SYSTEM_PROMPT + memoryService.profileToPrompt(userId)));
        messages.add(Map.of("role", "user", "content", userMessage));
        ChatResult r1 = doRound1(userId, messages);
        if ("search".equals(r1.action())) {
            return completeSearch(userId, userMessage, r1.queries());
        }
        return r1;
    }

    /**
     * 会话内发消息：属主校验 → 载入历史 →（滚动摘要）→ 注入画像 → Round1 →（搜索+Round2）→ 落库 → 画像抽取。
     */
    public ChatResult chatInSession(Long userId, Long sessionId, String userMessage) {
        XunweiSession session = sessionService.getOwnedSession(userId, sessionId);

        List<XunweiMessage> history = sessionService.getMessages(userId, sessionId);

        // 滚动摘要：历史超出窗口时，把最旧部分压缩成会话摘要（仅一次）
        String summary = session.getSummary();
        List<XunweiMessage> window;
        if (history.size() > HISTORY_WINDOW) {
            window = history.subList(history.size() - HISTORY_WINDOW, history.size());
            if (summary == null || summary.isBlank()) {
                String s = summarizeHistory(userId, history.subList(0, history.size() - HISTORY_WINDOW));
                if (s != null && !s.isBlank()) {
                    summary = s;
                    sessionService.updateSummary(userId, sessionId, s);
                }
            }
        } else {
            window = history;
        }

        StringBuilder sys = new StringBuilder(SYSTEM_PROMPT);
        if (summary != null && !summary.isBlank()) {
            sys.append("\n\n【本会话早期历史摘要】\n").append(summary);
        }
        sys.append(memoryService.profileToPrompt(userId));

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", sys.toString()));
        for (XunweiMessage m : window) {
            messages.add(Map.of("role", m.getRole(), "content", m.getContent()));
        }
        messages.add(Map.of("role", "user", "content", userMessage));

        // 用户消息先落库
        sessionService.addMessage(sessionId, "user", userMessage, null);

        ChatResult r1 = doRound1(userId, messages);
        ChatResult result;
        if ("search".equals(r1.action())) {
            result = completeSearch(userId, userMessage, r1.queries());
        } else {
            result = r1;
        }

        // 助手回复落库（附推荐视频 JSON）
        sessionService.addMessage(sessionId, "assistant", result.reply(), toVideosJson(result.videos()));

        // 异步抽取用户画像（跨会话记忆）
        memoryService.extractAndSaveAsync(userId, userMessage, result.reply());
        return result;
    }

    /** 把超出窗口的早期历史压缩成摘要（M4） */
    private String summarizeHistory(Long userId, List<XunweiMessage> old) {
        StringBuilder sb = new StringBuilder();
        for (XunweiMessage m : old) {
            sb.append(m.getRole()).append(": ").append(m.getContent()).append("\n");
        }
        try {
            return deepSeekClient.chat(List.of(
                    Map.of("role", "system", "content", SUMMARY_PROMPT),
                    Map.of("role", "user", "content", sb.toString())), false);
        } catch (Exception e) {
            log.warn("[寻味] 滚动摘要失败 userId={}: {}", userId, e.getMessage());
            return null;
        }
    }

    /** Round1：调 LLM 生成规划（chat / search） */
    private ChatResult doRound1(Long userId, List<Map<String, String>> messages) {
        String raw;
        try {
            raw = deepSeekClient.chat(messages, true);
        } catch (RuntimeException e) {
            log.error("[寻味] DeepSeek 调用失败 userId={}: {}", userId, e.getMessage());
            return new ChatResult("error", "抱歉，AI 暂时开小差了，请稍后再试。", List.of());
        }
        log.info("[寻味] userId={} round1={}", userId, raw);
        return parseRound1(raw);
    }

    /** 执行多源搜索 + Round2 推荐（M3） */
    private ChatResult completeSearch(Long userId, String userMessage, List<Map<String, Object>> queries) {
        log.info("[寻味] 执行搜索 userId={} queries={}", userId, queries);
        List<VideoCandidate> candidates;
        try {
            candidates = searchService.search(queries);
        } catch (Exception e) {
            log.error("[寻味] 搜索异常 userId={}", userId, e);
            return new ChatResult("search", "抱歉，视频搜索暂时不可用，请稍后再试。", queries);
        }
        if (candidates.isEmpty()) {
            return new ChatResult("search",
                    "暂时没有搜到合适的做菜视频，可能是平台风控或网络原因。你可以换个更具体的关键词再试，或先聊聊你想做什么菜、口味偏好。",
                    queries);
        }

        // P2 反馈闭环：用画像/行为记忆给候选打分重排，并把画像注入 Round2 推荐
        List<Map<String, Object>> ranked = memoryService.scoreAndRankCandidates(userId, candidates);
        log.info("[寻味] userId={} 候选重排 top5={}", userId,
                ranked.stream().limit(5).map(m -> m.get("matchScore") + "|" + m.get("title")).toList());
        String candidatesJson;
        try {
            candidatesJson = objectMapper.writeValueAsString(ranked);
        } catch (Exception e) {
            candidatesJson = candidates.toString();
        }
        String profile = memoryService.profileToPrompt(userId);
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", ROUND2_SYSTEM_PROMPT));
        messages.add(Map.of("role", "user", "content",
                "用户的做菜需求：" + userMessage
                        + (profile.isBlank() ? "" : "\n\n" + profile)
                        + "\n\n候选视频列表（已按贴合用户偏好的程度从高到低排序，matchScore/matchHint 为打分与理由）：\n"
                        + candidatesJson + "\n\n请从中挑选推荐。"));

        String raw;
        try {
            raw = deepSeekClient.chat(messages, true);
        } catch (RuntimeException e) {
            log.error("[寻味] Round2 调用失败 userId={}: {}", userId, e.getMessage());
            return new ChatResult("search", "抱歉，AI 推荐暂时不可用，请稍后再试。", queries);
        }
        log.info("[寻味] userId={} round2={}", userId, raw);
        return parseRound2(raw, candidates, queries);
    }

    /** 解析 Round2 推荐 JSON，过滤不在候选内的幻觉链接 */
    private ChatResult parseRound2(String raw, List<VideoCandidate> candidates,
                                   List<Map<String, Object>> queries) {
        try {
            JsonNode node = objectMapper.readTree(raw);
            String reply = node.path("reply").asText("");
            List<Map<String, Object>> videos = new ArrayList<>();
            Set<String> allowed = new HashSet<>();
            for (VideoCandidate c : candidates) allowed.add(c.url());

            JsonNode vs = node.path("videos");
            if (vs.isArray()) {
                for (JsonNode v : vs) {
                    String url = v.path("url").asText("");
                    if (url.isBlank() || !allowed.contains(url)) continue; // 过滤幻觉链接
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("title", v.path("title").asText(""));
                    m.put("url", url);
                    m.put("uploader", v.path("uploader").asText(""));
                    m.put("duration", v.path("duration").isNumber() ? v.path("duration").asDouble() : 0);
                    m.put("viewCount", v.path("viewCount").isNumber() ? v.path("viewCount").asLong() : 0);
                    m.put("thumbnail", v.path("thumbnail").asText(""));
                    m.put("reason", v.path("reason").asText(""));
                    videos.add(m);
                    if (videos.size() >= MAX_RECOMMEND) break;
                }
            }
            return new ChatResult("search", reply, queries, videos);
        } catch (Exception e) {
            log.warn("[寻味] Round2 解析失败 raw={}", raw, e);
            return new ChatResult("search", "抱歉，AI 推荐解析失败，请重试。", queries);
        }
    }

    private String toVideosJson(List<Map<String, Object>> videos) {
        if (videos == null || videos.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(videos);
        } catch (Exception e) {
            return null;
        }
    }

    /** 解析 Round1 规划输出 JSON（容错：解析失败回退为 chat 类型） */
    private ChatResult parseRound1(String raw) {
        try {
            JsonNode node = objectMapper.readTree(raw);
            String action = node.path("action").asText("chat");
            String reply = node.path("reply").asText("");
            List<Map<String, Object>> queries = new ArrayList<>();
            JsonNode qs = node.path("queries");
            if (qs.isArray()) {
                for (JsonNode q : qs) {
                    queries.add(Map.of(
                            "keyword", q.path("keyword").asText(""),
                            "platform", q.path("platform").asText("auto"),
                            "count", q.path("count").asInt(5)));
                }
            }
            return new ChatResult(action, reply, queries);
        } catch (Exception e) {
            log.warn("[寻味] 解析 LLM 输出失败，原文={}", raw, e);
            return new ChatResult("error", "抱歉，AI 回复解析失败，请重试。", List.of());
        }
    }
}
