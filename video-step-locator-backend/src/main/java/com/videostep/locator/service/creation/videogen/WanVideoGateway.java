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
 * 通义万相（Wan）真实视频生成网关（阿里云 DashScope 异步任务：提交 → 轮询 → 下载）。
 * 模型默认 wan2.7-t2v 720P（品质档 0.6 元/秒，见设计 §11）。需配置 ALIYUN_ASR_APP_KEY（DashScope key）。
 * 未配置 key 时 testConnection=false，由 AIVideoGenOrchestrator 降级 mock。
 */
@Component
@Slf4j
public class WanVideoGateway implements AIVideoGenGateway, AiConfigRefreshable {

    private static final ObjectMapper OM = new ObjectMapper();

    private final HttpClient httpClient;

    @Value("${creation.wan.api-key:}")
    private volatile String apiKey;

    @Value("${creation.wan.model:wan2.7-t2v}")
    private volatile String model;

    @Value("${creation.wan.resolution:720P}")
    private volatile String resolution;

    @Value("${creation.wan.endpoint:https://dashscope.aliyuncs.com/api/v1/services/aigc/video-generation/video-synthesis}")
    private volatile String endpoint;

    @Value("${creation.videogen.poll-interval-ms:20000}")
    private volatile long pollIntervalMs;

    @Value("${creation.videogen.default-segment-sec:5}")
    private volatile int defaultSegmentSec;

    public WanVideoGateway() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15)).build();
    }

    @Override
    public String providerKey() {
        return "video_gen.wan";
    }

    /** 配置中心热更新：下次提交即用新值 */
    @Override
    public void refreshConfig(AiProviderConfigSnapshot cfg) {
        if (cfg == null) return;
        this.apiKey = cfg.getApiKey();
        this.model = cfg.getModel();
        this.resolution = cfg.getExtra("resolution", this.resolution);
        this.endpoint = cfg.getEndpoint();
        this.pollIntervalMs = cfg.getExtraLong("poll_interval_ms", this.pollIntervalMs);
        this.defaultSegmentSec = cfg.getExtraInt("default_segment_sec", this.defaultSegmentSec);
        log.info("WanVideoGateway 配置已热更新: model={}, resolution={}, hasKey={}",
                model, resolution, apiKey != null && !apiKey.isBlank());
    }

    @Override
    public String provider() {
        return "wan";
    }

    @Override
    public boolean testConnection() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public VideoGenTaskRef submit(String prompt, int durationSec) {
        int dur = durationSec <= 0 ? defaultSegmentSec : durationSec;
        try {
            ObjectNode body = OM.createObjectNode();
            body.put("model", model);
            body.putObject("input").put("prompt", prompt);
            ObjectNode params = body.putObject("parameters");
            params.put("prompt_extend", true);
            params.put("size", "1280*720");
            params.put("resolution", resolution);
            // 时长由厂商模型支持范围决定，超出时由模型自动处理
            params.put("duration", dur);

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
                throw new RuntimeException("Wan 提交失败 HTTP " + res.statusCode() + ": " + res.body());
            }
            JsonNode root = OM.readTree(res.body());
            String taskId = root.path("output").path("task_id").asText("");
            if (taskId.isBlank()) {
                throw new RuntimeException("Wan 未返回 task_id: " + res.body());
            }
            log.info("[WanVideoGateway] 提交成功 taskId={}", taskId);
            return new VideoGenTaskRef(provider(), taskId);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Wan 提交异常: " + e.getMessage(), e);
        }
    }

    @Override
    public VideoGenResult poll(String taskRef) {
        String taskId = taskRef;
        long deadline = System.currentTimeMillis() + 10 * 60 * 1000; // 最长 10 分钟
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
                    throw new RuntimeException("Wan 查询失败 HTTP " + res.statusCode());
                }
                JsonNode root = OM.readTree(res.body());
                JsonNode output = root.path("output");
                String status = output.path("task_status").asText("");
                if ("SUCCEEDED".equals(status)) {
                    String videoUrl = output.path("video_url").asText("");
                    if (videoUrl.isBlank()) {
                        videoUrl = output.path("results").path(0).path("url").asText("");
                    }
                    if (videoUrl.isBlank()) {
                        throw new RuntimeException("Wan 成功但无视频 URL");
                    }
                    return new VideoGenResult("completed", videoUrl, defaultSegmentSec);
                }
                if ("FAILED".equals(status)) {
                    throw new RuntimeException("Wan 生成失败: " + output.path("message").asText(""));
                }
                Thread.sleep(pollIntervalMs);
            }
            throw new RuntimeException("Wan 生成超时");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Wan 轮询异常: " + e.getMessage(), e);
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
                throw new RuntimeException("Wan 下载失败 HTTP " + res.statusCode());
            }
            Path dst = Path.of(localPath);
            Files.createDirectories(dst.getParent());
            Path tmp = Files.createTempFile(dst.getParent(), "wan_", ".mp4");
            Files.write(tmp, res.body());
            Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING);
            return dst.toString();
        } catch (Exception e) {
            throw new RuntimeException("Wan 下载异常: " + e.getMessage(), e);
        }
    }
}
