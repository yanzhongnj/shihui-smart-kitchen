package com.videostep.locator.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.videostep.locator.entity.RecipeMetadata;
import com.videostep.locator.entity.User;
import com.videostep.locator.entity.Video;
import com.videostep.locator.entity.VideoReview;
import com.videostep.locator.repository.RecipeMetadataRepository;
import com.videostep.locator.repository.UserRepository;
import com.videostep.locator.repository.VideoRepository;
import com.videostep.locator.repository.VideoReviewRepository;
import com.videostep.locator.service.xunwei.XunweiMemoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReviewService {

    private final VideoReviewRepository reviewRepository;
    private final VideoRepository videoRepository;
    private final RecipeMetadataRepository recipeMetadataRepository;
    private final UserRepository userRepository;
    private final XunweiMemoryService xunweiMemoryService;

    public VideoReview add(Long userId, Long videoId, Integer rating, String comment, String tasteLabel) {
        Video video = videoRepository.selectById(videoId);
        if (video == null) throw new IllegalArgumentException("视频不存在");
        if (rating == null || rating < 1 || rating > 5) {
            throw new IllegalArgumentException("评分需在 1-5 之间");
        }
        VideoReview review;
        VideoReview existing = reviewRepository.selectOne(new LambdaQueryWrapper<VideoReview>()
                .eq(VideoReview::getUserId, userId)
                .eq(VideoReview::getVideoId, videoId));
        if (existing != null) {
            existing.setRating(rating);
            existing.setComment(comment);
            existing.setTasteLabel(tasteLabel);
            reviewRepository.updateById(existing);
            review = existing;
        } else {
            review = VideoReview.builder()
                    .userId(userId).videoId(videoId)
                    .rating(rating).comment(comment).tasteLabel(tasteLabel)
                    .build();
            reviewRepository.insert(review);
        }
        // 行为信号 → 寻味记忆：好评≥4 正向，差评≤2 负向
        if (rating >= 4) {
            xunweiMemoryService.ingestBehavior(userId, "review", video.getTitle(), true);
        } else if (rating <= 2) {
            xunweiMemoryService.ingestBehavior(userId, "review", video.getTitle(), false);
        }
        return review;
    }

    public Map<String, Object> listForVideo(Long videoId) {
        List<VideoReview> list = reviewRepository.findByVideoId(videoId);
        Double avg = reviewRepository.avgRatingByVideoId(videoId);
        long count = reviewRepository.countByVideoId(videoId);

        List<Map<String, Object>> items = list.stream().map(this::reviewToItem).collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();
        result.put("avgRating", avg == null ? 0 : Math.round(avg * 10) / 10.0);
        result.put("count", count);
        result.put("reviews", items);
        return result;
    }

    // ---------- 菜谱级评价（支持无视频菜谱：图文/手动/AI 创作） ----------
    // 有视频的菜谱自动回落到视频级评价（保持既有行为与视频页评价一致）。

    public VideoReview addByRecipe(Long userId, Long recipeId, Integer rating, String comment, String tasteLabel) {
        RecipeMetadata recipe = recipeMetadataRepository.selectById(recipeId);
        if (recipe == null) throw new IllegalArgumentException("菜谱不存在");
        if (recipe.getVideoId() != null) {
            return add(userId, recipe.getVideoId(), rating, comment, tasteLabel);
        }
        if (rating == null || rating < 1 || rating > 5) {
            throw new IllegalArgumentException("评分需在 1-5 之间");
        }
        VideoReview review;
        VideoReview existing = reviewRepository.selectOne(new LambdaQueryWrapper<VideoReview>()
                .eq(VideoReview::getUserId, userId)
                .eq(VideoReview::getRecipeId, recipeId));
        if (existing != null) {
            existing.setRating(rating);
            existing.setComment(comment);
            existing.setTasteLabel(tasteLabel);
            reviewRepository.updateById(existing);
            review = existing;
        } else {
            review = VideoReview.builder()
                    .userId(userId).recipeId(recipeId)
                    .rating(rating).comment(comment).tasteLabel(tasteLabel)
                    .build();
            reviewRepository.insert(review);
        }
        // 行为信号 → 寻味记忆：好评≥4 正向，差评≤2 负向
        if (rating >= 4) {
            xunweiMemoryService.ingestBehavior(userId, "review", recipe.getTitle(), true);
        } else if (rating <= 2) {
            xunweiMemoryService.ingestBehavior(userId, "review", recipe.getTitle(), false);
        }
        return review;
    }

    public Map<String, Object> listForRecipe(Long recipeId) {
        RecipeMetadata recipe = recipeMetadataRepository.selectById(recipeId);
        if (recipe == null) throw new IllegalArgumentException("菜谱不存在");
        if (recipe.getVideoId() != null) {
            return listForVideo(recipe.getVideoId());
        }
        List<VideoReview> list = reviewRepository.findByRecipeId(recipeId);
        Double avg = reviewRepository.avgRatingByRecipeId(recipeId);
        long count = reviewRepository.countByRecipeId(recipeId);

        List<Map<String, Object>> items = list.stream().map(this::reviewToItem).collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();
        result.put("avgRating", avg == null ? 0 : Math.round(avg * 10) / 10.0);
        result.put("count", count);
        result.put("reviews", items);
        return result;
    }

    private Map<String, Object> reviewToItem(VideoReview r) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", r.getId());
        m.put("userId", r.getUserId());
        m.put("rating", r.getRating());
        m.put("comment", r.getComment());
        m.put("tasteLabel", r.getTasteLabel());
        m.put("createdAt", r.getCreatedAt());
        User u = userRepository.selectById(r.getUserId());
        m.put("username", u != null ? u.getUsername() : null);
        return m;
    }
}
