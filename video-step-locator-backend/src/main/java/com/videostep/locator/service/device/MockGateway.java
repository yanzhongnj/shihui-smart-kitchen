package com.videostep.locator.service.device;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 模拟设备网关（P0 必须实现）：内存态状态机（idle→preheating→preheat_done→working→done），
 * 内置延迟触发事件，无需真实硬件即可验收「参数提取→下发→事件→WS 推送」全链路。
 */
@Component
@Slf4j
public class MockGateway implements DeviceGateway {

    private static final class MockDevice {
        String vendorDeviceId;
        String deviceType;
        String state = "idle";
        MockDevice(String id, String type) { this.vendorDeviceId = id; this.deviceType = type; }
    }

    private final Map<String, MockDevice> devices = new LinkedHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private Consumer<DeviceEventMessage> eventSink;

    @Value("${device.mock.simulate-delay-ms:3000}")
    private long simulateDelayMs;

    @Override
    public String vendor() { return "mock"; }

    @Override
    public DeviceCommandResult bind(Long userId, String nickname, String deviceType) {
        String type = deviceType == null || deviceType.isBlank() ? "oven" : deviceType;
        String id = "mock-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        devices.put(id, new MockDevice(id, type));
        return DeviceCommandResult.ok("模拟设备创建成功", id);
    }

    @Override
    public void unbind(String vendorDeviceId) {
        devices.remove(vendorDeviceId);
    }

    @Override
    public DeviceCommandResult sendCommand(String vendorDeviceId, DeviceCommand cmd) {
        MockDevice d = devices.get(vendorDeviceId);
        if (d == null) return DeviceCommandResult.fail("模拟设备不存在");
        if (cmd == null || cmd.action == null || cmd.action.isBlank()) {
            return DeviceCommandResult.fail("指令动作不能为空");
        }
        String action = cmd.action;
        switch (action) {
            case "preheat" -> {
                d.state = "preheating";
                emit(d, "work_started", Map.of("phase", "preheating", "temperature", cmd.temperature));
                schedule(d, "preheat_done", Map.of("temperature", cmd.temperature));
                schedule(d, "work_done", Map.of());
                return DeviceCommandResult.ok("已开始预热", cmd.describe());
            }
            case "bake", "steam", "air_fry", "start" -> {
                d.state = "working";
                emit(d, "work_started", Map.of("phase", "working", "mode", cmd.mode, "temperature", cmd.temperature, "durationMin", cmd.durationMin));
                schedule(d, "work_done", Map.of());
                return DeviceCommandResult.ok("已开始工作", cmd.describe());
            }
            case "set_timer" -> {
                emit(d, "work_started", Map.of("phase", "timing", "durationMin", cmd.durationMin));
                schedule(d, "work_done", Map.of());
                return DeviceCommandResult.ok("定时已设置", cmd.describe());
            }
            case "stop" -> {
                d.state = "idle";
                emit(d, "work_done", Map.of("stopped", true));
                return DeviceCommandResult.ok("已停止", cmd.describe());
            }
            default -> { return DeviceCommandResult.fail("不支持的指令动作：" + action); }
        }
    }

    private void schedule(MockDevice d, String eventType, Map<String, Object> payload) {
        long delay = eventType.equals("preheat_done") ? simulateDelayMs : simulateDelayMs * 2;
        scheduler.schedule(() -> {
            if (d.state.equals("idle")) return;
            if (eventType.equals("preheat_done")) d.state = "preheated";
            else if (eventType.equals("work_done")) d.state = "idle";
            emit(d, eventType, payload);
        }, delay, TimeUnit.MILLISECONDS);
    }

    private void emit(MockDevice d, String eventType, Map<String, Object> payload) {
        log.info("[Mock设备] {} → {} {}", d.vendorDeviceId, eventType, payload);
        if (eventSink != null) {
            eventSink.accept(new DeviceEventMessage(d.vendorDeviceId, eventType, payload));
        }
    }

    @Override
    public void setEventSink(Consumer<DeviceEventMessage> sink) {
        this.eventSink = sink;
    }
}
