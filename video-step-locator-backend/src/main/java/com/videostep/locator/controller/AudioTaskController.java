package com.videostep.locator.controller;

import com.videostep.locator.dto.response.ApiResponse;
import com.videostep.locator.entity.AudioTask;
import com.videostep.locator.service.AudioTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/audio")
@RequiredArgsConstructor
@Slf4j
public class AudioTaskController {

    private final AudioTaskService audioTaskService;

    @PostMapping("/analyze")
    public ApiResponse<AudioTask> analyze(
            @RequestAttribute("userId") Long userId,
            @RequestParam("file") MultipartFile file) {
        try {
            return ApiResponse.success(audioTaskService.createFromUpload(userId, file));
        } catch (Exception e) {
            log.error("Audio upload failed for user {}: {}", userId, e.getMessage(), e);
            return ApiResponse.error(400, "上传失败: " + e.getMessage());
        }
    }

    @GetMapping("/tasks")
    public ApiResponse<List<AudioTask>> list(@RequestAttribute("userId") Long userId) {
        return ApiResponse.success(audioTaskService.list(userId));
    }

    @GetMapping("/tasks/{id}")
    public ApiResponse<AudioTask> get(@RequestAttribute("userId") Long userId,
                                      @PathVariable Long id) {
        return ApiResponse.success(audioTaskService.get(userId, id));
    }
}
