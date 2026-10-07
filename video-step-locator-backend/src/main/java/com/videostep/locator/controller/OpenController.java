package com.videostep.locator.controller;

import com.videostep.locator.dto.response.ApiResponse;
import com.videostep.locator.dto.response.AsrResult;
import com.videostep.locator.entity.RecipeDeviceParam;
import com.videostep.locator.service.RecipeService;
import com.videostep.locator.service.ai.VoiceIntentMapper;
import com.videostep.locator.service.device.DeviceCommandService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 开放平台公开接口（方向 C，/api/open/**，由 ApiKeyAuthFilter 做 HMAC 鉴权 + 计量）。
 * 能力：菜谱搜索/详情/设备参数 + 语音文本命令（只读复用现有匹配，不触碰语音音频链路/红线）。
 */
@RestController
@RequestMapping("/api/open")
@RequiredArgsConstructor
@Slf4j
public class OpenController {

    private final RecipeService recipeService;
    private final DeviceCommandService deviceCommandService;
    private final VoiceIntentMapper voiceIntentMapper;

    /** 菜谱搜索（公开菜谱） */
    @GetMapping("/recipes/search")
    public ApiResponse<List<Map<String, Object>>> search(@RequestParam(required = false) String cuisine,
                                                         @RequestParam(required = false) String taste) {
        return ApiResponse.success(recipeService.list(cuisine, taste, null, null, null, null));
    }

    /** 菜谱详情 */
    @GetMapping("/recipes/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.success(recipeService.detail(id, null));
    }

    /** 菜谱设备参数 */
    @GetMapping("/recipes/{id}/device-params")
    public ApiResponse<List<RecipeDeviceParam>> deviceParams(@PathVariable Long id) {
        return ApiResponse.success(deviceCommandService.getRecipeDeviceParams(id));
    }

    /** 语音文本命令映射（只读复用 VoiceIntentMapper.match，非音频链路） */
    @PostMapping("/voice/command")
    public ApiResponse<AsrResult> voiceCommand(@RequestBody Map<String, String> body) {
        String text = body.get("text");
        if (text == null || text.isBlank()) {
            return ApiResponse.error(400, "语音文本不能为空");
        }
        AsrResult result = voiceIntentMapper.match(text);
        if (result == null) return ApiResponse.error(400, "无法识别的语音命令: " + text);
        return ApiResponse.success(result);
    }
}
