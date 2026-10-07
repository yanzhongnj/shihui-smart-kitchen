package com.videostep.locator.service.device;

import java.util.Map;

/** 归一化设备事件（厂商回调 → 统一格式 → 入库 + WS 推送） */
public class DeviceEventMessage {
    public String vendorDeviceId;
    public String eventType;   // preheat_done | work_started | work_done | offline | online
    public Map<String, Object> payload;

    public DeviceEventMessage(String vendorDeviceId, String eventType, Map<String, Object> payload) {
        this.vendorDeviceId = vendorDeviceId;
        this.eventType = eventType;
        this.payload = payload;
    }
}
