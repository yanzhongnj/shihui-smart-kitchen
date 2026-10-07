package com.videostep.locator.controller;

import com.videostep.locator.dto.response.ApiResponse;
import com.videostep.locator.dto.response.DownloadTaskResponse;
import com.videostep.locator.entity.DownloadTask;
import com.videostep.locator.exception.QuotaExceededException;
import com.videostep.locator.service.DownloadTaskService;
import com.videostep.locator.service.UsageService;
import com.videostep.locator.service.YtDlpService;
import com.videostep.locator.util.SsrfUtil;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/download")
@RequiredArgsConstructor
@Slf4j
public class DownloadController {

    private final DownloadTaskService downloadTaskService;
    private final UsageService usageService;
    private final YtDlpService ytDlpService;

    /** 网页URL -> 直连媒体(地址+防盗链头) 缓存（避免每个 Range 请求都重复解析） */
    private final Map<String, CachedMedia> directUrlCache = new ConcurrentHashMap<>();
    private static final long DIRECT_URL_TTL_MS = 30L * 60 * 1000;

    private record CachedMedia(YtDlpService.ResolvedMedia media, long time) {}

    /** 将可播放地址解析为可直接流式转发的媒体（网页URL用 yt-dlp -J 解析直连地址与防盗链头） */
    private YtDlpService.ResolvedMedia resolvePlayable(String url) {
        if (YtDlpService.isDirectMediaUrl(url)) {
            return new YtDlpService.ResolvedMedia(url, Map.of());
        }
        CachedMedia cached = directUrlCache.get(url);
        if (cached != null && System.currentTimeMillis() - cached.time() < DIRECT_URL_TTL_MS) {
            return cached.media();
        }
        YtDlpService.ResolvedMedia media = ytDlpService.resolveMedia(url);
        if (media == null || media.url() == null || media.url().isBlank()) {
            log.warn("preview: 无法解析直连地址，按原URL尝试: {}", url);
            return new YtDlpService.ResolvedMedia(url, Map.of());
        }
        directUrlCache.put(url, new CachedMedia(media, System.currentTimeMillis()));
        log.info("preview: resolved {} -> {}", url, media.url());
        return media;
    }

    /** 解析链接：返回合集/单视频条目列表（注册用户与游客均可，游客受配额） */
    @PostMapping("/parse")
    public ApiResponse<YtDlpService.ParseResult> parse(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
            @RequestBody Map<String, String> body) {
        try {
            usageService.assertGuestQuota(userId, deviceId, UsageService.TYPE_PARSE, 0);
            YtDlpService.ParseResult result = downloadTaskService.parse(body.get("url"));
            if (userId != null) {
                usageService.record(userId, null, UsageService.TYPE_PARSE, null, 0);
            } else {
                usageService.record(null, deviceId, UsageService.TYPE_PARSE, null, 0);
            }
            return ApiResponse.success(result);
        } catch (QuotaExceededException e) {
            return ApiResponse.error(429, e.getMessage());
        } catch (Exception e) {
            log.warn("Parse failed: {}", e.getMessage());
            return ApiResponse.error(400, "解析失败: " + e.getMessage());
        }
    }

    /** 方案乙：将链接解析为直连媒体地址(mp4/m3u8/audio) + 防盗链头，供手机端本地下载（进度纯本地、与后端无关） */
    @PostMapping("/resolve")
    public ApiResponse<Map<String, Object>> resolve(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
            @RequestBody Map<String, String> body) {
        try {
            usageService.assertGuestQuota(userId, deviceId, UsageService.TYPE_PARSE, 0);
            String url = body.get("url");
            if (url == null || url.isBlank()) {
                return ApiResponse.error(400, "缺少 url");
            }
            YtDlpService.ResolvedMedia media = resolvePlayable(url.trim());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("url", media.url());
            out.put("mediaType", detectMediaType(media.url()));
            out.put("headers", media.headers() == null ? Map.of() : media.headers());
            if (userId != null) {
                usageService.record(userId, null, UsageService.TYPE_PARSE, null, 0);
            } else {
                usageService.record(null, deviceId, UsageService.TYPE_PARSE, null, 0);
            }
            return ApiResponse.success(out);
        } catch (QuotaExceededException e) {
            return ApiResponse.error(429, e.getMessage());
        } catch (Exception e) {
            log.warn("Resolve failed: {}", e.getMessage());
            return ApiResponse.error(400, "解析失败: " + e.getMessage());
        }
    }

