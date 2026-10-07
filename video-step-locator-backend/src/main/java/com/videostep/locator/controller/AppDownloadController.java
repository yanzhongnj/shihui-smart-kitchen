package com.videostep.locator.controller;

import com.videostep.locator.dto.response.ApiResponse;
import com.videostep.locator.entity.AppVersion;
import com.videostep.locator.repository.AppVersionRepository;
import com.videostep.locator.service.AppDownloadService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * APP 安装包下载（公开，无需登录）。
 *
 * <p><b>扫码下载</b>：二维码内容指向 {@code /api/app/download?platform=android}，
 * 手机浏览器扫码后直接下载最新版 APK。登录页/分享页展示下载信息时调用
 * {@code /api/app/download/info} 获取最新版本号与文件信息。</p>
 */
@RestController
@RequestMapping("/api/app")
@RequiredArgsConstructor
@Slf4j
public class AppDownloadController {

    private final AppVersionRepository appVersionRepository;
    private final AppDownloadService appDownloadService;

    /** 最新启用版本信息 + APK 文件信息（供扫码下载页/登录页展示） */
    @GetMapping("/download/info")
    public ApiResponse<Map<String, Object>> info(
            @RequestParam(defaultValue = "android") String platform) {
        Map<String, Object> m = new LinkedHashMap<>();
        AppVersion latest = appVersionRepository.findLatestByPlatform(platform);
        if (latest == null) {
            m.put("available", false);
            return ApiResponse.success(m);
        }
        m.put("available", true);
        m.put("id", latest.getId());
        m.put("platform", latest.getPlatform());
        m.put("version", latest.getVersion());
        m.put("build", latest.getBuild());
        m.put("minVersion", latest.getMinVersion());
        m.put("changelog", latest.getChangelog());
        m.put("downloadUrl", "/api/app/download?platform=" + platform + "&id=" + latest.getId());
        try {
            Path f = appDownloadService.fileFor(latest);
            if (f != null) {
                m.put("filePresent", true);
                m.put("fileName", f.getFileName().toString());
                m.put("size", Files.size(f));
                m.put("md5", appDownloadService.md5(f));
            } else {
                m.put("filePresent", false);
            }
        } catch (Exception e) {
            log.warn("app download info: 读取 APK 文件信息失败: {}", e.getMessage());
            m.put("filePresent", false);
        }
        return ApiResponse.success(m);
    }

    /** 下载 APK：默认最新启用版本；可用 id 指定具体版本 */
    @GetMapping("/download")
    public ResponseEntity<?> download(
            @RequestParam(defaultValue = "android") String platform,
            @RequestParam(required = false) Long id) {
        try {
            AppVersion v;
            if (id != null) {
                v = appVersionRepository.selectById(id);
            } else {
                v = appVersionRepository.findLatestByPlatform(platform);
            }
            if (v == null) {
                return ResponseEntity.status(404)
                        .body(ApiResponse.error(404, "未找到版本记录"));
            }
            Path f = appDownloadService.resolveDownload(v);
            if (f == null) {
                return ResponseEntity.status(404)
                        .body(ApiResponse.error(404, "APK 文件不存在，请先在管理端上传"));
            }
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + f.getFileName() + "\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(Files.size(f))
                    .body(new FileSystemResource(f));
        } catch (Exception e) {
            log.error("APK download failed", e);
            return ResponseEntity.status(500)
                    .body(ApiResponse.error(500, "下载失败: " + e.getMessage()));
        }
    }
}
