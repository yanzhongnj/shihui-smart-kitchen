package com.videostep.locator.service.xunwei;

import com.videostep.locator.entity.Video;
import com.videostep.locator.entity.VideoStep;
import com.videostep.locator.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 视频「成品帧」抽取（M2）：从本地视频中抽一帧作为目标成品参照图（根治无封面视频无法纠偏）。
 * - 抽帧位置：优先「成品类步骤」（summary 含 出锅/装盘/盛出/摆盘/成品/完成/上桌 等）中间时刻；
 *   否则取末尾步骤起点+3s；再否则取视频时长 ×0.92。
 * - 缓存：抽帧结果存 {@code <base-path>/frames/<videoId>_finish.jpg}，并回写 videos.finish_frame（相对路径），下次直接命中。
 * - 失败（ffmpeg 缺失/视频缺失/超时）→ 返回 null，由调用方降级到封面或提示手动上传。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VideoFrameExtractor {

    @Value("${video.storage.base-path}")
    private String basePath;

    @Value("${video.ffmpeg-path:ffmpeg}")
    private String ffmpegPath;

    @Value("${video.frame-extract-timeout-ms:60000}")
    private long timeoutMs;

    private final VideoRepository videoRepository;

    private static final List<String> FINISH_KEYWORDS = List.of(
            "出锅", "装盘", "盛出", "摆盘", "成品", "完成", "上桌", "盛入", "装出", "出锅装盘");

    /** 抽取并缓存成品帧；成功返回帧文件 Path，失败返回 null。 */
    public Path extractAndCache(Video video, List<VideoStep> steps) {
        if (video == null || video.getFilePath() == null || video.getFilePath().isBlank()) return null;
        Path videoFile = Path.of(video.getFilePath());
        if (!Files.exists(videoFile)) {
            log.warn("frame extract: video file missing videoId={} path={}", video.getId(), video.getFilePath());
            return null;
        }
        // 缓存命中
        if (video.getFinishFrame() != null && !video.getFinishFrame().isBlank()) {
            Path cached = Path.of(basePath).resolve(video.getFinishFrame());
            if (Files.exists(cached)) return cached;
        }
        Double t = pickTimestamp(video, steps);
        if (t == null) {
            log.warn("frame extract: no timestamp for videoId={}", video.getId());
            return null;
        }
        String rel = "frames/" + video.getId() + "_finish.jpg";
        Path out = Path.of(basePath).resolve(rel);
        try {
            Files.createDirectories(out.getParent());
            List<String> cmd = List.of(ffmpegPath, "-y",
                    "-ss", String.format(Locale.ROOT, "%.3f", t),
                    "-i", video.getFilePath(),
                    "-frames:v", "1", "-q:v", "2",
                    out.toString());
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            log.debug("frame extract cmd: {}", cmd);
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) log.debug("[Frame-{}] {}", video.getId(), line);
            }
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                log.warn("frame extract timeout videoId={}", video.getId());
                return null;
            }
            if (p.exitValue() != 0 || !Files.exists(out) || Files.size(out) == 0) {
                log.warn("frame extract failed exit={} videoId={}", p.exitValue(), video.getId());
                return null;
            }
            // 回写缓存路径
            video.setFinishFrame(rel);
            videoRepository.updateById(video);
            log.info("finish frame cached videoId={} t={}s rel={}", video.getId(), t, rel);
            return out;
        } catch (Exception e) {
            log.warn("frame extract error videoId={}: {}", video.getId(), e.getMessage());
            return null;
        }
    }

    /** 选抽帧时刻：成品类步骤中段 > 末尾步骤起点+3s > 时长×0.92。 */
    private Double pickTimestamp(Video video, List<VideoStep> steps) {
        if (steps != null && !steps.isEmpty()) {
            for (VideoStep s : steps) {
                String sum = s.getSummary() == null ? "" : s.getSummary();
                if (FINISH_KEYWORDS.stream().anyMatch(sum::contains)) {
                    double start = s.getStartTime() == null ? 0 : s.getStartTime().doubleValue();
                    double end = s.getEndTime() == null ? start + 10 : s.getEndTime().doubleValue();
                    return start + (end - start) / 2;
                }
            }
            VideoStep last = steps.get(steps.size() - 1);
            if (last.getStartTime() != null) return Math.max(0, last.getStartTime().doubleValue() + 3);
        }
        if (video.getDuration() != null && video.getDuration().doubleValue() > 10) {
            return video.getDuration().doubleValue() * 0.92;
        }
        return null;
    }
}
