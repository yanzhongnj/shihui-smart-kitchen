package com.videostep.locator.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 下载任务进度推送：/ws/download/{taskId}
 */
@Component
@Slf4j
public class DownloadProgressHandler extends TextWebSocketHandler {

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String taskId = extractTaskId(session);
        if (taskId != null) {
            sessions.put(taskId, session);
            log.info("Download progress WS connected: taskId={}, session={}", taskId, session.getId());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.entrySet().removeIf(e -> e.getValue().getId().equals(session.getId()));
        log.info("Download progress WS disconnected: session={}", session.getId());
    }

    public void sendProgress(Long taskId, String status, int progress, String phase, String message) {
        WebSocketSession session = sessions.get(String.valueOf(taskId));
        if (session == null || !session.isOpen()) return;

        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "PROGRESS");
            payload.put("taskId", String.valueOf(taskId));
            payload.put("status", status);
            payload.put("progress", progress);
            payload.put("phase", phase != null ? phase : "");
            payload.put("message", message != null ? message : "");
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
        } catch (IOException e) {
            log.error("Failed to send download progress to task {}", taskId, e);
        }
    }

    /**
     * 从 /ws/download/{taskId} 提取 taskId：取路径最后一段（忽略 query string）。
     * 注意：不要用「查找名为 taskId 的路径段」的方式，实际路径段名是 download。
     */
    private String extractTaskId(WebSocketSession session) {
        String path = session.getUri() != null ? session.getUri().getPath() : "";
        String[] segments = path.split("/");
        for (int i = segments.length - 1; i >= 0; i--) {
            if (!segments[i].isEmpty()) return segments[i];
        }
        return null;
    }
}
