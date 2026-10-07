package com.videostep.locator.service.device;

import java.util.Map;

/**
 * 归一化设备指令（跨厂商统一）。
 * action: preheat | bake | steam | air_fry | set_timer | start | stop
 */
public class DeviceCommand {
    public String action;
    public String mode;
    public Integer temperature;   // ℃（可选）
    public Integer durationMin;   // 分钟（可选）
    public Map<String, Object> extra;

    public DeviceCommand() {}

    public DeviceCommand(String action, String mode, Integer temperature, Integer durationMin) {
        this.action = action;
        this.mode = mode;
        this.temperature = temperature;
        this.durationMin = durationMin;
    }

    public String describe() {
        StringBuilder sb = new StringBuilder(action == null ? "" : action);
        if (mode != null) sb.append("·").append(mode);
        if (temperature != null) sb.append("·").append(temperature).append("℃");
        if (durationMin != null) sb.append("·").append(durationMin).append("min");
        return sb.toString();
    }
}
