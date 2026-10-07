package com.videostep.locator.service;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * USER 来源菜谱校验（设计文档 §6.3.1）。
 * 工业型强校验：主料必须 amount+unit（单位白名单）、禁止定性词；每步需 timerMin/temperature/mode 之一。
 * 不通过抛 IllegalArgumentException → 全局处理器返回 400（APP 逐字段标红展示 message）。
 */
@Service
public class RecipeValidationService {

    private static final Set<String> UNITS = Set.of("g", "ml", "个", "勺", "片", "瓣", "人份", "克", "毫升");
    private static final Set<String> FORBIDDEN_QUALITATIVE = Set.of("适量", "少许", "一把", "若干", "少量", "一点", "一点点", "略");

    public void validateUserRecipe(List<Map<String, Object>> ingredients, List<Map<String, Object>> steps,
                                   boolean requireIndustrial) {
        if (ingredients == null || ingredients.isEmpty()) {
            throw new IllegalArgumentException("请至少填写一种食材");
        }
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("请至少填写一个步骤");
        }
        int idx = 0;
        for (Map<String, Object> ig : ingredients) {
            idx++;
            String name = str(ig.get("name"));
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("食材 #" + idx + "：缺少名称");
            }
            if (!requireIndustrial) continue;
            String amountRaw = ig.get("amount") == null ? null : String.valueOf(ig.get("amount"));
            boolean isMain = isMain(ig);
            // 定性词禁止（工业型所有食材）
            if (amountRaw != null) {
                for (String f : FORBIDDEN_QUALITATIVE) {
                    if (amountRaw.contains(f)) {
                        throw new IllegalArgumentException("食材 #" + idx + "「" + name + "」：工业型要求精确克/毫升用量，不能为「" + f + "」");
                    }
                }
            }
            // 主料必须精确（amount + unit 白名单）
            if (isMain) {
                if (amountRaw == null || amountRaw.isBlank()) {
                    throw new IllegalArgumentException("食材 #" + idx + "「" + name + "」：工业型要求主料给出精确用量");
                }
                String unit = str(ig.get("unit"));
                if (unit == null || !UNITS.contains(unit.trim().toLowerCase())) {
                    throw new IllegalArgumentException("食材 #" + idx + "「" + name + "」：用量单位必须为 " + String.join("/", UNITS));
                }
            }
        }
        if (requireIndustrial) {
            int s = 0;
            for (Map<String, Object> st : steps) {
                s++;
                String summary = str(st.get("summary"));
                if (summary == null || summary.isBlank()) {
                    throw new IllegalArgumentException("步骤 #" + s + "：缺少步骤概括");
                }
                if (isEmpty(st.get("timerMin")) && isEmpty(st.get("timer_min"))
                        && isEmpty(st.get("temperature")) && isEmpty(st.get("mode"))) {
                    throw new IllegalArgumentException("步骤 #" + s + "：工业型要求给出计时（timerMin）或温度/模式（temperature/mode）之一");
                }
            }
        }
    }

    private boolean isMain(Map<String, Object> ig) {
        Object v = ig.get("isMain") != null ? ig.get("isMain") : ig.get("is_main");
        if (v == null) return true; // 未标注视为主料（从严）
        String s = String.valueOf(v).trim();
        return "1".equals(s) || "true".equalsIgnoreCase(s);
    }

    private boolean isEmpty(Object o) {
        return o == null || String.valueOf(o).isBlank();
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
