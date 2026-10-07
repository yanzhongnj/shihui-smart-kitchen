package com.videostep.locator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videostep.locator.entity.CookingCheckin;
import com.videostep.locator.entity.DietRecord;
import com.videostep.locator.entity.RecipeMetadata;
import com.videostep.locator.repository.CookingCheckinRepository;
import com.videostep.locator.repository.RecipeMetadataRepository;
import com.videostep.locator.service.health.DietRecordService;
import com.videostep.locator.service.health.HealthProfileService;
import com.videostep.locator.service.safety.SafetyCheckService;
import com.videostep.locator.service.xunwei.XunweiMemoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 炒菜打卡（Keep 式习惯记录）：打卡 CRUD + 统计 + 经验/徽章联动 + 寻味记忆信号。
 * 打卡成功 → 经验 +10 → 徽章校验 → 行为信号(常做的菜)进寻味记忆。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CheckinService {

    private final CookingCheckinRepository checkinRepository;
    private final RecipeMetadataRepository recipeMetadataRepository;
    private final GrowthService growthService;
    private final XunweiMemoryService xunweiMemoryService;
    private final DietRecordService dietRecordService;
    private final HealthProfileService healthProfileService;
    private final SafetyCheckService safetyCheckService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Map<String, Object> add(Long userId, Long videoId, String recipeTitle, String mealType,
                                   String mood, String note, Integer calories, Double portionFactor) {
        // 未填菜名/热量时尝试从关联菜谱带出
        RecipeMetadata recipe = null;
        if (videoId != null) {
            recipe = recipeMetadataRepository.findByVideoId(videoId);
            if (recipe != null) {
                if (recipeTitle == null || recipeTitle.isBlank()) recipeTitle = recipe.getTitle();
                if (calories == null) calories = recipe.getCalories();
            }
        }
        if (recipeTitle == null || recipeTitle.isBlank()) recipeTitle = "家常菜";
        if (mealType == null || mealType.isBlank()) mealType = "dinner";
        if (mood == null || mood.isBlank()) mood = "normal";

        CookingCheckin c = CookingCheckin.builder()
                .userId(userId)
                .checkinDate(LocalDate.now())
                .videoId(videoId)
                .recipeTitle(recipeTitle.trim())
                .mealType(mealType)
                .mood(mood)
                .note(note)
                .calories(calories)
                .source("manual")
                .build();
        checkinRepository.insert(c);

        // 经验 + 徽章
        growthService.addExp(userId, 10, "checkin", c.getId());
        List<Map<String, Object>> newBadges = growthService.checkBadges(userId);

        // 行为信号 → 寻味记忆（常做的菜 → liked_dishes）
        if (!"家常菜".equals(c.getRecipeTitle())) {
            xunweiMemoryService.ingestBehavior(userId, "checkin", c.getRecipeTitle(), true);
        }

        // 跟做即记录（方案 B+C）：一键双写 → 自动生成饮食记录（无精确食材时兜底估算/跳过）
        DietRecord diet = null;
        try {
            diet = dietRecordService.createFromCheckin(userId, c.getId(), recipe, mealType, portionFactor);
        } catch (Exception e) {
            log.warn("createFromCheckin failed checkin={}: {}", c.getId(), e.getMessage());
        }

        // P2 饭后安全提醒：打卡食材 + 用户画像条件 → 规则库命中（非阻塞提示）
        List<Map<String, Object>> safetyTips = new ArrayList<>();
        try {
            List<String> ingredients = extractIngredients(recipe);
            safetyTips = safetyCheckService.checkForUser(userId, ingredients, null);
        } catch (Exception e) {
            log.warn("safety check on checkin failed checkin={}: {}", c.getId(), e.getMessage());
        }

        Map<String, Object> result = new HashMap<>();
        result.put("checkinId", c.getId());
        result.put("checkinDate", c.getCheckinDate().toString());
        result.put("newBadges", newBadges);
        result.put("dietRecordId", diet != null ? diet.getId() : null);
        result.put("dietEstimated", diet != null ? diet.getEstimated() : null);
        result.put("safetyTips", safetyTips);
        result.put("stats", stats(userId));
        return result;
    }

    /** P2：从关联菜谱提取食材名（ingredients_json.name，兜底 main_ingredients） */
    private List<String> extractIngredients(RecipeMetadata recipe) {
        List<String> out = new ArrayList<>();
        if (recipe == null) return out;
        if (recipe.getIngredientsJson() != null && !recipe.getIngredientsJson().isBlank()) {
            try {
                JsonNode arr = objectMapper.readTree(recipe.getIngredientsJson());
                if (arr.isArray()) {
                    arr.forEach(n -> {
                        String name = n.path("name").asText(null);
                        if (name != null && !name.isBlank()) out.add(name);
                    });
                }
            } catch (Exception ignored) {
            }
        }
        if (out.isEmpty() && recipe.getMainIngredients() != null) {
            for (String t : recipe.getMainIngredients().split("[,，、]")) {
                String trim = t.trim();
                if (!trim.isEmpty()) out.add(trim);
            }
        }
        return out;
    }

    public void delete(Long userId, Long id) {
        CookingCheckin c = checkinRepository.selectById(id);
        if (c == null) throw new IllegalArgumentException("打卡记录不存在");
        if (!c.getUserId().equals(userId)) throw new IllegalArgumentException("无权删除该打卡");
        // 级联删除该打卡自动生成的饮食记录（用户决策）
        dietRecordService.deleteByCheckin(userId, id);
        checkinRepository.softDeleteById(id); // 软删 + 记录 deleted_at
    }

    /** 按月查询打卡记录；month 形如 2026-08 */
    public List<Map<String, Object>> listByMonth(Long userId, String month) {
        YearMonth ym;
        try {
            ym = (month == null || month.isBlank()) ? YearMonth.now() : YearMonth.parse(month);
        } catch (Exception e) {
            throw new IllegalArgumentException("月份格式应为 yyyy-MM");
        }
        LocalDate start = ym.atDay(1);
        LocalDate end = ym.atEndOfMonth();
        return checkinRepository.findByUserAndDateRange(userId, start, end).stream()
                .map(c -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", c.getId());
                    m.put("date", c.getCheckinDate().toString());
                    m.put("videoId", c.getVideoId());
                    m.put("recipeTitle", c.getRecipeTitle());
                    m.put("mealType", c.getMealType());
                    m.put("mood", c.getMood());
                    m.put("note", c.getNote());
                    m.put("calories", c.getCalories());
                    return m;
                }).collect(Collectors.toList());
    }

    /** 打卡统计（首页/打卡页概览）：热量口径切换为 diet_records（营养），预算=档案→偏好→2000 */
    public Map<String, Object> stats(Long userId) {
        LocalDate today = LocalDate.now();
        List<CookingCheckin> todayList = checkinRepository.findByUserAndDateRange(userId, today, today);
        long thisMonthCount = checkinRepository
                .findByUserAndDateRange(userId, today.withDayOfMonth(1), today).size();

        // 热量预算口径（决策③）：health_profiles → user_preferences → 2000
        Map<String, Object> targets = healthProfileService.targets(userId);
        int budget = ((Number) targets.getOrDefault("calories", 2000)).intValue();

        // 今日营养（口径切换：diet_records 汇总 + 目标占比）
        Map<String, Object> todayNutrition = dietRecordService.today(userId);

        Map<String, Object> result = new HashMap<>();
        result.put("streak", growthService.calcStreak(userId));
        result.put("totalDays", checkinRepository.findDistinctDates(userId).size());
        result.put("totalCount", checkinRepository.countTotal(userId));
        result.put("thisMonthCount", thisMonthCount);
        result.put("todayChecked", !todayList.isEmpty());
        result.put("todayCalories", todayNutrition.get("calories"));
        result.put("caloriesBudget", budget);
        result.put("todayNutrition", todayNutrition);
        result.put("nutritionTargets", targets);
        return result;
    }
}
