package com.videostep.locator.service.creation.imagegen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.videostep.locator.service.aiconfig.AiConfigRefreshable;
import com.videostep.locator.service.aiconfig.AiProviderConfigSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

/**
 * 通义万相（WanX）真实文生图网关（阿里云 DashScope 异步任务：提交 → 轮询 → 下载，仿 WanVideoGateway）。
 * 模型默认 wanx2.1-t2i-turbo（低成本）；需配置 ALIYUN_ASR_APP_KEY（DashScope key）。
 * 未配置 key 时 testConnection=false，由 ImageGenOrchestrator 降级 mock（免费）。
 */
@Component
@Slf4j
public class WanImageGateway implements ImageGenGateway, AiConfigRefreshable {

    private static final ObjectMapper OM = new ObjectMapper();

    private final HttpClient httpClient;

    @Value("${creation.effect-image.wan.api-key:}")
    private volatile String apiKey;

    @Value("${creation.effect-image.wan.model:wanx2.1-t2i-turbo}")
    private volatile String model;

    @Value("${creation.effect-image.wan.size:1024*1024}")
    private volatile String size;

    @Value("${creation.effect-image.wan.endpoint:https://dashscope.aliyuncs.com/api/v1/services/aigc/text2image/image-synthesis}")
    private volatile String endpoint;

    @Value("${creation.effect-image.wan.poll-interval-ms:5000}")
    private volatile long pollIntervalMs;

    @Value("${creation.effect-image.wan.timeout-ms:180000}")
    private volatile long timeoutMs;

    public WanImageGateway() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15)).build();
    }

    @Override
    public String providerKey() {
        return "image_gen.wan_image";
    }

    /** 配置中心热更新：下次提交即用新值 */
    @Override
    public void refreshConfig(AiProviderConfigSnapshot cfg) {
        if (cfg == null) return;
        this.apiKey = cfg.getApiKey();
        this.model = cfg.getModel();
        this.size = cfg.getExtra("size", this.size);
        this.endpoint = cfg.getEndpoint();
        this.pollIntervalMs = cfg.getExtraLong("poll_interval_ms", this.pollIntervalMs);
        this.timeoutMs = cfg.getExtraLong("timeout_ms", this.timeoutMs);
        log.info("WanImageGateway 配置已热更新: model={}, hasKey={}",
                model, apiKey != null && !apiKey.isBlank());
    }

    @Override
    public String provider() {
        return "wan_image";
    }

    @Override
    public boolean testConnection() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public boolean isBillable() {
        return true;
    }

    @Override
    public ImageGenTaskRef submit(String prompt, String style) {
        try {
            ObjectNode body = OM.createObjectNode();
            body.put("model", model);
            body.putObject("input").put("prompt", prompt);
            ObjectNode params = body.putObject("parameters");
            params.put("size", size);
            if (style != null && !style.isBlank()) {
                params.put("style", style);
            }

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("X-DashScope-Async", "enable")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            OM.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> res = httpClient.send(req,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() != 200) {
                throw new RuntimeException("WanImage 提交失败 HTTP " + res.statusCode() + ": " + res.body());
            }
            JsonNode root = OM.readTree(res.body());
            String taskId = root.path("output").path("task_id").asText("");
            if (taskId.isBlank()) {
                throw new RuntimeException("WanImage 未返回 task_id: " + res.body());
            }
            log.info("[WanImageGateway] 提交成功 taskId={}", taskId);
            return new ImageGenTaskRef(provider(), taskId);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("WanImage 提交异常: " + e.getMessage(), e);
        }
    }

    @Override
    public ImageGenResult poll(String taskRef) {
        String taskId = taskRef;
        long deadline = System.currentTimeMillis() + timeoutMs;
        try {
            while (System.currentTimeMillis() < deadline) {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create("https://dashscope.aliyuncs.com/api/v1/tasks/" + taskId))
                        .timeout(Duration.ofSeconds(30))
                        .header("Authorization", "Bearer " + apiKey)
                        .header("X-DashScope-Async", "enable")
                        .GET().build();
                HttpResponse<String> res = httpClient.send(req,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (res.statusCode() != 200) {
                    throw new RuntimeException("WanImage 查询失败 HTTP " + res.statusCode());
                }
                JsonNode root = OM.readTree(res.body());
                JsonNode output = root.path("output");
                String status = output.path("task_status").asText("");
                if ("SUCCEEDED".equals(status)) {
                    String url = output.path("results").path(0).path("url").asText("");
                    if (url.isBlank()) {
                        url = output.path("result_url").asText("");
                    }
                    if (url.isBlank()) {
                        throw new RuntimeException("WanImage 成功但无图片 URL");
                    }
                    return new ImageGenResult("completed", url);
                }
                if ("FAILED".equals(status)) {
                    throw new RuntimeException("WanImage 生成失败: " + output.path("message").asText(""));
                }
                Thread.sleep(pollIntervalMs);
            }
            throw new RuntimeException("WanImage 生成超时");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("WanImage 轮询异常: " + e.getMessage(), e);
        }
    }

    @Override
    public String download(String resultUrl, String localPath) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(resultUrl))
                    .timeout(Duration.ofMinutes(5))
                    .header("Authorization", "Bearer " + apiKey)
                    .GET().build();
            HttpResponse<byte[]> res = httpClient.send(req,
                    HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() != 200) {
                throw new RuntimeException("WanImage 下载失败 HTTP " + res.statusCode());
            }
            Path dst = Path.of(localPath);
            Files.createDirectories(dst.getParent());
            Path tmp = Files.createTempFile(dst.getParent(), "wanimg_", ".png");
            Files.write(tmp, res.body());
            Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING);
            return dst.toString();
        } catch (Exception e) {
            throw new RuntimeException("WanImage 下载异常: " + e.getMessage(), e);
        }
    }
}
