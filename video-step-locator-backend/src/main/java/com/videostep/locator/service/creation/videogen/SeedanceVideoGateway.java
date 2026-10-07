package com.videostep.locator.service.creation.videogen;

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
 * Seedance 视频生成网关（字节跳动，火山方舟 Ark / 即梦）。
 * 与 Wan 同构的异步任务式适配器，使用<b>独立的 SEEDANCE_API_KEY</b>（与阿里云语音 key 无关，不回退 ALIYUN_ASR_APP_KEY）。
 * 端点/模型由配置提供；未配置 key 或端点时 testConnection=false，由 AIVideoGenOrchestrator 降级 mock。
 */
@Component
@Slf4j
public class SeedanceVideoGateway implements AIVideoGenGateway, AiConfigRefreshable {

    private static final ObjectMapper OM = new ObjectMapper();

    private final HttpClient httpClient;

    @Value("${creation.seedance.api-key:}")
    private volatile String apiKey;

    @Value("${creation.seedance.endpoint:}")
    private volatile String endpoint;

    /** 任务查询端点，支持 {task_id} 占位 */
    @Value("${creation.seedance.query-endpoint:}")
    private volatile String queryEndpoint;

    @Value("${creation.seedance.model:}")
    private volatile String model;

    @Value("${creation.seedance.size:1280*720}")
    private volatile String size;

    @Value("${creation.videogen.poll-interval-ms:20000}")
    private volatile long pollIntervalMs;

    @Value("${creation.videogen.default-segment-sec:5}")
    private volatile int defaultSegmentSec;

    public SeedanceVideoGateway() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15)).build();
    }

    @Override
    public String providerKey() {
        return "video_gen.seedance";
    }

    /** 配置中心热更新：下次提交即用新值 */
    @Override
    public void refreshConfig(AiProviderConfigSnapshot cfg) {
        if (cfg == null) return;
        this.apiKey = cfg.getApiKey();
        this.endpoint = cfg.getEndpoint();
        this.queryEndpoint = cfg.getExtra("query_endpoint", this.queryEndpoint);
        this.model = cfg.getModel();
        this.size = cfg.getExtra("size", this.size);
        this.pollIntervalMs = cfg.getExtraLong("poll_interval_ms", this.pollIntervalMs);
        this.defaultSegmentSec = cfg.getExtraInt("default_segment_sec", this.defaultSegmentSec);
        log.info("SeedanceVideoGateway 配置已热更新: model={}, hasKey={}, endpoint={}",
                model, apiKey != null && !apiKey.isBlank(), endpoint);
    }

    @Override
    public String provider() {
        return "seedance";
    }

    @Override
    public boolean testConnection() {
        return apiKey != null && !apiKey.isBlank()
                && endpoint != null && !endpoint.isBlank();
    }

    @Override
    public VideoGenTaskRef submit(String prompt, int durationSec) {
        int dur = durationSec <= 0 ? defaultSegmentSec : durationSec;
        try {
            ObjectNode body = OM.createObjectNode();
            body.put("prompt", prompt);
            body.put("duration", dur);
            if (model != null && !model.isBlank()) {
                body.put("model", model);
            }
            if (size != null && !size.isBlank()) {
                body.put("size", size);
            }
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            OM.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> res = httpClient.send(req,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() != 200) {
                throw new RuntimeException("Seedance 提交失败 HTTP " + res.statusCode() + ": " + res.body());
            }
            JsonNode root = OM.readTree(res.body());
            String taskId = root.path("task_id").asText(
                    root.path("output").path("task_id").asText(
                            root.path("id").asText("")));
            if (taskId.isBlank()) {
                throw new RuntimeException("Seedance 未返回 task_id: " + res.body());
            }
            log.info("[SeedanceVideoGateway] 提交成功 taskId={}", taskId);
            return new VideoGenTaskRef(provider(), taskId);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Seedance 提交异常: " + e.getMessage(), e);
        }
    }

    @Override
    public VideoGenResult poll(String taskRef) {
        String taskId = taskRef;
        if (queryEndpoint == null || queryEndpoint.isBlank()) {
            throw new RuntimeException("Seedance 未配置查询端点（creation.seedance.query-endpoint，需含 {task_id} 占位）");
        }
        long deadline = System.currentTimeMillis() + 10 * 60 * 1000; // 最长 10 分钟
        try {
            while (System.currentTimeMillis() < deadline) {
                String url = queryEndpoint.replace("{task_id}", taskId);
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(30))
                        .header("Authorization", "Bearer " + apiKey)
                        .GET().build();
                HttpResponse<String> res = httpClient.send(req,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (res.statusCode() != 200) {
                    throw new RuntimeException("Seedance 查询失败 HTTP " + res.statusCode() + ": " + res.body());
                }
                JsonNode root = OM.readTree(res.body());
                JsonNode output = root.path("output");
                // 兼容多种状态字段：output.task_status（DashScope 风格）| 顶层 task_status/status（Ark 风格）
                String status = output.path("task_status").asText(
                        root.path("task_status").asText(
                                root.path("status").asText("")));
                if ("SUCCEEDED".equalsIgnoreCase(status) || "succeeded".equalsIgnoreCase(status)) {
                    String videoUrl = output.path("video_url").asText("");
                    if (videoUrl.isBlank()) {
                        videoUrl = output.path("video").path("url").asText("");
                    }
                    if (videoUrl.isBlank()) {
                        videoUrl = output.path("results").path(0).path("url").asText("");
                    }
                    if (videoUrl.isBlank()) {
                        videoUrl = root.path("video_url").asText("");
                    }
                    if (videoUrl.isBlank()) {
                        throw new RuntimeException("Seedance 成功但无视频 URL: " + res.body());
                    }
                    log.info("[SeedanceVideoGateway] 生成成功 taskId={}", taskId);
                    return new VideoGenResult("completed", videoUrl, defaultSegmentSec);
                }
                if ("FAILED".equalsIgnoreCase(status) || "failed".equalsIgnoreCase(status)) {
                    throw new RuntimeException("Seedance 生成失败: "
                            + output.path("message").asText(root.path("message").asText("")));
                }
                Thread.sleep(pollIntervalMs);
            }
            throw new RuntimeException("Seedance 生成超时");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Seedance 轮询异常: " + e.getMessage(), e);
        }
    }

    @Override
    public String download(String resultUrl, String localPath) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(resultUrl))
                    .timeout(Duration.ofMinutes(5))
                    .GET().build();
            HttpResponse<byte[]> res = httpClient.send(req,
                    HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() != 200) {
                throw new RuntimeException("Seedance 下载失败 HTTP " + res.statusCode());
            }
            Path dst = Path.of(localPath);
            Files.createDirectories(dst.getParent());
            Files.write(dst, res.body());
            return dst.toString();
        } catch (Exception e) {
            throw new RuntimeException("Seedance 下载异常: " + e.getMessage(), e);
        }
    }
}
