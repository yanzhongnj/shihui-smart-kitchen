package com.videostep.locator.service.creation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videostep.locator.entity.AiCreation;
import com.videostep.locator.repository.AiCreationRepository;
import com.videostep.locator.service.xunwei.DeepSeekClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 酿味酱汁设计（P0，纯 LLM 零成本）。
 * 基于创作菜谱（口味/主料/辅料）+ 用户可选需求（更辣/低脂/无糖/换主料）生成结构化酱汁配方；
 * 支持「换一种口味」迭代（复用菜谱迭代范式，带 delta_note）；确认落库前走安全校验。
 * 配额：usage_records actionType='sauce' 按次限流（CreationQuotaService）。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SauceDesignService {

    private static final ObjectMapper OM = new ObjectMapper();

    private final CreationChatService chatService;
    private final DeepSeekClient deepSeekClient;
    private final AiCreationRepository creationRepository;
    private final CreationSafetyService safetyService;
    private final CreationQuotaService quotaService;

    static final String DESIGN_SYSTEM_PROMPT = """
            你是「拾味」的酱汁设计助手。基于给定的菜品信息，为这道菜设计一款合适的酱汁（蘸汁/浇汁/调味汁）。
            【硬性要求】
            - name 简洁（≤8 字，口味+质感，如「柠香椒麻汁」）
            - base 用「食材+用量」一句话描述（如「生抽2勺 + 青柠汁1勺 + 藤椒油1勺 + 白糖半勺」）
            - ratio 逐项列出配方（item 食材名 / amount 用量），3~8 项
            - steps 给出调制步骤（2~4 步，短句）
            - alternatives 给 2 个替代版（低脂/无糖/换主料方向，name+note 简短）
            - calories 为该酱汁单份热量估算（千卡）
            - 禁止生食肉类、有毒/相克食材；若涉及过敏原需在 note 中提示
            【输出格式（严格 JSON，仅输出 JSON）】
            {
              "name": "...",
              "base": "...",
              "ratio": [{"item":"...","amount":"..."}],
              "steps": ["..."],
              "alternatives": [{"name":"...","note":"..."}],
              "calories": 35
            }
            """;

    static final String REVISE_SYSTEM_PROMPT = """
            你是「拾味」的酱汁设计助手。用户对当前酱汁不满意，带反馈要求调整。
            请基于【当前酱汁】与【用户反馈】重新输出完整酱汁（不是局部 patch）。
            - 若反馈太模糊无法执行 → action=ask，reply 追问
            - 若可执行 → action=revise，输出调整后的完整酱汁 + 一句话 delta_note（说明改了什么）
            【输出格式（严格 JSON，仅输出 JSON）】
            {
              "action": "ask" | "revise",
              "reply": "回复文本",
              "delta_note": "相对上一版的修改说明",
              "sauce": { 与初始生成格式相同的完整酱汁 JSON（action=revise 时必填） }
            }
            其余硬性要求同初始生成（禁生食/相克、替代版含过敏原提示）。
            """;

    /**
     * 设计酱汁（首次）：返回结构化配方（不落库，确认后落库）。
     */
    public Map<String, Object> design(Long userId, Long creationId, String demand) {
        AiCreation c = chatService.getOwnedCreation(userId, creationId);
        quotaService.assertCanDesignSauce(userId);
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", DESIGN_SYSTEM_PROMPT));
        StringBuilder user = new StringBuilder("【菜品信息】\n")
                .append(toRecipeContext(c));
        if (demand != null && !demand.isBlank()) {
            user.append("\n\n【用户需求】\n").append(demand);
        }
        messages.add(Map.of("role", "user", "content", user.toString()));
        String raw = callWithRetry(messages);
        log.info("[酱汁] userId={} creationId={} designRaw={}", userId, creationId, raw);
        try {
            return parseSauce(OM.readTree(raw));
        } catch (Exception e) {
            throw new IllegalArgumentException("AI 酱汁解析失败，请重试");
        }
    }

    /**
     * 酱汁迭代（「更辣/低脂/无糖/换主料」等）：带当前酱汁上下文重生成，返回 {action, reply, sauce?, deltaNote?}。
     */
    public Map<String, Object> revise(Long userId, Long creationId, String demand) {
        AiCreation c = chatService.getOwnedCreation(userId, creationId);
        if (demand == null || demand.isBlank()) {
            throw new IllegalArgumentException("请告诉我怎么调整酱汁");
        }
        quotaService.assertCanDesignSauce(userId);
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", REVISE_SYSTEM_PROMPT));
        StringBuilder user = new StringBuilder("【当前酱汁】\n")
                .append(c.getSauceJson() == null || c.getSauceJson().isBlank()
                        ? "{ \"name\": \"（尚未确认酱汁，可基于菜品设计）\", " + toRecipeContext(c) + " }"
                        : c.getSauceJson());
        user.append("\n\n【菜品信息】\n").append(toRecipeContext(c));
        user.append("\n\n【用户反馈】\n").append(demand);
        messages.add(Map.of("role", "user", "content", user.toString()));
        String raw = callWithRetry(messages);
        log.info("[酱汁] userId={} creationId={} reviseRaw={}", userId, creationId, raw);
        try {
            JsonNode node = OM.readTree(raw);
            String action = node.path("action").asText("ask");
            String reply = node.path("reply").asText("");
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("action", action);
            r.put("reply", reply);
            if ("revise".equals(action)) {
                JsonNode sn = node.path("sauce");
                if (sn.isMissingNode() || sn.isNull()) {
                    r.put("action", "ask");
                    r.put("reply", "AI 未返回酱汁，请重试。");
                    return r;
                }
                r.put("sauce", parseSauce(sn));
                r.put("deltaNote", node.path("delta_note").asText(""));
            }
            return r;
        } catch (Exception e) {
            log.warn("[酱汁] 迭代解析失败 raw={}", raw, e);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("action", "ask");
            r.put("reply", "AI 酱汁迭代解析失败，请重试。");
            return r;
        }
    }

    /**
     * 确认酱汁：安全校验（生食/相克/过敏原/画像）通过后落库 sauce_json。
     * body.sauce 为当前酱汁配方 JSON；返回 {saved:true, sauce, safety:{level,reasons}}。
     */
    public Map<String, Object> confirm(Long userId, Long creationId, Map<String, Object> sauce) {
        AiCreation c = chatService.getOwnedCreation(userId, creationId);
        if (sauce == null || sauce.isEmpty()) {
            throw new IllegalArgumentException("酱汁内容为空，无法确认");
        }
        String sauceJson = CreationJsonUtil.toJson(sauce);
        // 安全校验：把酱汁食材并入辅料，复用安全规则（生食/相克/过敏原/画像）
        Map<String, Object> safety = safetyService.check(userId, withSauceIngredients(c, sauce));
        String level = (String) safety.getOrDefault("level", CreationSafetyService.LEVEL_GREEN);
        if (CreationSafetyService.LEVEL_RED.equals(level)) {
            throw new IllegalArgumentException("酱汁配方命中安全红线（" + safety.get("reasons") + "），请重新调整");
        }
        c.setSauceJson(sauceJson);
        creationRepository.updateById(c);
        quotaService.recordSauce(userId, creationId);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("saved", true);
        r.put("creationId", creationId);
        r.put("sauce", sauce);
        r.put("safety", safety);
        return r;
    }

    /** 获取当前已确认酱汁（未确认返回 null） */
    public Map<String, Object> getSauce(Long userId, Long creationId) {
        AiCreation c = chatService.getOwnedCreation(userId, creationId);
        if (c.getSauceJson() == null || c.getSauceJson().isBlank()) return null;
        JsonNode n = CreationJsonUtil.read(c.getSauceJson());
        if (n.isObject()) {
            return OM.convertValue(n, Map.class);
        }
        return null;
    }

    private Map<String, Object> parseSauce(JsonNode n) {
        if (n == null || !n.isObject()) {
            throw new IllegalArgumentException("AI 酱汁解析失败");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        String name = n.path("name").asText("");
        if (name.isBlank()) {
            throw new IllegalArgumentException("AI 未生成酱汁名称，请重试");
        }
        m.put("name", name);
        m.put("base", n.path("base").asText(""));
        m.put("ratio", toListOfMaps(n.path("ratio")));
        m.put("steps", CreationJsonUtil.arr(n.path("steps")));
        m.put("alternatives", toListOfMaps(n.path("alternatives")));
        m.put("calories", n.path("calories").isNumber() ? n.path("calories").asInt() : 0);
        return m;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> toListOfMaps(JsonNode arr) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            for (JsonNode x : arr) {
                if (x.isObject()) {
                    out.add(OM.convertValue(x, Map.class));
                } else if (!x.isMissingNode()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("item", x.asText());
                    out.add(m);
                }
            }
        }
        return out;
    }

    private AiCreation withSauceIngredients(AiCreation c, Map<String, Object> sauce) {
        AiCreation copy = new AiCreation();
        copy.setTitle(c.getTitle());
        copy.setSummary(c.getSummary());
        copy.setMainIngredients(c.getMainIngredients());
        copy.setTasteTags(c.getTasteTags());
        copy.setAllergens(c.getAllergens());
        copy.setSpiceLevel(c.getSpiceLevel());
        copy.setSteps(c.getSteps());
        // 酱汁食材（ratio item + base 文本）并入辅料参与安全校验
        List<String> aux = new ArrayList<>(CreationJsonUtil.parseStringArray(c.getAuxiliaryIngredients()));
        if (sauce != null) {
            Object ratio = sauce.get("ratio");
            if (ratio instanceof List) {
                for (Object o : (List<?>) ratio) {
                    if (o instanceof Map) {
                        Object item = ((Map<?, ?>) o).get("item");
                        if (item != null) aux.add(String.valueOf(item));
                    } else if (o != null) {
                        aux.add(String.valueOf(o));
                    }
                }
            }
            if (sauce.get("base") != null) aux.add(String.valueOf(sauce.get("base")));
        }
        copy.setAuxiliaryIngredients(CreationJsonUtil.toJson(aux));
        return copy;
    }

    private String toRecipeContext(AiCreation c) {
        StringBuilder sb = new StringBuilder();
        sb.append("菜名：").append(safe(c.getTitle())).append('\n');
        sb.append("主料：").append(String.join("、", CreationJsonUtil.parseStringArray(c.getMainIngredients()))).append('\n');
        sb.append("辅料/调料：").append(String.join("、", CreationJsonUtil.parseStringArray(c.getAuxiliaryIngredients()))).append('\n');
        sb.append("口味标签：").append(String.join("、", CreationJsonUtil.parseStringArray(c.getTasteTags()))).append('\n');
        sb.append("过敏原：").append(String.join("、", CreationJsonUtil.parseStringArray(c.getAllergens()))).append('\n');
        return sb.toString();
    }

    private String callWithRetry(List<Map<String, String>> messages) {
        try {
            return deepSeekClient.chat(messages, true);
        } catch (RuntimeException e) {
            try {
                return deepSeekClient.chat(messages, true);
            } catch (RuntimeException e2) {
                throw new IllegalArgumentException("AI 调用失败：" + e2.getMessage());
            }
        }
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }
}
