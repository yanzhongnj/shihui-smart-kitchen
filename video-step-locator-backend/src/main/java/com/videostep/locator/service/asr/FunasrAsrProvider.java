package com.videostep.locator.service.asr;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videostep.locator.dto.response.AsrResult;
import com.videostep.locator.service.ai.VoiceIntentMapper;
import com.videostep.locator.service.aiconfig.AiConfigRefreshable;
import com.videostep.locator.service.aiconfig.AiProviderConfigSnapshot;
import jakarta.websocket.*;
import jakarta.websocket.ClientEndpointConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Service
@Slf4j
public class FunasrAsrProvider implements VoiceRecognitionProvider, AiConfigRefreshable {

    private final VoiceIntentMapper voiceIntentMapper;
    private final Map<String, SessionHolder> sessions = new ConcurrentHashMap<>();
    // 诊断：首次发送音频到 FunASR 时打 INFO
    private final java.util.concurrent.atomic.AtomicBoolean audioSentLogged =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    @Value("${voice-control.funasr.host}")
    private volatile String host;

    @Value("${voice-control.funasr.port}")
    private volatile int port;

    public FunasrAsrProvider(VoiceIntentMapper voiceIntentMapper) {
        this.voiceIntentMapper = voiceIntentMapper;
    }

    @Override
    public String providerKey() {
        return "asr.funasr";
    }

    /** 配置中心热更新：新一句/新会话重连时使用新 host/port */
    @Override
    public void refreshConfig(AiProviderConfigSnapshot cfg) {
        if (cfg == null) return;
        this.host = cfg.getExtra("host", this.host);
        this.port = cfg.getExtraInt("port", this.port);
        log.info("FunasrAsrProvider 配置已热更新: host={}, port={}", host, port);
    }

    @Override
    public void startSession(String sessionId, Consumer<AsrResult> callback) {
        connect(sessionId, callback, false);
    }

    @Override
    public void startTranscribeSession(String sessionId, Consumer<AsrResult> callback) {
        connect(sessionId, callback, true);
    }

    /** 连接 FunASR 并发送握手（首次连接与自动重连共用） */
    private synchronized void connect(String sessionId, Consumer<AsrResult> callback, boolean transcribe) {
        try {
            String wsUrl = String.format("ws://%s:%d/ws/v1", host, port);

            WebSocketContainer container = ContainerProvider.getWebSocketContainer();
            // 使用程序化 API（ClientEndpointConfig + extends Endpoint），避免 @ClientEndpoint 
            // 注解方式带来的实例化问题
            ClientEndpointConfig config = ClientEndpointConfig.Builder.create().build();
            FunasrClientEndpoint clientEndpoint = new FunasrClientEndpoint(sessionId, callback);
            Session wsSession = container.connectToServer(clientEndpoint, config, URI.create(wsUrl));

            SessionHolder holder = new SessionHolder(sessionId, wsSession, callback, true, transcribe);
            // 发送 FunASR 握手初始化消息（在线 2pass 模式 + PCM 格式，is_speaking=true 保持持续识别）
            String initJson = "{\"mode\":\"2pass\",\"chunk_size\":[5,10,5],\"chunk_interval\":10,\"encoder_chunk_look_back\":4,\"decoder_chunk_look_back\":0,\"audio_fs\":16000,\"wav_name\":\"mic\",\"wav_format\":\"pcm\",\"is_speaking\":true,\"itn\":true}";
            synchronized (holder.sendLock) {
                wsSession.getBasicRemote().sendText(initJson);
            }

            sessions.put(sessionId, holder);
            log.info("FunASR session connected: {} -> {}", sessionId, wsUrl);

        } catch (Exception e) {
            log.error("Failed to start FunASR session: {}", sessionId, e);
            callback.accept(AsrResult.builder().action("ERROR").build());
        }
    }

    /** 一句话识别完成后，主动结束并立即重开 FunASR 会话（每句全新会话，保证下一句可识别）。
     *  原理：FunASR 2pass 在线模式输出一句 offline 结果后，即使重置 is_speaking 也不可靠地
     *  接收下一句；重建会话（重新握手 is_speaking:true）是最稳定的持续监听方式。 */
    private void restartFunAsrSession(SessionHolder holder) {
        if (holder == null) return;
        String sid = holder.sessionId;
        Consumer<AsrResult> cb = holder.callback;
        holder.active = false; // 先停用旧会话，避免重连期间音频发往旧连接
        try {
            holder.session.close();
        } catch (Exception ignored) {}
        try {
            Thread.sleep(200); // 等待对端释放再重连
        } catch (InterruptedException ignored) {}
        connect(sid, cb, holder.transcribe);
    }

    @Override
    public void sendAudio(String sessionId, byte[] audioData) {
        SessionHolder holder = sessions.get(sessionId);
        if (holder != null && holder.active && holder.session.isOpen()) {
            try {
                // 与 Web 端一致：以二进制 PCM s16le 16kHz 裸音频发送（FunASR 直接识别的格式）
                // ⚠️ 不要改成 JSON/base64 帧——FunASR 期望二进制 PCM，否则一条都不识别
                if (audioSentLogged.compareAndSet(false, true)) {
                    log.info(">>> [DIAG] First audio sent to FunASR: session={}, bytes={}",
                            sessionId, audioData.length);
                }
                // 加锁串行：防止 resetSpeaking 的 sendText 与音频 sendBinary 并发导致消息丢失
                synchronized (holder.sendLock) {
                    holder.session.getBasicRemote().sendBinary(ByteBuffer.wrap(audioData));
                }
            } catch (Exception e) {
                log.error("Failed to send audio to FunASR", e);
            }
        }
    }

