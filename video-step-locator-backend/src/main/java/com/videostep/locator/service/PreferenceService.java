package com.videostep.locator.service;

import com.videostep.locator.entity.RecipeMetadata;
import com.videostep.locator.entity.UserPreference;
import com.videostep.locator.entity.XunweiMemory;
import com.videostep.locator.repository.UserPreferenceRepository;
import com.videostep.locator.service.xunwei.XunweiMemoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 口味/忌口画像 与 菜谱兼容性告警引擎（RED=过敏/忌口，YELLOW=口味不符，GREEN=推荐）。
 * P3：融合两套画像 —— 显式设置（user_preferences）+ 寻味学习画像（xunwei_memory，含对话抽取与行为反馈）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PreferenceService {

    private final UserPreferenceRepository preferenceRepository;
    private final XunweiMemoryService xunweiMemoryService;

    public UserPreference get(Long userId) {
        UserPreference p = preferenceRepository.findByUserId(userId);
        if (p == null) {
            p = UserPreference.builder().userId(userId).spiceLevel(2).build();
        }
        return p;
    }

    public UserPreference update(Long userId, UserPreference update) {
        UserPreference p = preferenceRepository.findByUserId(userId);
        if (p == null) {
            if (update.getSpiceLevel() == null) update.setSpiceLevel(2);
            update.setId(null);
            update.setUserId(userId);
            preferenceRepository.insert(update);
            return update;
        }
        if (update.getSpiceLevel() != null) p.setSpiceLevel(update.getSpiceLevel());
        if (update.getTabooAllergens() != null) p.setTabooAllergens(update.getTabooAllergens());
        if (update.getTabooIngredients() != null) p.setTabooIngredients(update.getTabooIngredients());
        if (update.getPreferredTastes() != null) p.setPreferredTastes(update.getPreferredTastes());
        if (update.getPreferredCuisines() != null) p.setPreferredCuisines(update.getPreferredCuisines());
        if (update.getMaxCalories() != null) p.setMaxCalories(update.getMaxCalories());
        if (update.getNotes() != null) p.setNotes(update.getNotes());
        preferenceRepository.updateById(p);
        return p;
    }

    /** 计算某个菜谱与用户画像的兼容性（P3：显式设置 + 寻味学习画像融合） */
    public Map<String, Object> checkCompatibility(Long userId, RecipeMetadata recipe) {
        return evaluateMerged(userId, recipe);
    }

    /** 仅显式设置评估（兼容保留） */
    public Map<String, Object> evaluate(UserPreference pref, RecipeMetadata recipe) {
        if (pref == null || recipe == null) return emptyGreen("无画像或菜谱数据");
        int spice = pref.getSpiceLevel() == null ? 2 : pref.getSpiceLevel();
        return evaluateCore(recipe,
                split(pref.getTabooAllergens()), split(pref.getTabooIngredients()),
                split(pref.getPreferredTastes()), new HashSet<>(),
                split(pref.getPreferredCuisines()), spice);
    }

    /** 融合评估：显式设置（优先）+ 寻味学习画像（对话抽取/行为反馈） */
    public Map<String, Object> evaluateMerged(Long userId, RecipeMetadata recipe) {
        Profile p = loadMergedProfile(userId);
        if (p.nothing()) return emptyGreen("无画像或菜谱数据");
        return evaluateCore(recipe, p.tabooAllergens(), p.tabooIngredients(),
                p.prefTastes(), p.prefDishes(), p.prefCuisines(), p.spice());
    }

    /**
     * P3 个性化排序分：兼容性等级权重 + 偏好口味/喜欢的菜/菜系命中加成。
     * RED=-20 / YELLOW=-5 / GREEN=5，命中偏好口味 +3、喜欢的菜 +3、偏好菜系 +4。
     */
    public int matchScore(Long userId, RecipeMetadata recipe) {
        Profile p = loadMergedProfile(userId);
        if (p.nothing() || recipe == null) return 0;
        Map<String, Object> eval = evaluateCore(recipe, p.tabooAllergens(), p.tabooIngredients(),
                p.prefTastes(), p.prefDishes(), p.prefCuisines(), p.spice());
        int score = switch ((String) eval.get("level")) {
            case "RED" -> -20;
            case "YELLOW" -> -5;
            default -> 5;
        };
        Set<String> recipeTastes = split(recipe.getTasteTags());
        for (String t : p.prefTastes()) {
            if (recipeTastes.contains(t)) score += 3;
        }
        if (recipe.getTitle() != null) {
            for (String d : p.prefDishes()) {
                if (d.length() >= 2 && recipe.getTitle().contains(d)) {
                    score += 3;
                    break;
                }
            }
        }
        // P4 降权：AI 创作菜谱（source_type='ai_creation'）在个性化排序中降低权重（避免压过真实教学菜谱）
        if ("ai_creation".equals(recipe.getSourceType())) {
            score -= 3;
        }
        if (recipe.getCuisine() != null && p.prefCuisines().contains(recipe.getCuisine())) score += 4;
        return score;
    }

    private Map<String, Object> evaluateCore(RecipeMetadata recipe,
            Set<String> tabooAllergens, Set<String> tabooIngredients,
            Set<String> prefTastes, Set<String> prefDishes,
            Set<String> prefCuisines, int prefSpice) {
        List<String> reasons = new ArrayList<>();
        if (recipe == null) return emptyGreen("无画像或菜谱数据");
        String level = "GREEN";

        Set<String> recipeAllergens = split(recipe.getAllergens());
        Set<String> recipeIngredients = new HashSet<>();
        recipeIngredients.addAll(split(recipe.getMainIngredients()));
        recipeIngredients.addAll(split(recipe.getAuxiliaryIngredients()));
        Set<String> recipeTastes = split(recipe.getTasteTags());
        int recipeSpice = recipe.getSpiceLevel() == null ? 0 : recipe.getSpiceLevel();

        // RED：命中过敏原 / 忌口食材
        for (String a : recipeAllergens) {
            if (tabooAllergens.contains(a)) {
                level = "RED";
                reasons.add("含过敏原「" + a + "」，不建议食用");
            }
        }
        for (String i : recipeIngredients) {
            if (tabooIngredients.contains(i)) {
                level = "RED";
                reasons.add("含忌口食材「" + i + "」");
            }
        }
        // RED：口味忌口（如"不吃辣" → 菜谱口味含"辣"）
        boolean tasteTaboo = tabooIngredients.stream().anyMatch(t -> t != null && t.contains("辣"));
        if (tasteTaboo && recipeTastes.stream().anyMatch(rt -> rt != null && rt.contains("辣"))) {
            level = "RED";
            reasons.add("含忌口「不吃辣」风味");
        }

        // YELLOW：辛辣度过高
        if (!"RED".equals(level) && recipeSpice > prefSpice * 2) {
            level = "YELLOW";
            reasons.add("偏辣（辛辣度" + recipeSpice + "），可能不合口味");
        }

        // YELLOW：指定了偏好口味但菜谱口味未命中（仅口味词，不含喜欢的菜）
        if (!"RED".equals(level) && !prefTastes.isEmpty() && !recipeTastes.isEmpty()
                && recipeTastes.stream().noneMatch(prefTastes::contains)) {
            level = "YELLOW";
            reasons.add("口味「" + String.join("/", recipeTastes) + "」可能与偏好不符");
        }

        // GREEN：命中偏好口味/喜欢的菜/菜系 → 补充推荐理由
        if ("GREEN".equals(level)) {
            recipeTastes.stream().filter(prefTastes::contains).findFirst()
                    .ifPresent(t -> reasons.add("符合偏好口味「" + t + "」"));
            if (recipe.getTitle() != null) {
                for (String d : prefDishes) {
                    if (d.length() >= 2 && recipe.getTitle().contains(d)) {
                        reasons.add("符合你喜欢的菜「" + d + "」");
                        break;
                    }
                }
            }
            if (recipe.getCuisine() != null && prefCuisines.contains(recipe.getCuisine())) {
                reasons.add("符合偏好菜系「" + recipe.getCuisine() + "」");
            }
            if (reasons.isEmpty()) reasons.add("无冲突");
        }

        Map<String, Object> result = new HashMap<>();
        result.put("level", level);
        result.put("reasons", reasons);
        return result;
    }

    /** 融合画像：显式设置 + 寻味学习记忆（显式字段由显式设置占位，学习补充缺失维度） */
    private Profile loadMergedProfile(Long userId) {
        UserPreference pref = preferenceRepository.findByUserId(userId);
        Set<String> tabooAllergens = new HashSet<>();
        Set<String> tabooIngredients = new HashSet<>();
        Set<String> prefTastes = new HashSet<>();
        Set<String> prefDishes = new HashSet<>();
        Set<String> prefCuisines = new HashSet<>();
        int spice = 2;
        if (pref != null) {
            tabooAllergens.addAll(split(pref.getTabooAllergens()));
            tabooIngredients.addAll(split(pref.getTabooIngredients()));
            prefTastes.addAll(split(pref.getPreferredTastes()));
            prefCuisines.addAll(split(pref.getPreferredCuisines()));
            if (pref.getSpiceLevel() != null) spice = pref.getSpiceLevel();
        }
        for (XunweiMemory m : xunweiMemoryService.list(userId)) {
            String val = m.getMemValue();
            if (val == null || val.isBlank()) continue;
            switch (m.getMemKey()) {
                case "taboo", "disliked_dishes", "avoided_ingredients" -> tabooIngredients.addAll(split(val));
                case "taste" -> prefTastes.addAll(split(val));
                case "liked_dishes", "preferred_ingredients" -> prefDishes.addAll(split(val));
                case "cuisine" -> prefCuisines.addAll(split(val));
                default -> {
                }
            }
        }
        return new Profile(tabooAllergens, tabooIngredients, prefTastes, prefDishes, prefCuisines, spice);
    }

    private Map<String, Object> emptyGreen(String message) {
        Map<String, Object> empty = new HashMap<>();
        empty.put("level", "GREEN");
        empty.put("reasons", new ArrayList<String>());
        empty.put("message", message);
        return empty;
    }

    /** 融合后的有效画像 */
    private record Profile(Set<String> tabooAllergens, Set<String> tabooIngredients,
                           Set<String> prefTastes, Set<String> prefDishes,
                           Set<String> prefCuisines, int spice) {
        boolean nothing() {
            return tabooAllergens.isEmpty() && tabooIngredients.isEmpty()
                    && prefTastes.isEmpty() && prefDishes.isEmpty()
                    && prefCuisines.isEmpty() && spice == 2;
        }
    }

    private Set<String> split(String s) {
        if (s == null || s.isBlank()) return new HashSet<>();
        return Arrays.stream(s.split("[,，;；、]"))
                .map(String::trim).filter(x -> !x.isEmpty()).collect(Collectors.toSet());
    }
}
