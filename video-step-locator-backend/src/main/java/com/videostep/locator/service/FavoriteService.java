package com.videostep.locator.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.videostep.locator.entity.RecipeMetadata;
import com.videostep.locator.entity.UserFavorite;
import com.videostep.locator.entity.Video;
import com.videostep.locator.repository.RecipeMetadataRepository;
import com.videostep.locator.repository.UserFavoriteRepository;
import com.videostep.locator.repository.VideoRepository;
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
public class FavoriteService {

    private final UserFavoriteRepository favoriteRepository;
    private final VideoRepository videoRepository;
    private final RecipeMetadataRepository recipeMetadataRepository;
    private final XunweiMemoryService xunweiMemoryService;

    public void add(Long userId, Long videoId) {
        Video video = videoRepository.selectById(videoId);
        if (video == null) throw new IllegalArgumentException("视频不存在");
        UserFavorite exists = favoriteRepository.selectOne(new LambdaQueryWrapper<UserFavorite>()
                .eq(UserFavorite::getUserId, userId)
                .eq(UserFavorite::getVideoId, videoId));
        if (exists != null) return;
        favoriteRepository.insert(UserFavorite.builder()
                .userId(userId).videoId(videoId).build());
        // 行为信号 → 寻味记忆（正向）
        xunweiMemoryService.ingestBehavior(userId, "favorite", video.getTitle(), true);
    }

    public void remove(Long userId, Long videoId) {
        Video video = videoRepository.selectById(videoId);
        favoriteRepository.delete(new LambdaQueryWrapper<UserFavorite>()
                .eq(UserFavorite::getUserId, userId)
                .eq(UserFavorite::getVideoId, videoId));
        // 行为信号 → 寻味记忆（负向：取消收藏）
        if (video != null) {
            xunweiMemoryService.ingestBehavior(userId, "favorite", video.getTitle(), false);
        }
    }

    public boolean isFavorite(Long userId, Long videoId) {
        return favoriteRepository.selectCount(new LambdaQueryWrapper<UserFavorite>()
                .eq(UserFavorite::getUserId, userId)
                .eq(UserFavorite::getVideoId, videoId)) > 0;
    }

    // ---------- 菜谱级收藏（支持无视频菜谱：图文/手动/AI 创作） ----------
    // 有视频的菜谱自动回落到视频级收藏（保持既有行为与「我的收藏」列表一致）。

    public void addByRecipe(Long userId, Long recipeId) {
        RecipeMetadata recipe = recipeMetadataRepository.selectById(recipeId);
        if (recipe == null) throw new IllegalArgumentException("菜谱不存在");
        if (recipe.getVideoId() != null) {
            add(userId, recipe.getVideoId());
            return;
        }
        UserFavorite exists = favoriteRepository.selectOne(new LambdaQueryWrapper<UserFavorite>()
                .eq(UserFavorite::getUserId, userId)
                .eq(UserFavorite::getRecipeId, recipeId));
        if (exists != null) return;
        favoriteRepository.insert(UserFavorite.builder()
                .userId(userId).recipeId(recipeId).build());
        xunweiMemoryService.ingestBehavior(userId, "favorite", recipe.getTitle(), true);
    }

    public void removeByRecipe(Long userId, Long recipeId) {
        RecipeMetadata recipe = recipeMetadataRepository.selectById(recipeId);
        if (recipe != null && recipe.getVideoId() != null) {
            remove(userId, recipe.getVideoId());
            return;
        }
        favoriteRepository.delete(new LambdaQueryWrapper<UserFavorite>()
                .eq(UserFavorite::getUserId, userId)
                .eq(UserFavorite::getRecipeId, recipeId));
        if (recipe != null) {
            xunweiMemoryService.ingestBehavior(userId, "favorite", recipe.getTitle(), false);
        }
    }

    public boolean isRecipeFavorite(Long userId, Long recipeId) {
        if (userId == null) return false;
        RecipeMetadata recipe = recipeMetadataRepository.selectById(recipeId);
        if (recipe != null && recipe.getVideoId() != null) {
            return isFavorite(userId, recipe.getVideoId());
        }
        return favoriteRepository.selectCount(new LambdaQueryWrapper<UserFavorite>()
                .eq(UserFavorite::getUserId, userId)
                .eq(UserFavorite::getRecipeId, recipeId)) > 0;
    }

    public List<Map<String, Object>> list(Long userId) {
        return favoriteRepository.findByUserId(userId).stream().map(f -> {
            Map<String, Object> m = new HashMap<>();
            m.put("favoriteId", f.getId());
            m.put("createdAt", f.getCreatedAt());
            if (f.getRecipeId() != null) {
                // 菜谱收藏（无视频菜谱走 recipe_id；有视频菜谱收藏时回落为视频收藏行）
                m.put("type", "recipe");
                m.put("recipeId", f.getRecipeId());
                RecipeMetadata r = recipeMetadataRepository.selectById(f.getRecipeId());
                if (r != null) {
                    m.put("title", r.getTitle());
                    m.put("videoId", r.getVideoId());
                    m.put("cuisine", r.getCuisine());
                    m.put("status", "菜谱");
                }
            } else {
                m.put("type", "video");
                m.put("videoId", f.getVideoId());
                Video v = f.getVideoId() == null ? null : videoRepository.selectById(f.getVideoId());
                if (v != null) {
                    m.put("title", v.getTitle());
                    m.put("thumbnail", v.getThumbnail());
                    m.put("duration", v.getDuration());
                    m.put("status", v.getStatus());
                }
            }
            return m;
        }).collect(Collectors.toList());
    }
}
