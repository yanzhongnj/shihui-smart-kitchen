package com.videostep.locator.service.creation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videostep.locator.entity.AiCreationMessage;
import com.videostep.locator.service.xunwei.DeepSeekClient;
import com.videostep.locator.service.xunwei.XunweiMemoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 酿味创作意图理解 / 追问 / 收敛引擎（Round1）。
 *
 * 输出 action：ask（追问）| confirm（展示确认卡）| revise_recipe（针对既有产物迭代）。
 * 状态机流转由 CreationSessionService 单点控制。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CreationIntentService {

    private final DeepSeekClient deepSeekClient;
    private final XunweiMemoryService memoryService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    static final String INTENT_SYSTEM_PROMPT = """
            你是「酿味」，一位 AI 菜品创作助手，帮用户「发明一道世界上还没有的新菜」。
            你的职责是在多轮对话中逐步收敛创作意图，信息不足时主动追问，直到足够明确才引导用户确认。

            【收敛判定】
            - 已明确：至少 1 类主料 + 口味偏好 + 忌口情况（明确或用户表示无特殊忌口）
            - 信息不足 → action=ask，reply 里追问缺口（一次最多追问 2 个关键问题），不生成菜谱
            - 已收敛 → action=confirm，reply 里给出：菜名草案 + 用料 + 风格 + 预计时长，请用户确认
            - 用户对既有菜谱/创作提出修改意见（"不够辣/分量太少/换个主料/步骤太复杂/换个风格"等）→ action=revise_recipe，reply 简述已理解的修改点

            【输出格式（严格遵守，仅输出 JSON，不要输出其它文字）】
            {
              "action": "ask" | "confirm" | "revise_recipe",
              "intent": { "confirmed": false, "summary": "一句话意图描述" },
              "constraints": {
                "ingredients": [], "tastes": [], "taboo": [],
                "maxMinutes": 0, "scene": "", "style": "", "cuisine": ""
              },
              "missing": ["口味偏好"],
              "reply": "给用户的回复文本"
            }
            约束：constraints 里已明确的信息尽量填全；不明确字段留空数组/0/空串。
            """;

    /**
     * 理解意图（Round1）。
     *
     * @param userId           用户 id（画像注入）
     * @param history          会话历史（不含本轮）
     * @param userMessage      本轮用户消息
     * @param sessionIntentJson 会话已收敛意图快照（可空）
     */
    public IntentResult understand(Long userId, List<AiCreationMessage> history,
                                   String userMessage, String sessionIntentJson) {
        StringBuilder sys = new StringBuilder(INTENT_SYSTEM_PROMPT);
        sys.append(memoryService.profileToPrompt(userId));
        if (sessionIntentJson != null && !sessionIntentJson.isBlank() && !"{}".equals(sessionIntentJson)) {
            sys.append("\n\n【本会话已收敛的创作意图快照（迭代时在其基础上修改）】\n").append(sessionIntentJson);
        }
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", sys.toString()));
        for (AiCreationMessage m : history) {
            messages.add(Map.of("role", m.getRole(), "content", m.getContent()));
        }
        messages.add(Map.of("role", "user", "content", userMessage));

        String raw;
        try {
            raw = deepSeekClient.chat(messages, true);
        } catch (RuntimeException e) {
            log.error("[酿味] 意图 LLM 调用失败 userId={}: {}", userId, e.getMessage());
            return new IntentResult("error", "抱歉，AI 暂时开小差了，请稍后再试。", "{}");
        }
        log.info("[酿味] userId={} intentRaw={}", userId, raw);
        return parse(raw);
    }

    private IntentResult parse(String raw) {
        try {
            JsonNode node = objectMapper.readTree(raw);
            String action = node.path("action").asText("ask");
            String reply = node.path("reply").asText("");
            // 存完整快照（含 constraints），供 confirm 生成用
            String intentJson = objectMapper.writeValueAsString(node);
            return new IntentResult(action, reply, intentJson);
        } catch (Exception e) {
            log.warn("[酿味] 意图解析失败 raw={}", raw, e);
            return new IntentResult("error", "抱歉，AI 意图解析失败，请重试。", "{}");
        }
    }

    /** 意图理解结果 */
    public record IntentResult(String action, String reply, String intentJson) {
    }
}
