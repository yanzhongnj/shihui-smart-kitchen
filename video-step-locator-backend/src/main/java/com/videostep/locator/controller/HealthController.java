package com.videostep.locator.controller;

import com.videostep.locator.dto.response.ApiResponse;
import com.videostep.locator.entity.DietRecord;
import com.videostep.locator.entity.HealthProfile;
import com.videostep.locator.entity.NutritionFood;
import com.videostep.locator.repository.RecipeIngredientRepository;
import com.videostep.locator.service.NutritionCalculator;
import com.videostep.locator.service.health.AiNutritionService;
import com.videostep.locator.service.health.CalorieLevelService;
import com.videostep.locator.service.health.DietRecordService;
import com.videostep.locator.service.health.FoodLibraryService;
import com.videostep.locator.service.health.HealthProfileService;
import com.videostep.locator.service.health.HealthReviewService;
import com.videostep.locator.service.health.WeightService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 营养健康模块 P0：食物营养库 + 营养计算 + 卡路里分级。
 * 设计文档《拾味_营养健康模块_技术设计文档.md》§6。
 * 健康档案/饮食记录/体重/AI 复盘（P1-P3）后续版本。
 * 除 POST（用户数据）外均可匿名读取（参考数据）；POST 走 JWT。
 */
@RestController
@RequestMapping("/api/health")
@RequiredArgsConstructor
@Slf4j
public class HealthController {

    private final FoodLibraryService foodLibraryService;
    private final NutritionCalculator nutritionCalculator;
    private final RecipeIngredientRepository ingredientRepository;
    private final CalorieLevelService calorieLevelService;
    private final HealthProfileService healthProfileService;
    private final DietRecordService dietRecordService;
    private final HealthReviewService healthReviewService;
    private final AiNutritionService aiNutritionService;
    private final WeightService weightService;

    // ---------- 食物营养库 ----------

