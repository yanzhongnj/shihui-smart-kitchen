package com.videostep.locator.service.health;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.videostep.locator.entity.HealthProfile;
import com.videostep.locator.entity.UserPreference;
import com.videostep.locator.repository.HealthProfileRepository;
import com.videostep.locator.repository.UserPreferenceRepository;
import com.videostep.locator.service.safety.SensitiveFieldCipher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康档案 + TDEE（Mifflin-St Jeor）+ 个性化营养目标分解。
 * 热量预算口径（用户决策③）：health_profiles.target_calories → user_preferences.max_calories → 2000。
 * 设计文档《拾味_营养健康模块_技术设计文档.md》§5.1。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HealthProfileService {

    private final HealthProfileRepository repository;
    private final UserPreferenceRepository preferenceRepository;
    private final SensitiveFieldCipher cipher;

    @Value("${health.nutrition.target-ratio.protein:0.15}")
    private double ratioProtein;
    @Value("${health.nutrition.target-ratio.fat:0.25}")
    private double ratioFat;
    @Value("${health.nutrition.target-ratio.carbs:0.60}")
    private double ratioCarbs;
    @Value("${health.nutrition.default-calories:2000}")
    private int defaultCalories;

    /** 活动系数（Mifflin-St Jeor） */
    private static double activityMultiplier(String level) {
        if (level == null) return 1.55;
        return switch (level) {
            case "sedentary" -> 1.2;
            case "light" -> 1.375;
            case "active" -> 1.725;
            case "very_active" -> 1.9;
            default -> 1.55; // moderate
        };
    }

    public HealthProfile get(Long userId) {
        return repository.selectOne(new LambdaQueryWrapper<HealthProfile>()
                .eq(HealthProfile::getUserId, userId));
    }

    /** 读取档案（含目标） */
    public Map<String, Object> profileMap(Long userId) {
        HealthProfile p = get(userId);
        Map<String, Object> m = new LinkedHashMap<>();
        if (p == null) {
            m.put("exists", false);
            return m;
        }
        m.put("exists", true);
        m.put("height", p.getHeight());
        m.put("weight", p.getWeight());
        m.put("targetWeight", p.getTargetWeight());
        m.put("gender", p.getGender());
        m.put("age", p.getAge());
        m.put("activityLevel", p.getActivityLevel());
        m.put("goal", p.getGoal());
        m.put("bmr", p.getBmr());
        m.put("tdee", p.getTdee());
        m.put("targetCalories", p.getTargetCalories());
        m.put("targetProtein", p.getTargetProtein());
        m.put("targetFat", p.getTargetFat());
        m.put("targetCarbs", p.getTargetCarbs());
        // P1 敏感字段：仅本人解密可见
        m.put("medications", cipher.decrypt(p.getMedications()));
        m.put("conditions", cipher.decrypt(p.getConditions()));
        m.put("updatedAt", p.getUpdatedAt());
        return m;
    }

    /** 更新/建档并重算目标（upsert） */
    public HealthProfile update(Long userId, Map<String, Object> body) {
        HealthProfile p = get(userId);
        if (p == null) {
            p = HealthProfile.builder().userId(userId).build();
        }
        p.setHeight(dec(body.get("height")));
        p.setWeight(dec(body.get("weight")));
        p.setTargetWeight(dec(body.get("targetWeight")));
        p.setGender(str(body.get("gender")));
        p.setAge(body.get("age") instanceof Number n ? n.intValue() : null);
        String act = str(body.get("activityLevel"));
        p.setActivityLevel(act == null ? "moderate" : act);
        String goal = str(body.get("goal"));
        p.setGoal(goal == null ? "maintain" : goal);
        // P1 敏感字段：用药/疾病（AES 加密存储，仅本人解密可见，可一键清除）
        p.setMedications(cipher.encrypt(str(body.get("medications"))));
        p.setConditions(cipher.encrypt(str(body.get("conditions"))));
        computeTargets(p);
        if (p.getId() == null) {
            repository.insert(p);
        } else {
            repository.updateById(p);
        }
        return p;
    }

    /** Mifflin-St Jeor BMR + TDEE + 宏量分解（数据不足则保留空目标） */
    private void computeTargets(HealthProfile p) {
        if (p.getWeight() == null || p.getHeight() == null || p.getAge() == null) {
            p.setBmr(null);
            p.setTdee(null);
            p.setTargetCalories(null);
            p.setTargetProtein(null);
            p.setTargetFat(null);
            p.setTargetCarbs(null);
            return;
        }
        double w = p.getWeight().doubleValue();
        double h = p.getHeight().doubleValue();
        int age = p.getAge();
        boolean male = "male".equalsIgnoreCase(p.getGender());
        double bmr = male
                ? 10 * w + 6.25 * h - 5 * age + 5
                : 10 * w + 6.25 * h - 5 * age - 161;
        double tdee = bmr * activityMultiplier(p.getActivityLevel());
        double targetCal = switch (p.getGoal() == null ? "maintain" : p.getGoal()) {
            case "lose_weight" -> tdee - 500;
            case "gain_muscle" -> tdee + 300;
            default -> tdee;
        };
        targetCal = Math.max(1000, targetCal); // 下限保护
        p.setBmr((int) Math.round(bmr));
        p.setTdee((int) Math.round(tdee));
        p.setTargetCalories((int) Math.round(targetCal));
        p.setTargetProtein((int) Math.round(targetCal * ratioProtein / 4.0));
        p.setTargetFat((int) Math.round(targetCal * ratioFat / 9.0));
        p.setTargetCarbs((int) Math.round(targetCal * ratioCarbs / 4.0));
    }

    /** 目标卡：health_profiles → user_preferences.max_calories → 2000（用户决策③） */
    public Map<String, Object> targets(Long userId) {
        HealthProfile p = get(userId);
        Map<String, Object> m = new LinkedHashMap<>();
        if (p != null && p.getTargetCalories() != null) {
            m.put("calories", p.getTargetCalories());
            m.put("protein", p.getTargetProtein());
            m.put("fat", p.getTargetFat());
            m.put("carbs", p.getTargetCarbs());
            m.put("source", "profile");
            return m;
        }
        UserPreference pref = preferenceRepository.selectOne(
                new LambdaQueryWrapper<UserPreference>().eq(UserPreference::getUserId, userId));
        Integer maxCal = pref != null ? pref.getMaxCalories() : null;
        int base = maxCal != null && maxCal > 0 ? maxCal : defaultCalories;
        m.put("calories", base);
        m.put("protein", Math.round(base * ratioProtein / 4.0));
        m.put("fat", Math.round(base * ratioFat / 9.0));
        m.put("carbs", Math.round(base * ratioCarbs / 4.0));
        m.put("source", maxCal != null ? "preference" : "default");
        return m;
    }

    /** 转为前端可读 map */
    public Map<String, Object> toMap(HealthProfile p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("exists", p != null);
        if (p == null) return m;
        m.put("height", p.getHeight());
        m.put("weight", p.getWeight());
        m.put("targetWeight", p.getTargetWeight());
        m.put("gender", p.getGender());
        m.put("age", p.getAge());
        m.put("activityLevel", p.getActivityLevel());
        m.put("goal", p.getGoal());
        m.put("bmr", p.getBmr());
        m.put("tdee", p.getTdee());
        m.put("targetCalories", p.getTargetCalories());
        m.put("targetProtein", p.getTargetProtein());
        m.put("targetFat", p.getTargetFat());
        m.put("targetCarbs", p.getTargetCarbs());
        // P1 敏感字段：仅本人解密可见
        m.put("medications", cipher.decrypt(p.getMedications()));
        m.put("conditions", cipher.decrypt(p.getConditions()));
        m.put("updatedAt", p.getUpdatedAt());
        return m;
    }

    private BigDecimal dec(Object o) {
        return o instanceof Number n ? BigDecimal.valueOf(n.doubleValue()) : null;
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    static double round1(double v) {
        return BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }
}
