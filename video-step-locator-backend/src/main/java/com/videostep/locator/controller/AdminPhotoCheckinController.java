package com.videostep.locator.controller;

import com.videostep.locator.dto.response.ApiResponse;
import com.videostep.locator.entity.MealPhoto;
import com.videostep.locator.repository.MealPhotoRepository;
import com.videostep.locator.service.FileStorageService;
import com.videostep.locator.service.health.photo.AdminPhotoCheckinService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 拍照打卡 Admin 管理（Web 管理端）。设计文档 v0.2 §7.2 / §8。
 * 仅 ROLE_ADMIN。软删进回收站（photos 类型）；恢复走回收站 /api/admin/recycle/{type}/{id}/restore。
 */
@RestController
@RequestMapping("/api/admin/photo-checkins")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminPhotoCheckinController {

    private final AdminPhotoCheckinService adminService;
    private final MealPhotoRepository mealPhotoRepository;
    private final FileStorageService fileStorageService;

    /** 分页列表（keyword/日期范围/餐次/状态筛选） */
    @GetMapping
    public ApiResponse<Map<String, Object>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo,
            @RequestParam(required = false) String mealType,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            return ApiResponse.success(adminService.list(keyword, dateFrom, dateTo, mealType, status, page, size));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    /** 详情 */
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        try {
            return ApiResponse.success(adminService.detail(id));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(404, e.getMessage());
        }
    }

    /** 查看照片（Admin 可看任意用户） */
    @GetMapping("/{id}/image")
    public ResponseEntity<Resource> image(@PathVariable Long id) {
        MealPhoto mp = mealPhotoRepository.findActiveById(id);
        if (mp == null) return ResponseEntity.notFound().build();
        Resource res = fileStorageService.loadPhotoImage(mp.getPhotoPath());
        if (res == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .body(res);
    }

    /** 统计 */
    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> stats() {
        return ApiResponse.success(adminService.stats());
    }

    /** 软删（进回收站 photos 类型，级联软删关联 diet） */
    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable Long id) {
        try {
            adminService.delete(id);
            return ApiResponse.success(Map.of("deleted", true));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(404, e.getMessage());
        }
    }
}
