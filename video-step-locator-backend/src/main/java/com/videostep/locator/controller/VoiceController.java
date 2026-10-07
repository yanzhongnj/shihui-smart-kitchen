package com.videostep.locator.controller;

import com.videostep.locator.dto.response.ApiResponse;
import com.videostep.locator.dto.response.AsrResult;
import com.videostep.locator.service.ai.VoiceIntentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/voice")
@RequiredArgsConstructor
public class VoiceController {

    private final VoiceIntentMapper voiceIntentMapper;

    @PostMapping("/command")
    public ApiResponse<AsrResult> processCommand(
            // userId 可选：本接口仅做文本命令匹配，游客 / 无 token 时也可用
            @RequestAttribute(value = "userId", required = false) Long userId,
            @RequestBody Map<String, String> body) {

        String text = body.get("text");
        if (text == null || text.isBlank()) {
            return ApiResponse.error(400, "语音文本不能为空");
        }

        AsrResult result = voiceIntentMapper.match(text);
        if (result == null) {
            return ApiResponse.error(400, "无法识别的语音命令: " + text);
        }

        return ApiResponse.success(result);
    }
}
