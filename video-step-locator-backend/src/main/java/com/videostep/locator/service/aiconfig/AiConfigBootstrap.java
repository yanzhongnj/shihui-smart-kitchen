package com.videostep.locator.service.aiconfig;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * AI 配置中心启动引导（v0.3 §5.1/§5.3）：
 * ① 确保加密总钥匙存在（启动定死）；② 从 DB 加载缓存（回退链解析 env/yml）；
 * ③ 把初始配置推给全部引擎（DB 有配置则立即覆盖 @Value，实现"未初始化走 env、已初始化走 DB"）。
 */
@Component
@Slf4j
@RequiredArgsConstructor
@Order(200)
public class AiConfigBootstrap implements ApplicationRunner {

    private final AiConfigCipher cipher;
    private final AiConfigCache cache;
    private final AiProviderRegistry registry;

    @Override
    public void run(ApplicationArguments args) {
        cipher.ensureKey();
        cache.refreshAll();
        registry.refreshAll(cache);
        log.info("[AiConfig] 配置中心已初始化：主钥就绪 + {} 引擎配置已加载并推送", cache.providerKeys().size());
    }
}
