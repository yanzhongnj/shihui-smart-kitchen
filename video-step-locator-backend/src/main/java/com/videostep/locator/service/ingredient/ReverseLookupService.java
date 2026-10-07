package com.videostep.locator.service.ingredient;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videostep.locator.entity.RecipeMetadata;
import com.videostep.locator.entity.Video;
import com.videostep.locator.repository.RecipeMetadataRepository;
import com.videostep.locator.repository.UserFavoriteRepository;
import com.videostep.locator.repository.VideoRepository;
import com.videostep.locator.service.health.CalorieLevelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 食材反查引擎（P0，设计文档 §4.2）。
 * 多食材 AND 匹配（规范名集合包含判定，直接读 recipes.ingredients_json）；
 * 容错：默认 all，结果不足 min-results 自动降级 missing-one（缺一匹配）；
 * 排序：匹配数 DESC → like(收藏)/calorie(低→高)/difficulty(时长短→长)；
 * 结果增强：matchedIngredients 高亮 + calorieLevel 分级色（联动营养模块）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReverseLookupService {

    private final RecipeMetadataRepository recipeRepository;
    private final VideoRepository videoRepository;
    private final UserFavoriteRepository favoriteRepository;
    private final CalorieLevelService calorieLevelService;
    private final FoodAliasService foodAliasService;
    private final ObjectMapper objectMapper;

    @Value("${ingredient.reverse.min-results:5}")
    private int minResults;

    @Value("${ingredient.reverse.default-mode:all}")
    private String defaultMode;

    public Map<String, Object> reverse(List<String> items, String mode, String sort,
                                      Integer maxTime, String difficulty, Long viewerUserId) {
        // 1. 查询食材归一化（food_aliases 同义归并 + 去重）
        List<String> query = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String it : items) {
            if (it == null) continue;
            String n = foodAliasService.normalize(it.trim());
            if (!n.isEmpty() && seen.add(n)) query.add(n);
        }
        if (query.isEmpty()) throw new IllegalArgumentException("请至少选择一个食材");

        // 2. 加载全部菜谱结构化食材（key=recipeId → 规范食材名集合）
        List<RecipeMetadata> all = recipeRepository.selectList(null);
        Map<Long, Set<String>> recipeIngredients = new HashMap<>();
        for (RecipeMetadata r : all) {
            Set<String> names = readNames(r.getIngredientsJson());
            if (!names.isEmpty()) recipeIngredients.put(r.getId(), names);
        }

        // 3. 计算每道可见菜谱的匹配数
        List<Match> matches = new ArrayList<>();
        for (RecipeMetadata r : all) {
            if (!isVisible(r, viewerUserId)) continue;
            Set<String> names = recipeIngredients.getOrDefault(r.getId(), Set.of());
            int hit = 0;
            List<String> matched = new ArrayList<>();
            for (String q : query) {
                if (names.contains(q)) {
                    hit++;
                    matched.add(q);
                }
            }
            if (hit > 0) matches.add(new Match(r, hit, matched));
        }

        // 4. 容错：默认 all；结果不足 min-results 自动降级 missing-one（至少缺一）
        String effMode = (mode == null || mode.isBlank()) ? defaultMode : mode.trim();
        if (!"missing-one".equals(effMode)) effMode = "all";
        boolean autoDowngraded = false;
        List<Match> filtered = filter(matches, query.size(), effMode);
        if (query.size() > 1 && "all".equals(effMode)
                && filtered.size() < Math.max(1, minResults)) {
            List<Match> wider = filter(matches, query.size(), "missing-one");
            if (wider.size() > filtered.size()) {
                filtered = wider;
                autoDowngraded = true;
                effMode = "missing-one";
            }
        }

        // 5. 难度/时长筛选（P2）：estimatedTime 过滤（未知时长在筛选生效时排除）
        if (maxTime != null || (difficulty != null && !difficulty.isBlank())) {
            filtered = filtered.stream()
                    .filter(m -> matchTimeDifficulty(m.recipe, maxTime, difficulty))
                    .collect(java.util.stream.Collectors.toList());
        }

        // 6. 排序：匹配数 DESC → 排序维度
        String effSort = (sort == null || sort.isBlank()) ? "like" : sort.trim();
        filtered.sort((a, b) -> {
            int c = Integer.compare(b.hit, a.hit);
            if (c != 0) return c;
            return switch (effSort) {
                case "calorie" -> Integer.compare(cal(a.recipe), cal(b.recipe));
                case "difficulty" -> Integer.compare(diff(a.recipe), diff(b.recipe));
                default -> Long.compare(like(b.recipe), like(a.recipe));
            };
        });

        // 7. 组装结果卡片（复用 recipe 列表卡片字段 + 匹配高亮 + 分级色）
        List<Map<String, Object>> results = new ArrayList<>();
        for (Match m : filtered) {
            results.add(toCard(m));
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("items", query);
        res.put("mode", effMode);
        res.put("autoDowngraded", autoDowngraded);
        res.put("total", results.size());
        res.put("results", results);
        // 无匹配 → 引导酿味创作（设计 §4.2 空结果引导）
        res.put("emptyGuide", results.isEmpty());
        return res;
    }

    /** 难度/时长筛选：difficulty 以 estimatedTime 分桶（简单≤15 / 中等≤45 / 困难>45 分钟，与 P0 排序代理一致） */
    private boolean matchTimeDifficulty(RecipeMetadata r, Integer maxTime, String difficulty) {
        Integer t = r.getEstimatedTime();
        if (maxTime != null) {
            if (t == null || t > maxTime) return false;
        }
        if (difficulty != null && !difficulty.isBlank()) {
            if (t == null) return false;
            return switch (difficulty.trim()) {
                case "simple" -> t <= 15;
                case "medium" -> t > 15 && t <= 45;
                case "hard" -> t > 45;
                default -> true;
            };
        }
        return true;
    }

    private List<Match> filter(List<Match> matches, int target, String mode) {
        List<Match> out = new ArrayList<>();
        for (Match m : matches) {
            if ("missing-one".equals(mode)) {
                // 缺一匹配：命中 >= target-1（target=1 时退化为全匹配）
                if (m.hit >= Math.max(1, target - 1)) out.add(m);
            } else {
                if (m.hit == target) out.add(m);
            }
        }
        return out;
    }

    private Map<String, Object> toCard(Match m) {
        RecipeMetadata r = m.recipe;
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("id", r.getId());
        card.put("videoId", r.getVideoId());
        card.put("title", r.getTitle() != null ? r.getTitle() : "");
        card.put("summary", r.getSummary());
        card.put("mainIngredients", r.getMainIngredients());
        card.put("auxiliaryIngredients", r.getAuxiliaryIngredients());
        card.put("tasteTags", r.getTasteTags());
        card.put("cuisine", r.getCuisine());
        card.put("spiceLevel", r.getSpiceLevel());
        card.put("estimatedTime", r.getEstimatedTime());
        card.put("calories", r.getCalories());
        card.put("sourceType", r.getSourceType());
        card.put("source", r.getSource() != null ? r.getSource() : RecipeMetadata.SOURCE_VIDEO_LINK);
        card.put("recipeType", r.getRecipeType() != null ? r.getRecipeType() : RecipeMetadata.TYPE_HOME);
        card.put("precisionScore", r.getPrecisionScore());
        card.put("servings", r.getServings());
        card.put("matchCount", m.hit);
        card.put("matchedIngredients", m.matched);
        card.put("calorieLevel", calorieLevelService.levelInfo(r.getCalories() == null ? 0 : r.getCalories().doubleValue()));
        return card;
    }

    private boolean isVisible(RecipeMetadata r, Long viewerUserId) {
        if ("ai_creation".equals(r.getSourceType())) return true;
        // open_source：HowToCook 等开源内容源导入的公开菜谱库（content-sync，P1）
        if ("open_source".equals(r.getSourceType())) return true;
        if (RecipeMetadata.SOURCE_USER.equals(r.getSource())
                || RecipeMetadata.SOURCE_ARTICLE_LINK.equals(r.getSource())) {
            return viewerUserId != null && viewerUserId.equals(r.getUserId());
        }
        Video v = videoRepository.selectById(r.getVideoId());
        if (v == null) return false;
        if ("public".equals(v.getVisibility())) return true;
        return viewerUserId != null && v.getUserId().equals(viewerUserId);
    }

    private int cal(RecipeMetadata r) {
        return r.getCalories() == null ? Integer.MAX_VALUE : r.getCalories();
    }

    private int diff(RecipeMetadata r) {
        return r.getEstimatedTime() == null ? Integer.MAX_VALUE : r.getEstimatedTime();
    }

    private long like(RecipeMetadata r) {
        long byVideo = r.getVideoId() == null ? 0
                : favoriteRepository.countByVideoId(r.getVideoId());
        return byVideo + favoriteRepository.countByRecipeId(r.getId());
    }

    private Set<String> readNames(String json) {
        Set<String> out = new HashSet<>();
        if (json == null || json.isBlank() || "null".equals(json.trim())) return out;
        try {
            List<Map<String, Object>> list = objectMapper.readValue(
                    json, new TypeReference<List<Map<String, Object>>>() {});
            for (Map<String, Object> ig : list) {
                Object nameObj = ig.get("name");
                if (nameObj == null) continue;
                String n = foodAliasService.normalize(String.valueOf(nameObj).trim());
                if (!n.isEmpty()) out.add(n);
            }
        } catch (Exception e) {
            log.warn("ingredients_json 解析失败: {}", e.getMessage());
        }
        return out;
    }

    private static class Match {
        final RecipeMetadata recipe;
        final int hit;
        final List<String> matched;
        Match(RecipeMetadata recipe, int hit, List<String> matched) {
            this.recipe = recipe;
            this.hit = hit;
            this.matched = matched;
        }
    }
}
