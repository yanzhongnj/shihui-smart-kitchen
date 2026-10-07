package com.videostep.locator.service.aiconfig;

/**
 * 支持配置中心热刷新的引擎/编排器。
 * 实现方：DeepSeekClient / XunweiVisionService / WanVideoGateway / SeedanceVideoGateway /
 * WanImageGateway / AliyunAsrProvider / FunasrAsrProvider / CreationTtsService /
 * VoiceControlOrchestrator / AIVideoGenOrchestrator / ImageGenOrchestrator。
 *
 * <p>热更新语义：配置变更 → 写库 → 刷新内存缓存 → {@link #refreshConfig(AiProviderConfigSnapshot)}
 * 更新引擎 volatile 字段 → 下次调用即用新值（已开始的会话/已提交的异步任务沿用旧配置）。</p>
 */
public interface AiConfigRefreshable {

    /** 引擎配置键，如 llm.xunwei / video_gen.engine */
    String providerKey();

    /** 连通性测试（视频/文生图=仅校验鉴权，不真提交） */
    boolean testConnection();

    /** 从配置快照刷新引擎配置字段 */
    void refreshConfig(AiProviderConfigSnapshot cfg);
}
