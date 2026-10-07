package com.videostep.locator.controller;

import com.videostep.locator.dto.response.ApiResponse;
import com.videostep.locator.service.WorkService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 做菜作品（Keep 式作品记录）。登录用户专属（/api/** 默认 authenticated）。
 */
@RestController
@RequestMapping("/api/works")
@RequiredArgsConstructor
@Slf4j
public class WorkController {

    private final WorkService workService;

    /** 发布作品：多图(files) + 关联菜谱(videoId) + 菜名/标题/描述/可见性 */
    @PostMapping
    public ApiResponse<Map<String, Object>> create(
            @RequestAttribute("userId") Long userId,
            @RequestParam(value = "videoId", required = false) Long videoId,
            @RequestParam(value = "recipeTitle", required = false) String recipeTitle,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "files", required = false) MultipartFile[] files) {
        try {
            List<MultipartFile> list = files == null ? List.of() : Arrays.asList(files);
            return ApiResponse.success(workService.create(userId, videoId, recipeTitle, title,
                    description, status, list));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    @GetMapping("/mine")
    public ApiResponse<List<Map<String, Object>>> mine(@RequestAttribute("userId") Long userId) {
        return ApiResponse.success(workService.listMine(userId));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> get(
            @RequestAttribute("userId") Long userId, @PathVariable Long id) {
        try {
            return ApiResponse.success(workService.getById(userId, id));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, Object>> delete(
            @RequestAttribute("userId") Long userId, @PathVariable Long id) {
        try {
            workService.delete(userId, id);
            return ApiResponse.success(Map.of("deleted", true));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    /** 作品图片（按索引）；带鉴权（header token 或 ?token=） */
    @GetMapping("/{id}/image/{index}")
    public ResponseEntity<Resource> image(
            @RequestAttribute("userId") Long userId,
            @PathVariable Long id, @PathVariable int index) {
        Resource resource = workService.getImage(userId, id, index);
        if (resource == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(mediaTypeFor(resource.getFilename()))
                .body(resource);
    }

    private MediaType mediaTypeFor(String filename) {
        if (filename == null) return MediaType.APPLICATION_OCTET_STREAM;
        String lower = filename.toLowerCase();
        if (lower.endsWith(".png")) return MediaType.IMAGE_PNG;
        if (lower.endsWith(".gif")) return MediaType.IMAGE_GIF;
        if (lower.endsWith(".webp")) return MediaType.parseMediaType("image/webp");
        if (lower.endsWith(".bmp")) return MediaType.parseMediaType("image/bmp");
        return MediaType.IMAGE_JPEG;
    }
}
