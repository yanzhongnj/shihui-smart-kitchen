package com.videostep.locator.service.xunwei;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.videostep.locator.entity.UserPreference;
import com.videostep.locator.entity.XunweiMemory;
import com.videostep.locator.entity.XunweiMemorySetting;
import com.videostep.locator.repository.UserPreferenceRepository;
import com.videostep.locator.repository.XunweiMemoryRepository;
import com.videostep.locator.repository.XunweiMemorySettingRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「寻味」跨会话长期记忆（用户烹饪画像）—— 偏好自进化引擎。
 *
 * 信号来源：① 对话抽取（LLM）② 显式设置（user_preferences）③ 行为反馈（下载/收藏/评价）。
 * 记忆演化：增量合并（mergeUpsert）——同义内容累积置信度，矛盾内容交 LLM 消解（有每日上限），
 * 用户删除过的键软删除抑制重复抽取；每次请求把画像+行为+显式设置注入 system prompt。
 */
@Service
@Slf4j
public class XunweiMemoryService {

    private static final String[] PROFILE_KEYS =
            {"taste", "taboo", "cuisine", "skill", "scene", "notes",
             "liked_dishes", "disliked_dishes", "preferred_ingredients",
             "avoided_ingredients", "scenes"};

    /** 行为记忆（liked_videos / disliked_videos）容量上限 */
    private static final int MAX_BEHAVIOR_ITEMS = 20;

    private static final Set<String> NEGATIVE_WORDS =
            Set.of("不", "忌", "别", "少", "戒", "避免", "不要", "不喜欢", "怕");

    private static final String EXTRACT_PROMPT = """
            你是用户烹饪画像抽取器。请从用户的对话中提取烹饪偏好事实，只输出 JSON（不要输出其它文字或 Markdown）。
            输出格式（严格，所有字段都是字符串，没有的字段留空字符串""）：
            {"taste":"口味偏好，如 清淡/微辣/重口","taboo":"忌口/过敏，如 不吃辣/忌花生/不吃香菜","cuisine":"偏好菜系，如 川菜/粤菜/家常菜","skill":"厨艺水平，如 新手/会做饭/熟练","scene":"使用场景，如 快手菜/减脂/宴客/一人食","notes":"其他有用信息","liked_dishes":"明确喜欢/想吃的菜","disliked_dishes":"明确不喜欢/忌口的菜或食材","preferred_ingredients":"常用/喜欢的食材","avoided_ingredients":"避免/不吃的食材","scenes":"场景偏好"}
            提取规则：
            - 用户明确表达的都算，包括否定句和偏好句：如"我不吃辣"→taboo="不吃辣"；"喜欢清淡"→taste="清淡"；"想做鱼香肉丝"→liked_dishes="鱼香肉丝"；"忌花生"→taboo="忌花生"。
            - 没有提到的字段留空字符串""。
            - 只基于对话内容提取，不要编造对话里没有的信息。
            """;

    private final XunweiMemoryRepository memoryRepository;
    private final XunweiMemorySettingRepository settingsRepository;
    private final UserPreferenceRepository preferenceRepository;
    private final DeepSeekClient deepSeekClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService executor;

    /** 每用户每日 LLM 冲突消解调用上限（key: LocalDate+userId） */
    private final Map<String, AtomicInteger> mergeCounters = new ConcurrentHashMap<>();

    @Value("${xunwei.memory.merge-daily-limit:10}")
    private int mergeDailyLimit;

    /** 批量合并（3.2）：攒满多少轮触发一次批量抽取；闲置超过 IDLE_FLUSH_MS 强制抽取 */
    private static final int MAX_BUFFER_TURNS = 6;
    private static final long IDLE_FLUSH_MS = 10 * 60 * 1000L;
    /** 画像注入缓存（3.4）：30s TTL，避免每次请求重复查询拼串 */
    private static final long PROFILE_CACHE_TTL_MS = 30_000L;

    /** 画像注入串缓存（userId → 最近拼装结果 + 时间戳） */
    private final Map<Long, ProfileCacheEntry> profileCache = new ConcurrentHashMap<>();
    /** 会话对话缓冲（userId → 攒着的对话轮次，会话结束/攒满/闲置时批量抽取） */
    private final Map<Long, TurnBuffer> extractBuffers = new ConcurrentHashMap<>();

