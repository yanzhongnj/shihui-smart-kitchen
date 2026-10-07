package com.videostep.locator.service.device;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 涂鸦 IoT 网关（预留，P2 接入）：需企业认证 + OAuth。当前返回不可用，Registry 降级到 mock。
 */
@Component
@Slf4j
public class TuyaGateway implements DeviceGateway {

    @Override
    public String vendor() { return "tuya"; }

    @Override
    public boolean testConnection() { return false; }

    @Override
    public DeviceCommandResult bind(Long userId, String nickname, String deviceType) {
        return DeviceCommandResult.fail("涂鸦网关未配置（需企业认证 + OAuth，P2 接入）");
    }

    @Override
    public void unbind(String vendorDeviceId) {
        // no-op
    }

    @Override
    public DeviceCommandResult sendCommand(String vendorDeviceId, DeviceCommand cmd) {
        return DeviceCommandResult.fail("涂鸦网关未配置");
    }
}
