package com.videostep.locator.service.xunwei;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.videostep.locator.service.aiconfig.AiConfigRefreshable;
import com.videostep.locator.service.aiconfig.AiProviderConfigSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * DeepSeek 大模型客户端（OpenAI 兼容 chat/completions）。
 *
 * 调用模式参考 AliyunAsrProvider（java.net.http + Jackson），无需引入第三方 SDK。
 * 配置项见 application.yml 的 xunwei.llm.*（api-key 默认读 DEEPSEEK_API_KEY，兼容 LLM_API_KEY）。
 */
@Service
@Slf4j
public class DeepSeekClient implements AiConfigRefreshable {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient;

    @Value("${xunwei.llm.api-key:}")
    private volatile String apiKey;

    @Value("${xunwei.llm.base-url:https://api.deepseek.com/v1}")
    private volatile String baseUrl;

    @Value("${xunwei.llm.model:deepseek-chat}")
    private volatile String model;

    @Value("${xunwei.llm.timeout-ms:60000}")
    private volatile long timeoutMs;

    public DeepSeekClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String providerKey() {
        return "llm.xunwei";
    }

    /** 连通性测试：发最小 "ping" 消息（短超时），HTTP 200 即视为可用 */
    @Override
    public boolean testConnection() {
        if (apiKey == null || apiKey.isBlank()) return false;
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", model);
            ArrayNode arr = body.putArray("messages");
            arr.addObject().put("role", "user").put("content", "ping");
            body.put("stream", false);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .timeout(Duration.ofMillis(Math.min(timeoutMs, 8000)))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> res = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return res.statusCode() == 200;
        } catch (Exception e) {
            log.warn("DeepSeek testConnection 失败: {}", e.getMessage());
            return false;
        }
    }

    /** 配置中心热更新：下次 chat() 调用即用新值 */
    @Override
    public void refreshConfig(AiProviderConfigSnapshot cfg) {
        if (cfg == null) return;
        this.apiKey = cfg.getApiKey();
        this.baseUrl = cfg.getEndpoint();
        this.model = cfg.getModel();
        this.timeoutMs = cfg.getExtraLong("timeout_ms", this.timeoutMs);
        log.info("DeepSeekClient 配置已热更新: model={}, baseUrl={}, hasKey={}",
                model, baseUrl, apiKey != null && !apiKey.isBlank());
    }

    /**
     * 调用 DeepSeek chat/completions，返回助手回复文本。
     *
     * @param messages role/content 列表（system / user / assistant）
     * @param jsonMode 是否启用 JSON Output（response_format = json_object）
     */
    public String chat(List<Map<String, String>> messages, boolean jsonMode) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        ArrayNode arr = body.putArray("messages");
        for (Map<String, String> m : messages) {
            ObjectNode msg = arr.addObject();
            msg.put("role", m.get("role"));
            msg.put("content", m.get("content"));
        }
        body.put("temperature", 0.6);
        body.put("stream", false);
        if (jsonMode) {
            body.set("response_format",
                    objectMapper.createObjectNode().put("type", "json_object"));
        }

        String payload;
        try {
            payload = objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new RuntimeException("DeepSeek 序列化异常: " + e.getMessage(), e);
        }

        // DeepSeek 偶发「HTTP 200 但 content 为空」或 5xx/429 抖动 → 自动重试（最多 3 次），避免「AI 开小差」
        String lastError = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
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
                    lastError = "DeepSeek 调用失败: HTTP " + response.statusCode();
                    log.error("DeepSeek API error: status={}, body={}", response.statusCode(), response.body());
                    // 5xx/429 可重试；其余 4xx（401/400）重试无意义
                    boolean retriable = response.statusCode() >= 500 || response.statusCode() == 429;
                    if (retriable && attempt < 3) {
                        Thread.sleep(500L);
                        continue;
                    }
                    break;
                }

                JsonNode root = objectMapper.readTree(response.body());
                JsonNode content = root.path("choices").path(0).path("message").path("content");
                if (content.isMissingNode() || content.asText().isBlank()) {
                    lastError = "DeepSeek 返回空内容";
                    log.warn("DeepSeek 返回空内容 (attempt={}), raw={}", attempt, response.body());
                    if (attempt < 3) {
                        Thread.sleep(500L);
                        continue;
                    }
                    break;
                }
                return content.asText();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("DeepSeek 调用中断", ie);
            } catch (Exception e) {
                lastError = "DeepSeek 调用异常: " + e.getMessage();
                log.error("DeepSeek 调用异常 (attempt={})", attempt, e);
                if (attempt < 3) {
                    try {
                        Thread.sleep(500L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                }
                break;
            }
        }
        throw new RuntimeException(lastError == null ? "DeepSeek 调用失败" : lastError);
    }
}
