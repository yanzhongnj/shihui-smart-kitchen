package com.videostep.locator.controller;

import com.videostep.locator.dto.response.ApiResponse;
import com.videostep.locator.service.FavoriteService;
import com.videostep.locator.service.ReviewService;
import com.videostep.locator.service.ShareLinkService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 菜谱级 收藏/评价/分享（支持无视频菜谱：图文录入/手动录入/AI 创作）。
 * 有视频的菜谱自动回落到视频级数据（保持既有行为与列表一致，见各 Service）。
 * 收藏/评价/分享需登录；评价列表游客可读。
 */
@RestController
@RequestMapping("/api/recipes")
@RequiredArgsConstructor
@Slf4j
public class RecipeActionController {

    private final FavoriteService favoriteService;
    private final ReviewService reviewService;
    private final ShareLinkService shareLinkService;

    /** 收藏菜谱（登录） */
    @PostMapping("/{id}/favorite")
    public ApiResponse<Map<String, Object>> favorite(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @PathVariable Long id) {
        if (userId == null) return ApiResponse.error(401, "请登录后收藏");
        favoriteService.addByRecipe(userId, id);
        return ApiResponse.success(Map.of("recipeId", id, "favorite", true));
    }

    /** 取消收藏菜谱（登录） */
    @DeleteMapping("/{id}/favorite")
    public ApiResponse<Map<String, Object>> unfavorite(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @PathVariable Long id) {
        if (userId == null) return ApiResponse.error(401, "请登录后取消收藏");
        favoriteService.removeByRecipe(userId, id);
        return ApiResponse.success(Map.of("recipeId", id, "favorite", false));
    }

    /** 收藏状态（登录返回真实状态，游客 false） */
    @GetMapping("/{id}/favorite")
    public ApiResponse<Map<String, Object>> favoriteStatus(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @PathVariable Long id) {
        return ApiResponse.success(Map.of("recipeId", id, "favorite",
                favoriteService.isRecipeFavorite(userId, id)));
    }

    /** 评价菜谱（登录；好评≥4/差评≤2 进入寻味偏好记忆） */
    @PostMapping("/{id}/review")
    public ApiResponse<Map<String, Object>> review(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        if (userId == null) return ApiResponse.error(401, "请登录后评价");
        try {
            Integer rating = body.get("rating") instanceof Number n ? n.intValue() : null;
            String comment = body.get("comment") instanceof String s ? s : null;
            String tasteLabel = body.get("tasteLabel") instanceof String s ? s : null;
            var review = reviewService.addByRecipe(userId, id, rating, comment, tasteLabel);
            return ApiResponse.success(Map.of("reviewId", review.getId(), "rating", review.getRating()));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    /** 菜谱评价列表（游客可读） */
    @GetMapping("/{id}/reviews")
    public ApiResponse<Map<String, Object>> reviews(@PathVariable Long id) {
        return ApiResponse.success(reviewService.listForRecipe(id));
    }

    /** 生成菜谱分享链接（登录；有视频菜谱回落到视频分享） */
    @PostMapping("/{id}/share")
    public ApiResponse<Map<String, Object>> share(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> body) {
        if (userId == null) return ApiResponse.error(401, "请登录后分享");
        Integer expireDays = body != null && body.get("expireDays") instanceof Number n ? n.intValue() : null;
        var link = shareLinkService.generateForRecipe(userId, id, expireDays);
        // 注意：Map.of 遇 null 抛 NPE，expiresAt 可能为 null → 用 HashMap
        HashMap<String, Object> data = new HashMap<>();
        data.put("code", link.getCode());
        data.put("expiresAt", link.getExpiresAt());
        data.put("shareUrl", "/s/" + link.getCode());
        return ApiResponse.success(data);
    }
}
