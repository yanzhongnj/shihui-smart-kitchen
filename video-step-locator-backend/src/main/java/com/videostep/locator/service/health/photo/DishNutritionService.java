package com.videostep.locator.service.health.photo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.videostep.locator.entity.DishNutrition;
import com.videostep.locator.repository.DishNutritionRepository;
import com.videostep.locator.service.ingredient.FoodAliasService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 菜品级营养库（P1）：幂等种子（人工复核核心家常菜）→ 缓存命中 → 搜索 / 用户自定义 /
 * AI 兜底收录候选 / hit_count 回写。设计文档 v0.2 §6.3 / §8.4。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DishNutritionService {

    private final DishNutritionRepository repository;
    private final FoodAliasService foodAliasService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${health.dish-db.seed-init:true}")
    private boolean seedInit;

    /** 首批种子数量（人工复核核心家常菜） */
    @Value("${health.dish-db.init-top:200}")
    private int initTop;

    /** 菜名 → 菜品（缓存；DB 为权威，增删改后刷新） */
    private volatile Map<String, DishNutrition> cache = Collections.emptyMap();

    @PostConstruct
    public void init() {
        if (seedInit) {
            seedIfEmpty();
        }
        reloadCache();
        log.info("DishNutritionService ready, dishes={}", cache.size());
    }

    /** 幂等初始化：表为空才插入种子（is_verified=1，source=seed），返回本次插入数 */
    public int seedIfEmpty() {
        long count = repository.selectCount(null);
        if (count > 0) return 0;
        List<DishNutritionSeedData.Seed> seeds = DishNutritionSeedData.all();
        int n = Math.min(Math.max(initTop, 0), seeds.size());
        int inserted = 0;
        for (int i = 0; i < n; i++) {
            DishNutritionSeedData.Seed s = seeds.get(i);
            try {
                DishNutrition d = DishNutrition.builder()
                        .dishName(s.dishName())
                        .cuisine(s.cuisine())
                        .servingGrams(s.servingGrams())
                        .nutritionPerServing(nutritionJson(s))
                        .mainIngredients(ingredientsJson(s.mainIngredients()))
                        .isVerified(1)
                        .source("seed")
                        .hitCount(0)
                        .build();
                repository.insert(d);
                inserted++;
            } catch (Exception e) {
                log.debug("seed skip {}: {}", s.dishName(), e.getMessage());
            }
        }
        log.info("DishNutritionService seeded {} dishes (of {} seeds)", inserted, seeds.size());
        return inserted;
    }

    public void reloadCache() {
        Map<String, DishNutrition> m = new LinkedHashMap<>();
        for (DishNutrition d : repository.selectList(null)) {
            m.put(d.getDishName(), d);
        }
        this.cache = m;
    }

    /** 按菜名精确命中（识别菜名已过 food_aliases 归一） */
    public DishNutrition findByDishName(String name) {
        if (name == null || name.isBlank()) return null;
        return cache.get(name.trim());
    }

    /** 命中 → 返回每份营养（JSON 解析）；未命中返回 null（走 AI 兜底） */
    public Map<String, Object> nutritionOf(String name) {
        DishNutrition d = findByDishName(name);
        if (d == null || d.getNutritionPerServing() == null) return null;
        try {
            return objectMapper.readValue(d.getNutritionPerServing(),
                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
        } catch (Exception e) {
            log.warn("parse dish nutrition failed {}: {}", name, e.getMessage());
            return null;
        }
    }

    /**
     * 菜名归一 + 菜品库匹配（APP 直接编辑菜名辅助）：
     * 先用 food_aliases 归一到规范名，再查库；命中则附库条目（含 nutrition/servingGrams）。
     * 返回 {input, name, matched}。
     */
    public Map<String, Object> normalizeDishName(String name) {
        Map<String, Object> out = new LinkedHashMap<>();
        String raw = name == null ? "" : name.trim();
        out.put("input", raw);
        if (raw.isBlank()) {
            out.put("name", "");
            out.put("matched", null);
            return out;
        }
        String normalized = foodAliasService.normalize(raw);
        out.put("name", normalized);
        DishNutrition d = findByDishName(normalized);
        out.put("matched", d == null ? null : toMap(d));
        return out;
    }

    /** 模糊搜索（用户手动选择/语音补充辅助），默认 10 条 */
    public List<Map<String, Object>> search(String keyword, int limit) {
        String kw = (keyword == null || keyword.isBlank()) ? "" : keyword.trim();
        List<Map<String, Object>> out = new ArrayList<>();
        for (DishNutrition d : repository.search(kw, Math.max(1, Math.min(limit, 50)))) {
            out.add(toMap(d));
        }
        return out;
    }

    /** 命中次数 +1（用户确认/换选候选回写，驱动扩充优先级） */
    public void incrementHit(String dishName) {
        DishNutrition d = findByDishName(dishName);
        if (d == null || d.getId() == null) return;
        try {
            repository.incrementHit(d.getId());
        } catch (Exception e) {
            log.debug("incrementHit failed {}: {}", dishName, e.getMessage());
        }
    }

    /** AI 兜底营养用户确认 → 收录候选（source=ai, is_verified=0，待 Admin 审核） */
    @Transactional
    public void recordAiCandidate(String dishName, Map<String, Object> nutrition, int servingGrams) {
        if (dishName == null || dishName.isBlank() || findByDishName(dishName) != null) return;
        try {
            DishNutrition d = DishNutrition.builder()
                    .dishName(dishName.trim())
                    .servingGrams(servingGrams)
                    .nutritionPerServing(toJson(nutrition))
                    .isVerified(0)
                    .source("ai")
                    .hitCount(1)
                    .build();
            repository.insert(d);
            reloadCache();
            log.info("dish ai candidate recorded: {}", dishName);
        } catch (Exception e) {
            log.debug("recordAiCandidate skip {}: {}", dishName, e.getMessage());
        }
    }

    /** 用户自定义菜品（source=user，本人可信，is_verified=1） */
    @Transactional
    public DishNutrition createUserDish(String dishName, String cuisine, int servingGrams,
                                        Map<String, Object> nutrition, List<String> ingredients) {
        if (dishName == null || dishName.isBlank()) throw new IllegalArgumentException("菜名不能为空");
        if (nutrition == null || nutrition.isEmpty()) throw new IllegalArgumentException("营养数据不能为空");
        if (findByDishName(dishName) != null) throw new IllegalArgumentException("菜品已存在：" + dishName);
        // 同名在回收站（软删行仍占 uk_dish 唯一键）：提示先恢复或彻底删除，避免原生唯一键冲突 500
        if (repository.findDeletedByName(dishName.trim()) != null) {
            throw new IllegalArgumentException("菜品「" + dishName.trim() + "」已在回收站中，请联系管理员恢复或彻底删除后重建");
        }
        DishNutrition d = DishNutrition.builder()
                .dishName(dishName.trim())
                .cuisine(cuisine)
                .servingGrams(servingGrams > 0 ? servingGrams : 300)
                .nutritionPerServing(toJson(nutrition))
                .mainIngredients(ingredients == null ? null : ingredientsJson(ingredients))
                .isVerified(1)
                .source("user")
                .hitCount(0)
                .build();
        repository.insert(d);
        reloadCache();
        return d;
    }

    // ---------------- Admin ----------------

    public Map<String, Object> adminList(String keyword, Integer verified, String source,
                                         boolean sortHit, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        String kw = (keyword == null || keyword.isBlank()) ? null : keyword.trim();
        String src = (source == null || source.isBlank()) ? null : source.trim();
        List<Map<String, Object>> items = new ArrayList<>();
        for (DishNutrition d : repository.findAdminPage(kw, verified, src, sortHit, safePage * safeSize, safeSize)) {
            items.add(toMap(d));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("total", repository.countAdmin(kw, verified, src));
        out.put("page", safePage);
        out.put("size", safeSize);
        return out;
    }

    @Transactional
    public DishNutrition update(Long id, String dishName, String cuisine, Integer servingGrams,
                                Map<String, Object> nutrition, List<String> ingredients) {
        DishNutrition d = repository.selectById(id);
        if (d == null) throw new IllegalArgumentException("菜品不存在");
        if (dishName != null && !dishName.isBlank()) {
            DishNutrition exist = findByDishName(dishName.trim());
            if (exist != null && !exist.getId().equals(id)) throw new IllegalArgumentException("菜名已存在：" + dishName);
            d.setDishName(dishName.trim());
        }
        if (cuisine != null) d.setCuisine(cuisine);
        if (servingGrams != null && servingGrams > 0) d.setServingGrams(servingGrams);
        if (nutrition != null && !nutrition.isEmpty()) d.setNutritionPerServing(toJson(nutrition));
        if (ingredients != null) d.setMainIngredients(ingredientsJson(ingredients));
        repository.updateById(d);
        reloadCache();
        return d;
    }

    /** 审核通过（AI 候选 → is_verified=1） */
    @Transactional
    public DishNutrition verify(Long id) {
        DishNutrition d = repository.selectById(id);
        if (d == null) throw new IllegalArgumentException("菜品不存在");
        d.setIsVerified(1);
        repository.updateById(d);
        reloadCache();
        return d;
    }

    /** 驳回/删除（未复核候选或冗余条目） */
    @Transactional
    public void delete(Long id) {
        if (repository.selectById(id) == null) throw new IllegalArgumentException("菜品不存在");
        // 软删进回收站（对齐体系；deleted_at 一并记录）；恢复/彻底删除见 RecycleService(TYPE_DISHES)
        repository.softDeleteById(id);
        reloadCache();
    }

    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", repository.countAll());
        m.put("verified", repository.countVerified());
        m.put("aiCandidates", repository.countAiCandidates());
        m.put("topByHit", repository.topByHit(10));
        return m;
    }

    // ---------------- 工具 ----------------

    private Map<String, Object> toMap(DishNutrition d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("dishName", d.getDishName());
        m.put("cuisine", d.getCuisine());
        m.put("servingGrams", d.getServingGrams());
        m.put("isVerified", d.getIsVerified());
        m.put("source", d.getSource());
        m.put("hitCount", d.getHitCount());
        m.put("createdAt", d.getCreatedAt());
        m.put("mainIngredients", parseIngredients(d.getMainIngredients()));
        m.put("nutrition", nutritionOf(d.getDishName()));
        return m;
    }

    private List<String> parseIngredients(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<String> out = new ArrayList<>();
            ArrayNode arr = (ArrayNode) objectMapper.readTree(json);
            arr.forEach(n -> out.add(n.path("name").asText()));
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private String nutritionJson(DishNutritionSeedData.Seed s) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("calories", Math.round(s.cal()));
        n.put("protein", s.protein());
        n.put("fat", s.fat());
        n.put("carbs", s.carbs());
        n.put("fiber", s.fiber());
        n.put("sodium", s.sodium());
        return n.toString();
    }

    private String ingredientsJson(List<String> names) {
        ArrayNode arr = objectMapper.createArrayNode();
        for (String n : names) {
            ObjectNode o = arr.addObject();
            o.put("name", n);
        }
        return arr.toString();
    }

    private String toJson(Map<String, Object> m) {
        try {
            return objectMapper.writeValueAsString(m);
        } catch (Exception e) {
            return "{}";
        }
    }
}
