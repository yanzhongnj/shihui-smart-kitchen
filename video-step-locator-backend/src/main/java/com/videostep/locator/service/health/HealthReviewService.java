package com.videostep.locator.service.health;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videostep.locator.entity.DietRecord;
import com.videostep.locator.repository.DietRecordRepository;
import com.videostep.locator.service.xunwei.DeepSeekClient;
import com.videostep.locator.service.xunwei.XunweiMemoryService;
import com.videostep.locator.service.safety.SafetyCheckService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 饮食复盘（营养健康模块 P2）：画像 + 近 N 日饮食 + 目标 → LLM 营养平衡分析与个性化建议。
 * 差异化：注入寻味偏好画像（忌口/口味/目标），建议落到真实画像；可引「去酿味/寻味」做菜。
 * 合规：输出带免责声明（非医疗建议）。限流：health.review.rate-limit.daily-per-user（内存计数）。
 * 设计文档《拾味_营养健康模块_技术设计文档.md》§5.5。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HealthReviewService {

    private static final String DISCLAIMER = "本建议仅供参考，不构成医疗或营养专业建议。";

    private final DietRecordRepository dietRecordRepository;
    private final HealthProfileService healthProfileService;
    private final XunweiMemoryService xunweiMemoryService;
    private final DeepSeekClient deepSeekClient;
    private final SafetyCheckService safetyCheckService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${health.review.days:7}")
    private int reviewDays;

    @Value("${health.review.rate-limit.daily-per-user:10}")
    private int dailyLimit;

    /** 内存限流：userId → [epochDay, count] */
    private final Map<Long, long[]> rateBucket = new ConcurrentHashMap<>();

    private static final String SYSTEM_PROMPT = """
            你是「拾味」的营养健康助手。基于用户的近期饮食数据、健康目标与个人画像，\
            给出客观、温和、可执行的营养平衡分析与建议。要求：
            1. 只做营养与饮食习惯建议，不涉及疾病诊断、药物、医疗承诺。
            2. 结论用数据说话，明确指出优势与可改进点，措辞正面（先肯定再建议）。
            3. 建议要贴合用户画像（忌口/口味/目标），可推荐一道适合的菜，并说明是否可「去酿味创作」或「去寻味找菜谱」。
            4. 只输出 JSON，不要多余文字。
            JSON 结构：
            {"summary":"总体一句话评价","balance":{"protein":"蛋白摄入评估","fat":"脂肪摄入评估","carbs":"碳水摄入评估"},"strengths":["优点1","优点2"],"improvements":["改进1","改进2"],"suggestion":"一道适合你的菜及理由（可含去酿味/去寻味指引）"}
            """;

    public Map<String, Object> review(Long userId) {
        checkRateLimit(userId);
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(Math.max(1, reviewDays) - 1);

        List<DietRecord> records = dietRecordRepository.findByUserAndDateRange(userId, start, end);
        Map<String, Object> targets = healthProfileService.targets(userId);

        if (records.isEmpty()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("code", "no_data");
            out.put("summary", "近 " + reviewDays + " 天还没有饮食记录，先打一次卡或手动记一餐，我就能帮你复盘啦。");
            out.put("hasData", false);
            out.put("disclaimer", DISCLAIMER);
            return out;
        }

        // P2 饭后安全提醒：近 N 日饮食食材 + 画像条件 → 规则库命中（与 AI 复盘合并输出）
        List<Map<String, Object>> safetyTips = new ArrayList<>();
        try {
            List<String> ingredients = new ArrayList<>();
            for (DietRecord d : records) {
                ingredients.addAll(extractFoodNames(d.getItems()));
            }
            safetyTips = safetyCheckService.checkForUser(userId, ingredients, null);
        } catch (Exception e) {
            log.warn("safety check on review failed userId={}: {}", userId, e.getMessage());
        }

        String dietText = buildDietText(records, targets, start, end);
        String profileText = xunweiMemoryService.profileToPrompt(userId);

        try {
            String userPrompt = "【近 " + reviewDays + " 日饮食汇总】\n" + dietText
                    + "\n【营养目标】\n" + targets
                    + (profileText == null || profileText.isBlank() ? "" : profileText)
                    + "\n请按系统要求输出 JSON。";
            String raw = deepSeekClient.chat(List.of(
                    Map.of("role", "system", "content", SYSTEM_PROMPT),
                    Map.of("role", "user", "content", userPrompt)), true);

            JsonNode node = objectMapper.readTree(cleanJson(raw));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("code", "ok");
            out.put("hasData", true);
            out.put("summary", node.path("summary").asText(""));
            out.put("balance", parseMap(node.path("balance")));
            out.put("strengths", parseList(node.path("strengths")));
            out.put("improvements", parseList(node.path("improvements")));
            out.put("suggestion", node.path("suggestion").asText(""));
            out.put("days", records.stream().map(DietRecord::getRecordDate).distinct().count());
            out.put("safetyTips", safetyTips);
            out.put("disclaimer", DISCLAIMER);
            return out;
        } catch (Exception e) {
            log.warn("health review LLM failed userId={}: {}", userId, e.getMessage());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("code", "fallback");
            out.put("hasData", true);
            out.put("summary", "AI 复盘暂时不可用，先看你的近 " + reviewDays
                    + " 日数据（共 " + records.size() + " 条记录）。");
            out.put("diet", dietText);
            out.put("safetyTips", safetyTips);
            out.put("disclaimer", DISCLAIMER);
            return out;
        }
    }

    /** P2：从饮食记录 items JSON 提取食材名（字段 name，兼容 foodName） */
    private List<String> extractFoodNames(String itemsJson) {
        List<String> out = new ArrayList<>();
        if (itemsJson == null || itemsJson.isBlank()) return out;
        try {
            JsonNode arr = objectMapper.readTree(itemsJson);
            if (arr.isArray()) {
                arr.forEach(n -> {
                    String name = n.path("name").asText(null);
                    if (name == null || name.isBlank()) name = n.path("foodName").asText(null);
                    if (name != null && !name.isBlank()) out.add(name);
                });
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private String buildDietText(List<DietRecord> records, Map<String, Object> targets, LocalDate start, LocalDate end) {
        Map<LocalDate, double[]> byDay = new TreeMap<>();
        double totCal = 0, totPro = 0, totFat = 0, totCarb = 0;
        for (DietRecord d : records) {
            double[] arr = byDay.computeIfAbsent(d.getRecordDate(), k -> new double[4]);
            arr[0] += d.cal(); arr[1] += d.protein(); arr[2] += d.fat(); arr[3] += d.carbs();
            totCal += d.cal(); totPro += d.protein(); totFat += d.fat(); totCarb += d.carbs();
        }
        StringBuilder sb = new StringBuilder();
        for (var e : byDay.entrySet()) {
            sb.append(e.getKey()).append("：热量").append(Math.round(e.getValue()[0]))
                    .append("千卡 / 蛋白").append(round1(e.getValue()[1]))
                    .append("g / 脂肪").append(round1(e.getValue()[2]))
                    .append("g / 碳水").append(round1(e.getValue()[3])).append("g\n");
        }
        int days = Math.max(1, byDay.size());
        sb.append("日均：热量").append(Math.round(totCal / days))
                .append("千卡 / 蛋白").append(round1(totPro / days))
                .append("g / 脂肪").append(round1(totFat / days))
                .append("g / 碳水").append(round1(totCarb / days)).append("g\n");
        sb.append("目标（每日）：").append(targets);
        return sb.toString();
    }

    private Map<String, Object> parseMap(JsonNode n) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (n == null || !n.isObject()) return m;
        n.fields().forEachRemaining(e -> m.put(e.getKey(), e.getValue().asText("")));
        return m;
    }

    private List<String> parseList(JsonNode n) {
        List<String> l = new ArrayList<>();
        if (n == null || !n.isArray()) return l;
        n.forEach(x -> l.add(x.asText("")));
        return l;
    }

    /** 去除 LLM 可能包裹的 ```json ... ``` 等噪音 */
    private String cleanJson(String raw) {
        if (raw == null) return "{}";
        String s = raw.trim();
        if (s.startsWith("```")) {
            s = s.replaceAll("```(?:json)?", "").replace("```", "").trim();
        }
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start >= 0 && end > start) return s.substring(start, end + 1);
        return s;
    }

    private void checkRateLimit(Long userId) {
        long today = LocalDate.now().toEpochDay();
        long[] b = rateBucket.computeIfAbsent(userId, k -> new long[]{today, 0});
        synchronized (b) {
            if (b[0] != today) {
                b[0] = today;
                b[1] = 0;
            }
            b[1]++;
            if (b[1] > dailyLimit) {
                throw new IllegalArgumentException("今日 AI 复盘次数已达上限（" + dailyLimit + " 次），明天再来吧");
            }
        }
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
