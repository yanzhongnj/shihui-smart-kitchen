package com.videostep.locator.service.creation;

import com.videostep.locator.entity.AiCreation;
import com.videostep.locator.entity.AiCreationVideo;
import com.videostep.locator.entity.Video;
import com.videostep.locator.entity.VideoStep;
import com.videostep.locator.repository.AiCreationVideoRepository;
import com.videostep.locator.repository.VideoRepository;
import com.videostep.locator.repository.VideoStepRepository;
import com.videostep.locator.service.VideoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 酿味升 B + 视频常规化（v0.13）：
 * ①菜谱→常规菜谱：复用 {@link CreationPublishService#syncToLibrary}（POST /creations/{id}/publish）；
 * ②AI视频+同步语音 → 普通视频（方案B：按 steps_json 时间戳 adelay 铺讲解轨 + amix + mux），
 *    建成 videos 记录（私有、completed、进审核队列），并写入 video_steps 供常规播放器步骤导航。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CreationExportService {

    private final CreationChatService chatService;
    private final AiCreationVideoRepository aiVideoRepository;
    private final VideoService videoService;
    private final VideoRepository videoRepository;
    private final VideoStepRepository videoStepRepository;

    @Value("${video.storage.base-path}")
    private String basePath;

    @Value("${video.ffmpeg-path:ffmpeg}")
    private String ffmpegPath;

    /**
     * AI 视频 + 同步语音 → 普通视频（纯 ffmpeg，不重新生成、零费用）。
     * 讲解音轨按每步 start 时间戳精确铺轨（方案B），与静音 video.mp4 mux 合成带讲解的普通视频。
     */
    public Map<String, Object> exportVideo(Long userId, Long creationId) {
        AiCreation c = chatService.getOwnedCreation(userId, creationId);
        AiCreationVideo aiVideo = aiVideoRepository.findByCreationId(creationId);
        if (aiVideo == null || !AiCreationVideo.STATUS_READY.equals(aiVideo.getStatus())) {
            throw new IllegalArgumentException("该创作视频未就绪，无法导出");
        }
        if (aiVideo.getVideoPath() == null || !Files.exists(Path.of(aiVideo.getVideoPath()))) {
            throw new IllegalArgumentException("视频文件缺失，无法导出");
        }
        List<Map<String, Object>> steps = CreationJsonUtil.parseSteps(aiVideo.getStepsJson());
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("无步骤时间戳，无法合成讲解音轨");
        }
        // 校验讲解音频齐全 + 取每步 start 毫秒（用于 adelay 铺轨）
        Path creationDir = Path.of(basePath, "creations", String.valueOf(creationId));
        Path ttsDir = creationDir.resolve("tts");
        List<Path> wavs = new ArrayList<>();
        List<Long> delaysMs = new ArrayList<>();
        for (Map<String, Object> s : steps) {
            int idx = ((Number) s.getOrDefault("index", 0)).intValue();
            double start = ((Number) s.getOrDefault("start", 0.0)).doubleValue();
            Path wav = ttsDir.resolve("step_" + idx + ".wav");
            if (!Files.exists(wav)) {
                throw new IllegalArgumentException("讲解音频缺失 step_" + idx + ".wav，请先生成语音讲解");
            }
            wavs.add(wav);
            delaysMs.add(Math.round(start * 1000));
        }
        try {
            Files.createDirectories(creationDir.resolve("export"));
        } catch (Exception e) {
            throw new IllegalStateException("导出目录创建失败", e);
        }
        // 1) 合成讲解轨（方案B）
        Path narration = creationDir.resolve("export").resolve("narration.wav");
        buildNarrationTrack(wavs, delaysMs, narration);
        // 2) mux 成带讲解的普通视频
        String filename = "export_" + creationId + "_" + System.currentTimeMillis() + ".mp4";
        Path outVideo = creationDir.resolve("export").resolve(filename);
        mux(Path.of(aiVideo.getVideoPath()), narration, outVideo);
        long fileSize;
        try {
            fileSize = Files.size(outVideo);
        } catch (Exception e) {
            throw new IllegalStateException("导出文件读取失败", e);
        }
        // 3) 建 videos 记录（私有、已完成、进审核队列）
        String title = (c.getTitle() == null || c.getTitle().isBlank()) ? "AI 创作" : c.getTitle();
        Video v = videoService.createVideo(userId, null, title + "（AI 讲解版）",
                filename, fileSize, null, outVideo.toString());
        v.setStatus(Video.STATUS_COMPLETED);
        v.setReviewStatus(Video.REVIEW_PENDING);
        v.setStepCount(steps.size());
        videoRepository.updateById(v);
        // 4) 写入 video_steps（复用 AI 时间戳，供常规播放器步骤高亮/跳转）
        saveVideoSteps(v.getId(), steps);
        log.info("[酿味] AI视频导出为普通视频 creationId={} videoId={} title={} dur={}s",
                creationId, v.getId(), v.getTitle(), aiVideo.getDurationSec());
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("videoId", v.getId());
        r.put("filePath", outVideo.toString());
        r.put("durationSec", aiVideo.getDurationSec());
        return r;
    }

    /** 方案B：每步讲解按 start 毫秒 adelay 铺到时间轴，amix 合并为一条完整讲解轨 */
    private void buildNarrationTrack(List<Path> wavs, List<Long> delaysMs, Path out) {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);
        cmd.add("-y");
        for (Path wav : wavs) {
            cmd.add("-i");
            cmd.add(wav.toString());
        }
        StringBuilder fc = new StringBuilder();
        for (int i = 0; i < wavs.size(); i++) {
            fc.append('[').append(i).append(":a]adelay=").append(delaysMs.get(i)).append(":all=1[a").append(i).append("];");
        }
        fc.append('[');
        for (int i = 0; i < wavs.size(); i++) {
            fc.append("a").append(i);
            if (i < wavs.size() - 1) fc.append("][");
        }
        fc.append("]amix=inputs=").append(wavs.size()).append(":normalize=0:dropout_transition=0[aout]");
        cmd.add("-filter_complex");
        cmd.add(fc.toString());
        cmd.add("-map");
        cmd.add("[aout]");
        cmd.add("-ac");
        cmd.add("2");
        cmd.add("-ar");
        cmd.add("44100");
        cmd.add(out.toString());
        runFfmpeg(cmd);
    }

    /** 视频 + 讲解轨 mux（视频 copy 不重编码，讲解轨编码 AAC；apad 补齐音轨 + -shortest 截到视频全长，时长与步骤时间戳一致） */
    private void mux(Path video, Path narration, Path out) {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);
        cmd.add("-y");
        cmd.add("-i");
        cmd.add(video.toString());
        cmd.add("-i");
        cmd.add(narration.toString());
        cmd.add("-filter_complex");
        cmd.add("[1:a]apad[aout]");
        cmd.add("-map");
        cmd.add("0:v");
        cmd.add("-map");
        cmd.add("[aout]");
        cmd.add("-c:v");
        cmd.add("copy");
        cmd.add("-c:a");
        cmd.add("aac");
        cmd.add("-b:a");
        cmd.add("128k");
        cmd.add("-shortest");
        cmd.add("-movflags");
        cmd.add("+faststart");
        cmd.add(out.toString());
        runFfmpeg(cmd);
    }

    private void runFfmpeg(List<String> cmd) {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        try {
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) log.debug("[酿味-导出] {}", line);
            }
            if (!p.waitFor(300, TimeUnit.SECONDS) || p.exitValue() != 0) {
                throw new RuntimeException("ffmpeg 退出码 " + p.exitValue());
            }
        } catch (Exception e) {
            throw new IllegalStateException("ffmpeg 执行失败: " + e.getMessage(), e);
        }
    }

    /** 复用 AI 步骤时间戳写 video_steps（供常规播放器步骤导航/高亮/跳转） */
    private void saveVideoSteps(Long videoId, List<Map<String, Object>> steps) {
        for (Map<String, Object> s : steps) {
            int idx = ((Number) s.getOrDefault("index", 0)).intValue();
            double start = ((Number) s.getOrDefault("start", 0.0)).doubleValue();
            double end = ((Number) s.getOrDefault("end", start)).doubleValue();
            String summary = s.get("summary") == null ? "" : String.valueOf(s.get("summary"));
            VideoStep st = VideoStep.builder()
                    .videoId(videoId)
                    .stepIndex(idx)
                    .summary(summary)
                    .startTime(BigDecimal.valueOf(start))
                    .endTime(BigDecimal.valueOf(end))
                    .originalText(summary)
                    .confidence(VideoStep.CONFIDENCE_HIGH)
                    .loopCount(0)
                    .build();
            videoStepRepository.insert(st);
        }
    }
}
