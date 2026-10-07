package com.videostep.locator.service.device;

import java.util.function.Consumer;

/**
 * 设备网关抽象（仿 VoiceRecognitionProvider）：多厂商统一接口 + 健康检查 + 事件上送。
 * 实现：MockGateway（模拟）/ TuyaGateway（预留）/ MijiaGateway（预留）。
 */
public interface DeviceGateway {

    /** 厂商标识：tuya / mijia / mock */
    String vendor();

    /** 健康检查：连通性探测，失败时 Registry 可降级到其它 Gateway */
    default boolean testConnection() { return true; }

    /** 绑定：创建/绑定一台设备（mock 直接生成；tuya 走 OAuth 换 token） */
    DeviceCommandResult bind(Long userId, String nickname, String deviceType);

    /** 解绑 */
    void unbind(String vendorDeviceId);

    /** 下发指令 */
    DeviceCommandResult sendCommand(String vendorDeviceId, DeviceCommand cmd);

    /** 设备在线状态 */
    default boolean isOnline(String vendorDeviceId) { return true; }

    /** 设置事件上送接收器（由 DeviceCommandService 注入，收到厂商事件后入库+WS 推送） */
    default void setEventSink(Consumer<DeviceEventMessage> sink) { /* 默认无回调 */ }
}
