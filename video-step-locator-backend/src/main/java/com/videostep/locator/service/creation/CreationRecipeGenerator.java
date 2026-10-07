package com.videostep.locator.service.creation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videostep.locator.entity.AiCreation;
import com.videostep.locator.service.xunwei.DeepSeekClient;
import com.videostep.locator.service.xunwei.XunweiMemoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 酿味菜谱生成器（Round2 结构化输出 + 修订）。
 * 解析 / 校验 / 容错：steps 4~10 截断、字段完整性、JSON 解析失败重试 1 次。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CreationRecipeGenerator {

    private static final ObjectMapper OM = new ObjectMapper();

    private static final Set<String> DIFFICULTIES = Set.of("easy", "medium", "hard");

    private final DeepSeekClient deepSeekClient;
    private final XunweiMemoryService memoryService;

    static final String GEN_SYSTEM_PROMPT = """
            你是「酿味」，一位 AI 菜品创作助手。基于已确认的创作意图，发明一道世界上还没有的新菜，并输出结构化菜谱。
            【硬性要求】
            - steps 数量 4~10 个，严格按操作顺序
            - 每步 summary 简洁（≥4 字）、detail 含操作要点（≥10 字）
            - 含等待时长（腌制/焖煮/炖）的步骤填 timer_min（分钟，无则 0）
            - 每步必填 video_prompt：第一视角中式厨房 + 场景/食材/动作 + 镜头语言 + 写实美食风格 + 无文字水印 + 禁止人声旁白（画面静默：无人说话、无解说、无对白，只有烹饪画面与操作）
            - 禁止生食肉类、有毒/相克食材组合
            - device_params 仅在明确涉及厨电操作（温度/时长/模式）时输出，否则 []
            【输出格式（严格 JSON，仅输出 JSON）】
            {
              "title": "菜名",
              "summary": "创作理念一句话",
              "main_ingredients": [], "auxiliary_ingredients": [], "taste_tags": [], "allergens": [],
              "spice_level": 0, "calories": 0, "estimated_time": 0, "difficulty": "medium",
              "steps": [{"index":1,"summary":"...","detail":"...","timer_min":0,"video_prompt":"..."}],
              "device_params": []
            }
            """;

    static final String REVISE_SYSTEM_PROMPT = """
            你是「酿味」，一位 AI 菜品创作助手。用户对已生成的菜谱不满意，带反馈要求修订。
            请基于【当前菜谱】与【用户反馈】重新输出完整菜谱（不是局部 patch）。
            - 若反馈太模糊无法执行 → action=ask，reply 追问
            - 若可执行 → action=revise，输出修订后的完整 recipe + 一句话 delta_note（说明改了什么）
            【输出格式（严格 JSON，仅输出 JSON）】
            {
              "action": "ask" | "revise",
              "reply": "回复文本",
              "delta_note": "相对上一版的修改说明",
              "recipe": { 与生成格式相同的完整菜谱 JSON（action=revise 时必填） }
            }
            其余硬性要求同初始生成（steps 4~10、每步 video_prompt 需含禁人声旁白、禁生食/相克）。
            """;

    /**
     * 基于已确认意图（constraintsJson）生成结构化菜谱。
     */
    public AiCreation generate(Long userId, String constraintsJson) {
        String profile = memoryService.profileToPrompt(userId);
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", GEN_SYSTEM_PROMPT));
        StringBuilder user = new StringBuilder("【创作意图约束】\n")
                .append(constraintsJson == null || constraintsJson.isBlank() ? "{}" : constraintsJson);
        if (!profile.isBlank()) user.append("\n\n").append(profile);
        messages.add(Map.of("role", "user", "content", user.toString()));

        String raw = callWithRetry(messages);
        log.info("[酿味] userId={} genRaw={}", userId, raw);
        try {
            return parseAndValidate(OM.readTree(raw));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("AI 菜谱 JSON 解析失败，请重新确认创作");
        }
    }

    /**
     * 带反馈修订菜谱（v0.3 迭代）。
     */
    public ReviseResult revise(Long userId, AiCreation current, String feedback) {
        String profile = memoryService.profileToPrompt(userId);
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", REVISE_SYSTEM_PROMPT));
        StringBuilder user = new StringBuilder("【当前菜谱】\n").append(toCurrentJson(current));
        user.append("\n\n【用户反馈】\n").append(feedback);
        if (!profile.isBlank()) user.append("\n\n").append(profile);
        messages.add(Map.of("role", "user", "content", user.toString()));

        String raw = callWithRetry(messages);
        log.info("[酿味] userId={} reviseRaw={}", userId, raw);
        try {
            JsonNode node = OM.readTree(raw);
            String action = node.path("action").asText("ask");
            String reply = node.path("reply").asText("");
            if ("revise".equals(action)) {
                JsonNode rn = node.path("recipe");
                if (rn.isMissingNode() || rn.isNull()) {
                    return new ReviseResult("ask", "AI 未返回修订菜谱，请重试。", null, null, null);
                }
                AiCreation recipe = parseAndValidate(rn);
                String delta = node.path("delta_note").asText("");
                return new ReviseResult("revise", reply, recipe, delta, null);
            }
            return new ReviseResult("ask", reply, null, null, null);
        } catch (Exception e) {
            log.warn("[酿味] 修订解析失败 raw={}", raw, e);
            return new ReviseResult("ask", "AI 修订解析失败，请重试。", null, null, null);
        }
    }

    private String callWithRetry(List<Map<String, String>> messages) {
        try {
            return deepSeekClient.chat(messages, true);
        } catch (RuntimeException e) {
            // 失败重试 1 次
            try {
                return deepSeekClient.chat(messages, true);
            } catch (RuntimeException e2) {
                throw new IllegalArgumentException("AI 调用失败：" + e2.getMessage());
            }
        }
    }

    private AiCreation parseAndValidate(JsonNode rn) {
        AiCreation c = new AiCreation();
        c.setTitle(rn.path("title").asText(""));
        if (c.getTitle().isBlank()) {
            throw new IllegalArgumentException("AI 未生成菜名，请重新确认创作");
        }
        c.setSummary(rn.path("summary").asText(""));
        c.setMainIngredients(CreationJsonUtil.toJson(CreationJsonUtil.arr(rn.path("main_ingredients"))));
        c.setAuxiliaryIngredients(CreationJsonUtil.toJson(CreationJsonUtil.arr(rn.path("auxiliary_ingredients"))));
        c.setTasteTags(CreationJsonUtil.toJson(CreationJsonUtil.arr(rn.path("taste_tags"))));
        c.setAllergens(CreationJsonUtil.toJson(CreationJsonUtil.arr(rn.path("allergens"))));
        c.setSpiceLevel(rn.path("spice_level").asInt(0));
        c.setCalories(rn.path("calories").isNumber() ? rn.path("calories").asInt() : null);
        c.setEstimatedTime(rn.path("estimated_time").isNumber() ? rn.path("estimated_time").asInt() : null);
        String diff = rn.path("difficulty").asText("medium");
        c.setDifficulty(DIFFICULTIES.contains(diff) ? diff : "medium");

        // steps 校验：4~10
        List<Map<String, Object>> steps = new ArrayList<>();
        JsonNode stepsNode = rn.path("steps");
        if (stepsNode.isArray()) {
            for (JsonNode s : stepsNode) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("index", s.path("index").asInt(steps.size() + 1));
                m.put("summary", s.path("summary").asText(""));
                m.put("detail", s.path("detail").asText(""));
                m.put("timer_min", s.path("timer_min").isNumber() ? s.path("timer_min").asInt() : 0);
                m.put("video_prompt", s.path("video_prompt").asText(""));
                steps.add(m);
            }
        }
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("AI 未生成步骤，请重新确认创作");
        }
        if (steps.size() > 10) {
            steps = steps.subList(0, 10);
        }
        if (steps.size() < 4) {
            throw new IllegalArgumentException("AI 生成步骤过少（<4），请重新确认创作");
        }
        c.setSteps(CreationJsonUtil.toJson(steps));
        c.setDeviceParams(CreationJsonUtil.toJson(CreationJsonUtil.arr(rn.path("device_params"))));
        return c;
    }

    private String toCurrentJson(AiCreation c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", c.getTitle());
        m.put("summary", c.getSummary());
        m.put("main_ingredients", CreationJsonUtil.parseStringArray(c.getMainIngredients()));
        m.put("auxiliary_ingredients", CreationJsonUtil.parseStringArray(c.getAuxiliaryIngredients()));
        m.put("taste_tags", CreationJsonUtil.parseStringArray(c.getTasteTags()));
        m.put("allergens", CreationJsonUtil.parseStringArray(c.getAllergens()));
        m.put("spice_level", c.getSpiceLevel());
        m.put("calories", c.getCalories());
        m.put("estimated_time", c.getEstimatedTime());
        m.put("difficulty", c.getDifficulty());
        m.put("steps", CreationJsonUtil.parseSteps(c.getSteps()));
        m.put("device_params", CreationJsonUtil.parseStringArray(c.getDeviceParams()));
        return CreationJsonUtil.toJson(m);
    }

    /** 修订结果 */
    public record ReviseResult(String action, String reply, AiCreation recipe, String deltaNote,
                              List<Map<String, Object>> safetyItems) {
    }
}
