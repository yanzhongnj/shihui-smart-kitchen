package com.videostep.locator.service.device;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 设备网关编排（仿 VoiceControlOrchestrator）：按 vendor 路由；首选网关不可达时降级 mock，
 * 保证「下发→事件」链路不中断（与 FunASR→阿里云降级同思路）。
 */
@Service
@Slf4j
public class DeviceGatewayRegistry {

    private final List<DeviceGateway> gateways;
    private final Map<String, DeviceGateway> byVendor;

    public DeviceGatewayRegistry(List<DeviceGateway> gateways) {
        this.gateways = gateways;
        this.byVendor = gateways.stream()
                .collect(Collectors.toMap(DeviceGateway::vendor, Function.identity()));
    }

    public DeviceGateway getGateway(String vendor) {
        DeviceGateway g = byVendor.get(vendor);
        if (g == null) throw new IllegalArgumentException("不支持的设备厂商：" + vendor);
        return g;
    }

    /** 全部网关（供事件 sink 注入） */
    public List<DeviceGateway> getGateways() {
        return gateways;
    }

    /** 带健康检查的路由：首选网关不可达 → WARN 并降级 mock（保证功能不中断） */
    public DeviceGateway getGatewayWithFallback(String vendor) {
        DeviceGateway g = getGateway(vendor);
        try {
            if (g.testConnection()) return g;
            log.warn("网关 {} 不可达，降级到 mock", vendor);
        } catch (Exception e) {
            log.warn("网关 {} 健康检查异常，降级到 mock: {}", vendor, e.getMessage());
        }
        DeviceGateway mock = byVendor.get("mock");
        return mock != null ? mock : g;
    }
}
