package com.videostep.locator.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videostep.locator.entity.RecipeIngredient;
import com.videostep.locator.entity.RecipeMetadata;
import com.videostep.locator.repository.RecipeIngredientRepository;
import com.videostep.locator.repository.RecipeMetadataRepository;
import com.videostep.locator.service.xunwei.DeepSeekClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 精确化增强（POST /api/recipes/{id}/upgrade，设计文档 §4.4/§8）。
 * 用户确认 + 计费：LLM 从现有菜谱信息精化出精确用量 → 落 recipe_ingredients →
 * 重算 precision_score → 可升为工业型；成功才记一次用量（usage_records）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RecipeUpgradeService {

    private final RecipeMetadataRepository recipeRepository;
    private final RecipeIngredientRepository ingredientRepository;
    private final DeepSeekClient deepSeekClient;
    private final PrecisionScoreService precisionScoreService;
    private final RecipeProvenanceService provenanceService;
    private final UsageService usageService;
    private final ObjectMapper objectMapper;

    public Map<String, Object> upgrade(Long userId, Long recipeId, String deviceId) {
        Map<String, Object> res = performUpgrade(recipeId);
        // 计费（成功才记）
        try {
            usageService.record(userId, deviceId, "recipe_upgrade", recipeId, 4096);
        } catch (Exception e) {
            log.warn("精确化增强计费记录失败: {}", e.getMessage());
        }
        return res;
    }

    /** 管理端批量精确化升级（不计费）：HowToCook 批量升级复用（设计 §6.1 批量精确化可选升级） */
    public Map<String, Object> upgradeNoBilling(Long recipeId) {
        return performUpgrade(recipeId);
    }

    private Map<String, Object> performUpgrade(Long recipeId) {
        RecipeMetadata r = recipeRepository.selectById(recipeId);
        if (r == null) throw new IllegalArgumentException("菜谱不存在");

        List<Map<String, Object>> refined = refineIngredients(r);
        if (refined == null) {
            throw new IllegalArgumentException("精确化增强失败（LLM 未配置或输出异常），请稍后重试");
        }

        saveIngredients(recipeId, refined);
        List<Map<String, Object>> steps = parseSteps(r.getStepsJson());
        boolean nutritionOk = r.getCalories() != null;
        boolean provenanceOk = r.getSourceUrl() != null
                || RecipeMetadata.SOURCE_AI_CREATE.equals(r.getSource())
                || RecipeMetadata.SOURCE_USER.equals(r.getSource());
        BigDecimal score = precisionScoreService.compute(refined, steps, nutritionOk, provenanceOk);
        r.setPrecisionScore(score);
        r.setRecipeType(precisionScoreService.deriveType(score));
        r.setIngredientsJson(writeJson(refined));
        recipeRepository.updateById(r);
        provenanceService.record(r, "upgrade-llm");
        log.info("菜谱 {} 精确化增强完成 type={} score={}", recipeId, r.getRecipeType(), score);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("recipeId", recipeId);
        res.put("precisionScore", score);
        res.put("recipeType", r.getRecipeType());
        res.put("ingredients", refined);
        return res;
    }

    private List<Map<String, Object>> refineIngredients(RecipeMetadata r) {
        String system = "你是菜谱精确化助手。根据菜谱信息把每种食材用量精确化（克/毫升/个/勺等），无法确定的填 null，只输出 JSON，不要其他内容。";
        String user = "菜名：" + r.getTitle()
                + "\n主料：" + r.getMainIngredients()
                + "\n辅料：" + r.getAuxiliaryIngredients()
                + "\n摘要：" + r.getSummary()
                + "\n输出格式：{\"ingredients\":[{\"name\":\"食材名\",\"amount\":数值,\"unit\":\"g\",\"tolerance\":数值或null,\"is_main\":true,\"note\":\"备注或null\"}]}"
                + "\n要求：按常见做法给出合理用量；确实无法推断的 amount/unit 填 null；tolerance 可给约 ±10% 或 null；严禁编造极端数值。";
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", system));
        messages.add(Map.of("role", "user", "content", user));
        try {
            String raw = deepSeekClient.chat(messages, true);
            Map<String, Object> parsed = objectMapper.readValue(raw, Map.class);
            Object ing = parsed.get("ingredients");
            if (!(ing instanceof List<?>)) return null;
            return normalize((List<?>) ing);
        } catch (Exception e) {
            log.warn("精确化增强 LLM 调用/解析失败: {}", e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> normalize(List<?> ing) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : ing) {
            if (!(o instanceof Map<?, ?>)) continue;
            Map<String, Object> src = new LinkedHashMap<>((Map<String, Object>) o);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", src.get("name"));
            m.put("amount", src.get("amount"));
            m.put("unit", src.get("unit"));
            m.put("tolerance", src.get("tolerance"));
            m.put("is_main", src.getOrDefault("is_main", src.get("isMain")));
            m.put("note", src.get("note"));
            out.add(m);
        }
        return out;
    }

    private void saveIngredients(Long recipeId, List<Map<String, Object>> ingredients) {
        ingredientRepository.deleteByRecipeId(recipeId);
        int seq = 1;
        for (Map<String, Object> ig : ingredients) {
            RecipeIngredient row = RecipeIngredient.builder()
                    .recipeId(recipeId)
                    .seq(seq++)
                    .name(String.valueOf(ig.get("name")))
                    .amount(numOf(ig.get("amount")))
                    .unit(ig.get("unit") == null ? null : String.valueOf(ig.get("unit")))
                    .tolerance(numOf(ig.get("tolerance")))
                    .isMain(isMainFlag(ig))
                    .note(ig.get("note") == null ? null : String.valueOf(ig.get("note")))
                    .build();
            ingredientRepository.insert(row);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseSteps(String stepsJson) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (stepsJson == null || stepsJson.isBlank()) return out;
        try {
            Object arr = objectMapper.readValue(stepsJson, Object.class);
            if (arr instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?>) out.add(new LinkedHashMap<>((Map<String, Object>) o));
                }
            }
        } catch (Exception e) {
            log.warn("steps_json 解析失败: {}", e.getMessage());
        }
        return out;
    }

    private Integer isMainFlag(Map<String, Object> ig) {
        Object v = ig.get("is_main") != null ? ig.get("is_main") : ig.get("isMain");
        if (v == null) return 1;
        String s = String.valueOf(v).trim();
        return ("1".equals(s) || "true".equalsIgnoreCase(s)) ? 1 : 0;
    }

    private BigDecimal numOf(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        String s = String.valueOf(o).trim();
        try { return new BigDecimal(s); } catch (Exception e) { return null; }
    }

    private String writeJson(Object o) {
        if (o == null) return null;
        try {
            String s = objectMapper.writeValueAsString(o);
            return "null".equals(s) ? null : s;
        } catch (Exception e) {
            return null;
        }
    }
}
