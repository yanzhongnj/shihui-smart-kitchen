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
 * 设备状态实时推送（WS /ws/device/{deviceId}）。复用 TaskProgressHandler 的 sessions map 模式。
 * 推送格式：{type:"DEVICE_EVENT", deviceId, event, payload, timestamp}
 */
@Component
@Slf4j
public class DeviceProgressHandler extends TextWebSocketHandler {

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String deviceId = extractPathParam(session, "deviceId");
        if (deviceId != null) {
            sessions.put(deviceId, session);
            log.info("Device WS connected: deviceId={}, session={}", deviceId, session.getId());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.entrySet().removeIf(e -> e.getValue().getId().equals(session.getId()));
    }

    public void pushDeviceEvent(Long deviceId, String event, Map<String, Object> payload) {
        WebSocketSession session = sessions.get(String.valueOf(deviceId));
        if (session == null || !session.isOpen()) return;
        try {
            Map<String, Object> msg = new java.util.LinkedHashMap<>();
            msg.put("type", "DEVICE_EVENT");
            msg.put("deviceId", String.valueOf(deviceId));
            msg.put("event", event);
            msg.put("payload", payload == null ? Map.of() : payload);
            msg.put("timestamp", System.currentTimeMillis());
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(msg)));
        } catch (IOException e) {
            log.error("Failed to push device event to {}", deviceId, e);
        }
    }

    private String extractPathParam(WebSocketSession session, String name) {
        String path = session.getUri() != null ? session.getUri().getPath() : "";
        String[] segments = path.split("/");
        for (int i = 0; i < segments.length - 1; i++) {
            if (segments[i].equals(name)) return segments[i + 1];
        }
        return null;
    }
}
