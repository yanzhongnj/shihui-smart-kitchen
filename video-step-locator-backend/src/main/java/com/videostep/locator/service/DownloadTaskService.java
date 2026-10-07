package com.videostep.locator.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.videostep.locator.entity.DownloadTask;
import com.videostep.locator.entity.Video;
import com.videostep.locator.exception.PipelineException;
import com.videostep.locator.repository.DownloadTaskRepository;
import com.videostep.locator.service.xunwei.XunweiMemoryService;
import com.videostep.locator.util.Md5Utils;
import com.videostep.locator.websocket.DownloadProgressHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 下载任务编排：解析 → 创建任务 → 异步执行 yt-dlp 下载 → 入库(仅创建 videos 记录，不自动处理)。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DownloadTaskService {

    private final DownloadTaskRepository taskRepository;
    private final YtDlpService ytDlpService;
    private final VideoService videoService;
    private final FileStorageService fileStorageService;
    private final DownloadProgressHandler progressHandler;
    private final XunweiMemoryService xunweiMemoryService;

    @Qualifier("downloadExecutor")
    private final ThreadPoolTaskExecutor downloadExecutor;

    @Value("${video.storage.base-path}")
    private String basePath;

    // ==================== 解析 ====================

    public YtDlpService.ParseResult parse(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("链接不能为空");
        }
        if (!(url.startsWith("http://") || url.startsWith("https://"))) {
            throw new IllegalArgumentException("仅支持 http/https 链接");
        }
        if (url.length() > 1000) {
            throw new IllegalArgumentException("链接过长");
        }
        try {
            return ytDlpService.parse(url.trim());
        } catch (PipelineException e) {
            throw e;
        } catch (Exception e) {
            throw new PipelineException("链接解析异常: " + e.getMessage(), e);
        }
    }

    // ==================== 创建与执行 ====================

    public List<DownloadTask> start(Long userId, String deviceId, String pageUrl,
                                    List<YtDlpService.ParsedEntry> entries, String formatKey,
                                    boolean createCloud) {
        List<DownloadTask> tasks = new ArrayList<>();
        for (YtDlpService.ParsedEntry e : entries) {
            String url = e.getUrl();
            if (url == null || url.isBlank()) continue;

            // 源地址唯一性：同一用户/设备已下载过该 URL → 复用已有视频，不重复下载（可修改标题）
            DownloadTask existing = findDownloaded(userId, deviceId, url);
            if (existing != null) {
                if (e.getTitle() != null && !e.getTitle().isBlank()
                        && existing.getVideoId() != null) {
                    try {
                        videoService.updateVideo(existing.getVideoId(), userId, e.getTitle());
                        existing.setTitle(e.getTitle());
                        log.info("Download duplicate url {} -> update title, reuse video {}",
                                url, existing.getVideoId());
                    } catch (Exception ex) {
                        log.warn("update title for existing video {} failed: {}",
                                existing.getVideoId(), ex.getMessage());
                    }
                }
                existing.setDuplicate(true);
                tasks.add(existing);
                continue;
            }

            DownloadTask task = DownloadTask.builder()
                    .userId(userId)
                    .deviceId(deviceId)
                    .sourceUrl(url)
                    .pageUrl(pageUrl)
                    .title(e.getTitle())
                    .uploader(e.getUploader())
                    .duration(e.getDuration() != null ? BigDecimal.valueOf(e.getDuration()) : null)
                    .thumbnail(e.getThumbnail())
                    .formatRequested(formatKey)
                    .autoCloud(createCloud ? 1 : 0)
                    .status(DownloadTask.STATUS_QUEUED)
                    .progress(0)
                    .build();
            taskRepository.insert(task);
            tasks.add(task);
            execute(task, formatKey);
        }
        if (tasks.isEmpty()) {
            throw new IllegalArgumentException("没有可下载的视频条目");
        }
        return tasks;
    }

    /** 查找已下载过的同源任务（completed）：登录用户按 userId+deviceId，游客按 deviceId */
    private DownloadTask findDownloaded(Long userId, String deviceId, String url) {
        LambdaQueryWrapper<DownloadTask> qw = new LambdaQueryWrapper<DownloadTask>()
                .eq(DownloadTask::getSourceUrl, url)
                .eq(DownloadTask::getStatus, DownloadTask.STATUS_COMPLETED);
        if (userId != null) {
            qw.and(w -> w.eq(DownloadTask::getUserId, userId)
                    .or(o -> o.eq(DownloadTask::getDeviceId, deviceId)));
        } else {
            qw.eq(DownloadTask::getDeviceId, deviceId);
        }
        qw.orderByDesc(DownloadTask::getCompletedAt).last("LIMIT 1");
        return taskRepository.selectOne(qw);
    }

    private void execute(DownloadTask task, String formatKey) {
        downloadExecutor.execute(() -> {
            Long taskId = task.getId();
            task.setStatus(DownloadTask.STATUS_RUNNING);
            task.setStartedAt(LocalDateTime.now());
            taskRepository.updateById(task);
            progressHandler.sendProgress(taskId, "running", 0, "下载中", "开始下载");

            Path tempDir = Path.of(basePath, "temp", "download", String.valueOf(taskId));
            try {
                YtDlpService.DownloadResult dl = ytDlpService.download(taskId,
                        task.getSourceUrl(), tempDir, formatKey, task.getReferer(), pct -> {
                            task.setProgress(pct);
                            taskRepository.updateById(task);
                            progressHandler.sendProgress(taskId, "running", pct, "下载中",
                                    "下载进度 " + pct + "%");
                        });

                // 移动到正式存储目录
                String filename = sanitizeFilename(task.getTitle()) + "." + dl.getExtension();
                Path target = fileStorageService.getVideoPath(System.currentTimeMillis(), filename);
                Files.createDirectories(target.getParent());
                Files.move(dl.getFile(), target, StandardCopyOption.REPLACE_EXISTING);
                deleteDir(tempDir);

                task.setStatus(DownloadTask.STATUS_COMPLETED);
                task.setProgress(100);
                task.setFilePath(target.toString());
                task.setFileSize(dl.getFileSize());
                task.setFormat(dl.getExtension());
                task.setCompletedAt(LocalDateTime.now());
                task.setErrorMessage(null);

                // 下载后入库（仅记录，不自动处理）：登录用户归属 userId；游客归属 deviceId
                // 严格「下载不建云端、显式上传才建」：仅 Web 端/默认(autoCloud=1) 下载完成自动建云端视频；
                // APP 端(autoCloud=0) 只落盘供拉取到手机本地，云端视频由用户显式上传创建
                // 云端视频仅归属真实登录用户（userId 非空）且 autoCloud=1（Web 默认）；
                // 游客（仅 deviceId）无论来源都不建云端，避免 createVideo(null user) 报“用户不存在”
                boolean createCloudVideo = task.getUserId() != null
                        && Integer.valueOf(1).equals(task.getAutoCloud());
                if (createCloudVideo) {
                    String md5 = Md5Utils.calculate(target.toString());
                    // MD5 去重：同一用户相同内容的视频不重复存储，复用已有记录（避免磁盘与资源浪费）
                    java.util.Optional<Video> existing =
                            videoService.findByMd5(task.getUserId(), md5);
                    if (existing.isPresent()) {
                        Video ev = existing.get();
                        task.setVideoId(ev.getId());
                        Files.deleteIfExists(target);
                        log.info("Download task {} duplicate -> reuse video {} (md5 {})",
                                taskId, ev.getId(), md5);
                    } else {
                        Video video = videoService.createVideo(task.getUserId(),
                                task.getDeviceId(), task.getTitle(),
                                target.getFileName().toString(),
                                dl.getFileSize(), md5, target.toString());
                        task.setVideoId(video.getId());
                        log.info("Download task {} completed -> video {} (user={} device={})",
                                taskId, video.getId(), task.getUserId(), task.getDeviceId());
                    }
                } else {
                    log.info("Download task {} (autoCloud={}) completed -> file {} (no cloud video)",
                            taskId, task.getAutoCloud(), target);
                }
                taskRepository.updateById(task);
                // 行为信号 → 寻味记忆（下载完成=正向）
                if (task.getUserId() != null) {
                    xunweiMemoryService.ingestBehavior(task.getUserId(), "download", task.getTitle(), true);
                }
                progressHandler.sendProgress(taskId, "completed", 100, "完成",
                        createCloudVideo ? "下载完成，视频已入库，可在“我的视频”查看并开始处理"
                                : "下载完成，可在下载记录取回文件并上传到云端");

            } catch (Exception e) {
                log.error("Download task {} failed: {}", taskId, e.getMessage(), e);
                deleteDir(tempDir); // 清理残留临时文件
                DownloadTask cur = taskRepository.selectById(taskId);
                if (cur != null && DownloadTask.STATUS_CANCELLED.equals(cur.getStatus())) {
                    return; // 已取消，不覆盖状态
                }
                task.setStatus(DownloadTask.STATUS_FAILED);
                task.setErrorMessage(truncate(e.getMessage(), 2000));
                taskRepository.updateById(task);
                progressHandler.sendProgress(taskId, "failed", task.getProgress(), "失败",
                        e.getMessage() != null ? e.getMessage() : "未知错误");
            }
        });
    }

    // ==================== 查询 / 重试 / 取消 ====================

    /** 分页查询下载任务（注册用户按 userId，游客按 deviceId），count + LIMIT 手动分页 */
    public com.baomidou.mybatisplus.extension.plugins.pagination.Page<DownloadTask> list(
            Long userId, String deviceId, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        LambdaQueryWrapper<DownloadTask> qw = new LambdaQueryWrapper<DownloadTask>()
                .eq(userId != null, DownloadTask::getUserId, userId)
                .eq(userId == null && deviceId != null, DownloadTask::getDeviceId, deviceId)
                .orderByDesc(DownloadTask::getCreatedAt);
        long total = taskRepository.selectCount(qw);
        qw.last("LIMIT " + (safePage * safeSize) + ", " + safeSize);
        List<DownloadTask> records = taskRepository.selectList(qw);
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<DownloadTask> p =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(safePage, safeSize, total);
        p.setRecords(records);
        return p;
    }

    public DownloadTask get(Long userId, String deviceId, Long id) {
        DownloadTask t = taskRepository.selectById(id);
        if (t == null) throw new IllegalArgumentException("下载任务不存在");
        if (userId != null) {
            if (!userId.equals(t.getUserId())) throw new SecurityException("无权访问该下载任务");
        } else {
            if (deviceId == null || !deviceId.equals(t.getDeviceId())) throw new SecurityException("无权访问该下载任务");
        }
        return t;
    }

    public void retry(Long userId, String deviceId, Long id) {
        DownloadTask t = get(userId, deviceId, id);
        if (!DownloadTask.STATUS_FAILED.equals(t.getStatus())
                && !DownloadTask.STATUS_CANCELLED.equals(t.getStatus())) {
            throw new IllegalArgumentException("只有失败或已取消的任务可以重试");
        }
        t.setStatus(DownloadTask.STATUS_QUEUED);
        t.setProgress(0);
        t.setErrorMessage(null);
        t.setCompletedAt(null);
        t.setVideoId(null);
        taskRepository.updateById(t);
        execute(t, t.getFormatRequested());
    }

    public void cancel(Long userId, String deviceId, Long id) {
        DownloadTask t = get(userId, deviceId, id);
        if (DownloadTask.STATUS_RUNNING.equals(t.getStatus())
                || DownloadTask.STATUS_QUEUED.equals(t.getStatus())) {
            ytDlpService.cancel(id);
            deleteDir(Path.of(basePath, "temp", "download", String.valueOf(id)));
            t.setStatus(DownloadTask.STATUS_CANCELLED);
            t.setErrorMessage("用户取消");
            taskRepository.updateById(t);
            progressHandler.sendProgress(id, "cancelled", t.getProgress(), "已取消", "任务已取消");
        }
    }

    // ==================== 工具 ====================

    private String sanitizeFilename(String title) {
        if (title == null || title.isBlank()) return "downloaded_video";
        return title.replaceAll("[\\\\/:*?\"<>|\\s]+", "_").trim();
    }

    private void deleteDir(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException e) { log.warn("删除失败: {}", p); }
            });
        } catch (IOException e) {
            log.warn("清理临时目录失败: {}", dir);
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
