package com.videostep.locator.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videostep.locator.dto.response.AsrResult;
import com.videostep.locator.service.VoiceControlOrchestrator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 纯语音转写 WebSocket（/ws/transcribe/{sessionId}）——用于「语音输入文字内容」（创作/寻味按住说话）。
 * 与 /ws/voice（视频播放控制指令）【隔离】：本 handler 走 provider 纯转写模式，只回传识别文本，
 * 不做 VoiceIntentMapper 指令匹配，两个用途不混为一谈。
 */
@Component
@Slf4j
public class TranscribeAudioHandler extends AbstractWebSocketHandler {

    private final VoiceControlOrchestrator orchestrator;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    public TranscribeAudioHandler(VoiceControlOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String sessionId = extractPathParam(session, "sessionId");
        if (sessionId == null) {
            sessionId = session.getId();
        }
        sessions.put(sessionId, session);

        orchestrator.startTranscribeSession(sessionId, result -> {
            try {
                if (session.isOpen()) {
                    Map<String, Object> resultMap = new HashMap<>();
                    resultMap.put("type", "RESULT");
                    resultMap.put("text", result.getText());
                    resultMap.put("action",
                            result.getAction() == null ? "TRANSCRIBE" : result.getAction());
                    session.sendMessage(new TextMessage(objectMapper.writeValueAsString(resultMap)));
                }
            } catch (IOException e) {
                log.error("Failed to send transcribe result", e);
            }
        });

        log.info("Transcribe WS connected: sessionId={}", sessionId);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        String sessionId = extractPathParam(session, "sessionId");
        if (sessionId == null) sessionId = session.getId();
        orchestrator.sendAudio(sessionId, message.getPayload().array());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String payload = message.getPayload();
        String sessionId = extractPathParam(session, "sessionId");
        if (sessionId == null) sessionId = session.getId();
        // "stop"/"end" 表示按住说话松开 → 通知识别引擎结束本句（促使产出最终结果）
        if (payload != null && (payload.contains("\"type\":\"stop\"")
                || payload.contains("\"type\":\"end\"")
                || "stop".equalsIgnoreCase(payload.trim())
                || "end".equalsIgnoreCase(payload.trim()))) {
            orchestrator.flushVoice(sessionId);
            log.info("Transcribe flush requested: sessionId={}", sessionId);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String sessionId = extractPathParam(session, "sessionId");
        if (sessionId == null) sessionId = session.getId();

        orchestrator.endSession(sessionId);
        sessions.remove(sessionId);
        log.info("Transcribe WS disconnected: sessionId={}", sessionId);
    }

    private String extractPathParam(WebSocketSession session, String name) {
        String path = session.getUri() != null ? session.getUri().getPath() : "";
        String[] segments = path.split("/");
        for (int i = 0; i < segments.length - 1; i++) {
            if (segments[i].equals(name)) {
                return segments[i + 1];
            }
        }
        return null;
    }
}
