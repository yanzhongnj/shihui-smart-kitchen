package com.videostep.locator.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 酿味视频生成进度推送：WS /ws/videogen/{creationId}。
 * 消息格式：{type:"VIDEOGEN_PROGRESS", creationId, taskId, stepIndex, status, progress, durationSec}
 */
@Component
@Slf4j
public class VideoGenProgressHandler extends TextWebSocketHandler {

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String creationId = extractPathParam(session, "creationId");
        if (creationId != null) {
            sessions.put(creationId, session);
            log.info("VideoGen WS connected: creationId={} session={}", creationId, session.getId());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.entrySet().removeIf(e -> e.getValue().getId().equals(session.getId()));
        log.info("VideoGen WS disconnected: session={}", session.getId());
    }

    public void sendProgress(String creationId, Long taskId, Integer stepIndex,
                             String status, Integer progress, Double durationSec) {
        WebSocketSession session = sessions.get(creationId);
        if (session == null || !session.isOpen()) return;
        try {
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("type", "VIDEOGEN_PROGRESS");
            payload.put("creationId", creationId);
            payload.put("taskId", taskId);
            payload.put("stepIndex", stepIndex);
            payload.put("status", status);
            payload.put("progress", progress);
            payload.put("durationSec", durationSec);
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
        } catch (IOException e) {
            log.error("Failed to send videogen progress to creation {}", creationId, e);
        }
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
