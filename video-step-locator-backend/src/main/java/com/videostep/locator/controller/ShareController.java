package com.videostep.locator.controller;

import com.videostep.locator.dto.request.ShareRequest;
import com.videostep.locator.dto.response.ApiResponse;
import com.videostep.locator.dto.response.VideoResponse;
import com.videostep.locator.entity.VideoShare;
import com.videostep.locator.service.ShareService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ShareController {

    private final ShareService shareService;

    @PostMapping("/videos/{videoId}/share")
    public ApiResponse<Map<String, Object>> shareVideo(
            @RequestAttribute("userId") Long userId,
            @PathVariable Long videoId,
            @Valid @RequestBody ShareRequest req) {
        VideoShare share = shareService.shareVideo(
                videoId, userId, req.getSharedWithUserId(), req.getPermission());
        return ApiResponse.success(Map.of(
                "shareId", share.getId(),
                "sharedWith", share.getSharedWith(),
                "permission", share.getPermission()
        ));
    }

    @GetMapping("/videos/{videoId}/shares")
    public ApiResponse<List<Map<String, Object>>> getShares(
            @RequestAttribute("userId") Long userId,
            @PathVariable Long videoId) {
        List<VideoShare> shares = shareService.getShares(videoId, userId);
        List<Map<String, Object>> result = shares.stream()
                .map(s -> Map.of(
                        "id", s.getId(),
                        "sharedWithUserId", s.getSharedWith(),
                        "sharedWith", Map.of(
                                "id", s.getSharedWith(),
                                "username", "unknown",
                                "nickname", "unknown"
                        ),
                        "permission", s.getPermission(),
                        "createdAt", s.getCreatedAt(),
                        "expiresAt", s.getExpiresAt()
                ))
                .collect(Collectors.toList());
        return ApiResponse.success(result);
    }

    @DeleteMapping("/videos/{videoId}/share/{userId}")
    public ApiResponse<Void> removeShare(
            @RequestAttribute("userId") Long ownerId,
            @PathVariable Long videoId,
            @PathVariable Long userId) {
        shareService.removeShare(videoId, ownerId, userId);
        return ApiResponse.success(null);
    }

    @GetMapping("/videos/shared-with-me")
    public ApiResponse<List<Map<String, Object>>> getSharedWithMe(
            @RequestAttribute("userId") Long userId) {
        List<com.videostep.locator.entity.VideoShare> shares = shareService.getSharedWithMe(userId);
        List<Map<String, Object>> result = shares.stream()
                .map(s -> {
                    java.util.Map<String, Object> map = new java.util.HashMap<>();
                    map.put("id", s.getVideoId());
                    map.put("shareId", s.getId());
                    map.put("permission", s.getPermission());
                    return map;
                })
                .collect(Collectors.toList());
        return ApiResponse.success(result);
    }
}
