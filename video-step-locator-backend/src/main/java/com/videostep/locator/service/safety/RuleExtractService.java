package com.videostep.locator.service.safety;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videostep.locator.entity.FoodSafetyRuleCandidate;
import com.videostep.locator.repository.FoodSafetyRuleCandidateRepository;
import com.videostep.locator.service.ArticleFetcherService;
import com.videostep.locator.service.xunwei.DeepSeekClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则采集管道（运营增强 D5）：原文/URL → LLM 提取候选规则 → 待审核队列（pending）。
 * 半监督：LLM 只提取候选（必须带出处 source），是否入库由人工审核（AdminSafetyController approve）。
 * 设计文档《拾味_食物安全提醒知识库_技术设计文档.md》§1.2。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RuleExtractService {

    private static final int MAX_TEXT = 8000;
    private static final int MAX_SRC_TEXT = 2000;
    private static final int MAX_LLM_JSON = 4000;

    private final DeepSeekClient deepSeekClient;
    private final ArticleFetcherService articleFetcherService;
    private final FoodSafetyRuleCandidateRepository candidateRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String EXTRACT_PROMPT = """
            你是「拾味」食物安全知识库的规则采集助手。基于提供的权威资料，提取其中可作为食物安全提醒的规则候选。
            要求：
            1. 只提取原文中明确依据的规则（食物-药物相互作用 MED / 加工安全 PROC / 个体差异 IND / 营养素干扰 ABS / 辟谣 MYTH）。
            2. 每条必须能定位到原文出处（source 字段必填，格式「机构/资料类型」，如 NMPA/药品说明书、CDC/食品安全科普）；无出处的不要输出。
            3. risk_level 取值 RED|YELLOW|GREEN；MYTH 用 GREEN。
            4. trigger_type 取值 ingredient|combination|user_condition；trigger_value 填食材名/组合/用户条件；combination 时 related_food 填组合另一侧。
            5. advice 简洁专业、面向用户（勿含"相克"等伪科学表述）。
            只输出 JSON 数组，不要多余文字：
            [{"category":"MED","risk_level":"RED","trigger_type":"user_condition","trigger_value":"...","related_food":"...","title":"...","advice":"...","source":"..."}]
            """;

    /** 从原文提取候选并落待审核队列 */
    public Map<String, Object> extractFromText(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("原文不能为空");
        }
        String trimmed = text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text;
        String userPrompt = "【原始资料】\n" + trimmed + "\n请按系统要求输出候选规则 JSON。";
        List<Map<String, Object>> candidates = new ArrayList<>();
        String raw;
        try {
            raw = deepSeekClient.chat(List.of(
                    Map.of("role", "system", "content", EXTRACT_PROMPT),
                    Map.of("role", "user", "content", userPrompt)), true);
        } catch (Exception e) {
            log.warn("rule extract LLM failed: {}", e.getMessage());
            throw new IllegalArgumentException("规则提取失败（LLM 不可用）：" + e.getMessage());
        }
        try {
            JsonNode arr = objectMapper.readTree(cleanJson(raw));
            if (arr.isArray()) {
                for (JsonNode n : arr) {
                    FoodSafetyRuleCandidate c = toCandidate(n, trimmed);
                    candidateRepository.insert(c);
                    Map<String, Object> one = new LinkedHashMap<>();
                    one.put("id", c.getId());
                    one.put("title", c.getTitle());
                    one.put("category", c.getCategory());
                    one.put("riskLevel", c.getRiskLevel());
                    one.put("status", "pending");
                    candidates.add(one);
                }
            }
        } catch (Exception e) {
            log.warn("rule extract parse failed: {}", e.getMessage());
            throw new IllegalArgumentException("规则提取失败（解析错误）：" + e.getMessage());
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("count", candidates.size());
        r.put("candidates", candidates);
        return r;
    }

    /** 从 URL 抓正文提取候选 */
    public Map<String, Object> extractFromUrl(String url) {
        Map<String, Object> fetched = articleFetcherService.fetch(url);
        String text = fetched.get("text") == null ? "" : String.valueOf(fetched.get("text"));
        Map<String, Object> r = extractFromText(text);
        r.put("url", url);
        r.put("pageTitle", fetched.getOrDefault("title", ""));
        return r;
    }

    private FoodSafetyRuleCandidate toCandidate(JsonNode n, String srcText) {
        return FoodSafetyRuleCandidate.builder()
                .category(n.path("category").asText("MED"))
                .riskLevel(n.path("risk_level").asText("YELLOW"))
                .triggerType(n.path("trigger_type").asText("ingredient"))
                .triggerValue(n.path("trigger_value").asText(""))
                .relatedFood(n.path("related_food").asText(null))
                .title(n.path("title").asText(""))
                .advice(n.path("advice").asText(""))
                .source(n.path("source").asText(null))
                .sourceText(srcText.length() > MAX_SRC_TEXT ? srcText.substring(0, MAX_SRC_TEXT) : srcText)
                .llmJson(n.toString().length() > MAX_LLM_JSON ? n.toString().substring(0, MAX_LLM_JSON) : n.toString())
                .status("pending")
                .confidence(50)
                .build();
    }

    /** 去除 LLM 可能包裹的 ```json ... ``` 等噪音 */
    private String cleanJson(String raw) {
        if (raw == null) return "[]";
        String s = raw.trim();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl >= 0) s = s.substring(nl + 1);
            int end = s.lastIndexOf("```");
            if (end >= 0) s = s.substring(0, end);
            s = s.trim();
        }
        if (!s.startsWith("[")) {
            int b = s.indexOf('[');
            int e = s.lastIndexOf(']');
            if (b >= 0 && e > b) s = s.substring(b, e + 1);
        }
        return s;
    }
}
