package com.videostep.locator.service.xunwei;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videostep.locator.entity.UserCorrection;
import com.videostep.locator.repository.UserCorrectionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 个人易犯偏差画像（M3）：聚合历史纠偏报告（user_corrections.dimensions），
 * 找出高频「偏差结论」（过滤"基本一致/保持/适中"等中性项），
 * 回填寻味记忆（cooking_weakness 画像），供月度报告成长轨迹 / 个性化建议使用。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CorrectionProfileService {

    private final UserCorrectionRepository correctionRepository;
    private final XunweiMemoryService xunweiMemoryService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 中性/正向结论关键词：命中则不算「易犯偏差」 */
    private static final List<String> NEUTRAL_KEYWORDS = List.of(
            "基本一致", "保持", "适中", "无差异", "正常", "自然", "良好", "可以",
            "到位", "完美", "满分", "优秀", "合适", "均衡", "均匀", "接近",
            "相当", "无异", "一致", "恰到好处", "恰好", "刚好", "得当", "适宜",
            "很到位", "出色", "正好", "精准", "90%", "100%");

    /** 聚合某时间段内的易犯偏差（按 维度+结论 计数，过滤中性）；limit 为返回条数上限 */
    public List<Map<String, Object>> topMistakes(Long userId, LocalDateTime from, LocalDateTime to, int limit) {
        List<UserCorrection> corrections = correctionRepository.findByUserAndDateRange(userId, from, to);
        Map<String, Integer> counter = new LinkedHashMap<>();
        for (UserCorrection c : corrections) {
            String dims = c.getDimensions();
            if (dims == null || dims.isBlank()) continue;
            try {
                JsonNode arr = objectMapper.readTree(dims);
                if (!arr.isArray()) continue;
                for (JsonNode n : arr) {
                    String name = n.path("name").asText("").trim();
                    String conclusion = n.path("conclusion").asText("").trim();
                    if (name.isBlank() || conclusion.isBlank() || "总体完成度".equals(name)) continue;
                    if (isNeutral(conclusion)) continue;
                    counter.merge(name + "·" + conclusion, 1, Integer::sum);
                }
            } catch (Exception ignored) {
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        counter.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(Math.max(0, limit))
                .forEach(e -> {
                    String[] parts = e.getKey().split("·", 2);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("dimension", parts.length > 0 ? parts[0] : "");
                    m.put("conclusion", parts.length > 1 ? parts[1] : "");
                    m.put("count", e.getValue());
                    out.add(m);
                });
        return out;
    }

    /** 个人易犯偏差画像（默认最近 90 天） */
    public Map<String, Object> profile(Long userId) {
        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> mistakes = topMistakes(userId, now.minusDays(90), now.plusDays(1), 5);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mistakes", mistakes);
        out.put("summary", mistakes.isEmpty() ? "暂无明显易犯偏差，继续保持！" : weaknessSummary(mistakes));
        return out;
    }

    /** 纠偏后回填寻味记忆（best-effort）：把易犯偏差写入 cooking_weakness 画像 */
    public void syncWeaknessToMemory(Long userId) {
        try {
            if (!xunweiMemoryService.isAutoEvolveEnabled(userId)) return;
            Object raw = profile(userId).get("mistakes");
            List<Map<String, Object>> mistakes = raw instanceof List<?> l ? castList(l) : List.of();
            if (mistakes.isEmpty()) return;
            xunweiMemoryService.mergeUpsert(userId, "cooking_weakness", weaknessSummary(mistakes),
                    "correction", "profile");
        } catch (Exception e) {
            log.warn("syncWeaknessToMemory failed userId={}: {}", userId, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castList(List<?> l) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : l) {
            if (o instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        }
        return out;
    }

    private boolean isNeutral(String conclusion) {
        for (String k : NEUTRAL_KEYWORDS) {
            if (conclusion.contains(k)) return true;
        }
        return false;
    }

    private String weaknessSummary(List<Map<String, Object>> mistakes) {
        StringBuilder sb = new StringBuilder("易犯偏差：");
        for (int i = 0; i < mistakes.size(); i++) {
            if (i > 0) sb.append("、");
            Map<String, Object> m = mistakes.get(i);
            sb.append(m.get("dimension")).append("容易").append(cleanConclusion(String.valueOf(m.get("conclusion"))));
        }
        return sb.toString();
    }

    /** 结论措辞清洗：去掉「可能/推断/大概/略显」等前缀，读起来更顺（如 火候容易略欠） */
    private String cleanConclusion(String conclusion) {
        String c = conclusion == null ? "" : conclusion.trim();
        for (String p : List.of("可能", "推断", "大概", "略显")) {
            if (c.startsWith(p)) return c.substring(p.length()).trim();
        }
        return c;
    }
}
