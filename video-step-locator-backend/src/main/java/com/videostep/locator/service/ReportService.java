package com.videostep.locator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videostep.locator.entity.CookingCheckin;
import com.videostep.locator.entity.DietRecord;
import com.videostep.locator.entity.UserCorrection;
import com.videostep.locator.entity.WeightLog;
import com.videostep.locator.repository.CookingCheckinRepository;
import com.videostep.locator.repository.DietRecordRepository;
import com.videostep.locator.repository.DownloadTaskRepository;
import com.videostep.locator.repository.UserCorrectionRepository;
import com.videostep.locator.repository.UserWorkRepository;
import com.videostep.locator.repository.WeightLogRepository;
import com.videostep.locator.service.health.HealthProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 月度烹饪报告（Keep 式月度总结）：聚合打卡/作品/下载，输出菜系分布/常做菜/心情/热量/连续。
 * 数据源：cooking_checkins + user_works + download_tasks + recipe_metadata（菜系）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReportService {

    private final CookingCheckinRepository checkinRepository;
    private final UserWorkRepository workRepository;
    private final DownloadTaskRepository downloadTaskRepository;
    private final UserCorrectionRepository correctionRepository;
    private final DietRecordRepository dietRecordRepository;
    private final WeightLogRepository weightLogRepository;
    private final HealthProfileService healthProfileService;
    private final com.videostep.locator.service.xunwei.CorrectionProfileService correctionProfileService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Map<String, Object> monthlyReport(Long userId, String month) {
        YearMonth ym;
        try {
            ym = (month == null || month.isBlank()) ? YearMonth.now() : YearMonth.parse(month);
        } catch (Exception e) {
            throw new IllegalArgumentException("月份格式应为 yyyy-MM");
        }
        LocalDate start = ym.atDay(1);
        LocalDate end = ym.atEndOfMonth();
        LocalDateTime startDt = start.atStartOfDay();
        LocalDateTime endExcl = start.plusMonths(1).atStartOfDay();

        List<CookingCheckin> checkins = checkinRepository.findByUserAndDateRange(userId, start, end);

        int totalCount = checkins.size();
        Set<LocalDate> dates = new TreeSet<>();
        int totalCalories = 0;
        Map<String, Integer> recipeCount = new LinkedHashMap<>();
        Map<String, Integer> moodCount = new LinkedHashMap<>();
        for (CookingCheckin c : checkins) {
            dates.add(c.getCheckinDate());
            if (c.getCalories() != null) totalCalories += c.getCalories();
            String r = (c.getRecipeTitle() == null || c.getRecipeTitle().isBlank()) ? "家常菜" : c.getRecipeTitle();
            recipeCount.merge(r, 1, Integer::sum);
            moodCount.merge(c.getMood() == null ? "normal" : c.getMood(), 1, Integer::sum);
        }

        List<Map<String, Object>> topRecipes = recipeCount.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(5)
                .map(e -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("name", e.getKey());
                    m.put("count", e.getValue());
                    return m;
                }).collect(Collectors.toList());

        List<Map<String, Object>> cuisines = checkinRepository.countCuisines(userId, start, end, 6);
        long worksCount = workRepository.countInRange(userId, startDt, endExcl);
        long downloadsCount = downloadTaskRepository.countInRange(userId, startDt, endExcl);
        // 成品纠偏（M2 起以 user_corrections 为数据源：次数 + 高频维度结论分析）
        List<UserCorrection> corrections = correctionRepository.findByUserAndDateRange(userId, startDt, endExcl);
        long correctionsCount = corrections.size();

        Map<String, Object> summary = new HashMap<>();
        summary.put("month", ym.toString());
        summary.put("totalCount", totalCount);
        summary.put("totalDays", dates.size());
        summary.put("distinctRecipes", recipeCount.size());
        summary.put("totalCalories", totalCalories);
        summary.put("avgCaloriesPerDay", dates.isEmpty() ? 0 : totalCalories / dates.size());
        summary.put("maxStreakInMonth", maxConsecutive(dates));
        summary.put("worksCount", worksCount);
        summary.put("downloadsCount", downloadsCount);
        summary.put("correctionsCount", correctionsCount);

        Map<String, Object> result = new HashMap<>();
        result.put("summary", summary);
        result.put("topRecipes", topRecipes);
        result.put("cuisines", cuisines);
        result.put("moods", moodCount);
        Map<String, Object> correctionAnalysis = buildCorrectionAnalysis(corrections);
        // M3：月度成长轨迹 —— 本月易犯偏差（过滤中性结论）
        correctionAnalysis.put("topMistakes",
                correctionProfileService.topMistakes(userId, startDt, endExcl, 3));
        result.put("correctionAnalysis", correctionAnalysis);
        // P3：营养健康——营养分布/达标率 + 体重趋势
        result.put("nutrition", buildNutrition(userId, start, end));
        result.put("weightTrend", buildWeightTrend(userId, start, end));
        return result;
    }

    /** P3：本月营养汇总（饮食记录）+ 热量达标率 */
    private Map<String, Object> buildNutrition(Long userId, LocalDate start, LocalDate end) {
        List<DietRecord> diets = dietRecordRepository.findByUserAndDateRange(userId, start, end);
        Map<LocalDate, Double> calByDay = new HashMap<>();
        double totalCal = 0, totalPro = 0, totalFat = 0, totalCarb = 0;
        for (DietRecord d : diets) {
            totalCal += d.cal();
            totalPro += d.protein();
            totalFat += d.fat();
            totalCarb += d.carbs();
            calByDay.merge(d.getRecordDate(), d.cal(), Double::sum);
        }
        int days = calByDay.size();
        Map<String, Object> targets = healthProfileService.targets(userId);
        int tCal = targets.get("calories") instanceof Number n ? n.intValue() : 0;
        int achieveDays = 0;
        for (double v : calByDay.values()) {
            if (tCal > 0 && v >= tCal * 0.9) achieveDays++;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("recordCount", diets.size());
        m.put("recordDays", days);
        m.put("totalCalories", Math.round(totalCal));
        m.put("avgCaloriesPerDay", days == 0 ? 0 : Math.round(totalCal / days));
        m.put("avgProtein", days == 0 ? 0 : round1(totalPro / days));
        m.put("avgFat", days == 0 ? 0 : round1(totalFat / days));
        m.put("avgCarbs", days == 0 ? 0 : round1(totalCarb / days));
        m.put("targetCalories", tCal);
        m.put("achieveDays", achieveDays);
        m.put("attainmentRate", days == 0 ? 0 : Math.round(achieveDays * 100.0 / days));
        return m;
    }

    /** P3：本月体重趋势 */
    private Map<String, Object> buildWeightTrend(Long userId, LocalDate start, LocalDate end) {
        List<WeightLog> logs = weightLogRepository.findSince(userId, start);
        List<Map<String, Object>> items = new ArrayList<>();
        for (WeightLog w : logs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", w.getLogDate().toString());
            m.put("weight", w.getWeight());
            items.add(m);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count", items.size());
        m.put("items", items);
        m.put("firstWeight", items.isEmpty() ? null : items.get(0).get("weight"));
        m.put("lastWeight", items.isEmpty() ? null : items.get(items.size() - 1).get("weight"));
        return m;
    }

    private static double round1(double v) {
        return BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    /** 纠偏分析（M2）：仅统计 + 各维度高频结论（排除"总体完成度"）。返回 { count, dimensions:[{name, conclusions:[{conclusion,count}]}] } */
    private Map<String, Object> buildCorrectionAnalysis(List<UserCorrection> corrections) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", corrections.size());
        Map<String, Map<String, Integer>> dimCount = new LinkedHashMap<>();
        for (UserCorrection c : corrections) {
            String dims = c.getDimensions();
            if (dims == null || dims.isBlank()) continue;
            try {
                JsonNode arr = objectMapper.readTree(dims);
                if (!arr.isArray()) continue;
                for (JsonNode n : arr) {
                    String name = n.path("name").asText("");
                    String conclusion = n.path("conclusion").asText("");
                    if (name.isBlank() || conclusion.isBlank() || "总体完成度".equals(name)) continue;
                    dimCount.computeIfAbsent(name, k -> new LinkedHashMap<>())
                            .merge(conclusion, 1, Integer::sum);
                }
            } catch (Exception ignored) {
            }
        }
        List<Map<String, Object>> dims = new ArrayList<>();
        for (var e : dimCount.entrySet()) {
            List<Map<String, Object>> conclusions = e.getValue().entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue())
                    .limit(3)
                    .map(ce -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("conclusion", ce.getKey());
                        m.put("count", ce.getValue());
                        return m;
                    }).collect(Collectors.toList());
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("name", e.getKey());
            d.put("conclusions", conclusions);
            dims.add(d);
        }
        out.put("dimensions", dims);
        return out;
    }

    private int maxConsecutive(Set<LocalDate> dates) {
        if (dates.isEmpty()) return 0;
        List<LocalDate> sorted = new ArrayList<>(dates);
        Collections.sort(sorted);
        int max = 1, cur = 1;
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).equals(sorted.get(i - 1).plusDays(1))) {
                cur++;
                max = Math.max(max, cur);
            } else {
                cur = 1;
            }
        }
        return max;
    }
}
