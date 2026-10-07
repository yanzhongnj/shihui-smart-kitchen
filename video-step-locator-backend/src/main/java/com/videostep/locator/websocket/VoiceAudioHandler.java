package com.videostep.locator.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videostep.locator.dto.response.AsrResult;
import com.videostep.locator.service.VoiceControlOrchestrator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
public class VoiceAudioHandler extends AbstractWebSocketHandler {

    private final VoiceControlOrchestrator orchestrator;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    // 诊断：首次收到前端音频时打 INFO（默认是 DEBUG 看不到）
    private final java.util.concurrent.atomic.AtomicBoolean audioLogged =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public VoiceAudioHandler(VoiceControlOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String sessionId = extractPathParam(session, "sessionId");
        if (sessionId == null) {
            sessionId = session.getId();
        }
        sessions.put(sessionId, session);

        orchestrator.startSession(sessionId, result -> {
            try {
                if (session.isOpen()) {
                    // 使用 HashMap 避免 Map.of() 对 null 值抛 NPE
                    Map<String, Object> resultMap = new HashMap<>();
                    resultMap.put("type", "RESULT");
                    resultMap.put("text", result.getText());
                    resultMap.put("action", result.getAction());
                    resultMap.put("params", result.getParams());
                    resultMap.put("confidence", result.getConfidence());
                    String json = objectMapper.writeValueAsString(resultMap);
                    session.sendMessage(new TextMessage(json));
                }
            } catch (IOException e) {
                log.error("Failed to send ASR result", e);
            }
        });

        log.info("Voice WS connected: sessionId={}", sessionId);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        String sessionId = extractPathParam(session, "sessionId");
        if (sessionId == null) sessionId = session.getId();

        byte[] audioData = message.getPayload().array();
        if (audioLogged.compareAndSet(false, true)) {
            log.info(">>> [DIAG] First audio received from frontend: session={}, bytes={}",
                    sessionId, audioData.length);
        }
        orchestrator.sendAudio(sessionId, audioData);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // 支持客户发送控制命令；"stop"/"end" 表示按住说话松开 → 通知识别引擎结束本句
        String payload = message.getPayload();
        log.debug("Voice text msg: {}", payload);
        String sessionId = extractPathParam(session, "sessionId");
        if (sessionId == null) sessionId = session.getId();
        if (payload != null && (payload.contains("\"type\":\"stop\"")
                || payload.contains("\"type\":\"end\"")
                || "stop".equalsIgnoreCase(payload.trim())
                || "end".equalsIgnoreCase(payload.trim()))) {
            orchestrator.flushVoice(sessionId);
            log.info("Voice flush requested: sessionId={}", sessionId);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String sessionId = extractPathParam(session, "sessionId");
        if (sessionId == null) sessionId = session.getId();

        orchestrator.endSession(sessionId);
        sessions.remove(sessionId);
        log.info("Voice WS disconnected: sessionId={}", sessionId);
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
