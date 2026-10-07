package com.videostep.locator.service.device;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 米家开放平台网关（预留，P3）：需小米开发者账号 + 设备授权。 */
@Component
@Slf4j
public class MijiaGateway implements DeviceGateway {

    @Override
    public String vendor() { return "mijia"; }

    @Override
    public boolean testConnection() { return false; }

    @Override
    public DeviceCommandResult bind(Long userId, String nickname, String deviceType) {
        return DeviceCommandResult.fail("米家网关未配置（P3 接入）");
    }

    @Override
    public void unbind(String vendorDeviceId) { }

    @Override
    public DeviceCommandResult sendCommand(String vendorDeviceId, DeviceCommand cmd) {
        return DeviceCommandResult.fail("米家网关未配置");
    }
}
