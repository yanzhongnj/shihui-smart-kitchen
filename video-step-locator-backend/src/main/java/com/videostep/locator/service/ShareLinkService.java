package com.videostep.locator.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videostep.locator.entity.RecipeMetadata;
import com.videostep.locator.entity.ShareLink;
import com.videostep.locator.entity.User;
import com.videostep.locator.entity.Video;
import com.videostep.locator.repository.RecipeMetadataRepository;
import com.videostep.locator.repository.ShareLinkRepository;
import com.videostep.locator.repository.UserRepository;
import com.videostep.locator.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 站外分享链接：视频所有者生成分享码，游客（非注册）可只读访问步骤/菜谱。
 * v0.3 起支持无视频菜谱分享（recipe_id 目标），游客解析返回菜谱内容（无视频）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ShareLinkService {

    private final ShareLinkRepository shareLinkRepository;
    private final VideoRepository videoRepository;
    private final UserRepository userRepository;
    private final RecipeMetadataRepository recipeMetadataRepository;
    private final StepService stepService;
    private final ObjectMapper objectMapper;
    private final VideoService videoService;

    private static final String CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    public ShareLink generate(Long userId, Long videoId, Integer expireDays) {
        Video video = videoRepository.selectById(videoId);
        if (video == null) throw new IllegalArgumentException("视频不存在");
        if (!video.getUserId().equals(userId)) throw new SecurityException("只有视频所有者才能生成分享链接");
        if (!Video.REVIEW_APPROVED.equals(video.getReviewStatus())) {
            throw new IllegalArgumentException("视频尚未审核通过，不能生成分享链接");
        }

        ShareLink link = ShareLink.builder()
                .videoId(videoId)
                .code(generateCode())
                .createdBy(userId)
                .expiresAt(expireDays != null && expireDays > 0
                        ? LocalDateTime.now().plusDays(expireDays) : null)
                .build();
        shareLinkRepository.insert(link);
        return link;
    }

    public void revoke(Long userId, String code) {
        ShareLink link = shareLinkRepository.findByCode(code);
        if (link == null) throw new IllegalArgumentException("分享链接不存在");
        if (!link.getCreatedBy().equals(userId)) throw new SecurityException("无权撤销该分享链接");
        shareLinkRepository.deleteById(link.getId());
        if (link.getVideoId() != null) videoService.refreshShareLock(link.getVideoId());
    }

    public List<ShareLink> list(Long userId) {
        return shareLinkRepository.findByCreatedBy(userId);
    }

    /** 菜谱级分享：有视频菜谱回落到视频分享；无视频菜谱生成 recipe_id 分享码（登录用户可分享任意可见菜谱） */
    public ShareLink generateForRecipe(Long userId, Long recipeId, Integer expireDays) {
        RecipeMetadata recipe = recipeMetadataRepository.selectById(recipeId);
        if (recipe == null) throw new IllegalArgumentException("菜谱不存在");
        if (recipe.getVideoId() != null) {
            return generate(userId, recipe.getVideoId(), expireDays);
        }
        ShareLink link = ShareLink.builder()
                .recipeId(recipeId)
                .code(generateCode())
                .createdBy(userId)
                .expiresAt(expireDays != null && expireDays > 0
                        ? LocalDateTime.now().plusDays(expireDays) : null)
                .build();
        shareLinkRepository.insert(link);
        return link;
    }

    /** 游客只读解析分享链接（步骤 + 菜谱） */
    public Map<String, Object> resolve(String code) {
        ShareLink link = shareLinkRepository.findByCode(code);
        if (link == null) throw new IllegalArgumentException("分享链接不存在");
        if (link.getExpiresAt() != null && link.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("分享链接已过期");
        }
        // 无视频菜谱分享：直接返回菜谱内容
        if (link.getRecipeId() != null) {
            RecipeMetadata recipe = recipeMetadataRepository.selectById(link.getRecipeId());
            if (recipe == null) throw new IllegalArgumentException("菜谱不存在");
            Map<String, Object> m = new HashMap<>();
            m.put("code", code);
            m.put("videoId", null);
            m.put("title", recipe.getTitle());
            m.put("status", "PUBLIC");
            m.put("visibility", "public");
            m.put("steps", parseSteps(recipe.getStepsJson()));
            m.put("recipe", recipeToMap(recipe));
            User owner = userRepository.selectById(link.getCreatedBy());
            m.put("shareBy", owner != null ? owner.getUsername() : null);
            return m;
        }
        Video video = videoRepository.selectById(link.getVideoId());
        if (video == null) throw new IllegalArgumentException("视频不存在");

        Map<String, Object> m = new HashMap<>();
        m.put("code", code);
        m.put("videoId", video.getId());
        m.put("title", video.getTitle());
        m.put("status", video.getStatus());
        m.put("visibility", video.getVisibility());
        m.put("steps", stepService.getSteps(video.getId()));
        RecipeMetadata recipe = recipeMetadataRepository.findByVideoId(video.getId());
        m.put("recipe", recipe != null ? recipeToMap(recipe) : null);
        User owner = userRepository.selectById(link.getCreatedBy());
        m.put("shareBy", owner != null ? owner.getUsername() : null);
        return m;
    }

    /** 解析 recipes.steps_json → List<Map>（无视频菜谱分享展示用） */
    private List<Map<String, Object>> parseSteps(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.warn("recipe stepsJson 解析失败: {}", e.getMessage());
            return List.of();
        }
    }

    private String generateCode() {
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            sb.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
        }
        return sb.toString();
    }

    private Map<String, Object> recipeToMap(RecipeMetadata r) {
        Map<String, Object> m = new HashMap<>();
        m.put("title", r.getTitle());
        m.put("summary", r.getSummary());
        m.put("mainIngredients", r.getMainIngredients());
        m.put("auxiliaryIngredients", r.getAuxiliaryIngredients());
        m.put("tasteTags", r.getTasteTags());
        m.put("allergens", r.getAllergens());
        m.put("spiceLevel", r.getSpiceLevel());
        m.put("cuisine", r.getCuisine());
        m.put("estimatedTime", r.getEstimatedTime());
        m.put("calories", r.getCalories());
        return m;
    }
}
