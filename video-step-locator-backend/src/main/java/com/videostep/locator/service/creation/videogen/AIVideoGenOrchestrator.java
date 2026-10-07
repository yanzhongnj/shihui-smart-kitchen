package com.videostep.locator.service.creation.videogen;

import com.videostep.locator.service.aiconfig.AiConfigRefreshable;
import com.videostep.locator.service.aiconfig.AiProviderConfigSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * AI 视频生成网关编排器（仿 VoiceControlOrchestrator 双引擎降级）：
 * engine = wan | seedance | mock | auto；首选不可达/未配置 → WARN 降级 mock（保证链路不断）。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AIVideoGenOrchestrator implements AIVideoGenGateway, AiConfigRefreshable {

    private final MockVideoGateway mockGateway;
    private final WanVideoGateway wanGateway;
    private final SeedanceVideoGateway seedanceGateway;

    @Value("${creation.videogen.engine:mock}")
    private volatile String engine;

    @Override
    public String providerKey() {
        return "video_gen.engine";
    }

    /** 配置中心热更新：下一个任务按新引擎路由（wan|seedance|mock|auto） */
    @Override
    public void refreshConfig(AiProviderConfigSnapshot cfg) {
        if (cfg == null) return;
        String v = cfg.getExtra("engine", this.engine);
        if (v != null && !v.isBlank()) {
            this.engine = v;
            log.info("AIVideoGenOrchestrator 引擎路由已热更新: {}", this.engine);
        }
    }

    private AIVideoGenGateway select() {
        String p = engine;
        if ("auto".equals(p) || p == null || p.isBlank()) {
            p = wanGateway.testConnection() ? "wan"
                    : (seedanceGateway.testConnection() ? "seedance" : "mock");
        }
        if ("wan".equals(p)) {
            if (wanGateway.testConnection()) {
                return wanGateway;
            }
            log.warn("[酿味] Wan 不可用（主），回退 Seedance 备用");
            if (seedanceGateway.testConnection()) {
                return seedanceGateway;
            }
            log.warn("[酿味] Seedance 也不可用，降级 mock");
            return mockGateway;
        }
        if ("seedance".equals(p)) {
            if (seedanceGateway.testConnection()) {
                return seedanceGateway;
            }
            log.warn("[酿味] Seedance 不可用（主），回退 Wan 备用");
            if (wanGateway.testConnection()) {
                return wanGateway;
            }
            log.warn("[酿味] Wan 也不可用，降级 mock");
            return mockGateway;
        }
        // 其余回退 mock
        if (!"mock".equals(p)) {
            log.warn("[酿味] engine={} 未接入，降级 mock", p);
        }
        return mockGateway;
    }

    /** 是否产生真实计费（mock 免费；按实际选中引擎判断，未配 key 降级 mock 时不计费） */
    public boolean isBillable() {
        return !(select() instanceof MockVideoGateway);
    }

    @Override
    public String provider() {
        return select().provider();
    }

    @Override
    public boolean testConnection() {
        return select().testConnection();
    }

    @Override
    public VideoGenTaskRef submit(String prompt, int durationSec) {
        return select().submit(prompt, durationSec);
    }

    @Override
    public VideoGenResult poll(String taskRef) {
        return select().poll(taskRef);
    }

    @Override
    public String download(String resultUrl, String localPath) {
        return select().download(resultUrl, localPath);
    }
}
