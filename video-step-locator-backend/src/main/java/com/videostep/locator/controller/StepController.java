package com.videostep.locator.controller;

import com.videostep.locator.dto.response.ApiResponse;
import com.videostep.locator.dto.response.StepResponse;
import com.videostep.locator.service.StepService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class StepController {

    private final StepService stepService;

    @GetMapping("/api/videos/{videoId}/steps")
    public ApiResponse<List<StepResponse>> getSteps(
            @PathVariable Long videoId) {
        List<StepResponse> steps = stepService.getSteps(videoId);
        return ApiResponse.success(steps);
    }

    @PutMapping("/api/steps/{stepId}")
    public ApiResponse<StepResponse> updateStep(
            @RequestAttribute("userId") Long userId,
            @PathVariable Long stepId,
            @RequestBody Map<String, String> body) {
        StepResponse step = stepService.updateStep(stepId, userId,
                body.get("summary"), body.get("originalText"));
        return ApiResponse.success(step);
    }

    @PostMapping("/api/steps/{stepId}/loop")
    public ApiResponse<Map<String, Object>> toggleLoop(
            @PathVariable Long stepId) {
        boolean isLooping = stepService.toggleLoop(stepId);
        return ApiResponse.success(Map.of(
                "stepId", stepId,
                "isLooping", isLooping
        ));
    }

    @GetMapping("/api/steps/{stepId}/loop")
    public ApiResponse<Map<String, Object>> getLoopStatus(
            @PathVariable Long stepId) {
        boolean isLooping = stepService.getLoopStatus(stepId);
        return ApiResponse.success(Map.of(
                "stepId", stepId,
                "isLooping", isLooping
        ));
    }
}
