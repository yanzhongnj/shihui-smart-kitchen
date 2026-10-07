package com.videostep.locator.controller;

import com.videostep.locator.dto.request.ChunkUploadInitRequest;
import com.videostep.locator.dto.response.ApiResponse;
import com.videostep.locator.dto.response.VideoResponse;
import com.videostep.locator.entity.ProcessingTask;
import com.videostep.locator.entity.Video;
import com.videostep.locator.repository.ProcessingTaskRepository;
import com.videostep.locator.repository.VideoRepository;
import com.videostep.locator.service.FileStorageService;
import com.videostep.locator.service.QuotaService;
import com.videostep.locator.service.TaskSchedulerService;
import com.videostep.locator.service.UserService;
import com.videostep.locator.service.VideoService;
import com.videostep.locator.util.Md5Utils;
import com.videostep.locator.util.VideoUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/videos")
@RequiredArgsConstructor
@Slf4j
public class VideoController {

    private final VideoService videoService;
    private final FileStorageService fileStorageService;
    private final UserService userService;
    private final VideoRepository videoRepository;
    private final ProcessingTaskRepository processingTaskRepository;
    private final TaskSchedulerService taskSchedulerService;
    private final QuotaService quotaService;

    private final Map<String, UploadState> uploads = new ConcurrentHashMap<>();

    @PostMapping("/upload/init")
    public ApiResponse<Map<String, Object>> initUpload(
            @RequestAttribute("userId") Long userId,
            @Valid @RequestBody ChunkUploadInitRequest req) {

        if (!VideoUtils.isFormatAllowed(req.getFilename())) {
            return ApiResponse.error(400, "不支持的文件格式");
        }
        if (!VideoUtils.isFileSizeAllowed(req.getFileSize())) {
            return ApiResponse.error(400, "文件大小不能超过1GB");
        }

        String uploadId = UUID.randomUUID().toString();
        UploadState state = new UploadState(uploadId, req);
        uploads.put(uploadId, state);

        return ApiResponse.success(Map.of(
                "uploadId", uploadId,
                "chunkSize", 5242880,
                "totalChunks", req.getTotalChunks()
        ));
    }

    @PostMapping("/upload/chunk")
    public ApiResponse<Map<String, Object>> uploadChunk(
            @RequestParam("uploadId") String uploadId,
            @RequestParam("chunkIndex") Integer chunkIndex,
            @RequestParam("file") MultipartFile file) throws IOException {

        UploadState state = uploads.get(uploadId);
        if (state == null) {
            return ApiResponse.error(404, "uploadId 不存在或已过期");
        }

        try (InputStream is = file.getInputStream()) {
            fileStorageService.saveChunk(uploadId, chunkIndex, is);
        }

        state.receivedChunks.add(chunkIndex);

        return ApiResponse.success(Map.of(
                "uploadId", uploadId,
                "chunkIndex", chunkIndex
        ));
    }

    @GetMapping("/upload/status")
    public ApiResponse<Map<String, Object>> getUploadStatus(
            @RequestAttribute("userId") Long userId,
            @RequestParam(value = "fileMd5", required = false) String fileMd5) {

        if (fileMd5 != null) {
            // 检查是否已存在相同MD5的视频（秒传）
            Optional<Video> existing = videoService.findByMd5(userId, fileMd5);
            if (existing.isPresent()) {
                return ApiResponse.success(Map.of(
                        "exists", true,
                        "videoId", existing.get().getId()
                ));
            }
        }

        return ApiResponse.success(Map.of("exists", false));
    }

