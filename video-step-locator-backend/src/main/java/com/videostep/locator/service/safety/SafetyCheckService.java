package com.videostep.locator.service.safety;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.videostep.locator.entity.FoodSafetyRule;
import com.videostep.locator.entity.HealthProfile;
import com.videostep.locator.repository.FoodSafetyRuleRepository;
import com.videostep.locator.repository.HealthProfileRepository;
import com.videostep.locator.service.ingredient.FoodAliasService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 食物安全检查引擎（SafetyCheckService）。
 * <p>P0：规则库匹配（ingredient / combination / user_condition 三类 trigger），食材名经 {@link FoodAliasService} 归一；
 * 输出 {@code {riskItems:[{category,level,title,advice,source,confidence}], blocked}}，blocked=命中 PROC 硬禁（河豚/毒蘑菇）。
 * 设计文档《拾味_食物安全提醒知识库_技术设计文档.md》§5.1/§14。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SafetyCheckService {

    /** 全量规则统一免责声明（MYTH 辟谣除外） */
    public static final String DISCLAIMER = "仅供参考，非医疗建议";

    private final FoodSafetyRuleRepository repository;
    private final FoodAliasService foodAliasService;
    private final HealthProfileRepository healthProfileRepository;
    private final SensitiveFieldCipher cipher;

    @Value("${safety.rules.seed-init:true}")
    private boolean seedInit;

    @PostConstruct
    public void init() {
        if (seedInit) {
            seedIfEmpty();
        }
        log.info("SafetyCheckService ready");
    }

    /** 幂等初始化：表为空才插入 52 条种子，返回本次插入数 */
    public int seedIfEmpty() {
        long count = repository.selectCount(null);
        if (count > 0) {
            return 0;
        }
        int inserted = 0;
        for (SafetyRuleSeedData.Seed s : SafetyRuleSeedData.all()) {
            try {
                repository.insert(FoodSafetyRule.builder()
                        .category(s.category())
                        .riskLevel(s.riskLevel())
                        .triggerType(s.triggerType())
                        .triggerValue(s.triggerValue())
                        .relatedFood(s.relatedFood())
                        .title(s.title())
                        .advice(s.advice())
                        .source(s.source())
                        .sourceUrl(null)
                        .confidence(s.confidence())
                        .version(1)
                        .enabled(1)
                        .deleted(0)
                        .build());
                inserted++;
            } catch (Exception e) {
                // 已存在/异常 → 跳过
                log.debug("seed skip {}: {}", s.title(), e.getMessage());
            }
        }
        log.info("SafetyCheckService seeded {} rules", inserted);
        return inserted;
    }

    /**
     * P2：按用户画像条件（health_profiles medications/conditions，AES 解密）做安全校验，返回 riskItems。
     * 供打卡/复盘等「饭后提醒」场景复用（CreationSafetyService 保留自有实现）。
     */
    public List<Map<String, Object>> checkForUser(Long userId, List<String> ingredients,
                                                 List<List<String>> combinations) {
        List<String> conditions = new ArrayList<>();
        if (userId != null) {
            HealthProfile p = healthProfileRepository.selectOne(new LambdaQueryWrapper<HealthProfile>()
                    .eq(HealthProfile::getUserId, userId));
            if (p != null) {
                addConditionTokens(conditions, cipher.decrypt(p.getMedications()));
                addConditionTokens(conditions, cipher.decrypt(p.getConditions()));
            }
        }
        Map<String, Object> r = check(ingredients, combinations, conditions);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) r.getOrDefault("riskItems", List.of());
        return items;
    }

    private void addConditionTokens(List<String> out, String plain) {
        if (plain == null || plain.isBlank()) return;
        for (String t : plain.split("[,，;；\\s]+")) {
            String trim = t.trim();
            if (!trim.isEmpty()) out.add(trim);
        }
    }

    /**
     * 安全校验：对食材/组合/用户条件逐条匹配启用规则。
     *
     * @param ingredients     归一前食材名（如菜谱主辅料）
     * @param combinations    显式组合（元素并入食材集合）
     * @param userConditions  用户画像条件（medications/conditions，如"痛风""头孢"）
     * @return { riskItems:[...], blocked }
     */
    public Map<String, Object> check(List<String> ingredients,
                                     List<List<String>> combinations,
                                     List<String> userConditions) {
        Set<String> norm = new LinkedHashSet<>();
        if (ingredients != null) {
            for (String i : ingredients) {
                String n = foodAliasService.normalize(i);
                if (n != null && !n.isBlank()) norm.add(n);
            }
        }
        if (combinations != null) {
            for (List<String> c : combinations) {
                if (c == null) continue;
                for (String i : c) {
                    String n = foodAliasService.normalize(i);
                    if (n != null && !n.isBlank()) norm.add(n);
                }
            }
        }

        List<Map<String, Object>> riskItems = new ArrayList<>();
        boolean blocked = false;

        List<FoodSafetyRule> rules = repository.selectList(
                new LambdaQueryWrapper<FoodSafetyRule>().eq(FoodSafetyRule::getEnabled, 1));
        for (FoodSafetyRule r : rules) {
            boolean hit = switch (r.getTriggerType()) {
                case "ingredient" -> matchAny(norm, r.getTriggerValue());
                case "combination" ->
                        matchAny(norm, r.getTriggerValue()) && matchAny(norm, r.getRelatedFood());
                case "user_condition" -> matchAny(userConditions, r.getTriggerValue());
                default -> false;
            };
            if (hit) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("ruleId", r.getId());
                item.put("category", r.getCategory());
                item.put("level", r.getRiskLevel());
                item.put("title", r.getTitle());
                item.put("advice", decorate(r));
                item.put("source", r.getSource());
                item.put("confidence", r.getConfidence());
                riskItems.add(item);
                // PROC 硬禁（河豚/毒蘑菇）→ blocked
                if ("PROC".equals(r.getCategory()) && "RED".equals(r.getRiskLevel())) {
                    blocked = true;
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("riskItems", riskItems);
        result.put("blocked", blocked);
        return result;
    }

    /**
     * 规则库查询（管理/展示）。all=true 返回全部启用规则；all=false 仅返回可公开的 GREEN/MYTH（科普）。
     */
    public List<Map<String, Object>> listRules(String category, String level, boolean all) {
        LambdaQueryWrapper<FoodSafetyRule> w = new LambdaQueryWrapper<FoodSafetyRule>()
                .eq(FoodSafetyRule::getEnabled, 1)
                .orderByAsc(FoodSafetyRule::getCategory)
                .orderByAsc(FoodSafetyRule::getId);
        if (category != null && !category.isBlank()) w.eq(FoodSafetyRule::getCategory, category);
        if (level != null && !level.isBlank()) w.eq(FoodSafetyRule::getRiskLevel, level);

        List<Map<String, Object>> out = new ArrayList<>();
        for (FoodSafetyRule r : repository.selectList(w)) {
            if (!all && !isPublicRule(r)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("category", r.getCategory());
            m.put("level", r.getRiskLevel());
            m.put("triggerType", r.getTriggerType());
            m.put("triggerValue", r.getTriggerValue());
            m.put("relatedFood", r.getRelatedFood());
            m.put("title", r.getTitle());
            m.put("advice", r.getAdvice());
            m.put("source", r.getSource());
            m.put("confidence", r.getConfidence());
            out.add(m);
        }
        return out;
    }

    /** 辟谣列表（MYTH，GREEN 科普） */
    public List<Map<String, Object>> listMyths() {
        return listRules("MYTH", null, true);
    }

    /** 是否可公开展示（GREEN 或 MYTH） */
    private boolean isPublicRule(FoodSafetyRule r) {
        return "GREEN".equals(r.getRiskLevel()) || "MYTH".equals(r.getCategory());
    }

    /** 对集合内任一名称与 trigger 多值任一 token 做双向包含匹配 */
    private boolean matchAny(Set<String> names, String value) {
        if (value == null || value.isBlank()) return false;
        for (String token : split(value)) {
            for (String n : names) {
                if (n.contains(token) || token.contains(n)) return true;
            }
        }
        return false;
    }

    /** 对用户条件集合与 trigger 多值任一 token 做双向包含匹配 */
    private boolean matchAny(List<String> conditions, String value) {
        if (conditions == null || conditions.isEmpty() || value == null || value.isBlank()) return false;
        for (String token : split(value)) {
            for (String c : conditions) {
                if (c != null && (c.contains(token) || token.contains(c))) return true;
            }
        }
        return false;
    }

    /** 按逗号/斜杠/空白拆分为 token 列表 */
    private List<String> split(String v) {
        return Arrays.stream(v.split("[/,，;；\\s]+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** 附加免责声明：MYTH 不加；RED 追加"请遵医嘱/咨询医生"；其余追加"仅供参考，非医疗建议" */
    private String decorate(FoodSafetyRule r) {
        if ("MYTH".equals(r.getCategory())) {
            return r.getAdvice();
        }
        String suffix = "RED".equals(r.getRiskLevel()) ? "（请遵医嘱/咨询医生）" : "";
        return r.getAdvice() + suffix + "（" + DISCLAIMER + "）";
    }
}
