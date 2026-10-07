package com.videostep.locator.controller;

import com.videostep.locator.dto.response.ApiResponse;
import com.videostep.locator.entity.KitchenDevice;
import com.videostep.locator.entity.RecipeDeviceParam;
import com.videostep.locator.service.device.DeviceCommandService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 智能厨电中控接口（A3）：设备绑定/下发指令/事件日志/菜谱设备参数。
 * 鉴权：/api/** 默认 authenticated；设备操作校验属主或企业成员。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class DeviceController {

    private final DeviceCommandService deviceCommandService;

    /** 我的设备列表 */
    @GetMapping("/devices")
    public ApiResponse<Map<String, Object>> devices(@RequestAttribute("userId") Long userId,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(deviceCommandService.listDevices(userId, page, size));
    }

    /** 绑定设备（mock 立即创建；tuya/mijia 未配置返回友好提示） */
    @PostMapping("/devices/bind")
    public ApiResponse<KitchenDevice> bind(@RequestAttribute("userId") Long userId,
                                           @RequestBody Map<String, String> body) {
        return ApiResponse.success(deviceCommandService.bind(userId,
                body.get("vendor"), body.get("nickname"), body.get("deviceType")));
    }

    /** 解绑设备 */
    @PostMapping("/devices/{id}/unbind")
    public ApiResponse<Map<String, Object>> unbind(@RequestAttribute("userId") Long userId,
                                                   @PathVariable Long id) {
        deviceCommandService.unbind(userId, id);
        return ApiResponse.success(Map.of("message", "设备已解绑"));
    }

    /** 下发指令：{paramId} 或 {command:{action,mode,temperature,durationMin}} */
    @PostMapping("/devices/{id}/command")
    public ApiResponse<Map<String, Object>> command(@RequestAttribute("userId") Long userId,
                                                    @PathVariable Long id,
                                                    @RequestBody Map<String, Object> body) {
        Long paramId = body.get("paramId") == null ? null : Long.valueOf(String.valueOf(body.get("paramId")));
        @SuppressWarnings("unchecked")
        Map<String, Object> commandMap = (Map<String, Object>) body.get("command");
        return ApiResponse.success(deviceCommandService.sendCommand(userId, id, paramId, commandMap));
    }

    /** 指令日志（分页） */
    @GetMapping("/device-commands")
    public ApiResponse<Map<String, Object>> commandLogs(@RequestAttribute("userId") Long userId,
                                                        @RequestParam(defaultValue = "0") int page,
                                                        @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(deviceCommandService.listCommandLogs(userId, page, size));
    }

    /** 失败指令重试 */
    @PostMapping("/device-commands/{id}/retry")
    public ApiResponse<Map<String, Object>> retry(@RequestAttribute("userId") Long userId,
                                                  @PathVariable Long id) {
        return ApiResponse.success(deviceCommandService.retry(userId, id));
    }

    /** 菜谱设备参数（公开只读，供播放页/APP 展示） */
    @GetMapping("/recipes/{id}/device-params")
    public ApiResponse<List<RecipeDeviceParam>> deviceParams(@PathVariable Long id) {
        return ApiResponse.success(deviceCommandService.getRecipeDeviceParams(id));
    }
}