    @Override
    public void endSession(String sessionId) {
        SessionHolder holder = sessions.remove(sessionId);
        if (holder != null) {
            holder.active = false;
            try {
                // 通知 FunASR 语音结束
                String endJson = "{\"is_speaking\":false}";
                if (holder.session.isOpen()) {
                    synchronized (holder.sendLock) {
                        holder.session.getBasicRemote().sendText(endJson);
                    }
                }
                holder.session.close();
            } catch (Exception e) {
                log.warn("Error closing FunASR session", e);
            }
        }
    }

    /** 通知识别引擎「说话已结束」，促使其尽快产出最终结果（按住说话 PTT 松开时使用） */
    @Override
    public void flush(String sessionId) {
        SessionHolder holder = sessions.get(sessionId);
        if (holder != null && holder.active && holder.session.isOpen()) {
            try {
                synchronized (holder.sendLock) {
                    holder.session.getBasicRemote().sendText("{\"is_speaking\":false}");
                }
                log.info("FunASR flush (is_speaking=false): {}", sessionId);
            } catch (Exception e) {
                log.warn("FunASR flush failed: {}", sessionId, e);
            }
        }
    }

    /** FunASR 会话被对端关闭/出错时自动重连，保持持续监听（无需用户重开语音开关） */
    private void reconnectIfActive(String sessionId) {
        SessionHolder holder = sessions.get(sessionId);
        if (holder == null || !holder.active) return;
        try {
            Thread.sleep(300);
            connect(sessionId, holder.callback, holder.transcribe);
            log.info("FunASR WS auto-reconnected: {}", sessionId);
        } catch (Exception e) {
            log.warn("FunASR WS auto-reconnect failed: {}", sessionId, e);
        }
    }

    @Override
    public boolean testConnection() {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress(host, port), 2000);
            return true;
        } catch (Exception e) {
            log.warn("FunASR server not reachable at {}:{}", host, port);
            return false;
        }
    }

    @Override
    public String getName() { return "FunasrAsrProvider"; }

    @SuppressWarnings("unchecked")
    private void handleResult(SessionHolder holder, String message) {
        log.info("FunASR raw message: {}", message);
        try {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> root = mapper.readValue(message, Map.class);

            // FunASR 2pass 模式输出说明:
            // - 2pass-online: 实时部分结果 (is_final=false), 忽略
            // - 2pass-offline: 最终完整结果 (is_final=false, 但这是最终结果!), 需要处理
            // - 部分版本也有 is_final=true 的普通结果
            String text = (String) root.get("text");
            String mode = (String) root.get("mode");
            Boolean isFinal = (Boolean) root.get("is_final");

            // 2pass-offline 模式的结果是最终识别结果，不论 is_final 字段值
            boolean finalResult = "2pass-offline".equals(mode) || "offline".equals(mode)
                    || (isFinal != null && isFinal);

            if (text != null && !text.isBlank() && finalResult) {
                if (holder.transcribe) {
                    // 纯转写模式：不匹配控制指令，直接回传识别文本
                    holder.callback.accept(AsrResult.builder()
                            .text(text).action("TRANSCRIBE").confidence(0.7f).build());
                } else {
                    AsrResult matched = voiceIntentMapper.match(text);
                    if (matched != null) {
                        holder.callback.accept(matched);
                    } else {
                        holder.callback.accept(AsrResult.builder().text(text).action("UNKNOWN").confidence(0.7f).build());
                    }
                }
                // 一句话识别完成：主动重启 FunASR 会话（每句全新会话），保证下一句可识别
                // （实验证明 resetSpeaking 重置 is_speaking 后 FunASR 仍不可靠接收第二句）
                restartFunAsrSession(holder);
            }
        } catch (Exception e) {
            log.error("Parse FunASR result failed: {}", message, e);
        }
    }

    private static final class SessionHolder {
        final String sessionId;
        final Session session;
        final Consumer<AsrResult> callback;
        final Object sendLock = new Object(); // 同一 FunASR session 的消息串行化，防止并发丢消息
        volatile boolean active;
        final boolean transcribe; // true=纯转写模式（不匹配控制指令）
        SessionHolder(String sessionId, Session session, Consumer<AsrResult> callback, boolean active, boolean transcribe) {
            this.sessionId = sessionId;
            this.session = session;
            this.callback = callback;
            this.active = active;
            this.transcribe = transcribe;
        }
    }

    /**
     * FunASR WebSocket 客户端端点
     * 使用程序化 API（extends Endpoint），通过 connectToServer(Endpoint, ClientEndpointConfig, URI)
     * 传入实例，避免 @ClientEndpoint 注解方式带来的实例化问题和无参构造器要求。
     */
    private class FunasrClientEndpoint extends Endpoint {
        private final String sessionId;
        private final Consumer<AsrResult> callback;

        FunasrClientEndpoint(String sessionId, Consumer<AsrResult> callback) {
            this.sessionId = sessionId;
            this.callback = callback;
        }

        @Override
        public void onOpen(Session session, EndpointConfig config) {
            log.info("FunASR WS opened: {}", sessionId);
            session.addMessageHandler(new MessageHandler.Whole<String>() {
                @Override
                public void onMessage(String message) {
                    SessionHolder holder = sessions.get(sessionId);
                    if (holder != null) {
                        handleResult(holder, message);
                    }
                }
            });
        }

        @Override
        public void onClose(Session session, CloseReason reason) {
            log.info("FunASR WS closed: {} reason={}", sessionId, reason);
            reconnectIfActive(sessionId);
        }

        @Override
        public void onError(Session session, Throwable thr) {
            log.error("FunASR WS error: {}", sessionId, thr);
            reconnectIfActive(sessionId);
        }
    }
}
