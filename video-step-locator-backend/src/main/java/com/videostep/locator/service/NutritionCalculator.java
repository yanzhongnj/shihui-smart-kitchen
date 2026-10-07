package com.videostep.locator.service;

import com.videostep.locator.entity.NutritionFood;
import com.videostep.locator.entity.RecipeIngredient;
import com.videostep.locator.repository.RecipeIngredientRepository;
import com.videostep.locator.service.health.FoodLibraryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 营养估算（P 阶段定位：估算而非权威计算，决策 v0.2）。
 * 营养数据源已 DB 化：查询 nutrition_food_db（FoodLibraryService 内存缓存，DB 为权威）。
 * 只累计 g/克 用量；定性（适量）、非克单位（个/勺/片）或库未命中 → 跳过并标记 estimated。
 * 库内字典优先；AI 计算兜底/回填（calcByAi）属营养健康模块 P2，本期未接入。
 */
@Service
@RequiredArgsConstructor
public class NutritionCalculator {

    private final RecipeIngredientRepository ingredientRepository;
    private final FoodLibraryService foodLibraryService;

    public static class NutritionResult {
        public double calories;
        public double protein;
        public double fat;
        public double carbs;
        public double fiber;
        public double sodium;
        public boolean estimated = false;
        public int matchedCount = 0;
        public int totalCount = 0;
        public final List<String> unmatched = new ArrayList<>();
        public final List<Map<String, Object>> items = new ArrayList<>();

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("calories", Math.round(calories));
            m.put("protein", round1(protein));
            m.put("fat", round1(fat));
            m.put("carbs", round1(carbs));
            m.put("fiber", round1(fiber));
            m.put("sodium", round1(sodium));
            m.put("source", estimated ? "估算" : "库内");
            m.put("estimated", estimated);
            m.put("matchedCount", matchedCount);
            m.put("totalCount", totalCount);
            m.put("unmatched", unmatched);
            m.put("items", items);
            return m;
        }
    }

    /** 按基准份量计算（factor=1） */
    public NutritionResult compute(Long recipeId) {
        return compute(ingredientRepository.findByRecipeId(recipeId), 1.0);
    }

    /** 按份量换算因子计算（供详情/换算共用） */
    public NutritionResult compute(List<RecipeIngredient> ingredients, double factor) {
        NutritionResult r = new NutritionResult();
        if (ingredients == null) return r;
        r.totalCount = ingredients.size();
        for (RecipeIngredient ig : ingredients) {
            if (ig.getAmount() == null) {
                r.estimated = true; // 定性用量，跳过
                continue;
            }
            String unit = ig.getUnit() == null ? "" : ig.getUnit().trim();
            if (!"g".equalsIgnoreCase(unit) && !"克".equals(unit)) {
                r.estimated = true; // 非克单位（个/勺/ml 等）不参与克级营养累计
                r.unmatched.add(ig.getName() + "(" + unit + ")");
                continue;
            }
            NutritionFood food = foodLibraryService.findByExact(ig.getName());
            if (food == null) {
                r.estimated = true;
                r.unmatched.add(ig.getName());
                continue;
            }
            double grams = ig.getAmount().doubleValue() * factor;
            r.calories += food.cal() * grams / 100.0;
            r.protein += food.protein() * grams / 100.0;
            r.fat += food.fat() * grams / 100.0;
            r.carbs += food.carbs() * grams / 100.0;
            r.fiber += food.fiber() * grams / 100.0;
            r.sodium += food.sodium() * grams / 100.0;
            r.matchedCount++;

            Map<String, Object> im = new LinkedHashMap<>();
            im.put("name", ig.getName());
            im.put("amount", round1(grams));
            im.put("calories", Math.round(food.cal() * grams / 100.0));
            im.put("protein", round1(food.protein() * grams / 100.0));
            im.put("fat", round1(food.fat() * grams / 100.0));
            im.put("carbs", round1(food.carbs() * grams / 100.0));
            im.put("fiber", round1(food.fiber() * grams / 100.0));
            im.put("sodium", round1(food.sodium() * grams / 100.0));
            r.items.add(im);
        }
        return r;
    }

    /** 手动食材清单计算（POST /api/health/nutrition/calc 字典路径） */
    public NutritionResult computeFoods(List<Map<String, Object>> foodItems, double factor) {
        List<RecipeIngredient> list = new ArrayList<>();
        if (foodItems != null) {
            for (Map<String, Object> it : foodItems) {
                String name = it.get("name") instanceof String s ? s : null;
                Object amt = it.get("amount");
                Object unit = it.get("unit");
                if (name == null || name.isBlank()) continue;
                RecipeIngredient ig = new RecipeIngredient();
                ig.setName(name);
                ig.setAmount(amt instanceof Number n ? BigDecimal.valueOf(n.doubleValue()) : null);
                ig.setUnit(unit instanceof String s2 ? s2 : "g");
                list.add(ig);
            }
        }
        return compute(list, factor);
    }

    private static double round1(double v) {
        return BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    // 供外部识别食材是否可参与克级营养计算
    public boolean canComputeInGrams(String unit) {
        return unit != null && ("g".equalsIgnoreCase(unit.trim()) || "克".equals(unit.trim()));
    }
}
