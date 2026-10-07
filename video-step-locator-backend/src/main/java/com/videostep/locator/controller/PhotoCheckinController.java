package com.videostep.locator.controller;

import com.videostep.locator.dto.response.ApiResponse;
import com.videostep.locator.entity.MealPhoto;
import com.videostep.locator.repository.MealPhotoRepository;
import com.videostep.locator.service.FileStorageService;
import com.videostep.locator.service.health.photo.PhotoCheckinService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 拍照打卡（用户端）。设计文档 v0.2 §7.1。
 * 默认 /api/** JWT 认证，仅本人数据。图片读取支持 ?token=（复刻 works 图片鉴权）。
 */
@RestController
@RequestMapping("/api/health/checkin/photo")
@RequiredArgsConstructor
public class PhotoCheckinController {

    private final PhotoCheckinService photoCheckinService;
    private final MealPhotoRepository mealPhotoRepository;
    private final FileStorageService fileStorageService;

    /** 拍照打卡：多菜识别+份量+营养 → 返回预览 */
    @PostMapping
    public ApiResponse<Map<String, Object>> checkin(
            @RequestAttribute("userId") Long userId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String mealType,
            @RequestParam(required = false) String recordDate) {
        try {
            return ApiResponse.success(photoCheckinService.checkin(userId, file, mealType, recordDate));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    /** 重取识别预览（断线重试/刷新） */
    @GetMapping("/{id}/preview")
    public ApiResponse<Map<String, Object>> preview(
            @RequestAttribute("userId") Long userId,
            @PathVariable Long id) {
        try {
            return ApiResponse.success(photoCheckinService.preview(userId, id));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(404, e.getMessage());
        }
    }

    /** 确认/修正 → 写 diet_records + EXP+5；支持 voiceText 语音说菜名 */
    @PostMapping("/{id}/confirm")
    public ApiResponse<Map<String, Object>> confirm(
            @RequestAttribute("userId") Long userId,
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        try {
            String voiceText = body.get("voiceText") instanceof String s ? s : null;
            return ApiResponse.success(photoCheckinService.confirm(userId, id, castItems(body.get("items")), voiceText));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    /** 识别不准确 → 手动改选（换候选/从食物库/菜品库选/语音说菜名） */
    @PostMapping("/{id}/revise")
    public ApiResponse<Map<String, Object>> revise(
            @RequestAttribute("userId") Long userId,
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        try {
            String voiceText = body.get("voiceText") instanceof String s ? s : null;
            return ApiResponse.success(photoCheckinService.revise(userId, id, castItems(body.get("items")), voiceText));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    /** 照片读取（仅本人；支持 ?token= 供 Image.network 加载） */
    @GetMapping("/{id}/image")
    public ResponseEntity<Resource> image(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @PathVariable Long id) {
        MealPhoto mp = mealPhotoRepository.findActiveById(id);
        if (mp == null || userId == null || !mp.getUserId().equals(userId)) {
            return ResponseEntity.status(403).build();
        }
        Resource res = fileStorageService.loadPhotoImage(mp.getPhotoPath());
        if (res == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .body(res);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castItems(Object o) {
        // null/缺省 items 返回空列表，交由服务层 voiceText 语音补充兜底
        if (o == null) return List.of();
        if (o instanceof List<?> list) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object x : list) {
                if (x instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
            }
            return out;
        }
        throw new IllegalArgumentException("items 不能为空");
    }
}
