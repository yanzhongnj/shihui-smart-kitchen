package com.videostep.locator.service.ingredient;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videostep.locator.entity.IngredientTag;
import com.videostep.locator.entity.RecipeMetadata;
import com.videostep.locator.repository.IngredientTagRepository;
import com.videostep.locator.repository.RecipeMetadataRepository;
import com.videostep.locator.service.health.FoodLibraryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 食材标签索引构建（食材反查 P0，设计文档 §4.1）。
 * 数据源：recipes.ingredients_json（结构化食材，非正则解析）；
 * 归一：food_aliases 同义归并；分类：联动 nutrition_food_db.category；
 * 重建策略：启动懒重建（表空时首次访问）+ 每日 cron（ingredient.index.rebuild-cron）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IngredientIndexService {

    private final RecipeMetadataRepository recipeRepository;
    private final IngredientTagRepository tagRepository;
    private final FoodAliasService foodAliasService;
    private final FoodLibraryService foodLibraryService;
    private final ObjectMapper objectMapper;

    /** 首次访问懒重建标记（避免每次请求探测表空） */
    private volatile boolean built = false;

    /** 首次访问确保索引存在（表空才重建，幂等） */
    public void ensureIndex() {
        if (built) return;
        synchronized (this) {
            if (built) return;
            if (tagRepository.selectCount(null) == 0) {
                rebuild();
            }
            built = true;
        }
    }

    /** 每日重建食材标签索引（配置 ingredient.index.rebuild-cron，Spring 6 段 cron） */
    @Scheduled(cron = "${ingredient.index.rebuild-cron:0 0 3 * * *}")
    public synchronized int rebuild() {
        Map<String, TagAccum> acc = new LinkedHashMap<>();
        List<RecipeMetadata> recipes = recipeRepository.selectList(null);
        int withJson = 0;
        for (RecipeMetadata r : recipes) {
            List<Map<String, Object>> ingredients = readIngredients(r.getIngredientsJson());
            if (ingredients.isEmpty()) continue;
            withJson++;
            // 每菜谱内去重计数（同一菜谱同一食材只计一次）
            Set<String> seen = new HashSet<>();
            for (Map<String, Object> ig : ingredients) {
                Object nameObj = ig.get("name");
                if (nameObj == null || String.valueOf(nameObj).isBlank()) continue;
                String canonical = foodAliasService.normalize(String.valueOf(nameObj).trim());
                if (canonical.isEmpty() || seen.contains(canonical)) continue;
                seen.add(canonical);
                TagAccum a = acc.computeIfAbsent(canonical, TagAccum::new);
                a.frequency++;
                if (!isMain(ig)) a.hasNonMain = true;
            }
        }
        // 全量重建（物理删除 + 重插，幂等）
        tagRepository.deleteAll();
        for (TagAccum a : acc.values()) {
            IngredientTag t = IngredientTag.builder()
                    .name(a.name)
                    .canonicalName(a.name)
                    .category(categoryOf(a.name))
                    .frequency(a.frequency)
                    .isMainOnly(a.hasNonMain ? 0 : 1)
                    .build();
            try {
                tagRepository.insert(t);
            } catch (Exception e) {
                log.debug("ingredient tag insert skip {}: {}", a.name, e.getMessage());
            }
        }
        built = true;
        log.info("食材标签索引重建完成：{} 道菜谱含结构化食材，生成 {} 个标签", withJson, acc.size());
        return acc.size();
    }

    /** 标签云（按频次倒序，支持名称过滤与数量限制） */
    public List<Map<String, Object>> tags(int limit, String keyword) {
        LambdaQueryWrapper<IngredientTag> w = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            w.like(IngredientTag::getName, keyword.trim());
        }
        w.orderByDesc(IngredientTag::getFrequency).orderByAsc(IngredientTag::getName);
        int safeLimit = Math.max(1, Math.min(limit, 500));
        w.last("LIMIT " + safeLimit);
        List<Map<String, Object>> out = new ArrayList<>();
        for (IngredientTag t : tagRepository.selectList(w)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("name", t.getName());
            m.put("canonicalName", t.getCanonicalName());
            m.put("category", t.getCategory());
            m.put("frequency", t.getFrequency());
            m.put("isMainOnly", t.getIsMainOnly());
            out.add(m);
        }
        return out;
    }

    /** 分类联动 nutrition_food_db.category（未收录为 null） */
    private String categoryOf(String name) {
        var food = foodLibraryService.findByExact(name);
        return food == null ? null : food.getCategory();
    }

    private boolean isMain(Map<String, Object> ig) {
        Object v = ig.get("isMain") != null ? ig.get("isMain") : ig.get("is_main");
        if (v == null) return true;
        String s = String.valueOf(v).trim();
        return !("0".equals(s) || "false".equalsIgnoreCase(s));
    }

    /** 解析 ingredients_json → List<Map>（空/非法返回空列表） */
    private List<Map<String, Object>> readIngredients(String json) {
        if (json == null || json.isBlank() || "null".equals(json.trim())) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.warn("ingredients_json 解析失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** 聚合器：频次 + 是否出现过非主料 */
    private static class TagAccum {
        final String name;
        int frequency;
        boolean hasNonMain;
        TagAccum(String name) { this.name = name; }
    }
}
