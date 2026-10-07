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

@Component
@Slf4j
public class TaskProgressHandler extends TextWebSocketHandler {

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String taskId = extractPathParam(session, "taskId");
        if (taskId != null) {
            sessions.put(taskId, session);
            log.info("Task progress WS connected: taskId={}, session={}",
                    taskId, session.getId());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.entrySet().removeIf(e -> e.getValue().getId().equals(session.getId()));
        log.info("Task progress WS disconnected: session={}", session.getId());
    }

    public void sendProgress(String taskId, String status, int progress,
                              String phase, String message) {
        WebSocketSession session = sessions.get(taskId);
        if (session == null || !session.isOpen()) return;

        try {
            Map<String, Object> payload = Map.of(
                    "type", "PROGRESS",
                    "taskId", taskId,
                    "status", status,
                    "progress", progress,
                    "phase", phase != null ? phase : "",
                    "message", message != null ? message : ""
            );
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
        } catch (IOException e) {
            log.error("Failed to send progress to task {}", taskId, e);
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
