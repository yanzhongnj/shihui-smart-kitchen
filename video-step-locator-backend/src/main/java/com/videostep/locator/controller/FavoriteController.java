package com.videostep.locator.controller;

import com.videostep.locator.dto.response.ApiResponse;
import com.videostep.locator.service.FavoriteService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/favorites")
@RequiredArgsConstructor
@Slf4j
public class FavoriteController {

    private final FavoriteService favoriteService;

    @PostMapping("/{videoId}")
    public ApiResponse<Map<String, Object>> add(
            @RequestAttribute("userId") Long userId,
            @PathVariable Long videoId) {
        favoriteService.add(userId, videoId);
        return ApiResponse.success(Map.of("videoId", videoId, "favorite", true));
    }

    @DeleteMapping("/{videoId}")
    public ApiResponse<Map<String, Object>> remove(
            @RequestAttribute("userId") Long userId,
            @PathVariable Long videoId) {
        favoriteService.remove(userId, videoId);
        return ApiResponse.success(Map.of("videoId", videoId, "favorite", false));
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(
            @RequestAttribute("userId") Long userId) {
        return ApiResponse.success(favoriteService.list(userId));
    }
}
