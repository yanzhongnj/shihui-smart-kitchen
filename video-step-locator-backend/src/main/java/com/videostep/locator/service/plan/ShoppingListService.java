package com.videostep.locator.service.plan;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videostep.locator.entity.CookingPlan;
import com.videostep.locator.entity.CookingPlanItem;
import com.videostep.locator.entity.NutritionFood;
import com.videostep.locator.entity.RecipeIngredient;
import com.videostep.locator.entity.RecipeMetadata;
import com.videostep.locator.entity.ShoppingList;
import com.videostep.locator.repository.CookingPlanItemRepository;
import com.videostep.locator.repository.CookingPlanRepository;
import com.videostep.locator.repository.RecipeIngredientRepository;
import com.videostep.locator.repository.RecipeMetadataRepository;
import com.videostep.locator.repository.ShoppingListRepository;
import com.videostep.locator.service.health.FoodLibraryService;
import com.videostep.locator.service.ingredient.FoodAliasService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 购物清单生成（做饭计划执行闭环 P0，v0.3 决策 7.7-1/7.7-7）。
 *
 * 从计划生成购物清单：仅取有 recipe_id 的条目（无 recipe_id 手工条目记入 skippedItems，
 * 仅提示不阻塞）→ 取 recipe_ingredients 精确用量 → 按人数换算（persons / servings）→
 * food_aliases 归一去重 → nutrition_food_db 分类 → 本次拍照识别的存量扣减（stockIngredients）→
 * 手动勾选/改量/补录（updateItems）→ 导出文本（export）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ShoppingListService {

    private final ShoppingListRepository listRepository;
    private final CookingPlanRepository planRepository;
    private final CookingPlanItemRepository itemRepository;
    private final RecipeMetadataRepository recipeRepository;
    private final RecipeIngredientRepository ingredientRepository;
    private final FoodAliasService foodAliasService;
    private final FoodLibraryService foodLibraryService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${plan.shopping.default-persons:2}")
    private int defaultPersons;

    @Value("${plan.shopping.export-fmt:categorized}")
    private String exportFmt;

    // ================= 生成 =================

    /**
     * 从计划生成购物清单。
     * req: {planId, persons?, stockIngredients?[]}  stockIngredients 为本次拍照识别出的库存食材名列表。
     */
    public Map<String, Object> generate(Long userId, Map<String, Object> req) {
        Long planId = req.get("planId") instanceof Number n ? n.longValue() : null;
        if (planId == null) throw new IllegalArgumentException("缺少 planId");
        int persons = req.get("persons") instanceof Number n ? Math.max(1, n.intValue()) : defaultPersons;

        CookingPlan plan = planRepository.selectById(planId);
        if (plan == null || !plan.getUserId().equals(userId)) {
            throw new IllegalArgumentException("计划不存在或无权访问");
        }

        List<CookingPlanItem> items = itemRepository.findByPlanId(planId);
        Set<String> stock = new LinkedHashSet<>();
        if (req.get("stockIngredients") instanceof List<?> sl) {
            for (Object o : sl) {
                if (o != null && !String.valueOf(o).isBlank()) {
                    String norm = foodAliasService.normalize(String.valueOf(o));
                    if (!norm.isBlank()) stock.add(norm);
                }
            }
        }

        // 聚合（按归一食材名）
        Map<String, Agg> byName = new LinkedHashMap<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        for (CookingPlanItem it : items) {
            if (it.getRecipeId() == null) {
                Map<String, Object> sk = new LinkedHashMap<>();
                sk.put("itemId", it.getId());
                sk.put("recipeTitle", it.getRecipeTitle());
                sk.put("mealType", it.getMealType());
                sk.put("reason", "无关联菜谱（手工条目），未纳入清单");
                skipped.add(sk);
                continue;
            }
            RecipeMetadata r = recipeRepository.selectById(it.getRecipeId());
            if (r == null) {
                skipped.add(skItem(it, "菜谱不存在"));
                continue;
            }
            double servings = r.getServings() != null && r.getServings() > 0 ? r.getServings() : 1;
            double factor = persons / servings;
            for (RecipeIngredient ig : ingredientRepository.findByRecipeId(r.getId())) {
                String name = foodAliasService.normalize(ig.getName());
                if (name.isBlank()) continue;
                Agg agg = byName.computeIfAbsent(name, k -> new Agg());
                agg.aliases.add(ig.getName() == null ? name : ig.getName());
                if (ig.getAmount() != null) {
                    double amt = ig.getAmount().doubleValue() * factor;
                    agg.amountG += amt;
                    agg.hasAmount = true;
                    agg.unit = ig.getUnit() != null && !ig.getUnit().isBlank() ? ig.getUnit() : "g";
                } else {
                    agg.qualitative = true;
                }
                agg.category = categoryOf(name);
            }
        }

        // 构建 items_json
        List<Map<String, Object>> listItems = new ArrayList<>();
        for (Map.Entry<String, Agg> e : byName.entrySet()) {
            Agg a = e.getValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ingredient", e.getKey());
            m.put("aliases", new ArrayList<>(a.aliases));
            m.put("amount_g", a.hasAmount ? round1(a.amountG) : null);
            m.put("unit", a.unit == null ? "g" : a.unit);
            m.put("category", a.category == null ? "其他" : a.category);
            m.put("qualitative", a.qualitative);
            m.put("checked", hasStock(e.getKey(), stock) ? 1 : 0); // 本次拍照扣减 → 已有
            listItems.add(m);
        }

        // 持久化（draft）
        ShoppingList sl = ShoppingList.builder()
                .userId(userId)
                .planId(planId)
                .itemsJson(toJson(listItems))
                .persons(persons)
                .totalAmount(BigDecimal.ZERO)
                .source("menu")
                .status("draft")
                .build();
        listRepository.insert(sl);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("listId", sl.getId());
        out.put("planId", planId);
        out.put("persons", persons);
        out.put("items", listItems);
        out.put("skippedItems", skipped);
        out.put("skippedCount", skipped.size());
        out.put("status", "draft");
        return out;
    }

    // ================= 手动调整 =================

    /** 手动勾选/改量/补录：整体替换 items（保持 id=清单 id，含 item 结构） */
    public Map<String, Object> updateItems(Long userId, Long listId, List<Map<String, Object>> items) {
        ShoppingList sl = requireOwned(userId, listId);
        if ("exported".equals(sl.getStatus())) {
            // 允许改回 draft 继续编辑
            sl.setStatus("draft");
        }
        sl.setItemsJson(toJson(items == null ? new ArrayList<>() : items));
        listRepository.updateById(sl);
        return detail(userId, listId);
    }

    // ================= 导出 =================

    public Map<String, Object> export(Long userId, Long listId) {
        ShoppingList sl = requireOwned(userId, listId);
        List<Map<String, Object>> items = readJsonList(sl.getItemsJson());
        String text = buildExportText(items);
        sl.setExportText(text);
        sl.setStatus("exported");
        listRepository.updateById(sl);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("listId", listId);
        out.put("exportText", text);
        out.put("status", "exported");
        return out;
    }

    // ================= 查询 =================

    public Map<String, Object> detail(Long userId, Long listId) {
        ShoppingList sl = requireOwned(userId, listId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", sl.getId());
        out.put("planId", sl.getPlanId());
        out.put("persons", sl.getPersons());
        out.put("status", sl.getStatus());
        out.put("items", readJsonList(sl.getItemsJson()));
        out.put("exportText", sl.getExportText());
        out.put("createdAt", sl.getCreatedAt());
        return out;
    }

    // ================= 内部 =================

    private String buildExportText(List<Map<String, Object>> items) {
        // 按分类分组（已勾选=已有库存，剔除）
        Map<String, List<String>> groups = new LinkedHashMap<>();
        int buyCount = 0;
        for (Map<String, Object> it : items) {
            if (Integer.valueOf(1).equals(it.get("checked"))) continue;
            String cat = it.get("category") instanceof String s && !s.isBlank() ? s : "其他";
            StringBuilder line = new StringBuilder(String.valueOf(it.getOrDefault("ingredient", "")));
            Object amt = it.get("amount_g");
            if (amt instanceof Number num && num.doubleValue() > 0) {
                line.append(" ").append(round1(num.doubleValue())).append(it.getOrDefault("unit", "g"));
            } else if (it.get("qualitative") != null && Boolean.TRUE.equals(it.get("qualitative"))) {
                line.append("（适量）");
            }
            groups.computeIfAbsent(cat, k -> new ArrayList<>()).add(line.toString());
            buyCount++;
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> e : groups.entrySet()) {
            sb.append("【").append(e.getKey()).append("】\n");
            for (String line : e.getValue()) sb.append("· ").append(line).append("\n");
        }
        if (sb.length() == 0) sb.append("（清单为空或全部已有）");
        sb.append("\n合计需购 ").append(buyCount).append(" 项");
        return sb.toString();
    }

    private boolean hasStock(String name, Set<String> stock) {
        for (String s : stock) {
            if (s.equals(name) || s.contains(name) || name.contains(s)) return true;
        }
        return false;
    }

    private String categoryOf(String name) {
        try {
            List<NutritionFood> foods = foodLibraryService.search(name, null, null);
            if (!foods.isEmpty() && foods.get(0).getCategory() != null) {
                return foods.get(0).getCategory();
            }
        } catch (Exception ignored) {
        }
        return "其他";
    }

    private ShoppingList requireOwned(Long userId, Long listId) {
        ShoppingList sl = listRepository.selectById(listId);
        if (sl == null || !sl.getUserId().equals(userId)) {
            throw new IllegalArgumentException("清单不存在或无权访问");
        }
        return sl;
    }

    private Map<String, Object> skItem(CookingPlanItem it, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("itemId", it.getId());
        m.put("recipeTitle", it.getRecipeTitle());
        m.put("mealType", it.getMealType());
        m.put("reason", reason);
        return m;
    }

    private List<Map<String, Object>> readJsonList(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node.isArray()) {
                return objectMapper.convertValue(node, new TypeReference<List<Map<String, Object>>>() {
                });
            }
        } catch (Exception e) {
            log.warn("清单 JSON 解析失败: {}", e.getMessage());
        }
        return new ArrayList<>();
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return "[]";
        }
    }

    private double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    /** 聚合中间结构 */
    private static class Agg {
        final Set<String> aliases = new LinkedHashSet<>();
        double amountG = 0;
        boolean hasAmount = false;
        boolean qualitative = false;
        String unit = "g";
        String category = null;
    }
}
