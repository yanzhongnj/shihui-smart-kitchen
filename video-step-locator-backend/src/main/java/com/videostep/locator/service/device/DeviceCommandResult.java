package com.videostep.locator.service.device;

/** 指令下发结果 */
public class DeviceCommandResult {
    public boolean success;
    public String message;
    public String result;

    public static DeviceCommandResult ok(String message, String result) {
        DeviceCommandResult r = new DeviceCommandResult();
        r.success = true;
        r.message = message;
        r.result = result;
        return r;
    }

    public static DeviceCommandResult fail(String message) {
        DeviceCommandResult r = new DeviceCommandResult();
        r.success = false;
        r.message = message;
        return r;
    }
}
