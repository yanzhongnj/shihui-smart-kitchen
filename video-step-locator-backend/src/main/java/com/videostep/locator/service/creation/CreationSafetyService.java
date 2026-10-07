package com.videostep.locator.service.creation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videostep.locator.entity.AiCreation;
import com.videostep.locator.entity.HealthProfile;
import com.videostep.locator.entity.RecipeMetadata;
import com.videostep.locator.repository.HealthProfileRepository;
import com.videostep.locator.service.PreferenceService;
import com.videostep.locator.service.safety.SafetyCheckService;
import com.videostep.locator.service.safety.SensitiveFieldCipher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 酿味安全红线校验（CreationSafetyService）。
 *
 * ① 生食/相克规则层（LLM 之外的硬兜底，净新增）：生食肉类 → RED 拦截；相克组合 → YELLOW 提示。
 * ② 画像兼容性：通过 RecipeMetadata 适配复用 {@link PreferenceService#evaluateMerged}（RED/YELLOW/GREEN），不改动既有寻味调用。
 * 返回结构与 PreferenceService 一致：{ level: RED|YELLOW|GREEN, reasons: [] }。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CreationSafetyService {

    public static final String LEVEL_RED = "RED";
    public static final String LEVEL_YELLOW = "YELLOW";
    public static final String LEVEL_GREEN = "GREEN";

    private final PreferenceService preferenceService;
    private final SafetyCheckService safetyCheckService;
    private final HealthProfileRepository healthProfileRepository;
    private final SensitiveFieldCipher cipher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 生食风险短语（命中即 RED） */
    private static final Set<String> RAW_EAT_PHRASES = Set.of(
            "生吃", "刺身", "生食", "凉拌生", "直接生", "生拌", "不烹饪", "无需烹饪");

    /** 常见肉类关键词（配合生食短语判断） */
    private static final Set<String> MEAT_KEYWORDS = Set.of(
            "鸡", "鸭", "鹅", "猪", "牛", "羊", "鱼", "虾", "蟹", "贝", "肉", "海鲜", "三文鱼", "刺身", "蛙");

    /**
     * 对生成/修订后的菜谱做安全校验。
     *
     * @return { level, reasons, safetyItems }；level=RED 时调用方应拦截不允许发布。
     */
    public Map<String, Object> check(Long userId, AiCreation recipe) {
        List<String> hardReasons = new ArrayList<>();

        // ① 生食肉类 → RED（保留，真风险）
        String rawReason = checkRawMeat(recipe);
        if (rawReason != null) hardReasons.add(rawReason);

        // ② 规则库匹配（D1：伪相克 CONFLICT_PAIRS 已移除 → 由规则库 MYTH 科普 + 四类真实风险承担）
        List<String> ingredients = new ArrayList<>(parseStringArray(recipe.getMainIngredients()));
        ingredients.addAll(parseStringArray(recipe.getAuxiliaryIngredients()));
        List<String> userConditions = loadUserConditions(userId);
        Map<String, Object> safety = safetyCheckService.check(ingredients, null, userConditions);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> riskItems = (List<Map<String, Object>>) safety.getOrDefault("riskItems", List.of());
        boolean blocked = Boolean.TRUE.equals(safety.get("blocked"));
        if (blocked) {
            hardReasons.add("命中高风险禁用食材（河豚/毒蘑菇等），禁止创作该菜品");
        }

        // ③ 画像兼容性（复用 PreferenceService）
        Map<String, Object> compat = preferenceService.checkCompatibility(userId, toRecipeMetadata(recipe));
        String compatLevel = (String) compat.getOrDefault("level", LEVEL_GREEN);
        @SuppressWarnings("unchecked")
        List<String> compatReasons = (List<String>) compat.getOrDefault("reasons", List.of());

        // 合并：生食/硬禁 RED 优先；其次画像 RED；规则库非 GREEN 提示 + 画像 YELLOW；MYTH 仅在 safetyItems 展示
        List<String> yellow = new ArrayList<>();
        for (Map<String, Object> item : riskItems) {
            String lv = String.valueOf(item.getOrDefault("level", LEVEL_GREEN));
            if (!LEVEL_GREEN.equals(lv)) {
                yellow.add(String.valueOf(item.getOrDefault("title", "")));
            }
        }
        yellow.addAll(compatReasons);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("safetyItems", riskItems);
        if (!hardReasons.isEmpty()) {
            r.put("level", LEVEL_RED);
            r.put("reasons", hardReasons);
            return r;
        }
        if (LEVEL_RED.equals(compatLevel)) {
            r.put("level", LEVEL_RED);
            r.put("reasons", compatReasons);
            return r;
        }
        if (!yellow.isEmpty()) {
            r.put("level", LEVEL_YELLOW);
            r.put("reasons", yellow);
            return r;
        }
        r.put("level", LEVEL_GREEN);
        r.put("reasons", List.of());
        return r;
    }

    /** 生食肉类规则：步骤/食材中出现「生食短语 + 肉类关键词」→ 提示 */
    private String checkRawMeat(AiCreation recipe) {
        String haystack = String.join(" ",
                safe(recipe.getTitle()),
                safe(recipe.getSummary()),
                safe(recipe.getSteps()),
                safe(recipe.getMainIngredients()),
                safe(recipe.getAuxiliaryIngredients()));
        String lower = haystack.toLowerCase(Locale.ROOT);
        for (String phrase : RAW_EAT_PHRASES) {
            if (lower.contains(phrase)) {
                // 确认邻近有肉类关键词
                for (String meat : MEAT_KEYWORDS) {
                    if (lower.contains(meat)) {
                        return "检测到「" + phrase + "」相关生食肉类风险，请避免创作生食肉类菜品";
                    }
                }
            }
        }
        return null;
    }

    /** 用户画像条件：health_profiles 的 medications/conditions（AES 解密，逗号拆分） */
    private List<String> loadUserConditions(Long userId) {
        List<String> out = new ArrayList<>();
        if (userId == null) return out;
        HealthProfile p = healthProfileRepository.selectOne(new LambdaQueryWrapper<HealthProfile>()
                .eq(HealthProfile::getUserId, userId));
        if (p == null) return out;
        addConditionTokens(out, cipher.decrypt(p.getMedications()));
        addConditionTokens(out, cipher.decrypt(p.getConditions()));
        return out;
    }

    private void addConditionTokens(List<String> out, String plain) {
        if (plain == null || plain.isBlank()) return;
        for (String t : plain.split("[,，;；\\s]+")) {
            String trim = t.trim();
            if (!trim.isEmpty()) out.add(trim);
        }
    }

    /** 适配：ai_creations → RecipeMetadata（不改动 PreferenceService 既有签名） */
    private RecipeMetadata toRecipeMetadata(AiCreation recipe) {
        RecipeMetadata meta = new RecipeMetadata();
        meta.setTitle(recipe.getTitle());
        meta.setMainIngredients(String.join("、", parseStringArray(recipe.getMainIngredients())));
        meta.setAuxiliaryIngredients(String.join("、", parseStringArray(recipe.getAuxiliaryIngredients())));
        meta.setTasteTags(String.join("、", parseStringArray(recipe.getTasteTags())));
        meta.setAllergens(String.join("、", parseStringArray(recipe.getAllergens())));
        meta.setSpiceLevel(recipe.getSpiceLevel() == null ? 0 : recipe.getSpiceLevel());
        return meta;
    }

    private List<String> parseStringArray(String json) {
        List<String> out = new ArrayList<>();
        if (json == null || json.isBlank()) return out;
        try {
            JsonNode arr = objectMapper.readTree(json);
            if (arr.isArray()) {
                arr.forEach(n -> out.add(n.asText()));
            } else {
                out.add(json);
            }
        } catch (Exception e) {
            // 非 JSON：按逗号切分（兼容）
            out.addAll(Arrays.stream(json.split("[,，、]")).map(String::trim).filter(s -> !s.isEmpty()).toList());
        }
        return out;
    }

    private Map<String, Object> result(String level, List<String> reasons) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("level", level);
        r.put("reasons", reasons);
        return r;
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }
}