    /** 按 URL 特征判定媒体类型（hls / audio / mp4） */
    private String detectMediaType(String url) {
        if (url == null) return "mp4";
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.contains(".m3u8") || lower.contains("mpegurl")
                || lower.contains("mime=video/mp2t")) {
            return "hls";
        }
        if (lower.matches(".*\\.(mp3|m4a|aac)(\\?.*)?$")) {
            return "audio";
        }
        return "mp4";
    }

    /** 在线预览：后台代理流播放解析出的视频地址（规避防盗链；游客受配额 + SSRF 防护） */
    @GetMapping("/preview")
    public void preview(
            @RequestParam String url,
            @RequestParam(value = "referer", required = false) String referer,
            @RequestParam(value = "deviceId", required = false) String deviceId,
            @RequestHeader(value = "Range", required = false) String range,
            @RequestHeader(value = "X-Device-Id", required = false) String headerDeviceId,
            @RequestAttribute(value = "userId", required = false) Long userId,
            HttpServletResponse response) throws Exception {
        if (deviceId == null || deviceId.isBlank()) deviceId = headerDeviceId;
        try {
            log.info("preview: in userId={} deviceId={} url={}", userId, deviceId, url);
            usageService.assertGuestQuota(userId, deviceId, UsageService.TYPE_PREVIEW, 0);
            SsrfUtil.assertPublicUrl(url);
            if (userId != null) {
                usageService.record(userId, null, UsageService.TYPE_PREVIEW, null, 0);
            } else {
                usageService.record(null, deviceId, UsageService.TYPE_PREVIEW, null, 0);
            }

            // 网页URL(抖音/B站分享页等)先解析为直连媒体地址，再流式转发（带上防盗链头）
            YtDlpService.ResolvedMedia media = resolvePlayable(url);

            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(media.url()))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36");
            // 应用 yt-dlp 解析出的防盗链头（B站 Referer 等，可能覆盖 UA）
            Map<String, String> headers = media.headers() == null ? Map.of() : media.headers();
            headers.forEach((k, v) -> {
                if (!"Range".equalsIgnoreCase(k)) rb.header(k, v);
            });
            if (referer != null && !referer.isBlank() && !headers.containsKey("Referer")) {
                rb.header("Referer", referer);
            }
            if (range != null) rb.header("Range", range);

            HttpResponse<InputStream> upstream = client.send(rb.build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            log.info("preview: upstream status={} type={} for {}", upstream.statusCode(),
                    upstream.headers().firstValue("Content-Type").orElse("?"), media.url());
            response.setStatus(upstream.statusCode());
            upstream.headers().firstValue("Content-Type")
                    .ifPresent(v -> response.setContentType(v));
            upstream.headers().firstValue("Content-Length")
                    .ifPresent(v -> response.setHeader("Content-Length", v));
            upstream.headers().firstValue("Content-Range")
                    .ifPresent(v -> response.setHeader("Content-Range", v));
            response.setHeader("Accept-Ranges", "bytes");
            try (InputStream is = upstream.body(); OutputStream os = response.getOutputStream()) {
                is.transferTo(os);
            }
        } catch (QuotaExceededException e) {
            response.sendError(429, e.getMessage());
        }
    }

    /** 批量创建下载任务（注册用户与游客均可，游客受配额） */
    @PostMapping("/start")
    public ApiResponse<List<DownloadTaskResponse>> start(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
            @RequestHeader(value = "X-Client", required = false) String client,
            @RequestBody Map<String, Object> body) {
        try {
            usageService.assertGuestQuota(userId, deviceId, UsageService.TYPE_DOWNLOAD, 0);
            String pageUrl = (String) body.get("pageUrl");
            String formatKey = asStr(body.get("format"));
            Object raw = body.get("entries");
            if (!(raw instanceof List<?> rawEntries) || rawEntries.isEmpty()) {
                return ApiResponse.error(400, "未选择任何视频");
            }
            List<YtDlpService.ParsedEntry> entries = new ArrayList<>();
            for (Object o : rawEntries) {
                if (!(o instanceof Map<?, ?> m)) continue;
                YtDlpService.ParsedEntry e = new YtDlpService.ParsedEntry();
                e.setUrl(asStr(m.get("url")));
                e.setTitle(asStr(m.get("title")));
                e.setUploader(asStr(m.get("uploader")));
                Object dur = m.get("duration");
                e.setDuration(dur instanceof Number ? ((Number) dur).doubleValue() : null);
                e.setThumbnail(asStr(m.get("thumbnail")));
                entries.add(e);
            }
            // 下载用量在「取回文件」时按字节记录（避免与 start 重复计数）
            // X-Client: app → 严格「下载不建云端、显式上传才建」；Web/缺省仍自动建云端
            List<DownloadTask> tasks = downloadTaskService.start(userId, deviceId, pageUrl, entries, formatKey,
                    !"app".equalsIgnoreCase(client));
            return ApiResponse.success(tasks.stream().map(this::toResponse).collect(Collectors.toList()));
        } catch (Exception e) {
            log.error("Start download failed: {}", e.getMessage(), e);
            return ApiResponse.error(400, "创建下载任务失败: " + e.getMessage());
        }
    }

    @GetMapping("/tasks")
    public ApiResponse<Map<String, Object>> list(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var pg = downloadTaskService.list(userId, deviceId, page, size);
        List<DownloadTaskResponse> items = pg.getRecords().stream()
                .map(this::toResponse).collect(Collectors.toList());
        return ApiResponse.success(Map.of(
                "items", items,
                "total", pg.getTotal(),
                "page", pg.getCurrent(),
                "size", pg.getSize()
        ));
    }

    @GetMapping("/tasks/{id}")
    public ApiResponse<DownloadTaskResponse> get(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
            @PathVariable Long id) {
        return ApiResponse.success(toResponse(downloadTaskService.get(userId, deviceId, id)));
    }

    @PostMapping("/tasks/{id}/retry")
    public ApiResponse<Map<String, Object>> retry(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
            @PathVariable Long id) {
        downloadTaskService.retry(userId, deviceId, id);
        return ApiResponse.success(Map.of("message", "任务已重新加入队列"));
    }

    @PostMapping("/tasks/{id}/cancel")
    public ApiResponse<Map<String, Object>> cancel(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
            @PathVariable Long id) {
        downloadTaskService.cancel(userId, deviceId, id);
        return ApiResponse.success(Map.of("message", "任务已取消"));
    }

    /** 取回下载的文件（注册用户与游客按归属校验；游客取回计流量配额） */
    @GetMapping("/tasks/{id}/file")
    public ResponseEntity<Resource> downloadFile(
            @PathVariable Long id,
            @RequestParam(value = "deviceId", required = false) String deviceId,
            @RequestHeader(value = "X-Device-Id", required = false) String headerDeviceId,
            @RequestAttribute(value = "userId", required = false) Long userId) throws IOException {
        if (deviceId == null || deviceId.isBlank()) deviceId = headerDeviceId;
        DownloadTask task = downloadTaskService.get(userId, deviceId, id);
        String filePath = task.getFilePath();
        if (filePath == null || filePath.isBlank()) return ResponseEntity.notFound().build();
        Path path = Path.of(filePath);
        if (!Files.exists(path)) return ResponseEntity.notFound().build();
        long size = Files.size(path);
        if (userId == null) {
            usageService.assertGuestQuota(null, deviceId, UsageService.TYPE_DOWNLOAD, size);
            usageService.record(null, deviceId, UsageService.TYPE_DOWNLOAD, id, size);
        } else {
            // 注册用户累计下载流量，供按流量计费
            usageService.record(userId, null, UsageService.TYPE_DOWNLOAD, id, size);
        }
        Resource resource = new FileSystemResource(path);
        String filename = path.getFileName().toString().replace("\"", "");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .header(HttpHeaders.CONTENT_TYPE, "application/octet-stream")
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(size))
                .body(resource);
    }

    private String asStr(Object o) {
        return o == null ? null : o.toString();
    }

    private DownloadTaskResponse toResponse(DownloadTask t) {
        return DownloadTaskResponse.builder()
                .id(t.getId())
                .userId(t.getUserId())
                .sourceUrl(t.getSourceUrl())
                .pageUrl(t.getPageUrl())
                .title(t.getTitle())
                .uploader(t.getUploader())
                .duration(t.getDuration())
                .thumbnail(t.getThumbnail())
                .filePath(t.getFilePath())
                .fileSize(t.getFileSize())
                .format(t.getFormat())
                .formatRequested(t.getFormatRequested())
                .status(t.getStatus())
                .progress(t.getProgress())
                .errorMessage(t.getErrorMessage())
                .videoId(t.getVideoId())
                .duplicate(t.getDuplicate())
                .createdAt(t.getCreatedAt())
                .startedAt(t.getStartedAt())
                .completedAt(t.getCompletedAt())
                .build();
    }
}
