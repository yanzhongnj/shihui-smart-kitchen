package com.videostep.locator.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videostep.locator.service.xunwei.DeepSeekClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 链接图文 → 结构化菜谱草稿（ARTICLE_LINK 来源，设计文档 §6.2）。
 * LLM 从网页正文抽取食材（含克/毫升用量）、步骤（含计时/温度）、口味、营养。
 * 输出与 POST /api/recipes 请求体同构（camelCase），可直接用于预览/保存；图文精度高 → 默认 industrial=true。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ArticleRecipeExtractor {

    /** 工业型允许的用量单位白名单（与 RecipeValidationService 保持一致） */
    private static final Set<String> PRECISE_UNITS = Set.of("g", "ml", "个", "勺", "片", "瓣", "人份", "克", "毫升");

    private final DeepSeekClient deepSeekClient;
    private final ObjectMapper objectMapper;

    public Map<String, Object> extract(String url, String title, String text) {
        String system = "你是菜谱结构化助手。从网页正文提取菜谱信息，输出严格 JSON，不要输出其他内容。";
        String user = "网页标题：" + (title == null ? "" : title)
                + "\n网页正文：\n" + (text == null ? "" : text)
                + "\n\n输出格式：{\"title\":\"菜名\",\"summary\":\"摘要\",\"servings\":2,"
                + "\"cuisine\":\"菜系\",\"estimatedTime\":分钟数,\"calories\":千卡,"
                + "\"tasteTags\":[\"口味\"],\"allergens\":[\"过敏原\"],\"spiceLevel\":0到10,"
                + "\"ingredients\":[{\"name\":\"食材\",\"amount\":数值,\"unit\":\"g\",\"tolerance\":误差或null,\"isMain\":true,\"note\":\"备注或null\"}],"
                + "\"steps\":[{\"summary\":\"概括\",\"detail\":\"要点\",\"timerMin\":分钟或null,\"temperature\":℃或null,\"mode\":\"模式或null\"}]}"
                + "\n要求：1) 用量能精确到克/毫升必须给 amount/unit/tolerance，无法确定则 amount 填 null；"
                + "2) 步骤涉及固定时长/温度必须填 timerMin/temperature/mode；3) 无法确定的字段填 null 或 []。";
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", system));
        messages.add(Map.of("role", "user", "content", user));
        try {
            String raw = deepSeekClient.chat(messages, true);
            Map<String, Object> parsed = objectMapper.readValue(raw, Map.class);
            return normalize(parsed);
        } catch (Exception e) {
            log.warn("图文菜谱结构化失败: {}", e.getMessage());
            throw new IllegalArgumentException("菜谱结构化失败（LLM 未配置或输出异常），请稍后重试");
        }
    }

    /** 归一化为 create 请求体结构（camelCase） */
    @SuppressWarnings("unchecked")
    private Map<String, Object> normalize(Map<String, Object> p) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> ingredients = normalizeIngredients(p.get("ingredients"));
        List<Map<String, Object>> steps = normalizeSteps(p.get("steps"));
        out.put("title", str(p.get("title")));
        out.put("summary", str(p.get("summary")));
        out.put("servings", intOf(p.getOrDefault("servings", p.get("serving"))));
        out.put("cuisine", str(p.get("cuisine")));
        out.put("estimatedTime", intOf(p.get("estimatedTime")));
        out.put("calories", intOf(p.get("calories")));
        out.put("tasteTags", stringList(p.get("tasteTags")));
        out.put("allergens", stringList(p.get("allergens")));
        out.put("spiceLevel", intOf(p.get("spiceLevel")));
        // 按数据完整度派生工业型（设计 §6.2「图文精度高→可标 INDUSTRIAL」）：
        // 主料全部克/毫升精确 + 每步有计时/温度/模式 → industrial=true；否则回落家常型（可后续 upgrade），
        // 避免文章菜谱（含「适量/少许」、步骤无参数）保存时被工业型强校验 400 拦截。
        out.put("industrial", isPreciseDraft(ingredients, steps));
        out.put("ingredients", ingredients);
        out.put("steps", steps);
        return out;
    }

    /** 图文草稿精度判定：主料全部有 amount+unit（单位白名单）且每步有计时/温度/模式之一，才算工业型 */
    private boolean isPreciseDraft(List<Map<String, Object>> ingredients, List<Map<String, Object>> steps) {
        if (ingredients.isEmpty() || steps.isEmpty()) return false;
        for (Map<String, Object> ig : ingredients) {
            if (!Boolean.TRUE.equals(ig.get("isMain"))) continue;
            if (ig.get("amount") == null) return false;
            String unit = ig.get("unit") == null ? null : String.valueOf(ig.get("unit")).trim().toLowerCase();
            if (unit == null || unit.isEmpty() || !PRECISE_UNITS.contains(unit)) return false;
        }
        for (Map<String, Object> st : steps) {
            boolean hasParam = st.get("timerMin") != null || st.get("temperature") != null
                    || (st.get("mode") != null && !String.valueOf(st.get("mode")).isBlank());
            if (!hasParam) return false;
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> normalizeIngredients(Object o) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (o instanceof List<?> list) {
            for (Object x : list) {
                if (!(x instanceof Map<?, ?>)) continue;
                Map<String, Object> src = new LinkedHashMap<>((Map<String, Object>) x);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", str(src.get("name")));
                m.put("amount", num(src.get("amount")));
                m.put("unit", str(src.get("unit")));
                m.put("tolerance", num(src.get("tolerance")));
                Object isMain = src.getOrDefault("isMain", src.get("is_main"));
                m.put("isMain", isMain == null ? true : isTrue(isMain));
                m.put("note", str(src.get("note")));
                out.add(m);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> normalizeSteps(Object o) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (o instanceof List<?> list) {
            for (Object x : list) {
                if (!(x instanceof Map<?, ?>)) continue;
                Map<String, Object> src = new LinkedHashMap<>((Map<String, Object>) x);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("summary", str(src.get("summary")));
                m.put("detail", str(src.get("detail")));
                m.put("timerMin", intOf(src.getOrDefault("timerMin", src.get("timer_min"))));
                m.put("temperature", intOf(src.get("temperature")));
                m.put("mode", str(src.get("mode")));
                out.add(m);
            }
        }
        return out;
    }

    private List<String> stringList(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> list) {
            for (Object x : list) {
                String s = x == null ? null : String.valueOf(x).trim();
                if (s != null && !s.isEmpty() && !"null".equalsIgnoreCase(s)) out.add(s);
            }
        }
        return out;
    }

    private boolean isTrue(Object v) {
        String s = String.valueOf(v).trim();
        return "1".equals(s) || "true".equalsIgnoreCase(s);
    }

    private Integer intOf(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        try { return Integer.valueOf(String.valueOf(o).trim()); } catch (Exception e) { return null; }
    }

    private Number num(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n;
        try { return Double.valueOf(String.valueOf(o).trim()); } catch (Exception e) { return null; }
    }

    private String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() || "null".equalsIgnoreCase(s) ? null : s;
    }
}
