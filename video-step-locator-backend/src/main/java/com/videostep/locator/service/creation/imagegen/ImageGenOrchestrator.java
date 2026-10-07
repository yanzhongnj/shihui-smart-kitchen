package com.videostep.locator.service.creation.imagegen;

import com.videostep.locator.service.aiconfig.AiConfigRefreshable;
import com.videostep.locator.service.aiconfig.AiProviderConfigSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * AI 文生图网关编排器（仿 AIVideoGenOrchestrator）：
 * engine = wan_image | mock | auto；首选不可达/未配置 → 降级 mock（免费，保证链路不断）。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ImageGenOrchestrator implements ImageGenGateway, AiConfigRefreshable {

    private final MockImageGateway mockGateway;
    private final WanImageGateway wanImageGateway;

    @Value("${creation.effect-image.provider:mock}")
    private volatile String engine;

    @Override
    public String providerKey() {
        return "image_gen.engine";
    }

    /** 配置中心热更新：下一个任务按新引擎路由（wan_image|mock|auto） */
    @Override
    public void refreshConfig(AiProviderConfigSnapshot cfg) {
        if (cfg == null) return;
        String v = cfg.getExtra("engine", this.engine);
        if (v != null && !v.isBlank()) {
            this.engine = v;
            log.info("ImageGenOrchestrator 引擎路由已热更新: {}", this.engine);
        }
    }

    private ImageGenGateway select() {
        String p = engine;
        if ("auto".equals(p)) {
            p = wanImageGateway.testConnection() ? "wan_image" : "mock";
        }
        if ("wan_image".equals(p)) {
            if (wanImageGateway.testConnection()) {
                return wanImageGateway;
            }
            log.warn("[效果图] WanImage 网关不可用/未配置 key（provider={}），降级 mock", engine);
            return mockGateway;
        }
        if (!"mock".equals(p)) {
            log.warn("[效果图] provider={} 未接入，降级 mock", p);
        }
        return mockGateway;
    }

    /** 是否产生真实计费（mock 免费；按实际选中引擎判断，未配 key 降级 mock 时不计费） */
    public boolean isBillable() {
        return !(select() instanceof MockImageGateway);
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
    public ImageGenTaskRef submit(String prompt, String style) {
        return select().submit(prompt, style);
    }

    @Override
    public ImageGenResult poll(String taskRef) {
        return select().poll(taskRef);
    }

    @Override
    public String download(String resultUrl, String localPath) {
        return select().download(resultUrl, localPath);
    }
}
