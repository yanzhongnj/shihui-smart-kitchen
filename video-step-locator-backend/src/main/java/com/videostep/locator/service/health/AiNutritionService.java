package com.videostep.locator.service.health;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videostep.locator.entity.NutritionFood;
import com.videostep.locator.service.NutritionCalculator;
import com.videostep.locator.service.xunwei.DeepSeekClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

/**
 * AI 营养计算兜底（营养健康模块 P2，设计文档 §5.3 路径2）。
 * 原则：库内字典优先、AI 兜底；AI 识别的新食材作为回填候选（用户确认后经 /foods/confirm-ai 入库 source=ai）。
 * 场景：① 整道菜描述 → AI 估算；② 手动食材清单中未命中库的 → LLM 补齐 per100。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AiNutritionService {

    private final FoodLibraryService foodLibraryService;
    private final NutritionCalculator nutritionCalculator;
    private final DeepSeekClient deepSeekClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String SYSTEM_FOODS = """
            你是食材营养专家。根据食材名给出每 100g 的合理营养近似值（热量千卡/蛋白g/脂肪g/碳水g）。
            只输出 JSON 数组，不要多余文字。结构：[{"name":"食材名","per100":{"calories":118,"protein":22.2,"fat":3.4,"carbs":0}}]
            """;

    private static final String SYSTEM_DISH = """
            你是营养估算助手。根据菜品描述，识别主要食材与调味油，估算每 100g 营养与整道菜的份量(克)，并汇总整道菜营养。
            只输出 JSON，不要多余文字。结构：{"items":[{"name":"食材","amount":150,"per100":{"calories":395,"protein":13.2,"fat":37,"carbs":2.4}}],"calories":850,"protein":32,"fat":48,"carbs":60}
            """;

    /** 整道菜描述 → AI 估算（无精确食材场景） */
    public Map<String, Object> estimateByDescription(String description) {
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("请提供菜品描述");
        }
        String raw = deepSeekClient.chat(List.of(
                Map.of("role", "system", "content", SYSTEM_DISH),
                Map.of("role", "user", "content", "菜品描述：" + description.trim())), true);
        JsonNode node;
        try {
            node = objectMapper.readTree(cleanJson(raw));
        } catch (Exception e) {
            log.warn("ai dish parse failed: {}", e.getMessage());
            throw new IllegalArgumentException("AI 营养估算失败，请稍后重试或改用手动食材清单");
        }
        List<Map<String, Object>> aiFoods = new ArrayList<>();
        double cal = 0, protein = 0, fat = 0, carbs = 0;
        int matched = 0;

        JsonNode items = node.path("items");
        if (items.isArray() && items.size() > 0) {
            for (JsonNode it : items) {
                String name = it.path("name").asText("");
                if (name.isBlank()) continue;
                double amount = it.path("amount").asDouble(0);
                JsonNode p100 = it.path("per100");
                double pCal = p100.path("calories").asDouble(0);
                double pPro = p100.path("protein").asDouble(0);
                double pFat = p100.path("fat").asDouble(0);
                double pCarb = p100.path("carbs").asDouble(0);

                NutritionFood lib = foodLibraryService.findByExact(name);
                if (lib != null) { // 字典优先
                    pCal = lib.cal(); pPro = lib.protein(); pFat = lib.fat(); pCarb = lib.carbs();
                    matched++;
                } else {
                    aiFoods.add(Map.of(
                            "name", name,
                            "per100", Map.of("calories", pCal, "protein", pPro, "fat", pFat, "carbs", pCarb)));
                }
                if (amount <= 0) continue;
                cal += pCal * amount / 100.0;
                protein += pPro * amount / 100.0;
                fat += pFat * amount / 100.0;
                carbs += pCarb * amount / 100.0;
            }
        }
        // items 缺失时用 LLM 汇总值兜底
        if (cal <= 0) {
            cal = node.path("calories").asDouble(0);
            protein = node.path("protein").asDouble(0);
            fat = node.path("fat").asDouble(0);
            carbs = node.path("carbs").asDouble(0);
        }
        return buildResult(cal, protein, fat, carbs, true, aiFoods, matched);
    }

    /** 手动食材清单：未命中库的 → LLM 补 per100 → 合并计算（字典优先） */
    public Map<String, Object> fillMissing(List<Map<String, Object>> items) {
        if (items == null || items.isEmpty()) throw new IllegalArgumentException("请至少填写一种食材");
        NutritionCalculator.NutritionResult base = nutritionCalculator.computeFoods(items, 1.0);
        List<String> missing = new ArrayList<>(base.unmatched);
        missing.removeIf(s -> s == null || s.isBlank());

        Map<String, Map<String, Double>> aiMap = new HashMap<>();
        if (!missing.isEmpty()) {
            String q = "这些食材每 100g 营养是多少？" + String.join("、", missing);
            try {
                String raw = deepSeekClient.chat(List.of(
                        Map.of("role", "system", "content", SYSTEM_FOODS),
                        Map.of("role", "user", "content", q)), true);
                JsonNode arr = objectMapper.readTree(cleanJson(raw));
                if (arr.isArray()) {
                    for (JsonNode n : arr) {
                        String name = n.path("name").asText("");
                        if (name.isBlank()) continue;
                        JsonNode p100 = n.path("per100");
                        aiMap.put(name, Map.of(
                                "calories", p100.path("calories").asDouble(0),
                                "protein", p100.path("protein").asDouble(0),
                                "fat", p100.path("fat").asDouble(0),
                                "carbs", p100.path("carbs").asDouble(0)));
                    }
                }
            } catch (Exception e) {
                log.warn("ai fillMissing failed: {}", e.getMessage());
            }
        }

        // 重新计算：库内用库值，缺失用 AI 值
        double cal = 0, protein = 0, fat = 0, carbs = 0;
        List<Map<String, Object>> aiFoods = new ArrayList<>();
        int matched = 0, aiUsed = 0;
        for (Map<String, Object> it : items) {
            String name = it.get("name") instanceof String s ? s : null;
            if (name == null || name.isBlank()) continue;
            Object amt = it.get("amount");
            double amount = amt instanceof Number n ? n.doubleValue() : 0;
            if (amount <= 0) continue;
            NutritionFood lib = foodLibraryService.findByExact(name);
            double pCal, pPro, pFat, pCarb;
            if (lib != null) {
                pCal = lib.cal(); pPro = lib.protein(); pFat = lib.fat(); pCarb = lib.carbs();
                matched++;
            } else {
                Map<String, Double> ai = aiMap.get(name);
                if (ai == null) continue;
                pCal = ai.get("calories"); pPro = ai.get("protein"); pFat = ai.get("fat"); pCarb = ai.get("carbs");
                aiUsed++;
                aiFoods.add(Map.of(
                        "name", name,
                        "per100", Map.of("calories", pCal, "protein", pPro, "fat", pFat, "carbs", pCarb)));
            }
            cal += pCal * amount / 100.0;
            protein += pPro * amount / 100.0;
            fat += pFat * amount / 100.0;
            carbs += pCarb * amount / 100.0;
        }
        boolean estimated = base.estimated || aiUsed > 0;
        return buildResult(cal, protein, fat, carbs, estimated, aiFoods, matched);
    }

    private Map<String, Object> buildResult(double cal, double protein, double fat, double carbs,
                                            boolean estimated, List<Map<String, Object>> aiFoods, int matched) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("calories", Math.round(cal));
        result.put("protein", round1(protein));
        result.put("fat", round1(fat));
        result.put("carbs", round1(carbs));
        result.put("source", estimated ? "AI估算" : "库内");
        result.put("estimated", estimated);
        result.put("matchedCount", matched);
        result.put("aiFoods", aiFoods);
        result.put("aiUsed", !aiFoods.isEmpty());
        return result;
    }

    private String cleanJson(String raw) {
        if (raw == null) return "{}";
        String s = raw.trim();
        if (s.startsWith("```")) s = s.replaceAll("```(?:json)?", "").replace("```", "").trim();
        int start = s.indexOf('[');
        int startObj = s.indexOf('{');
        char open = (start >= 0 && (startObj < 0 || start < startObj)) ? '[' : '{';
        char close = open == '[' ? ']' : '}';
        int a = s.indexOf(open);
        int b = s.lastIndexOf(close);
        if (a >= 0 && b > a) return s.substring(a, b + 1);
        return s;
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    static BigDecimal dec(double v) {
        return BigDecimal.valueOf(v);
    }
}
