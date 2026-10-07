package com.videostep.locator.controller;

import com.videostep.locator.dto.response.ApiResponse;
import com.videostep.locator.service.ReviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class ReviewController {

    private final ReviewService reviewService;

    @PostMapping("/videos/{videoId}/review")
    public ApiResponse<Map<String, Object>> add(
            @RequestAttribute("userId") Long userId,
            @PathVariable Long videoId,
            @RequestBody Map<String, Object> body) {
        try {
            Integer rating = body.get("rating") instanceof Number n ? n.intValue() : null;
            String comment = body.get("comment") instanceof String s ? s : null;
            String tasteLabel = body.get("tasteLabel") instanceof String s ? s : null;
            var review = reviewService.add(userId, videoId, rating, comment, tasteLabel);
            return ApiResponse.success(Map.of("reviewId", review.getId(), "rating", review.getRating()));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    @GetMapping("/videos/{videoId}/reviews")
    public ApiResponse<Map<String, Object>> list(@PathVariable Long videoId) {
        return ApiResponse.success(reviewService.listForVideo(videoId));
    }
}
