package com.videostep.locator.service.aiconfig;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 引擎注册表（v0.3 §5.4）：收集所有 {@link AiConfigRefreshable} Bean，
 * 按 providerKey 分组；配置变更时通知对应引擎/编排器热刷新。
 */
@Component
@Slf4j
public class AiProviderRegistry {

    private final Map<String, List<AiConfigRefreshable>> byProviderKey = new LinkedHashMap<>();

    public AiProviderRegistry(List<AiConfigRefreshable> refreshables) {
        for (AiConfigRefreshable r : refreshables) {
            byProviderKey.computeIfAbsent(r.providerKey(), k -> new ArrayList<>()).add(r);
        }
        log.info("[AiProviderRegistry] 已注册 {} 个可热刷新引擎/编排器: {}", refreshables.size(), byProviderKey.keySet());
    }

    /** 通知某引擎配置变更 → 热刷新 */
    public void notify(String providerKey, AiProviderConfigSnapshot cfg) {
        List<AiConfigRefreshable> list = byProviderKey.get(providerKey);
        if (list == null) return;
        for (AiConfigRefreshable r : list) {
            try {
                r.refreshConfig(cfg);
            } catch (Exception e) {
                log.error("[AiProviderRegistry] {} refreshConfig 异常: {}", providerKey, e.getMessage(), e);
            }
        }
    }

    /** 启动/全量变更后：把所有缓存快照推给引擎 */
    public void refreshAll(AiConfigCache cache) {
        for (String key : cache.providerKeys()) {
            AiProviderConfigSnapshot snap = cache.get(key);
            if (snap != null) {
                notify(key, snap);
            }
        }
        log.info("[AiProviderRegistry] 已向全部引擎推送配置快照");
    }

    /** 连通性测试（取第一个实现，返回是否可用） */
    public boolean test(String providerKey) {
        List<AiConfigRefreshable> list = byProviderKey.get(providerKey);
        if (list == null || list.isEmpty()) return false;
        try {
            return list.get(0).testConnection();
        } catch (Exception e) {
            log.warn("[AiProviderRegistry] {} testConnection 异常: {}", providerKey, e.getMessage());
            return false;
        }
    }

    public List<AiConfigRefreshable> refreshables(String providerKey) {
        return byProviderKey.getOrDefault(providerKey, List.of());
    }
}
