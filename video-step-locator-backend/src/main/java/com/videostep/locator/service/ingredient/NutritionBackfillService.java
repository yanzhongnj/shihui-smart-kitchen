package com.videostep.locator.service.ingredient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videostep.locator.entity.RecipeIngredient;
import com.videostep.locator.repository.RecipeIngredientRepository;
import com.videostep.locator.service.health.FoodLibraryService;
import com.videostep.locator.service.xunwei.DeepSeekClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 营养库回填增强（P 增强，设计《拾味_食材反查与内容源》§5.3 双向增强 + 营养模块 §5.3）。
 * 从导入菜谱 recipe_ingredients 收集营养库缺失食材 → LLM 估算每 100g 营养 → 回填 nutrition_food_db（source=ai）。
 * 幂等：createAiFoods 对已存在食材跳过；回填后可反查 calorieLevel 更准、跟做即记录营养更准。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NutritionBackfillService {

    private final RecipeIngredientRepository ingredientRepository;
    private final FoodLibraryService foodLibraryService;
    private final DeepSeekClient deepSeekClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String SYSTEM_FOODS = """
            你是食材营养专家。根据食材名给出每 100g 的合理营养近似值，并给出所属分类（主食/蔬菜/水果/肉类/海鲜/蛋奶/豆类/坚果/饮品/其他）。
            只输出 JSON 数组，不要多余文字。结构：[{"name":"食材名","category":"肉类","per100":{"calories":118,"protein":22.2,"fat":3.4,"carbs":0}}]
            """;

    @Value("${content-sync.howtocook.backfill-max:200}")
    private int backfillMax;

    /** 收集营养库缺失食材：扫描 recipe_ingredients → 名称清洗 → 不在 nutrition_food_db 的食材名 + 出现频次 */
    public Map<String, Object> collectMissing() {
        Map<String, Integer> counter = new HashMap<>();
        for (RecipeIngredient ig : ingredientRepository.selectList(null)) {
            String name = cleanName(ig.getName());
            if (name.isEmpty() || foodLibraryService.findByExact(name) != null) continue;
            counter.merge(name, 1, Integer::sum);
        }
        List<Map<String, Object>> missing = counter.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(Math.max(1, backfillMax))
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", e.getKey());
                    m.put("count", e.getValue());
                    return m;
                })
                .collect(Collectors.toList());
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("total", counter.size());
        res.put("missing", missing);
        return res;
    }

    /** 食材名清洗：去括号注释（“生抽（可选）”→生抽）、去 emoji/特殊符号（“鸡蛋🥚”→鸡蛋），清洗后命中营养库即跳过（去重） */
    static String cleanName(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        s = s.replaceAll("[（(][^（）()]*[）)]", " ").trim();
        s = s.replaceAll("[^\\u4e00-\\u9fa5A-Za-z0-9·、，, ]", " ").trim();
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }

    /** 回填：收集缺失 → LLM 估算 → 入库（source=ai）；dryRun=true 仅返回候选不落库 */
    public Map<String, Object> backfill(boolean dryRun, Integer max) {
        Map<String, Object> collected = collectMissing();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> missing = (List<Map<String, Object>>) collected.get("missing");
        List<String> names = missing.stream()
                .map(m -> String.valueOf(m.get("name")))
                .collect(Collectors.toList());
        if (max != null && max > 0 && names.size() > max) {
            names = names.subList(0, max);
        }
        List<Map<String, Object>> foods = estimate(names);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("dryRun", dryRun);
        res.put("collectedTotal", collected.get("total"));
        res.put("estimated", foods.size());
        res.put("candidates", foods);
        if (!dryRun) {
            int inserted = foodLibraryService.createAiFoods(foods);
            res.put("inserted", inserted);
            res.put("skippedExisting", foods.size() - inserted);
            log.info("营养库回填完成：候选 {} 入库 {} 已存在跳过 {}（源缺失 {}）",
                    foods.size(), inserted, foods.size() - inserted, collected.get("total"));
        }
        return res;
    }

    /** LLM 估算缺失食材每 100g 营养（分批，每批 ~30 个避免超长 prompt） */
    List<Map<String, Object>> estimate(List<String> names) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (names == null || names.isEmpty()) return out;
        int BATCH = 30;
        for (int i = 0; i < names.size(); i += BATCH) {
            List<String> batch = names.subList(i, Math.min(names.size(), i + BATCH));
            try {
                String raw = deepSeekClient.chat(List.of(
                        Map.of("role", "system", "content", SYSTEM_FOODS),
                        Map.of("role", "user", "content", "这些食材每 100g 营养与分类是多少？" + String.join("、", batch))), true);
                JsonNode arr = objectMapper.readTree(cleanJson(raw));
                if (arr.isArray()) {
                    for (JsonNode n : arr) {
                        String name = n.path("name").asText("").trim();
                        if (name.isBlank()) continue;
                        JsonNode p100 = n.path("per100");
                        Map<String, Object> f = new LinkedHashMap<>();
                        f.put("name", name);
                        f.put("category", n.path("category").asText(null));
                        f.put("calories", p100.path("calories").asDouble(0));
                        f.put("protein", p100.path("protein").asDouble(0));
                        f.put("fat", p100.path("fat").asDouble(0));
                        f.put("carbs", p100.path("carbs").asDouble(0));
                        out.add(f);
                    }
                }
            } catch (Exception e) {
                log.warn("营养估算 LLM 失败（批 {}）: {}", i / BATCH, e.getMessage());
            }
        }
        return out;
    }

    private String cleanJson(String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl >= 0) t = t.substring(nl + 1);
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3).trim();
        }
        return t;
    }
}
