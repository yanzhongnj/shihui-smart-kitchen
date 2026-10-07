package com.videostep.locator.service.device;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videostep.locator.entity.DeviceEvent;
import com.videostep.locator.entity.KitchenDevice;
import com.videostep.locator.repository.DeviceEventRepository;
import com.videostep.locator.websocket.DeviceProgressHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 设备事件归一 → 入库（device_events 可回放/审计）→ WS 推送 /ws/device/{deviceId}。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeviceEventService {

    private final DeviceEventRepository eventRepository;
    private final DeviceProgressHandler deviceProgressHandler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public void recordEvent(KitchenDevice device, String eventType, Map<String, Object> payload) {
        try {
            DeviceEvent evt = DeviceEvent.builder()
                    .deviceId(device.getId())
                    .vendorOrgId(device.getOrgId())
                    .eventType(eventType)
                    .payload(payload == null ? null : objectMapper.writeValueAsString(payload))
                    .build();
            eventRepository.insert(evt);
            log.info("[设备事件] device={} event={}", device.getId(), eventType);
            deviceProgressHandler.pushDeviceEvent(device.getId(), eventType, payload);
        } catch (Exception e) {
            log.warn("[设备事件] 记录/推送失败 device={} event={}: {}", device.getId(), eventType, e.getMessage());
        }
    }
}