    @PostMapping("/upload/merge")
    public ApiResponse<Map<String, Object>> mergeChunks(
            @RequestAttribute("userId") Long userId,
            @RequestBody Map<String, String> body) throws IOException {

        String uploadId = body.get("uploadId");
        UploadState state = uploads.get(uploadId);
        if (state == null) {
            return ApiResponse.error(404, "uploadId 不存在");
        }

        // 合并文件
        String filename = state.filename;
        long fileSize = state.fileSize;
        String md5 = state.md5;

        Path targetPath = fileStorageService.getVideoPath(
                System.currentTimeMillis(), filename);
        fileStorageService.mergeChunks(uploadId, targetPath, state.totalChunks);

        // 校验全文件 MD5
        String calculatedMd5 = Md5Utils.calculate(targetPath.toString());
        if (!md5.equalsIgnoreCase(calculatedMd5)) {
            Files.deleteIfExists(targetPath);
            return ApiResponse.error(400, "文件MD5校验失败，上传可能已损坏");
        }

        // 创建视频记录（去除文件扩展名作为标题）：登录用户上传，无 deviceId
        String title = filename.replaceAll("\\.(mp4|webm|avi|mov|mkv)$", "");
        Video video = videoService.createVideo(
                userId, null, title, filename, fileSize, md5, targetPath.toString());

        // 新流程：上传完成自动开始步骤识别处理；失败不阻断上传成功（状态展示+手动重试兜底）
        autoStartProcessing(video.getId());

        uploads.remove(uploadId);

        return ApiResponse.success(Map.of(
                "videoId", video.getId(),
                "message", "上传成功，已自动开始智能处理（步骤识别）"
        ));
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> getVideos(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String order) {
        // 游客（未登录）无 userId：返回空列表而非 500（/api/videos 为公开只读接口）
        if (userId == null) {
            return ApiResponse.success(Map.of("items", List.of(), "total", 0L));
        }
        // ADMIN：我的视频显示全量（跨用户），避免「列表为空但详情可看」的困惑
        if (isAdmin(userId)) {
            return ApiResponse.success(videoService.adminGetVideos(page, size, status, keyword, sortBy, order));
        }
        return ApiResponse.success(videoService.getUserVideos(userId, page, size, status, keyword, sortBy, order));
    }

    private boolean isAdmin(Long userId) {
        var u = userService.getById(userId);
        return u != null && "ADMIN".equals(u.getRole());
    }

    /** 上传后自动开始智能处理：已有排队/运行中任务则跳过；失败不阻断（可手动重试） */
    private void autoStartProcessing(Long videoId) {
        try {
            Video video = videoRepository.selectById(videoId);
            if (video == null) return;
            List<ProcessingTask> existing = processingTaskRepository.findByVideoIdOrderByCreatedAtDesc(videoId);
            boolean busy = existing.stream().anyMatch(t ->
                    ProcessingTask.STATUS_QUEUED.equals(t.getStatus())
                            || ProcessingTask.STATUS_RUNNING.equals(t.getStatus()));
            if (busy) return;
            if (video.getOrgId() != null) quotaService.checkTaskQuota(video.getOrgId());
            taskSchedulerService.createAndExecuteTask(video);
            log.info("upload merge: 自动开始处理 video={}", videoId);
        } catch (Exception e) {
            log.warn("upload merge: 自动处理失败(可手动重试) video={}: {}", videoId, e.getMessage());
        }
    }

    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> getStats(@RequestAttribute("userId") Long userId) {
        if (isAdmin(userId)) {
            return ApiResponse.success(videoService.adminGetVideoStats());
        }
        return ApiResponse.success(videoService.getUserVideoStats(userId));
    }

    @GetMapping("/{id}")
    public ApiResponse<VideoResponse> getVideo(
            @RequestAttribute("userId") Long userId,
            @PathVariable Long id) {
        VideoResponse video = videoService.getVideoDetail(id, userId);
        return ApiResponse.success(video);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteVideo(
            @RequestAttribute("userId") Long userId,
            @PathVariable Long id) {
        videoService.deleteVideo(id, userId);
        return ApiResponse.success(null);
    }

    @PutMapping("/{id}")
    public ApiResponse<VideoResponse> updateVideo(
            @RequestAttribute("userId") Long userId,
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        VideoResponse video = videoService.updateVideo(id, userId, body.get("title"));
        return ApiResponse.success(video);
    }

    @PostMapping("/{id}/review-result")
    public ApiResponse<VideoResponse> reviewResult(
            @RequestAttribute("userId") Long userId,
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        Object approvedObj = body.get("approved");
        boolean approved = Boolean.TRUE.equals(approvedObj)
                || "true".equalsIgnoreCase(String.valueOf(approvedObj));
        String comment = body.get("comment") instanceof String s ? s : null;
        return ApiResponse.success(videoService.reviewResult(id, userId, approved, comment));
    }

    @PostMapping("/{id}/publish")
    public ApiResponse<VideoResponse> publish(
            @RequestAttribute("userId") Long userId,
            @PathVariable Long id) {
        return ApiResponse.success(videoService.publish(id, userId));
    }

    @PostMapping("/{id}/unpublish")
    public ApiResponse<VideoResponse> unpublish(
            @RequestAttribute("userId") Long userId,
            @PathVariable Long id) {
        return ApiResponse.success(videoService.unpublish(id, userId));
    }

    @GetMapping("/{id}/stream")
    public ResponseEntity<Resource> streamVideo(
            @RequestAttribute(value = "userId", required = false) Long userId,
            @PathVariable Long id,
            @RequestHeader(value = "Range", required = false) String rangeHeader) {

        String filePath = videoService.getVideoStreamPath(id, userId);
        Path path = Path.of(filePath);
        if (!Files.exists(path)) {
            return ResponseEntity.notFound().build();
        }

        try {
            long fileLength = Files.size(path);
            String contentType = Files.probeContentType(path);
            if (contentType == null) contentType = "video/mp4";

            // 无 Range 头：返回完整文件
            if (rangeHeader == null || !rangeHeader.startsWith("bytes=")) {
                Resource resource = new FileSystemResource(path);
                return ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_TYPE, contentType)
                        .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(fileLength))
                        .header("Accept-Ranges", "bytes")
                        .body(resource);
            }

            // 解析 Range 请求
            String range = rangeHeader.substring(6).split(",")[0].trim();
            String[] parts = range.split("-");
            long start = Long.parseLong(parts[0]);
            long end = parts.length > 1 && !parts[1].isEmpty()
                    ? Long.parseLong(parts[1]) : fileLength - 1;

            // 边界安全校验
            if (start >= fileLength) start = fileLength - 1;
            if (end >= fileLength) end = fileLength - 1;
            if (start < 0) start = 0;
            if (end < start) end = start;

            long contentLength = end - start + 1;

            // 只返回请求的字节范围
            InputStream fullStream = Files.newInputStream(path);
            fullStream.skip(start);
            InputStreamResource rangeResource = new InputStreamResource(
                    new LimitedInputStream(fullStream, contentLength));

            return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                    .header(HttpHeaders.CONTENT_TYPE, contentType)
                    .header(HttpHeaders.CONTENT_RANGE,
                            "bytes " + start + "-" + end + "/" + fileLength)
                    .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(contentLength))
                    .header("Accept-Ranges", "bytes")
                    .body(rangeResource);

        } catch (IOException e) {
            log.error("Failed to stream video {}: {}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * 限制 InputStream 只读取指定字节数
     */
    private static class LimitedInputStream extends FilterInputStream {
        private final long limit;
        private long count = 0;

        protected LimitedInputStream(InputStream in, long limit) {
            super(in);
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            if (count >= limit) return -1;
            int b = super.read();
            if (b >= 0) count++;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (count >= limit) return -1;
            int maxLen = (int) Math.min(len, limit - count);
            int n = super.read(b, off, maxLen);
            if (n > 0) count += n;
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = super.skip(Math.min(n, limit - count));
            count += skipped;
            return skipped;
        }

        @Override
        public int available() throws IOException {
            return (int) Math.min(super.available(), limit - count);
        }
    }

    private record UploadState(String uploadId, String filename, long fileSize,
                                String md5, int totalChunks, Set<Integer> receivedChunks) {
        public UploadState(String uploadId, ChunkUploadInitRequest req) {
            this(uploadId, req.getFilename(), req.getFileSize(),
                    req.getMd5(), req.getTotalChunks(), new HashSet<>());
        }
    }
}
