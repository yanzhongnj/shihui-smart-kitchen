package com.videostep.locator.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.videostep.locator.dto.response.ApiResponse;
import com.videostep.locator.entity.UsageRecord;
import com.videostep.locator.entity.UserPreference;
import com.videostep.locator.entity.Video;
import com.videostep.locator.entity.XunweiMemory;
import com.videostep.locator.repository.UsageRecordRepository;
import com.videostep.locator.repository.UserPreferenceRepository;
import com.videostep.locator.repository.VideoRepository;
import com.videostep.locator.repository.XunweiMemoryRepository;
import com.videostep.locator.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 平台数据/画像管理接口（仅 ADMIN 角色）。统一 /api/admin/**。
 * 覆盖：寻味画像记忆、用户口味/忌口偏好、用量计费记录 的全局查看与治理。
 * 全部为新增接口，不改动既有记忆演化/偏好/用量业务逻辑。
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('ADMIN')")
public class AdminProfileController {

    private final XunweiMemoryRepository memoryRepository;
    private final UserPreferenceRepository preferenceRepository;
    private final UsageRecordRepository usageRecordRepository;
    private final VideoRepository videoRepository;
    private final UserService userService;

    // ---------------- 寻味画像记忆 ----------------

    /** 全量寻味画像（分页 + userId/键/类型/来源/软删/值关键字筛选） */
    @GetMapping("/memories")
    public ApiResponse<Map<String, Object>> memories(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String memKey,
            @RequestParam(required = false) String memType,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) Integer deleted,
            @RequestParam(required = false) String keyword) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        String kw = (keyword == null || keyword.isBlank()) ? null : keyword.trim();
        String k = (memKey == null || memKey.isBlank()) ? null : memKey.trim();
        String t = (memType == null || memType.isBlank()) ? null : memType.trim();
        String s = (source == null || source.isBlank()) ? null : source.trim();
        // 原生 SQL：MP selectCount/selectList 会自动加 deleted=0，deleted 筛选必须走原生查询（附带修复 2026-08-18）
        long total = memoryRepository.countFiltered(userId, k, t, s, deleted, kw);
        List<Map<String, Object>> items = memoryRepository
                .findPageFiltered(userId, k, t, s, deleted, kw, safePage * safeSize, safeSize).stream()
                .map(m -> {
                    Map<String, Object> r = new HashMap<>();
                    r.put("id", m.getId());
                    r.put("userId", m.getUserId());
                    r.put("username", username(m.getUserId()));
                    r.put("memKey", m.getMemKey());
                    r.put("memValue", m.getMemValue());
                    r.put("memType", m.getMemType());
                    r.put("confidence", m.getConfidence());
                    r.put("source", m.getSource());
                    r.put("refId", m.getRefId());
                    r.put("hitCount", m.getHitCount());
                    r.put("deleted", m.getDeleted());
                    r.put("updatedAt", m.getUpdatedAt());
                    return r;
                })
                .collect(Collectors.toList());
        return ApiResponse.success(Map.of("items", items, "total", total));
    }

    /** 删除画像条目（管理员治理；软删 + 记录 deleted_at） */
    @DeleteMapping("/memories/{id}")
    public ApiResponse<Map<String, Object>> deleteMemory(@PathVariable Long id) {
        memoryRepository.softDeleteById(id);
        return ApiResponse.success(Map.of("deleted", true));
    }

    // ---------------- 用户偏好（口味/忌口） ----------------

    /** 全量用户偏好（分页 + userId + 偏好/忌口关键字筛选） */
    @GetMapping("/preferences")
    public ApiResponse<Map<String, Object>> preferences(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String keyword) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        LambdaQueryWrapper<UserPreference> qw = new LambdaQueryWrapper<UserPreference>()
                .eq(userId != null, UserPreference::getUserId, userId);
        if (keyword != null && !keyword.isBlank()) {
            qw.and(w -> w.like(UserPreference::getPreferredTastes, keyword)
                    .or().like(UserPreference::getPreferredCuisines, keyword)
                    .or().like(UserPreference::getTabooAllergens, keyword)
                    .or().like(UserPreference::getTabooIngredients, keyword)
                    .or().like(UserPreference::getNotes, keyword));
        }
        qw.orderByDesc(UserPreference::getUpdatedAt);
        long total = preferenceRepository.selectCount(qw);
        qw.last("LIMIT " + (safePage * safeSize) + ", " + safeSize);
        List<Map<String, Object>> items = preferenceRepository.selectList(qw).stream()
                .map(p -> {
                    Map<String, Object> r = new HashMap<>();
                    r.put("id", p.getId());
                    r.put("userId", p.getUserId());
                    r.put("username", username(p.getUserId()));
                    r.put("spiceLevel", p.getSpiceLevel());
                    r.put("tabooAllergens", p.getTabooAllergens());
                    r.put("tabooIngredients", p.getTabooIngredients());
                    r.put("preferredTastes", p.getPreferredTastes());
                    r.put("preferredCuisines", p.getPreferredCuisines());
                    r.put("maxCalories", p.getMaxCalories());
                    r.put("notes", p.getNotes());
                    r.put("updatedAt", p.getUpdatedAt());
                    return r;
                })
                .collect(Collectors.toList());
        return ApiResponse.success(Map.of("items", items, "total", total));
    }

    // ---------------- 用量计费记录 ----------------

    /** 全量用量记录（分页 + userId + 动作类型筛选） */
    @GetMapping("/usage")
    public ApiResponse<Map<String, Object>> usage(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String actionType) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        LambdaQueryWrapper<UsageRecord> qw = new LambdaQueryWrapper<UsageRecord>()
                .eq(userId != null, UsageRecord::getUserId, userId)
                .eq(actionType != null && !actionType.isBlank(), UsageRecord::getActionType, actionType);
        qw.orderByDesc(UsageRecord::getCreatedAt);
        long total = usageRecordRepository.selectCount(qw);
        qw.last("LIMIT " + (safePage * safeSize) + ", " + safeSize);
        List<Map<String, Object>> items = usageRecordRepository.selectList(qw).stream()
                .map(u -> {
                    Map<String, Object> r = new HashMap<>();
                    r.put("id", u.getId());
                    r.put("userId", u.getUserId());
                    r.put("username", username(u.getUserId()));
                    r.put("deviceId", u.getDeviceId());
                    r.put("actionType", u.getActionType());
                    r.put("videoId", u.getVideoId());
                    r.put("videoTitle", videoTitle(u.getVideoId()));
                    r.put("bytes", u.getBytes());
                    r.put("createdAt", u.getCreatedAt());
                    return r;
                })
                .collect(Collectors.toList());
        return ApiResponse.success(Map.of("items", items, "total", total));
    }

    /** 用量汇总：总条数 / 总流量 / 按动作类型（条数 + 流量） */
    @GetMapping("/usage/summary")
    public ApiResponse<Map<String, Object>> usageSummary() {
        List<UsageRecord> all = usageRecordRepository.selectList(null);
        Map<String, Object> m = new HashMap<>();
        m.put("totalRecords", all.size());
        long totalBytes = all.stream().mapToLong(u -> u.getBytes() == null ? 0 : u.getBytes()).sum();
        m.put("totalBytes", totalBytes);
        Map<String, Long> byType = new HashMap<>();
        Map<String, Long> bytesByType = new HashMap<>();
        for (UsageRecord u : all) {
            String t = u.getActionType() == null ? "unknown" : u.getActionType();
            byType.merge(t, 1L, Long::sum);
            bytesByType.merge(t, u.getBytes() == null ? 0 : u.getBytes(), Long::sum);
        }
        m.put("byType", byType);
        m.put("bytesByType", bytesByType);
        return ApiResponse.success(m);
    }

    // ---------------- 辅助 ----------------

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
}
