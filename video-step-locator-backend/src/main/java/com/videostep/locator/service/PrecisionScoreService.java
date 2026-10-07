package com.videostep.locator.service;

import com.videostep.locator.entity.RecipeMetadata;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 菜谱精确化评分（precision_score 0~1，设计文档 §7.0）。
 * <p>模型：score = 0.5*主料精确率 + 0.25*步骤参数完整率 + 0.15*营养可算性 + 0.10*溯源完整度。
 * recipe_type 由阈值（默认 ≥0.7 → INDUSTRIAL，可配置 recipe.precision.threshold）派生，
 * 评分与 confidence（提取置信度）分离。
 */
@Service
public class PrecisionScoreService {

    @Value("${recipe.precision.threshold:0.7}")
    private double industrialThreshold;

    private static final double W_MAIN = 0.5;
    private static final double W_STEP = 0.25;
    private static final double W_NUTRITION = 0.15;
    private static final double W_PROVENANCE = 0.10;

    /**
     * @param ingredients   食材列表 [{name,amount,unit,tolerance,is_main}]（可为空）
     * @param steps         步骤列表 [{summary,timer_min,temperature,mode}]（可为空）
     * @param nutritionOk   营养可算（P 阶段：calories 非空即视为可估算）
     * @param provenanceOk  溯源完整（video/article 有 source_url；AI 有 ref_id+raw_digest；user 有 user_id）
     */
    public BigDecimal compute(List<Map<String, Object>> ingredients, List<Map<String, Object>> steps,
                              boolean nutritionOk, boolean provenanceOk) {
        double mainRatio = mainIngredientPreciseRatio(ingredients);
        double stepRatio = stepParamRatio(steps);
        double nutritionScore = nutritionOk ? 1.0 : 0.0;
        double provenanceScore = provenanceOk ? 1.0 : 0.0;

        double score = W_MAIN * mainRatio + W_STEP * stepRatio
                + W_NUTRITION * nutritionScore + W_PROVENANCE * provenanceScore;
        return BigDecimal.valueOf(Math.round(score * 100.0) / 100.0);
    }

    /** 当前工业型阈值（可配置，默认 0.7） */
    public double threshold() {
        return industrialThreshold;
    }

    /** 派生类型：precision_score ≥ 阈值 → INDUSTRIAL，否则 HOME */
    public String deriveType(BigDecimal score) {
        return score != null && score.doubleValue() >= industrialThreshold
                ? RecipeMetadata.TYPE_INDUSTRIAL
                : RecipeMetadata.TYPE_HOME;
    }

    /** 主料精确率：主料（is_main=true）中 amount+unit 齐全条数 / 主料总数；无主料 → 0 */
    private double mainIngredientPreciseRatio(List<Map<String, Object>> ingredients) {
        if (ingredients == null || ingredients.isEmpty()) return 0.0;
        int mains = 0;
        int precise = 0;
        for (Map<String, Object> ig : ingredients) {
            if (!isMain(ig)) continue;
            mains++;
            if (hasText(ig.get("amount")) && hasText(ig.get("unit"))) precise++;
        }
        return mains == 0 ? 0.0 : (double) precise / mains;
    }

    /** 步骤参数完整率：有 timer_min/timerMin/temperature/mode 之一的步骤占比；无步骤 → 0 */
    private double stepParamRatio(List<Map<String, Object>> steps) {
        if (steps == null || steps.isEmpty()) return 0.0;
        int withParam = 0;
        for (Map<String, Object> s : steps) {
            if (hasText(s.get("timer_min")) || hasText(s.get("timerMin"))
                    || hasText(s.get("temperature")) || hasText(s.get("mode"))) {
                withParam++;
            }
        }
        return (double) withParam / steps.size();
    }

    /** 兼容 snake_case 与 camelCase 键名（流水线/录入/升级来源不同） */
    private boolean isMain(Map<String, Object> ig) {
        Object v = ig.get("is_main") != null ? ig.get("is_main") : ig.get("isMain");
        if (v == null) return false;
        String s = String.valueOf(v).trim();
        return "1".equals(s) || "true".equalsIgnoreCase(s) || "是".equals(s);
    }

    private boolean hasText(Object o) {
        return o != null && !String.valueOf(o).isBlank() && !"null".equalsIgnoreCase(String.valueOf(o).trim());
    }
}
