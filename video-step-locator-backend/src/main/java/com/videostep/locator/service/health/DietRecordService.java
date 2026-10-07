package com.videostep.locator.service.health;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videostep.locator.entity.DietRecord;
import com.videostep.locator.entity.RecipeMetadata;
import com.videostep.locator.entity.RecipeIngredient;
import com.videostep.locator.repository.DietRecordRepository;
import com.videostep.locator.repository.MealPhotoRepository;
import com.videostep.locator.repository.RecipeIngredientRepository;
import com.videostep.locator.service.NutritionCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 饮食记录（diet_records）：跟做即记录 + 手动录入 + 今日汇总/目标对比 + 历史 + 删除。
 * 兜底（用户决策）：菜谱无精确食材时——可算就算、算不了标 estimated、份量可调(0.5x/1x/1.5x)。
 * 删除打卡级联删除其自动生成的饮食记录（deleteByCheckin）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DietRecordService {

    private final DietRecordRepository repository;
    private final RecipeIngredientRepository ingredientRepository;
    private final NutritionCalculator nutritionCalculator;
    private final HealthProfileService healthProfileService;
    private final MealPhotoRepository mealPhotoRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ---------- 跟做自动记录（打卡双写） ----------

    /**
     * 打卡时自动生成饮食记录。
     * 返回 null 表示该菜谱完全无法计算营养（仅打卡、不写饮食记录）。
     * 兜底链：recipe_ingredients 精确计算 → recipes.nutrition_json → recipes.calories → null。
     */
    public DietRecord createFromCheckin(Long userId, Long checkinId, RecipeMetadata recipe,
                                        String mealType, Double portionFactor) {
        return createFromCheckin(userId, checkinId, recipe, mealType, portionFactor, "cook");
    }

    /**
     * 同 createFromCheckin，但可指定 source（v0.3 决策 7.7-4：计划自动打卡用 plan）。
     */
    public DietRecord createFromCheckin(Long userId, Long checkinId, RecipeMetadata recipe,
                                        String mealType, Double portionFactor, String source) {
        if (recipe == null) return null;
        double factor = portionFactor != null && portionFactor > 0 ? portionFactor : 1.0;
        List<RecipeIngredient> ingredients = ingredientRepository.findByRecipeId(recipe.getId());
        NutritionCalculator.NutritionResult nut = nutritionCalculator.compute(ingredients, factor);

        boolean estimated = nut.estimated;
        String itemsJson = toJson(nut.items);

        // 兜底：无精确食材（matchedCount==0）→ 用 nutrition_json / calories 估算
        if (nut.matchedCount == 0) {
            Map<String, Double> fallback = fallbackNutrition(recipe);
            if (fallback == null) {
                return null; // 完全无法计算 → 不写饮食记录
            }
            nut.calories = fallback.get("calories") * factor;
            nut.protein = fallback.get("protein") * factor;
            nut.fat = fallback.get("fat") * factor;
            nut.carbs = fallback.get("carbs") * factor;
            estimated = true;
            itemsJson = "[]";
        }

        DietRecord d = DietRecord.builder()
                .userId(userId)
                .recordDate(LocalDate.now())
                .mealType(mealType == null || mealType.isBlank() ? "dinner" : mealType)
                .recipeId(recipe.getId())
                .checkinId(checkinId)
                .items(itemsJson)
                .calories(BigDecimal.valueOf(nut.calories))
                .protein(BigDecimal.valueOf(nut.protein))
                .fat(BigDecimal.valueOf(nut.fat))
                .carbs(BigDecimal.valueOf(nut.carbs))
                .portionFactor(BigDecimal.valueOf(factor).setScale(2, RoundingMode.HALF_UP))
                .estimated(estimated ? 1 : 0)
                .source(source == null || source.isBlank() ? "cook" : source)
                .build();
        repository.insert(d);
        return d;
    }

    /** 无精确食材时的营养兜底：nutrition_json → calories → null */
    private Map<String, Double> fallbackNutrition(RecipeMetadata recipe) {
        if (recipe.getNutritionJson() != null && !recipe.getNutritionJson().isBlank()) {
            try {
                JsonNode n = objectMapper.readTree(recipe.getNutritionJson());
                if (n.isObject() && n.path("calories").isNumber()) {
                    Map<String, Double> m = new LinkedHashMap<>();
                    m.put("calories", n.path("calories").asDouble());
                    m.put("protein", n.path("protein").asDouble(0));
                    m.put("fat", n.path("fat").asDouble(0));
                    m.put("carbs", n.path("carbs").asDouble(0));
                    return m;
                }
            } catch (Exception ignored) {
            }
        }
        if (recipe.getCalories() != null) {
            Map<String, Double> m = new LinkedHashMap<>();
            m.put("calories", recipe.getCalories().doubleValue());
            m.put("protein", 0.0);
            m.put("fat", 0.0);
            m.put("carbs", 0.0);
            return m;
        }
        return null;
    }

    // ---------- 手动录入 ----------

    public DietRecord createManual(Long userId, LocalDate date, String mealType,
                                   List<Map<String, Object>> items, String note) {
        if (items == null || items.isEmpty()) throw new IllegalArgumentException("请至少填写一种食材");
        NutritionCalculator.NutritionResult nut = nutritionCalculator.computeFoods(items, 1.0);
        if (nut.totalCount == 0) throw new IllegalArgumentException("请至少填写一种食材");
        DietRecord d = DietRecord.builder()
                .userId(userId)
                .recordDate(date == null ? LocalDate.now() : date)
                .mealType(mealType == null || mealType.isBlank() ? "dinner" : mealType)
                .items(toJson(nut.items))
                .calories(BigDecimal.valueOf(nut.calories))
                .protein(BigDecimal.valueOf(nut.protein))
                .fat(BigDecimal.valueOf(nut.fat))
                .carbs(BigDecimal.valueOf(nut.carbs))
                .portionFactor(BigDecimal.ONE)
                .estimated(nut.estimated ? 1 : 0)
                .source("manual")
                .note(note)
                .build();
        repository.insert(d);
        return d;
    }

    // ---------- 查询/汇总 ----------

    /** 今日汇总 + 目标对比（多指标进度环数据源） */
    public Map<String, Object> today(Long userId) {
        LocalDate today = LocalDate.now();
        List<DietRecord> list = repository.findByUserAndDate(userId, today);
        double cal = 0, protein = 0, fat = 0, carbs = 0;
        for (DietRecord d : list) {
            cal += d.cal();
            protein += d.protein();
            fat += d.fat();
            carbs += d.carbs();
        }
        Map<String, Object> targets = healthProfileService.targets(userId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", today.toString());
        out.put("calories", Math.round(cal));
        out.put("protein", round1(protein));
        out.put("fat", round1(fat));
        out.put("carbs", round1(carbs));
        out.put("recordCount", list.size());
        out.put("targets", targets);
        out.put("ratio", ratio(cal, protein, fat, carbs, targets));
        out.put("records", list.stream().map(this::toMap).toList());
        return out;
    }

    private Map<String, Object> ratio(double cal, double protein, double fat, double carbs, Map<String, Object> targets) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("calories", pct(cal, num(targets.get("calories"))));
        m.put("protein", pct(protein, num(targets.get("protein"))));
        m.put("fat", pct(fat, num(targets.get("fat"))));
        m.put("carbs", pct(carbs, num(targets.get("carbs"))));
        return m;
    }

    private int pct(double val, double target) {
        if (target <= 0) return 0;
        return (int) Math.min(200, Math.round(val * 100 / target));
    }

    private double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }

    /** 历史饮食（date 可选；分页） */
    public Map<String, Object> history(Long userId, String dateStr, int page, int size) {
        LocalDate date = null;
        if (dateStr != null && !dateStr.isBlank()) {
            try {
                date = LocalDate.parse(dateStr);
            } catch (Exception e) {
                throw new IllegalArgumentException("日期格式应为 yyyy-MM-dd");
            }
        }
        LocalDate start = date != null ? date : LocalDate.now().minusDays(29);
        LocalDate end = date != null ? date : LocalDate.now();
        List<DietRecord> all = repository.findByUserAndDateRange(userId, start, end);
        int total = all.size();
        int p = Math.max(1, page);
        int s = Math.max(1, size);
        int from = (p - 1) * s;
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = from; i < Math.min(from + s, all.size()); i++) {
            items.add(toMap(all.get(i)));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("total", total);
        out.put("page", p);
        out.put("size", s);
        return out;
    }

    public void delete(Long userId, Long id) {
        DietRecord d = repository.selectById(id);
        if (d == null) throw new IllegalArgumentException("饮食记录不存在");
        if (!d.getUserId().equals(userId)) throw new IllegalArgumentException("无权删除该记录");
        repository.softDeleteById(id); // 软删 + 记录 deleted_at
        // scan 来源：级联软删其 meal_photos（拍照打卡删除级联，对齐"删打卡级联删 diet"）
        if (d.getPhotoId() != null) {
            try {
                mealPhotoRepository.softDeleteById(d.getPhotoId());
            } catch (Exception e) {
                log.warn("delete diet cascade meal_photo failed diet={}: {}", id, e.getMessage());
            }
        }
    }

    /** 级联删除：删除打卡时清理其自动生成的饮食记录 */
    public void deleteByCheckin(Long userId, Long checkinId) {
        repository.deleteByCheckinId(userId, checkinId);
    }

    // ---------- 工具 ----------

    private Map<String, Object> toMap(DietRecord d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("date", d.getRecordDate().toString());
        m.put("mealType", d.getMealType());
        m.put("recipeId", d.getRecipeId());
        m.put("checkinId", d.getCheckinId());
        m.put("items", parseItems(d.getItems()));
        m.put("calories", d.getCalories());
        m.put("protein", d.getProtein());
        m.put("fat", d.getFat());
        m.put("carbs", d.getCarbs());
        m.put("portionFactor", d.getPortionFactor());
        m.put("estimated", d.getEstimated());
        m.put("source", d.getSource());
        m.put("note", d.getNote());
        m.put("createdAt", d.getCreatedAt());
        return m;
    }

    private List<Map<String, Object>> parseItems(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private String toJson(List<Map<String, Object>> items) {
        try {
            return objectMapper.writeValueAsString(items);
        } catch (Exception e) {
            return "[]";
        }
    }

    private static double round1(double v) {
        return BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }
}
