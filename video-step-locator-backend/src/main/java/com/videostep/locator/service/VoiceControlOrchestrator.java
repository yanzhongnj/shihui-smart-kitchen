package com.videostep.locator.service;

import com.videostep.locator.dto.response.AsrResult;
import com.videostep.locator.service.asr.AliyunAsrProvider;
import com.videostep.locator.service.asr.FunasrAsrProvider;
import com.videostep.locator.service.asr.VoiceRecognitionProvider;
import com.videostep.locator.service.aiconfig.AiConfigRefreshable;
import com.videostep.locator.service.aiconfig.AiProviderConfigSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Service
@Slf4j
public class VoiceControlOrchestrator implements AiConfigRefreshable {

    private final AliyunAsrProvider aliyunAsr;
    private final FunasrAsrProvider funasrAsr;
    private volatile String engine;
    private final Map<String, VoiceSession> sessions = new ConcurrentHashMap<>();

    public VoiceControlOrchestrator(
            ObjectProvider<AliyunAsrProvider> aliyunAsrProvider,
            ObjectProvider<FunasrAsrProvider> funasrAsrProvider,
            @Value("${voice-control.engine:auto}") String engine) {
        this.aliyunAsr = aliyunAsrProvider.getIfAvailable();
        this.funasrAsr = funasrAsrProvider.getIfAvailable();
        this.engine = (engine == null || engine.isBlank()) ? "auto" : engine;
        if (this.aliyunAsr == null) {
            log.warn("AliyunAsrProvider not available");
        }
        if (this.funasrAsr == null) {
            log.warn("FunasrAsrProvider not available");
        }
        log.info("Voice control engine mode: {}", this.engine);
    }

    @Override
    public String providerKey() {
        return "asr.engine";
    }

    @Override
    public boolean testConnection() {
        if (aliyunAsr == null && funasrAsr == null) return false;
        try {
            VoiceRecognitionProvider p = selectProvider();
            return p != null && p.testConnection();
        } catch (Exception e) {
            log.warn("Voice 引擎连通性测试异常: {}", e.getMessage());
            return false;
        }
    }

    /** 配置中心热更新：下一条命令按新引擎路由（funasr|aliyun|auto） */
    @Override
    public void refreshConfig(AiProviderConfigSnapshot cfg) {
        if (cfg == null) return;
        String v = cfg.getExtra("engine", this.engine);
        if (v != null && !v.isBlank()) {
            this.engine = v;
            log.info("VoiceControlOrchestrator 引擎路由已热更新: {}", this.engine);
        }
    }

    public void startSession(String sessionId, Consumer<AsrResult> callback) {
        VoiceRecognitionProvider provider = selectProvider();
        VoiceSession session = new VoiceSession(sessionId, callback, provider);
        sessions.put(sessionId, session);
        provider.startSession(sessionId, callback);
        log.info("Voice session started: {} engine={}", sessionId, provider.getName());
    }

    /** 纯转写会话（不匹配控制指令，仅回传识别文本）——语音输入文字内容使用（与视频语音控制隔离） */
    public void startTranscribeSession(String sessionId, Consumer<AsrResult> callback) {
        VoiceRecognitionProvider provider = selectProvider();
        VoiceSession session = new VoiceSession(sessionId, callback, provider);
        sessions.put(sessionId, session);
        provider.startTranscribeSession(sessionId, callback);
        log.info("Voice transcribe session started: {} engine={}", sessionId, provider.getName());
    }

    public void sendAudio(String sessionId, byte[] audioData) {
        VoiceSession session = sessions.get(sessionId);
        if (session == null) {
            log.warn("No session found for: {}", sessionId);
            return;
        }
        session.provider.sendAudio(sessionId, audioData);
    }

    public void onAsrResult(String sessionId, AsrResult result) {
        VoiceSession session = sessions.get(sessionId);
        if (session != null && result != null) {
            session.callback.accept(result);
        }
    }

    /** 通知识别引擎「说话已结束」，促使尽快产出最终结果（按住说话 PTT 松开时使用） */
    public void flushVoice(String sessionId) {
        VoiceSession session = sessions.get(sessionId);
        if (session != null) {
            session.provider.flush(sessionId);
        }
    }

    public void endSession(String sessionId) {
        VoiceSession session = sessions.remove(sessionId);
        if (session != null) {
            session.provider.endSession(sessionId);
            log.info("Voice session ended: {} engine={}", sessionId, session.provider.getName());
        }
    }

    /**
     * 按 voice-control.engine 选择识别引擎（每个语音会话固定一次，路由一致）：
     * - funasr : 只用本地 FunASR
     * - aliyun : 用阿里云；若未配置 key / 配置不合法则降级 FunASR
     * - auto   : 优先阿里云（key 已配置），否则用 FunASR（保留本地能力）
     */
    private VoiceRecognitionProvider selectProvider() {
        String mode = engine.toLowerCase();
        if ("funasr".equals(mode)) {
            // 优先 FunASR，但不可达（未启动/端口不通）时自动回退 Aliyun，保证语音可用
            if (funasrAsr != null && funasrAsr.testConnection()) {
                return funasrAsr;
            }
            log.warn("FunASR ASR unavailable, falling back to Aliyun");
            return aliyunAsr != null ? aliyunAsr : funasrAsr;
        }
        if ("aliyun".equals(mode)) {
            if (aliyunAsr != null && aliyunAsr.testConnection()) {
                return aliyunAsr;
            }
            log.warn("Aliyun ASR unavailable, falling back to FunASR");
            return funasrAsr != null ? funasrAsr : aliyunAsr;
        }
        // auto：优先阿里云，其次 FunASR
        if (aliyunAsr != null && aliyunAsr.testConnection()) {
            return aliyunAsr;
        }
        if (funasrAsr != null && funasrAsr.testConnection()) {
            return funasrAsr;
        }
        return aliyunAsr != null ? aliyunAsr : funasrAsr;
    }

    private static class VoiceSession {
        final String sessionId;
        final Consumer<AsrResult> callback;
        final VoiceRecognitionProvider provider;

        VoiceSession(String sessionId, Consumer<AsrResult> callback, VoiceRecognitionProvider provider) {
            this.sessionId = sessionId;
            this.callback = callback;
            this.provider = provider;
        }
    }
}
