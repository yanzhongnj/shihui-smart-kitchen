package com.videostep.locator.service;

import com.videostep.locator.entity.RecipeIngredient;
import com.videostep.locator.entity.RecipeMetadata;
import com.videostep.locator.repository.RecipeIngredientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分量换算（对标下厨房智能换算）：按目标份数等比缩放用量，并联动营养估算。
 * 基准份量 recipes.servings（缺省按 1 人份）。
 */
@Service
@RequiredArgsConstructor
public class RecipeScaleService {

    private final RecipeIngredientRepository ingredientRepository;
    private final NutritionCalculator nutritionCalculator;

    /** 按人数换算（基准份量 recipes.servings，缺省 1 人份） */
    public Map<String, Object> scale(RecipeMetadata r, Integer targetServings) {
        int base = r.getServings() != null && r.getServings() > 0 ? r.getServings() : 1;
        int target = targetServings != null && targetServings > 0 ? targetServings : base;
        double factor = (double) target / base;
        Map<String, Object> res = buildScale(r, factor);
        res.put("baseServings", base);
        res.put("servings", target);
        res.put("mode", "people");
        return res;
    }

    /**
     * 按模具尺寸换算（P4，对标下厨房智能换算）：factor = 目标模具面积 / 基准模具面积。
     * 基准模具默认圆模 18cm（6寸）；shape=round(直径)/square(边长)/rect(长×宽)。
     */
    public Map<String, Object> scaleByMold(RecipeMetadata r, String shape, Double size1, Double size2) {
        double targetArea = moldArea(shape, size1, size2);
        double baseArea = moldArea("round", 18.0, null);
        if (targetArea <= 0) throw new IllegalArgumentException("模具尺寸无效");
        double factor = targetArea / baseArea;
        Map<String, Object> res = buildScale(r, factor);
        res.put("mode", "mold");
        res.put("shape", shape == null ? "round" : shape);
        res.put("baseMold", "圆模 18cm");
        res.put("factor", round2(BigDecimal.valueOf(factor)));
        return res;
    }

    private double moldArea(String shape, Double s1, Double s2) {
        String sh = shape == null ? "round" : shape.toLowerCase();
        switch (sh) {
            case "square": return s1 != null ? s1 * s1 : 0;
            case "rect":
            case "rectangle": return s1 != null && s2 != null ? s1 * s2 : 0;
            case "round":
            default: return s1 != null ? Math.PI * Math.pow(s1 / 2.0, 2) : 0;
        }
    }

    private Map<String, Object> buildScale(RecipeMetadata r, double factor) {
        List<RecipeIngredient> ingredients = ingredientRepository.findByRecipeId(r.getId());
        List<Map<String, Object>> scaled = new ArrayList<>();
        for (RecipeIngredient ig : ingredients) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", ig.getSeq());
            m.put("name", ig.getName());
            m.put("amount", ig.getAmount());
            m.put("unit", ig.getUnit());
            m.put("tolerance", ig.getTolerance());
            m.put("isMain", ig.getIsMain());
            m.put("note", ig.getNote());
            if (ig.getAmount() != null) {
                m.put("scaledAmount", round2(ig.getAmount().multiply(BigDecimal.valueOf(factor))));
                m.put("scaledTolerance", ig.getTolerance() != null
                        ? round2(ig.getTolerance().multiply(BigDecimal.valueOf(factor))) : null);
            } else {
                m.put("scaledAmount", null);
                m.put("scaledTolerance", null);
            }
            scaled.add(m);
        }
        NutritionCalculator.NutritionResult nutrition = nutritionCalculator.compute(ingredients, factor);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("recipeId", r.getId());
        res.put("title", r.getTitle());
        res.put("factor", round2(BigDecimal.valueOf(factor)));
        res.put("ingredients", scaled);
        res.put("nutrition", nutrition.toMap());
        return res;
    }

    private BigDecimal round2(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
