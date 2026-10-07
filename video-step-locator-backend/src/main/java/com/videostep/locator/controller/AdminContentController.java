package com.videostep.locator.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.videostep.locator.dto.response.ApiResponse;
import com.videostep.locator.entity.RecipeMetadata;
import com.videostep.locator.entity.ShareLink;
import com.videostep.locator.entity.Video;
import com.videostep.locator.entity.VideoReview;
import com.videostep.locator.repository.RecipeMetadataRepository;
import com.videostep.locator.repository.ShareLinkRepository;
import com.videostep.locator.repository.VideoRepository;
import com.videostep.locator.repository.VideoReviewRepository;
import com.videostep.locator.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 平台内容管理接口（仅 ADMIN 角色）。统一 /api/admin/**。
 * 覆盖：站外分享链接、评价、菜谱 的全局查看与治理（新增接口，不改动既有业务逻辑）。
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('ADMIN')")
public class AdminContentController {

    private final ShareLinkRepository shareLinkRepository;
    private final VideoReviewRepository videoReviewRepository;
    private final RecipeMetadataRepository recipeMetadataRepository;
    private final VideoRepository videoRepository;
    private final UserService userService;

    // ---------------- 分享链接管理 ----------------

    /** 全量分享链接（分页 + code/videoId 关键字筛选） */
    @GetMapping("/share-links")
    public ApiResponse<Map<String, Object>> shareLinks(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        LambdaQueryWrapper<ShareLink> qw = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            Long kid = tryLong(keyword);
            if (kid != null) {
                qw.and(w -> w.like(ShareLink::getCode, keyword).or().eq(ShareLink::getVideoId, kid));
            } else {
                qw.like(ShareLink::getCode, keyword);
            }
        }
        qw.orderByDesc(ShareLink::getCreatedAt);
        long total = shareLinkRepository.selectCount(qw);
        qw.last("LIMIT " + (safePage * safeSize) + ", " + safeSize);
        List<Map<String, Object>> items = shareLinkRepository.selectList(qw).stream()
                .map(this::shareLinkToMap)
                .collect(Collectors.toList());
        return ApiResponse.success(Map.of("items", items, "total", total));
    }

    /** 撤销分享链接（管理员） */
    @DeleteMapping("/share-links/{id}")
    public ApiResponse<Map<String, Object>> deleteShareLink(@PathVariable Long id) {
        shareLinkRepository.deleteById(id);
        return ApiResponse.success(Map.of("deleted", true));
    }

    // ---------------- 评价管理 ----------------

    /** 全量评价（分页 + 评分筛选 + 评论关键字） */
    @GetMapping("/reviews")
    public ApiResponse<Map<String, Object>> reviews(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Integer rating,
            @RequestParam(required = false) String keyword) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        LambdaQueryWrapper<VideoReview> qw = new LambdaQueryWrapper<VideoReview>()
                .eq(rating != null, VideoReview::getRating, rating);
        if (keyword != null && !keyword.isBlank()) {
            qw.like(VideoReview::getComment, keyword);
        }
        qw.orderByDesc(VideoReview::getCreatedAt);
        long total = videoReviewRepository.selectCount(qw);
        qw.last("LIMIT " + (safePage * safeSize) + ", " + safeSize);
        List<Map<String, Object>> items = videoReviewRepository.selectList(qw).stream()
                .map(this::reviewToMap)
                .collect(Collectors.toList());
        return ApiResponse.success(Map.of("items", items, "total", total));
    }

    /** 删除评价（管理员治理） */
    @DeleteMapping("/reviews/{id}")
    public ApiResponse<Map<String, Object>> deleteReview(@PathVariable Long id) {
        videoReviewRepository.deleteById(id);
        return ApiResponse.success(Map.of("deleted", true));
    }

    // ---------------- 菜谱管理 ----------------

    /** 全量菜谱元数据（分页 + 标题/原料关键字 + 菜系/口味/来源/类型筛选） */
    @GetMapping("/recipes")
    public ApiResponse<Map<String, Object>> recipes(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String cuisine,
            @RequestParam(required = false) String taste,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String type) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        LambdaQueryWrapper<RecipeMetadata> qw = new LambdaQueryWrapper<RecipeMetadata>()
                .eq(cuisine != null && !cuisine.isBlank(), RecipeMetadata::getCuisine, cuisine);
        if (source != null && !source.isBlank()) {
            qw.eq(RecipeMetadata::getSource, source.trim().toUpperCase());
        }
        if (type != null && !type.isBlank()) {
            qw.eq(RecipeMetadata::getRecipeType, type.trim().toUpperCase());
        }
        if (keyword != null && !keyword.isBlank()) {
            qw.and(w -> w.like(RecipeMetadata::getTitle, keyword)
                    .or().like(RecipeMetadata::getMainIngredients, keyword));
        }
        if (taste != null && !taste.isBlank()) {
            qw.like(RecipeMetadata::getTasteTags, taste);
        }
        qw.orderByDesc(RecipeMetadata::getCreatedAt);
        long total = recipeMetadataRepository.selectCount(qw);
        qw.last("LIMIT " + (safePage * safeSize) + ", " + safeSize);
        List<Map<String, Object>> items = recipeMetadataRepository.selectList(qw).stream()
                .map(this::recipeToMap)
                .collect(Collectors.toList());
        return ApiResponse.success(Map.of("items", items, "total", total));
    }

    // ---------------- 转换辅助 ----------------

    private Map<String, Object> shareLinkToMap(ShareLink l) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", l.getId());
        m.put("code", l.getCode());
        m.put("videoId", l.getVideoId());
        m.put("videoTitle", videoTitle(l.getVideoId()));
        m.put("createdBy", l.getCreatedBy());
        m.put("creator", username(l.getCreatedBy()));
        m.put("expiresAt", l.getExpiresAt());
        m.put("expired", l.getExpiresAt() != null && l.getExpiresAt().isBefore(LocalDateTime.now()));
        m.put("createdAt", l.getCreatedAt());
        return m;
    }

    private Map<String, Object> reviewToMap(VideoReview r) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", r.getId());
        m.put("userId", r.getUserId());
        m.put("username", username(r.getUserId()));
        m.put("videoId", r.getVideoId());
        m.put("videoTitle", videoTitle(r.getVideoId()));
        m.put("rating", r.getRating());
        m.put("comment", r.getComment());
        m.put("tasteLabel", r.getTasteLabel());
        m.put("createdAt", r.getCreatedAt());
        return m;
    }

    private Map<String, Object> recipeToMap(RecipeMetadata rc) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", rc.getId());
        m.put("videoId", rc.getVideoId());
        m.put("videoTitle", videoTitle(rc.getVideoId()));
        m.put("title", rc.getTitle());
        m.put("summary", rc.getSummary());
        m.put("mainIngredients", rc.getMainIngredients());
        m.put("tasteTags", rc.getTasteTags());
        m.put("allergens", rc.getAllergens());
        m.put("spiceLevel", rc.getSpiceLevel());
        m.put("cuisine", rc.getCuisine());
        m.put("estimatedTime", rc.getEstimatedTime());
        m.put("calories", rc.getCalories());
        // 统一模型 v0.2：来源 / 派生类型 / 精确化评分
        m.put("source", rc.getSource());
        m.put("recipeType", rc.getRecipeType());
        m.put("precisionScore", rc.getPrecisionScore());
        m.put("createdAt", rc.getCreatedAt());
        return m;
    }

    private String videoTitle(Long videoId) {
        if (videoId == null) return null;
        Video v = videoRepository.selectById(videoId);
        return v != null ? v.getTitle() : null;
    }

    private String username(Long userId) {
        if (userId == null) return null;
        try { return userService.getById(userId).getUsername(); }
        catch (Exception e) { return null; }
    }

    private Long tryLong(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return null; }
    }
}