    @GetMapping("/foods/search")
    public ApiResponse<?> searchFoods(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) Integer page,
            @RequestParam(defaultValue = "10") Integer size) {
        if (page != null) {
            List<Map<String, Object>> items = new ArrayList<>();
            for (NutritionFood f : foodLibraryService.searchPage(keyword, category, source, page, size)) {
                items.add(toFoodMap(f));
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("items", items);
            data.put("total", foodLibraryService.count(keyword, category, source));
            data.put("page", page);
            data.put("size", size);
            return ApiResponse.success(data);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (NutritionFood f : foodLibraryService.search(keyword, category, source)) {
            out.add(toFoodMap(f));
        }
        return ApiResponse.success(out);
    }

    @GetMapping("/foods/categories")
    public ApiResponse<List<Map<String, Object>>> categories() {
        return ApiResponse.success(foodLibraryService.categories());
    }

    @PostMapping("/foods")
    public ApiResponse<Map<String, Object>> createFood(
            @RequestAttribute("userId") Long userId,
            @RequestBody Map<String, Object> body) {
        try {
            String name = body.get("name") instanceof String s ? s : null;
            String category = body.get("category") instanceof String s ? s : null;
            NutritionFood f = foodLibraryService.createUserFood(
                    userId, name, category,
                    dec(body.get("calories")), dec(body.get("protein")),
                    dec(body.get("fat")), dec(body.get("carbs")),
                    dec(body.get("fiber")), dec(body.get("sodium")));
            return ApiResponse.success("已添加食材", toFoodMap(f));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    /** AI 回填确认入库（P2）：body {foods:[{name, category?, calories, protein, fat, carbs}]}，source=ai，幂等 */
    @PostMapping("/foods/confirm-ai")
    public ApiResponse<Map<String, Object>> confirmAiFoods(@RequestBody Map<String, Object> body) {
        Object foodsObj = body.get("foods");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> foods = foodsObj instanceof List<?> l
                ? new ArrayList<>((List<Map<String, Object>>) l) : new ArrayList<>();
        int inserted = foodLibraryService.createAiFoods(foods);
        return ApiResponse.success("已回填 " + inserted + " 种食材", Map.of("inserted", inserted));
    }

    /** 管理端：更新食物营养（ADMIN） */
    @PutMapping("/foods/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> updateFood(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        try {
            NutritionFood f = foodLibraryService.updateFood(
                    id,
                    body.get("category") instanceof String s ? s : null,
                    dec(body.get("calories")), dec(body.get("protein")),
                    dec(body.get("fat")), dec(body.get("carbs")),
                    dec(body.get("fiber")), dec(body.get("sodium")));
            return ApiResponse.success("已更新食材", toFoodMap(f));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    /** 管理端：删除食物（ADMIN） */
    @DeleteMapping("/foods/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<String> deleteFood(@PathVariable Long id) {
        try {
            foodLibraryService.deleteFood(id);
            return ApiResponse.success("已删除食材", "ok");
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    // ---------- 营养计算 ----------

    @PostMapping("/nutrition/calc")
    public ApiResponse<Map<String, Object>> calc(@RequestBody Map<String, Object> body) {
        try {
            Object recipeIdObj = body.get("recipeId");
            if (recipeIdObj instanceof Number rid) {
                NutritionCalculator.NutritionResult res = nutritionCalculator
                        .compute(ingredientRepository.findByRecipeId(rid.longValue()), 1.0);
                Map<String, Object> data = new LinkedHashMap<>(res.toMap());
                data.put("calorieLevel", calorieLevelService.levelInfo(res.calories));
                return ApiResponse.success(data);
            }
            Object desc = body.get("description");
            Object items = body.get("ingredients");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> ingredients = items instanceof List<?> l
                    ? new ArrayList<>((List<Map<String, Object>>) l) : new ArrayList<>();
            boolean useAi = body.get("useAi") == Boolean.TRUE || "true".equals(String.valueOf(body.get("useAi")));

            Map<String, Object> data;
            if (desc instanceof String s && !s.isBlank() && ingredients.isEmpty()) {
                // AI 整道菜估算（P2）
                data = aiNutritionService.estimateByDescription(s);
            } else if (useAi) {
                // 字典优先 + AI 补齐缺失食材（P2）
                data = aiNutritionService.fillMissing(ingredients);
            } else {
                data = nutritionCalculator.computeFoods(ingredients, 1.0).toMap();
            }
            data.put("calorieLevel", calorieLevelService.levelInfo(
                    data.get("calories") instanceof Number n ? n.doubleValue() : 0));
            return ApiResponse.success(data);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    // ---------- 卡路里分级 ----------

    @GetMapping("/calorie-levels")
    public ApiResponse<List<Map<String, Object>>> calorieLevels() {
        return ApiResponse.success(calorieLevelService.levels());
    }

    @GetMapping("/calorie-level")
    public ApiResponse<Map<String, Object>> calorieLevel(@RequestParam double calories) {
        return ApiResponse.success(calorieLevelService.levelInfo(calories));
    }

    // ---------- 健康档案（P1） ----------

    @GetMapping("/profile")
    public ApiResponse<Map<String, Object>> profile(@RequestAttribute("userId") Long userId) {
        return ApiResponse.success(healthProfileService.profileMap(userId));
    }

    @PutMapping("/profile")
    public ApiResponse<Map<String, Object>> updateProfile(@RequestAttribute("userId") Long userId,
                                                          @RequestBody Map<String, Object> body) {
        try {
            HealthProfile p = healthProfileService.update(userId, body);
            return ApiResponse.success("已保存健康档案", healthProfileService.toMap(p));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    @GetMapping("/profile/targets")
    public ApiResponse<Map<String, Object>> targets(@RequestAttribute("userId") Long userId) {
        return ApiResponse.success(healthProfileService.targets(userId));
    }

    // ---------- 饮食记录（P1） ----------

    @GetMapping("/diet/today")
    public ApiResponse<Map<String, Object>> dietToday(@RequestAttribute("userId") Long userId) {
        return ApiResponse.success(dietRecordService.today(userId));
    }

    @GetMapping("/diet")
    public ApiResponse<Map<String, Object>> dietHistory(
            @RequestAttribute("userId") Long userId,
            @RequestParam(required = false) String date,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        try {
            return ApiResponse.success(dietRecordService.history(userId, date, page, size));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    @PostMapping("/diet")
    public ApiResponse<Map<String, Object>> addDiet(@RequestAttribute("userId") Long userId,
                                                    @RequestBody Map<String, Object> body) {
        try {
            String date = body.get("date") instanceof String s ? s : null;
            String mealType = body.get("mealType") instanceof String s ? s : null;
            String note = body.get("note") instanceof String s ? s : null;
            LocalDate d = null;
            if (date != null && !date.isBlank()) {
                try {
                    d = LocalDate.parse(date);
                } catch (Exception e) {
                    return ApiResponse.error(400, "日期格式应为 yyyy-MM-dd");
                }
            }
            Object itemsObj = body.get("items");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = itemsObj instanceof List<?> l
                    ? new ArrayList<>((List<Map<String, Object>>) l) : new ArrayList<>();
            DietRecord rec = dietRecordService.createManual(userId, d, mealType, items, note);
            return ApiResponse.success("已记录", Map.of("id", rec.getId()));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    @DeleteMapping("/diet/{id}")
    public ApiResponse<Map<String, Object>> deleteDiet(@RequestAttribute("userId") Long userId,
                                                       @PathVariable Long id) {
        try {
            dietRecordService.delete(userId, id);
            return ApiResponse.success(Map.of("deleted", true));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    // ---------- AI 饮食复盘（P2） ----------

    @GetMapping("/review")
    public ApiResponse<Map<String, Object>> review(@RequestAttribute("userId") Long userId) {
        try {
            return ApiResponse.success(healthReviewService.review(userId));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(429, e.getMessage());
        }
    }

    // ---------- 体重追踪（P3） ----------

    @PostMapping("/weight-logs")
    public ApiResponse<Map<String, Object>> recordWeight(@RequestAttribute("userId") Long userId,
                                                         @RequestBody Map<String, Object> body) {
        try {
            BigDecimal weight = body.get("weight") instanceof Number n
                    ? BigDecimal.valueOf(n.doubleValue()) : null;
            String date = body.get("date") instanceof String s ? s : null;
            LocalDate d = null;
            if (date != null && !date.isBlank()) {
                try {
                    d = LocalDate.parse(date);
                } catch (Exception e) {
                    return ApiResponse.error(400, "日期格式应为 yyyy-MM-dd");
                }
            }
            return ApiResponse.success("已记录", weightService.record(userId, weight, d));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    @GetMapping("/weight-logs")
    public ApiResponse<Map<String, Object>> weightLogs(@RequestAttribute("userId") Long userId,
                                                       @RequestParam(defaultValue = "30") int days) {
        return ApiResponse.success(weightService.trend(userId, days));
    }

    @GetMapping("/weight-trend")
    public ApiResponse<Map<String, Object>> weightTrend(@RequestAttribute("userId") Long userId,
                                                        @RequestParam(defaultValue = "30") int days) {
        return ApiResponse.success(weightService.trend(userId, days));
    }

    // ---------- 工具 ----------

    private Map<String, Object> toFoodMap(NutritionFood f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", f.getId());
        m.put("name", f.getName());
        m.put("nameEn", f.getNameEn());
        m.put("category", f.getCategory());
        m.put("calories", f.getCalories());
        m.put("protein", f.getProtein());
        m.put("fat", f.getFat());
        m.put("carbs", f.getCarbs());
        m.put("fiber", f.getFiber());
        m.put("sodium", f.getSodium());
        m.put("isCore", f.getIsCore());
        m.put("source", f.getSource());
        return m;
    }

    private BigDecimal dec(Object o) {
        return o instanceof Number n ? BigDecimal.valueOf(n.doubleValue()) : null;
    }
}
