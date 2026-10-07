package com.videostep.locator.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.videostep.locator.dto.response.AsrResult;
import com.videostep.locator.util.CommandMatcher;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 语音命令 LLM 语义分类器（DeepSeek，OpenAI 兼容 chat/completions）。
 *
 * 用于 C 方案"LLM 语义分类"环节：当规则（同义表）匹配不上时，把 ASR 文本交给
 * LLM 判定意图，输出固定 action 枚举 + 参数（JSON）。
 * 带短超时与降级：任何异常 / 超时 / 未配置 key / 节流命中，都返回 null（调用方按
 * UNKNOWN 处理），保证不阻塞、不影响语音链路可用性。
 */
@Service
@Slf4j
public class VoiceLlmClassifier {

    /** 两次 LLM 调用最小间隔（毫秒）：防止连续未识别时频繁打 LLM */
    private static final long MIN_INTERVAL_MS = 1500;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient;
    private final AtomicLong lastCallMs = new AtomicLong(0);

    @Value("${video.processing.llm-api-key:}")
    private String apiKey;

    @Value("${video.processing.llm-base-url:https://api.deepseek.com/v1}")
    private String baseUrl;

    @Value("${video.processing.llm-model:deepseek-chat}")
    private String model;

    @Value("${voice-control.llm.timeout-ms:2000}")
    private long timeoutMs;

    /** 语音命令提示词资源（classpath:prompts/voice_command_prompt.txt） */
    @Value("classpath:prompts/voice_command_prompt.txt")
    private Resource systemPromptResource;

    /** 运行期从提示词文件加载的 system 提示词（读取失败时回退默认值） */
    private String systemPrompt = DEFAULT_SYSTEM_PROMPT;

    /** 提示词文件缺失/读取失败时的兜底提示词 */
    private static final String DEFAULT_SYSTEM_PROMPT = 
            "你是中文语音控制命令解析器，识别用户对烹饪视频说的控制指令。" +
                "只输出 JSON，不要任何多余文字。JSON 格式：{\"action\": \"...\", \"params\": {...}}\n" +
                "action 只能是以下之一：NEXT_STEP, PREV_STEP, LOOP_STEP, LOOP_CURRENT, STOP_LOOP, " +
                "PLAY, PAUSE, RESTART, FORWARD, BACKWARD, SPEED, UNKNOWN\n" +
                "含义：NEXT_STEP=去下一个步骤；PREV_STEP=回上一个步骤；LOOP_STEP=循环第N步（params.stepIndex=数字）；" +
                "LOOP_CURRENT=循环当前这一步；STOP_LOOP=停止循环；PLAY=播放/继续播放；PAUSE=暂停；RESTART=从头/重新开始；" +
                "FORWARD=快进N秒（params.seconds=数字）；BACKWARD=后退N秒（params.seconds=数字）；" +
                "SPEED=倍速（params.speed=数字）；UNKNOWN=无法判断或不是控制指令。\n" +
                "要求：除非确定是控制指令，否则输出 UNKNOWN；数值参数用阿拉伯数字；" +
                "params 仅在对应动作需要时给出，不需要时省略或给空对象。";

    @PostConstruct
    void initPrompt() {
        try {
            String content = new String(
                    systemPromptResource.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8).trim();
            if (!content.isBlank()) {
                systemPrompt = content;
                log.info("Loaded voice command prompt from classpath, {} chars", content.length());
            }
        } catch (Exception e) {
            log.warn("Failed to load voice command prompt resource, use default: {}", e.toString());
        }
    }

    public VoiceLlmClassifier() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * 对 ASR 文本做 LLM 语义分类。
     *
     * @return 命中的 AsrResult（confidence 较低，0.6）；无法识别 / LLM 异常 / 超时 / 未配置时返回 null
     */
    public AsrResult classify(String text) {
        if (text == null || text.isBlank()) return null;
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("LLM not configured (LLM_API_KEY empty), skip semantic classify");
            return null;
        }
        // 节流：避免连续 UNKNOWN 时频繁打 LLM
        long now = System.currentTimeMillis();
        long last = lastCallMs.get();
        if (now - last < MIN_INTERVAL_MS) {
            return null;
        }
        lastCallMs.set(now);

        try {
            String reply = chat(buildMessages(text), true);
            Map<String, Object> parsed = parse(reply);
            if (parsed == null) return null;
            String action = (String) parsed.get("action");
            if (action == null || !isKnownAction(action)) return null;
            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) parsed.get("params");
            if (params == null) params = new HashMap<>();
            normalizeParams(params);

            if ("UNKNOWN".equals(action)) return null;

            return AsrResult.builder()
                    .text(text)
                    .action(action)
                    .params(params)
                    .confidence(0.6f)
                    .build();
        } catch (Exception e) {
            log.warn("LLM classify failed: text=[{}] err={}", text, e.toString());
            return null;
        }
    }

    private List<Map<String, String>> buildMessages(String text) {
        // system 提示词运行期直接读取 classpath:prompts/voice_command_prompt.txt（见 initPrompt）
        String user = "用户说：\"" + text + "\"";
        return List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", user));
    }

    /** 调用 DeepSeek chat/completions，返回助手回复文本（JSON Output 模式）。 */
    private String chat(List<Map<String, String>> messages, boolean jsonMode) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        ArrayNode arr = body.putArray("messages");
        for (Map<String, String> m : messages) {
            ObjectNode msg = arr.addObject();
            msg.put("role", m.get("role"));
            msg.put("content", m.get("content"));
        }
        body.put("temperature", 0.1);
        body.put("stream", false);
        if (jsonMode) {
            body.set("response_format",
                    objectMapper.createObjectNode().put("type", "json_object"));
        }

        String payload = objectMapper.writeValueAsString(body);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new RuntimeException("LLM HTTP " + response.statusCode());
        }
        JsonNode root = objectMapper.readTree(response.body());
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.asText().isBlank()) {
            throw new RuntimeException("LLM empty reply");
        }
        return content.asText();
    }

    /** 从 LLM 回复中提取 JSON 对象并解析 action/params。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String reply) throws Exception {
        if (reply == null) return null;
        String r = reply.trim();
        int start = r.indexOf('{');
        int end = r.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            JsonNode root = objectMapper.readTree(r.substring(start, end + 1));
            Map<String, Object> map = new HashMap<>();
            String action = root.path("action").asText("").trim().toUpperCase();
            if (!action.isEmpty()) map.put("action", action);
            JsonNode paramsNode = root.path("params");
            if (paramsNode.isObject()) {
                map.put("params", objectMapper.convertValue(paramsNode, Map.class));
            }
            return map;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isKnownAction(String action) {
        if (action == null) return false;
        for (CommandMatcher.Action a : CommandMatcher.Action.values()) {
            if (a.name().equals(action)) return true;
        }
        return "UNKNOWN".equals(action);
    }

    /** 把参数字符串形式的数字规整为数值类型（"10"→10、"1.5"→1.5）。 */
    private void normalizeParams(Map<String, Object> params) {
        params.replaceAll((k, v) -> {
            if (v instanceof String s) {
                try {
                    return s.contains(".") ? Double.parseDouble(s) : Integer.parseInt(s);
                } catch (NumberFormatException e) {
                    return v;
                }
            }
            return v;
        });
    }
}