    /** 画像注入串缓存条目 */
    private static final class ProfileCacheEntry {
        final String prompt;
        final long createdAt;

        ProfileCacheEntry(String prompt) {
            this.prompt = prompt;
            this.createdAt = System.currentTimeMillis();
        }
    }

    /** 会话对话缓冲（按用户攒着待批量抽取的对话轮次） */
    private static final class TurnBuffer {
        final StringBuilder content = new StringBuilder();
        long lastActivity = System.currentTimeMillis();
        int turns = 0;
    }

    public XunweiMemoryService(XunweiMemoryRepository memoryRepository,
                               XunweiMemorySettingRepository settingsRepository,
                               UserPreferenceRepository preferenceRepository,
                               DeepSeekClient deepSeekClient) {
        this.memoryRepository = memoryRepository;
        this.settingsRepository = settingsRepository;
        this.preferenceRepository = preferenceRepository;
        this.deepSeekClient = deepSeekClient;
        AtomicInteger n = new AtomicInteger(1);
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "xunwei-memory-" + n.getAndIncrement());
            t.setDaemon(true);
            return t;
        });
    }

    /** 加载用户全部有效画像（不含软删除） */
    public Map<String, String> loadProfile(Long userId) {
        Map<String, String> map = new LinkedHashMap<>();
        for (XunweiMemory m : memoryRepository.findActive(userId)) {
            map.put(m.getMemKey(), m.getMemValue());
        }
        return map;
    }

    /** 生成注入 system prompt 的画像段落（画像 + 行为反馈 + 显式设置，无画像时返回空串）。
     *  带 30s 内存缓存（3.4）：命中缓存直接返回，避免每次请求重复查询拼串。
     *  任何记忆写入（mergeUpsert/行为/清空/删改）都会失效对应缓存。 */
    public String profileToPrompt(Long userId) {
        ProfileCacheEntry cached = profileCache.get(userId);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.createdAt < PROFILE_CACHE_TTL_MS) {
            return cached.prompt;
        }
        String prompt = buildProfileToPrompt(userId);
        profileCache.put(userId, new ProfileCacheEntry(prompt));
        return prompt;
    }

    /** 实际拼装画像注入串（无缓存逻辑；由 profileToPrompt 缓存包装调用） */
    private String buildProfileToPrompt(Long userId) {
        StringBuilder sb = new StringBuilder();
        List<XunweiMemory> all = memoryRepository.findActive(userId);
        Map<String, String> profile = new LinkedHashMap<>();
        Map<String, Object> behaviors = new LinkedHashMap<>();
        for (XunweiMemory m : all) {
            if ("behavior".equals(m.getMemType())) {
                behaviors.put(m.getMemKey(), m.getMemValue());
            } else {
                profile.put(m.getMemKey(), m.getMemValue());
            }
        }
        if (!profile.isEmpty()) {
            try {
                sb.append("\n\n【用户画像（跨会话记忆，来自历史对话，推荐时请贴合）】\n")
                        .append(objectMapper.writeValueAsString(profile));
            } catch (Exception ignored) {
            }
        }
        if (!behaviors.isEmpty()) {
            try {
                sb.append("\n\n【用户行为反馈（来自下载/收藏/评价，代表真实偏好，推荐优先贴合）】\n")
                        .append(objectMapper.writeValueAsString(behaviors));
            } catch (Exception ignored) {
            }
        }
        sb.append(explicitProfileToPrompt(userId));
        return sb.toString();
    }

    /**
     * 用户显式设置（「口味/忌口设置」页主动填写）→ 最高优先级画像段落。
     * 仅当用户确实填写过设置才注入（未设置返回空串，避免默认值误导 LLM）。
     */
    public String explicitProfileToPrompt(Long userId) {
        UserPreference p = preferenceRepository.findByUserId(userId);
        if (p == null) return "";
        Map<String, Object> map = new LinkedHashMap<>();
        if (p.getSpiceLevel() != null) map.put("辣度(1-5)", p.getSpiceLevel());
        if (notBlank(p.getTabooAllergens())) map.put("过敏原", p.getTabooAllergens());
        if (notBlank(p.getTabooIngredients())) map.put("忌口食材", p.getTabooIngredients());
        if (notBlank(p.getPreferredTastes())) map.put("偏好口味", p.getPreferredTastes());
        if (notBlank(p.getPreferredCuisines())) map.put("偏好菜系", p.getPreferredCuisines());
        if (p.getMaxCalories() != null) map.put("最大热量(千卡)", p.getMaxCalories());
        if (notBlank(p.getNotes())) map.put("备注", p.getNotes());
        if (map.isEmpty()) return "";
        try {
            return "\n\n【用户显式设置（用户主动填写，最高优先级，推荐与告警必须严格遵守）】\n"
                    + objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return "";
        }
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /** 异步抽取并保存用户画像（不阻塞主流程；偏好自进化关闭时不抽取）。
     *  批量合并（3.2）：不再每轮调 LLM，改为把「用户消息+AI回复」攒进该用户的内存缓冲，
     *  在「会话结束（新建/删除会话）/攒满 {@link #MAX_BUFFER_TURNS} 轮/闲置超时」时一次性批量抽取合并，省成本。 */
    public void extractAndSaveAsync(Long userId, String userMessage, String assistantReply) {
        if (!isAutoEvolveEnabled(userId)) return;
        TurnBuffer buf = extractBuffers.computeIfAbsent(userId, k -> new TurnBuffer());
        synchronized (buf) {
            buf.turns++;
            buf.lastActivity = System.currentTimeMillis();
            buf.content.append("用户消息：").append(userMessage == null ? "" : userMessage).append("\n")
                    .append("AI回复：").append(assistantReply == null ? "" : assistantReply).append("\n\n");
            // 攒满阈值：即使会话未结束也先抽一次，避免缓冲无限增长/延迟过久
            if (buf.turns >= MAX_BUFFER_TURNS) {
                extractBuffers.remove(userId);
                submitBatchExtraction(userId, buf);
            }
        }
    }

    /** 会话结束（新建/删除会话）时冲刷该用户攒下的对话缓冲，触发一次性批量抽取 */
    public void flushExtraction(Long userId) {
        TurnBuffer buf = extractBuffers.remove(userId);
        if (buf == null) return;
        synchronized (buf) {
            submitBatchExtraction(userId, buf);
        }
    }

    /** 定时冲刷：闲置超过 {@link #IDLE_FLUSH_MS} 的缓冲强制抽取（防内存堆积/防丢记忆） */
    @Scheduled(fixedDelayString = "${xunwei.memory.flush-schedule-ms:300000}")
    public void flushIdleExtractionBuffers() {
        long now = System.currentTimeMillis();
        extractBuffers.forEach((uid, buf) -> {
            synchronized (buf) {
                if (now - buf.lastActivity >= IDLE_FLUSH_MS) {
                    extractBuffers.remove(uid);
                    submitBatchExtraction(uid, buf);
                }
            }
        });
    }

    /** 批量抽取执行（异步）：把一整段会话文本交 LLM 抽取 → 空结果重试 → 启发式兜底 → 增量合并落库 */
    private void submitBatchExtraction(Long userId, TurnBuffer buf) {
        if (!isAutoEvolveEnabled(userId)) return;
        final String conversation = buf.content.toString();
        final int turns = buf.turns;
        executor.submit(() -> {
            try {
                List<Map<String, String>> messages = List.of(
                        Map.of("role", "system", "content", EXTRACT_PROMPT),
                        Map.of("role", "user", "content", conversation));
                String raw = deepSeekClient.chat(messages, true);
                JsonNode node = objectMapper.readTree(raw);
                // LLM 全空 → 重试一次（模型偶发返回空字段）
                if (!hasAnyField(node)) {
                    try {
                        raw = deepSeekClient.chat(messages, true);
                        node = objectMapper.readTree(raw);
                    } catch (Exception ignored) {
                    }
                }
                Map<String, String> extracted = new LinkedHashMap<>();
                for (String key : PROFILE_KEYS) {
                    String v = node.path(key).asText("").trim();
                    if (!v.isBlank()) extracted.put(key, v);
                }
                // 关键词启发式兜底：LLM 未抽到的明确信号（保证自进化不空转）
                for (Map.Entry<String, String> e : heuristicExtract(conversation).entrySet()) {
                    extracted.putIfAbsent(e.getKey(), e.getValue());
                }
                for (Map.Entry<String, String> e : extracted.entrySet()) {
                    mergeUpsert(userId, e.getKey(), e.getValue(), "chat", "profile");
                }
                log.info("[寻味] 批量画像抽取完成 userId={} turns={} fields={} raw={}",
                        userId, turns, extracted.keySet(), raw);
            } catch (Exception e) {
                log.debug("[寻味] 批量画像抽取失败: {}", e.getMessage());
            }
        });
    }

    private boolean hasAnyField(JsonNode node) {
        for (String key : PROFILE_KEYS) {
            if (!node.path(key).asText("").trim().isBlank()) return true;
        }
        return false;
    }

    /** 关键词启发式抽取（LLM 兜底，保证明确信号一定被记录） */
    private Map<String, String> heuristicExtract(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        if (text == null || text.isBlank()) return out;
        String t = text.replaceAll("\\s+", "");
        // 口味
        if (t.contains("清淡")) out.put("taste", "清淡");
        else if (t.contains("重辣")) out.put("taste", "重辣");
        else if (t.contains("麻辣")) out.put("taste", "麻辣");
        else if (t.contains("微辣")) out.put("taste", "微辣");
        else if (t.contains("中辣")) out.put("taste", "中辣");
        else if (t.contains("不吃辣") || t.contains("不能吃辣") || t.contains("忌辣")) out.put("taste", "不辣");
        // 忌口
        if (t.contains("不吃辣") || t.contains("不能吃辣") || t.contains("忌辣")) out.put("taboo", "不吃辣");
        else if (t.contains("不吃香菜") || t.contains("忌香菜")) out.put("taboo", "不吃香菜");
        else if (t.contains("素食")) out.put("taboo", "素食");
        if (t.contains("少油")) out.merge("taste", "少油", (a, b) -> a + "，" + b);
        // 菜系
        if (t.contains("川菜")) out.put("cuisine", "川菜");
        else if (t.contains("粤菜")) out.put("cuisine", "粤菜");
        else if (t.contains("湘菜")) out.put("cuisine", "湘菜");
        else if (t.contains("鲁菜")) out.put("cuisine", "鲁菜");
        else if (t.contains("东北菜")) out.put("cuisine", "东北菜");
        else if (t.contains("江浙菜")) out.put("cuisine", "江浙菜");
        else if (t.contains("家常菜")) out.put("cuisine", "家常菜");
        else if (t.contains("西餐")) out.put("cuisine", "西餐");
        // 厨艺
        if (t.contains("新手") || t.contains("小白") || t.contains("不会做饭")) out.put("skill", "新手");
        else if (t.contains("熟练") || t.contains("拿手") || t.contains("会做饭")) out.put("skill", "会做饭");
        // 场景
        if (t.contains("快手") || t.contains("简单") || t.contains("快速")) out.put("scene", "快手菜");
        else if (t.contains("减脂") || t.contains("减肥") || t.contains("健身")) out.put("scene", "减脂");
        else if (t.contains("一人食") || t.contains("一个人")) out.put("scene", "一人食");
        else if (t.contains("宴客") || t.contains("请客")) out.put("scene", "宴客");
        // 想做的菜
        Matcher m = Pattern.compile("(?:想吃|想做|做道|做一道|做一下|做个|来个)([\\u4e00-\\u9fa5A-Za-z0-9]{2,10})").matcher(t);
        if (m.find()) out.put("liked_dishes", m.group(1));
        return out;
    }

    /**
     * 增量合并写入（记忆演化核心）：
     * - 无旧记录 → 新建（置信度按来源定）
     * - 同义/包含 → 合并为更完整内容，命中次数+1，置信度上调
     * - 矛盾（如"不吃辣" vs "吃辣"）→ 交 LLM 消解（每日上限控制成本）
     * - 其余新增偏好 → 与旧内容并存（"；"分隔去重），避免互相覆盖丢失
     * - 显式来源（explicit）覆盖学习画像；用户删除过的键不再重复抽取
     */
    public void mergeUpsert(Long userId, String key, String value, String source, String memType) {
        if (value == null || value.isBlank()) return;
        profileCache.remove(userId); // 画像已变 → 失效注入串缓存
        XunweiMemory mem = memoryRepository.findOneByUserAndKey(userId, key);
        String val = value.trim();
        if (mem == null) {
            memoryRepository.insert(XunweiMemory.builder()
                    .userId(userId).memKey(key).memValue(val)
                    .memType(memType == null ? "profile" : memType)
                    .confidence(initialConfidence(source))
                    .source(source)
                    .hitCount(1)
                    .deleted(0)
                    .build());
            return;
        }
        if (mem.getDeleted() != null && mem.getDeleted() == 1) {
            return; // 用户曾删除 → 抑制重复抽取
        }
        String old = mem.getMemValue() == null ? "" : mem.getMemValue().trim();
        if ("explicit".equals(source)) {
            mem.setMemValue(val);
            mem.setSource("explicit");
            mem.setConfidence(95);
            mem.setHitCount((mem.getHitCount() == null ? 0 : mem.getHitCount()) + 1);
            memoryRepository.updateById(mem);
            return;
        }
        int hit = (mem.getHitCount() == null ? 0 : mem.getHitCount()) + 1;
        if (old.isEmpty() || containsPhrase(old, val)) {
            mem.setMemValue(val.length() >= old.length() ? val : old);
            mem.setConfidence(Math.min(95, 40 + hit * 10));
        } else if (looksContradictory(old, val)) {
            resolveConflictAsync(userId, key, old, val);
            mem.setConfidence(Math.min(95, 40 + hit * 10));
            mem.setHitCount(hit);
            memoryRepository.updateById(mem);
            return;
        } else {
            Set<String> set = new HashSet<>();
            for (String s : (old + "；" + val).split("[；;，,、]")) {
                String t = s.trim();
                if (!t.isEmpty()) set.add(t);
            }
            mem.setMemValue(String.join("；", set));
            mem.setConfidence(Math.min(95, 40 + hit * 10));
        }
        mem.setHitCount(hit);
        memoryRepository.updateById(mem);
    }

    /**
     * 行为信号 → 记忆（同步、不抛异常、不影响主流程）。
     * 正向（下载完成/收藏/好评≥4）→ liked_videos；负向（取消收藏/差评≤2）→ disliked_videos。
     * 值为 {title,count} JSON 数组，容量上限 {@link #MAX_BEHAVIOR_ITEMS}。
     */
    public void ingestBehavior(Long userId, String source, String title, boolean positive) {
        try {
            if (userId == null || title == null || title.isBlank()) return;
            if (!isAutoEvolveEnabled(userId)) return;
            profileCache.remove(userId); // 行为记忆已变 → 失效注入串缓存
            String key = positive ? "liked_videos" : "disliked_videos";
            XunweiMemory mem = memoryRepository.findOneByUserAndKey(userId, key);
            if (mem != null && mem.getDeleted() != null && mem.getDeleted() == 1) return;
            ArrayNode arr;
            try {
                arr = (ArrayNode) objectMapper.readTree(
                        mem == null || mem.getMemValue() == null ? "[]" : mem.getMemValue());
            } catch (Exception e) {
                arr = objectMapper.createArrayNode();
            }
            boolean found = false;
            for (int i = 0; i < arr.size(); i++) {
                JsonNode it = arr.get(i);
                if (title.equals(it.path("title").asText())) {
                    ((ObjectNode) it).put("count", it.path("count").asInt(1) + 1);
                    found = true;
                    break;
                }
            }
            if (!found) {
                arr.add(objectMapper.createObjectNode()
                        .put("title", title)
                        .put("count", 1));
            }
            while (arr.size() > MAX_BEHAVIOR_ITEMS) {
                arr.remove(0);
            }
            String newVal = objectMapper.writeValueAsString(arr);
            if (mem == null) {
                memoryRepository.insert(XunweiMemory.builder()
                        .userId(userId).memKey(key).memValue(newVal)
                        .memType("behavior").confidence(90).source(source)
                        .hitCount(1).deleted(0).build());
            } else {
                mem.setMemValue(newVal);
                mem.setSource(source);
                mem.setConfidence(90);
                mem.setHitCount((mem.getHitCount() == null ? 0 : mem.getHitCount()) + 1);
                memoryRepository.updateById(mem);
            }
            log.info("[寻味] 行为记忆 userId={} key={} source={} title={}", userId, key, source, title);
        } catch (Exception e) {
            log.warn("[寻味] 行为记忆写入失败: {}", e.getMessage());
        }
    }

    /** 矛盾消解：异步 LLM 决策（每日上限内），不阻塞主流程 */
    private void resolveConflictAsync(Long userId, String key, String oldVal, String newVal) {
        if (!isAutoEvolveEnabled(userId)) return;
        String dayKey = LocalDate.now() + ":" + userId;
        AtomicInteger c = mergeCounters.computeIfAbsent(dayKey, k -> new AtomicInteger());
        if (c.incrementAndGet() > mergeDailyLimit) {
            log.debug("[寻味] 冲突消解已达当日上限 userId={} key={}", userId, key);
            return;
        }
        executor.submit(() -> {
            try {
                String prompt = """
                        你是用户烹饪画像矛盾消解器。同一字段出现两条可能矛盾的信息，请判断保留哪条或如何合并。
                        只输出 JSON：{"action":"keep_old"|"keep_new"|"merge"|"keep_both","value":"最终字段值","reason":"理由"}
                        - keep_old：旧信息更可信；keep_new：新信息更可信（用户可能改变了偏好）
                        - merge：两者可共存；keep_both：无法判断，两者并存
                        - 显式设置(explicit)、行为来源(download/favorite/review)的信息通常更可信
                        字段：%s
                        旧值：%s
                        新值：%s
                        """.formatted(key, oldVal, newVal);
                String raw = deepSeekClient.chat(List.of(
                        Map.of("role", "system", "content", prompt)), true);
                JsonNode node = objectMapper.readTree(raw);
                String action = node.path("action").asText("");
                String v = node.path("value").asText("").trim();
                XunweiMemory mem = memoryRepository.selectOne(new LambdaQueryWrapper<XunweiMemory>()
                        .eq(XunweiMemory::getUserId, userId)
                        .eq(XunweiMemory::getMemKey, key));
                if (mem == null) return;
                if ("keep_new".equals(action) || "merge".equals(action)) {
                    if (!v.isEmpty()) {
                        mem.setMemValue(v);
                        mem.setSource("chat");
                    }
                }
                mem.setHitCount((mem.getHitCount() == null ? 0 : mem.getHitCount()) + 1);
                memoryRepository.updateById(mem);
                profileCache.remove(userId);
                log.info("[寻味] 冲突消解 userId={} key={} action={} raw={}", userId, key, action, raw);
            } catch (Exception e) {
                log.debug("[寻味] 冲突消解失败: {}", e.getMessage());
            }
        });
    }

    private int initialConfidence(String source) {
        if (source == null) return 50;
        return switch (source) {
            case "explicit" -> 95;
            case "download", "favorite", "review" -> 90;
            default -> 50;
        };
    }

    private boolean containsPhrase(String a, String b) {
        if (b == null || b.isEmpty()) return false;
        return a.contains(b) || b.contains(a);
    }

    private boolean looksContradictory(String a, String b) {
        boolean an = hasNegation(a), bn = hasNegation(b);
        if (an == bn) return false;
        String coreA = stripNegation(a), coreB = stripNegation(b);
        return coreA.equals(coreB)
                || (coreA.length() >= 2 && coreB.contains(coreA))
                || (coreB.length() >= 2 && coreA.contains(coreB));
    }

    private boolean hasNegation(String s) {
        for (String n : NEGATIVE_WORDS) {
            if (s.contains(n)) return true;
        }
        return false;
    }

    private String stripNegation(String s) {
        String r = s;
        for (String n : NEGATIVE_WORDS) {
            r = r.replace(n, "");
        }
        return r.trim();
    }

    public List<XunweiMemory> list(Long userId) {
        return memoryRepository.findActive(userId);
    }

    public void clear(Long userId) {
        memoryRepository.delete(new LambdaQueryWrapper<XunweiMemory>()
                .eq(XunweiMemory::getUserId, userId));
        profileCache.remove(userId);
        log.info("[寻味] 已清除用户画像 userId={}", userId);
    }

    /** 偏好自进化是否开启（默认开启） */
    public boolean isAutoEvolveEnabled(Long userId) {
        XunweiMemorySetting s = settingsRepository.findByUserId(userId);
        return s == null || s.getAutoEvolve() == null || s.getAutoEvolve() == 1;
    }

    /** 设置偏好自进化开关 */
    public void setAutoEvolve(Long userId, boolean enabled) {
        XunweiMemorySetting s = settingsRepository.findByUserId(userId);
        int val = enabled ? 1 : 0;
        if (s == null) {
            settingsRepository.insert(XunweiMemorySetting.builder()
                    .userId(userId).autoEvolve(val).build());
        } else {
            s.setAutoEvolve(val);
            settingsRepository.updateById(s);
        }
        log.info("[寻味] 偏好自进化 switch userId={} enabled={}", userId, enabled);
    }

    /** 删除单条记忆（属主校验；MyBatis-Plus 逻辑删除=软删除 deleted=1，抑制重复抽取），不存在/无权返回 false */
    public boolean deleteOne(Long userId, Long id) {
        XunweiMemory m = memoryRepository.selectById(id);
        if (m == null || !m.getUserId().equals(userId)) return false;
        boolean ok = memoryRepository.softDeleteById(id) > 0; // 软删 + 记录 deleted_at
        if (ok) profileCache.remove(userId);
        return ok;
    }

    /** 修改单条记忆内容（属主校验；用户纠正视为重新启用），不存在/无权/空值返回 false */
    public boolean updateOne(Long userId, Long id, String value) {
        if (value == null || value.isBlank()) return false;
        XunweiMemory m = memoryRepository.selectById(id);
        if (m == null || !m.getUserId().equals(userId)) return false;
        m.setMemValue(value.trim());
        m.setDeleted(0);
        m.setConfidence(Math.max(m.getConfidence() == null ? 60 : m.getConfidence(), 70));
        memoryRepository.updateById(m);
        profileCache.remove(userId);
        return true;
    }

    /**
     * P2 反馈闭环：用画像+行为记忆给候选视频打分并重排（无画像时保持原序）。
     * 返回候选 map（含 matchScore/matchHint），已按贴合度从高到低排序，供 Round2 推荐使用。
     */
    public List<Map<String, Object>> scoreAndRankCandidates(Long userId, List<VideoCandidate> candidates) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (candidates == null || candidates.isEmpty()) return out;
        Set<String> pos = new HashSet<>();
        Set<String> neg = new HashSet<>();
        for (XunweiMemory m : memoryRepository.findActive(userId)) {
            String val = m.getMemValue();
            if (val == null || val.isBlank()) continue;
            String key = m.getMemKey();
            if ("behavior".equals(m.getMemType())) {
                if ("liked_videos".equals(key)) addTitlesToSet(pos, val);
                else if ("disliked_videos".equals(key)) addTitlesToSet(neg, val);
            } else {
                switch (key) {
                    case "taboo", "disliked_dishes", "avoided_ingredients" -> addPhrasesToSet(neg, val);
                    case "liked_dishes", "preferred_ingredients", "cuisine", "taste" -> addPhrasesToSet(pos, val);
                    default -> {
                    }
                }
            }
        }
        if (pos.isEmpty() && neg.isEmpty()) {
            for (VideoCandidate c : candidates) out.add(candidateToScoredMap(c, 0, ""));
            return out;
        }
        for (VideoCandidate c : candidates) {
            int score = 0;
            List<String> reasons = new ArrayList<>();
            String title = c.title() == null ? "" : c.title();
            for (String p : pos) {
                if (title.contains(p)) {
                    score += 2;
                    reasons.add("贴合「" + p + "」");
                }
            }
            for (String n : neg) {
                if (title.contains(n)) {
                    score -= 3;
                    reasons.add("含忌口「" + n + "」");
                }
            }
            out.add(candidateToScoredMap(c, score, String.join("，", reasons)));
        }
        out.sort((a, b) -> Integer.compare((Integer) b.get("matchScore"), (Integer) a.get("matchScore")));
        return out;
    }

    private Map<String, Object> candidateToScoredMap(VideoCandidate c, int score, String hint) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id());
        m.put("title", c.title());
        m.put("url", c.url());
        m.put("uploader", c.uploader());
        m.put("duration", c.duration());
        m.put("viewCount", c.viewCount());
        m.put("thumbnail", c.thumbnail());
        m.put("platform", c.platform());
        m.put("matchScore", score);
        m.put("matchHint", hint);
        return m;
    }

    private void addTitlesToSet(Set<String> set, String json) {
        try {
            JsonNode arr = objectMapper.readTree(json);
            if (arr.isArray()) {
                for (JsonNode n : arr) {
                    String t = n.path("title").asText("").trim();
                    if (t.length() >= 2) set.add(t);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void addPhrasesToSet(Set<String> set, String val) {
        for (String s : val.split("[；;，,、]")) {
            String t = s.trim();
            if (t.length() >= 2) set.add(t);
        }
    }
}
