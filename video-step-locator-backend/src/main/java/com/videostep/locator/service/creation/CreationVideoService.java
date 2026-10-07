package com.videostep.locator.service.creation;

import com.videostep.locator.entity.AiCreation;
import com.videostep.locator.entity.AiCreationSession;
import com.videostep.locator.entity.AiCreationVideo;
import com.videostep.locator.entity.AiVideoTask;
import com.videostep.locator.repository.AiCreationVideoRepository;
import com.videostep.locator.repository.AiCreationRepository;
import com.videostep.locator.repository.AiVideoTaskRepository;
import com.videostep.locator.service.creation.videogen.AIVideoGenOrchestrator;
import com.videostep.locator.service.creation.videogen.VideoGenResult;
import com.videostep.locator.service.creation.videogen.VideoGenTaskRef;
import com.videostep.locator.websocket.VideoGenProgressHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.Set;

/**
 * 酿味 AI 视频教程编排（P2，Mock 链路）：
 * 过滤长等待步骤（timer_min>0 用图文+计时器）→ 逐段 Mock 生成 → FFmpeg concat 拼接
 * → 累计时长生成步骤时间戳 → ai_creation_videos ready（结构性红利：时间戳由拼接自然产生，无需对齐算法）。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CreationVideoService {

    private final CreationChatService chatService;
    private final CreationSessionService sessionService;
    private final AiVideoTaskRepository taskRepository;
    private final AiCreationVideoRepository videoRepository;
    private final AiCreationRepository creationRepository;
    private final AIVideoGenOrchestrator videoGateway;
    private final CreationQuotaService quotaService;
    private final VideoGenProgressHandler progressHandler;
    private final CreationTtsService ttsService;

    @Value("${video.storage.base-path}")
    private String basePath;

    @Value("${video.ffmpeg-path:ffmpeg}")
    private String ffmpegPath;

    @Value("${creation.videogen.default-segment-sec:5}")
    private int defaultSegmentSec;

    @Value("${creation.cost.per-second-unit:0}")
    private double costPerSecondUnit;

    /** 视频生成异步执行器（长任务：逐段生成/轮询/下载/拼接，进度经 /video-gen/tasks 与 WS 推送） */
    private static final ExecutorService VIDEO_EXEC =
            Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "creation-videogen");
                t.setDaemon(true);
                return t;
            });
    /** 正在生成中的创作（防止重复触发同一创作并发生成） */
    private static final Set<Long> GENERATING = ConcurrentHashMap.newKeySet();

    /** 视频单段/整条重创（v0.3）：scope=step 只重做指定段再重拼；scope=all 整条重生成（异步，进度经 /video-gen/tasks 轮询） */
    public Map<String, Object> startVideoRegenerate(Long userId, Long creationId, String scope, Integer stepIndex) {
        AiCreation creation = chatService.getOwnedCreation(userId, creationId);
        List<Map<String, Object>> steps = CreationJsonUtil.parseSteps(creation.getSteps());
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("当前菜谱无步骤，无法重创视频");
        }
        if ("all".equalsIgnoreCase(scope)) {
            return startVideoGeneration(userId, creationId, true);
        }
        int idx = stepIndex == null ? -1 : stepIndex;
        boolean found = steps.stream().anyMatch(s ->
                ((Number) s.getOrDefault("index", 0)).intValue() == idx);
        if (!found) {
            throw new IllegalArgumentException("步骤不存在：" + stepIndex);
        }
        if (!GENERATING.add(creationId)) {
            throw new IllegalArgumentException("该创作正在生成视频中，请稍候");
        }
        // 会话置为「视频生成中」（列表徽标 VIDEO_GEN），生成完成后由 updateSessionStatus 置 VIDEO_READY
        markSessionVideoGen(userId, creation);
        VIDEO_EXEC.submit(() -> {
            try {
                regenerateStep(userId, creationId, stepIndex);
            } catch (Exception e) {
                log.error("[酿味] 单段重创异常 creationId={} step={}: {}", creationId, stepIndex, e.getMessage(), e);
                try {
                    AiCreationVideo v = videoRepository.findByCreationId(creationId);
                    if (v != null) {
                        v.setStatus(AiCreationVideo.STATUS_FAILED);
                        v.setErrorMessage(e.getMessage());
                        videoRepository.updateById(v);
                    }
                } catch (Exception ex) {
                    log.warn("[酿味] 标记重创失败异常 creationId={}: {}", creationId, ex.getMessage());
                }
            } finally {
                GENERATING.remove(creationId);
            }
        });
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("started", true);
        r.put("creationId", creationId);
        r.put("scope", "step");
        r.put("stepIndex", stepIndex);
        return r;
    }

    /** 启动恢复：续传上次未完成的视频生成（进程重启/崩溃后），保留已完成分段不重复计费 */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedGeneration() {
        try {
            List<AiCreationVideo> stuck = videoRepository.findGenerating();
            if (stuck.isEmpty()) {
                return;
            }
            for (AiCreationVideo v : stuck) {
                Long creationId = v.getCreationId();
                if (creationId == null || GENERATING.contains(creationId)) continue;
                AiCreation c = creationRepository.selectById(creationId);
                if (c == null || c.getUserId() == null) continue;
                GENERATING.add(creationId);
                VIDEO_EXEC.submit(() -> {
                    try {
                        resumeVideos(c.getUserId(), creationId);
                    } catch (Exception e) {
                        log.error("[酿味] 启动恢复续传失败 creationId={}: {}", creationId, e.getMessage());
                        try {
                            AiCreationVideo vv = videoRepository.findByCreationId(creationId);
                            if (vv != null) {
                                vv.setStatus(AiCreationVideo.STATUS_FAILED);
                                vv.setErrorMessage(e.getMessage());
                                videoRepository.updateById(vv);
                            }
                        } catch (Exception ex) {
                            log.warn("[酿味] 标记恢复失败 creationId={}: {}", creationId, ex.getMessage());
                        }
                    } finally {
                        GENERATING.remove(creationId);
                    }
                });
                log.info("[酿味] 启动恢复：续传生成中的视频 creationId={}", creationId);
            }
        } catch (Exception e) {
            log.warn("[酿味] 启动恢复扫描异常: {}", e.getMessage());
        }
    }

    /**
     * 触发 AI 视频教程生成（P2/P3）：付费引擎无 confirm 先返成本预估；
     * 确认后异步执行长任务并立即返回 started，进度经 GET /video-gen/tasks 与 WS /ws/videogen/{id} 推送。
     */
    public Map<String, Object> startVideoGeneration(Long userId, Long creationId, boolean confirm) {
        AiCreation creation = chatService.getOwnedCreation(userId, creationId);
        List<Map<String, Object>> steps = CreationJsonUtil.parseSteps(creation.getSteps());
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("当前菜谱无步骤，无法生成视频");
        }
        int segments = steps.size();
        boolean billable = videoGateway.isBillable();
        // 付费引擎：先返回成本预估，用户确认后才执行（Mock 免费直接生成）
        if (billable && !confirm) {
            return estimate(creationId, segments, defaultSegmentSec);
        }
        if (billable) {
            quotaService.assertCanGenerate(userId, segments, defaultSegmentSec);
        }
        // 防重复触发：同一创作生成中则拒绝
        if (!GENERATING.add(creationId)) {
            throw new IllegalArgumentException("该创作正在生成视频中，请稍候");
        }
        // 会话置为「视频生成中」（列表徽标 VIDEO_GEN），生成完成后由 updateSessionStatus 置 VIDEO_READY
        markSessionVideoGen(userId, creation);
        // 异步执行长任务；完成后/异常时清除守卫
        VIDEO_EXEC.submit(() -> {
            try {
                generateVideos(userId, creationId, true);
            } catch (Exception e) {
                log.error("[酿味] 异步视频生成异常 creationId={}: {}", creationId, e.getMessage(), e);
                try {
                    AiCreationVideo v = videoRepository.findByCreationId(creationId);
                    if (v != null) {
                        v.setStatus(AiCreationVideo.STATUS_FAILED);
                        v.setErrorMessage(e.getMessage());
                        videoRepository.updateById(v);
                    }
                } catch (Exception ex) {
                    log.warn("[酿味] 标记视频失败异常 creationId={}: {}", creationId, ex.getMessage());
                }
            } finally {
                GENERATING.remove(creationId);
            }
        });
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("started", true);
        r.put("creationId", creationId);
        r.put("segments", segments);
        r.put("billable", billable);
        return r;
    }

    /**
     * 为创作生成 AI 视频教程（全量重生成）。
     * 由 {@link #startVideoGeneration} 异步调用；生成中可经 /video-gen/tasks 轮询逐段进度。
     */
    public Map<String, Object> generateVideos(Long userId, Long creationId, boolean confirm) {
        return doGenerate(userId, creationId, confirm, false, null);
    }

    /** 断点续传：保留已完成分段，仅生成剩余段（后端重启恢复用，不重复计费） */
    public Map<String, Object> resumeVideos(Long userId, Long creationId) {
        return doGenerate(userId, creationId, true, true, null);
    }

    /** 单段重创：仅强制重新生成指定步骤片段，其余已完成段复用后重拼（v0.3） */
    public Map<String, Object> regenerateStep(Long userId, Long creationId, Integer stepIndex) {
        return doGenerate(userId, creationId, true, true, Set.of(stepIndex));
    }

    private Map<String, Object> doGenerate(Long userId, Long creationId, boolean confirm, boolean resume,
            Set<Integer> forceSteps) {
        AiCreation creation = chatService.getOwnedCreation(userId, creationId);
        List<Map<String, Object>> steps = CreationJsonUtil.parseSteps(creation.getSteps());
        // 全部步骤都进视频（含 timer 等待步骤：也生成短片并配语音讲解，播放时不丢步骤）
        List<Map<String, Object>> videoSteps = new ArrayList<>(steps);
        if (videoSteps.isEmpty()) {
            throw new IllegalArgumentException("当前菜谱无步骤，无法生成视频");
        }
        int segments = videoSteps.size();
        boolean billable = videoGateway.isBillable();
        // 付费引擎：先返回成本预估，用户确认后才执行（Mock 免费直接生成）
        if (billable && !confirm) {
            return estimate(creationId, segments, defaultSegmentSec);
        }
        if (billable) {
            quotaService.assertCanGenerate(userId, segments, defaultSegmentSec);
        }

        Path dir = Path.of(basePath, "creations", String.valueOf(creationId));
        Path segDir = dir.resolve("segments");

        // 重置既有视频与任务
        AiCreationVideo video = videoRepository.findByCreationId(creationId);
        if (video == null) {
            video = AiCreationVideo.builder()
                    .creationId(creationId).status(AiCreationVideo.STATUS_GENERATING).build();
            videoRepository.insert(video);
        } else {
            video.setStatus(AiCreationVideo.STATUS_GENERATING);
            video.setVideoPath(null);
            video.setDurationSec(null);
            video.setStepsJson(null);
            videoRepository.updateById(video);
        }
        // 断点续传：保留已完成分段（复用不重复计费）；全量重生成：清空旧任务
        List<AiVideoTask> existingTasks = taskRepository.findByCreationId(creationId);
        Map<Integer, AiVideoTask> existingCompleted = new HashMap<>();
        if (resume) {
            for (AiVideoTask t : existingTasks) {
                if (AiVideoTask.STATUS_COMPLETED.equals(t.getStatus())
                        && t.getVideoPath() != null && Files.exists(Path.of(t.getVideoPath()))) {
                    existingCompleted.put(t.getStepIndex(), t);
                }
            }
            log.info("[酿味] 断点续传：检测到已完成分段 {} 个 creationId={}", existingCompleted.size(), creationId);
        } else {
            for (AiVideoTask t : existingTasks) {
                taskRepository.deleteById(t.getId());
            }
        }
        try {
            Files.createDirectories(segDir);
        } catch (Exception e) {
            log.warn("[酿味] 创建视频目录失败: {}", e.getMessage());
        }

        // 逐段生成
        List<Map<String, Object>> timestamps = new ArrayList<>();
        double cursor = 0;
        int newGenerated = 0;
        for (Map<String, Object> s : videoSteps) {
            int stepIndex = ((Number) s.getOrDefault("index", 0)).intValue();
            String prompt = s.get("video_prompt") == null ? "" : (String) s.get("video_prompt");
            // 禁止人声旁白兜底：Wan 易生成人声旁白与 TTS 讲解混音；统一在提交前追加约束
            prompt = appendNoNarration(prompt);
            Path segFile = segDir.resolve("step_" + stepIndex + ".mp4");
            boolean force = forceSteps != null && forceSteps.contains(stepIndex);
            // 断点续传/单段重创：已完成分段（非强制）直接复用（不重新生成、不重复计费）
            if (resume && !force) {
                AiVideoTask reuse = existingCompleted.get(stepIndex);
                if (reuse != null) {
                    double dur = probeDuration(segFile);
                    if (dur <= 0) {
                        dur = reuse.getDurationSec() != null
                                ? reuse.getDurationSec().doubleValue() : defaultSegmentSec;
                    }
                    Map<String, Object> ts = new LinkedHashMap<>();
                    ts.put("index", stepIndex);
                    ts.put("start", round2(cursor));
                    ts.put("end", round2(cursor + dur));
                    ts.put("summary", s.get("summary"));
                    timestamps.add(ts);
                    cursor += dur;
                    log.info("[酿味] 断点续传复用已完成段 creationId={} step={} dur={}", creationId, stepIndex, dur);
                    continue;
                }
            }
            if (resume) {
                // 该步旧任务清理（未完成 或 强制重创），避免任务重复
                for (AiVideoTask old : existingTasks) {
                    if (old.getStepIndex() == stepIndex) {
                        taskRepository.deleteById(old.getId());
                    }
                }
                if (force) {
                    try {
                        Files.deleteIfExists(segFile);
                    } catch (Exception ignored) {
                        // 忽略
                    }
                }
            }
            AiVideoTask task = AiVideoTask.builder()
                    .creationId(creationId).stepIndex(stepIndex)
                    .provider(videoGateway.provider()).prompt(prompt)
                    .status(AiVideoTask.STATUS_QUEUED).progress(0).genRound(1)
                    .build();
            taskRepository.insert(task);
            progressHandler.sendProgress(String.valueOf(creationId), task.getId(), stepIndex, "queued", 0, null);
            newGenerated++;
            try {
                task.setStatus(AiVideoTask.STATUS_RUNNING);
                task.setProgress(10);
                taskRepository.updateById(task);
                progressHandler.sendProgress(String.valueOf(creationId), task.getId(), stepIndex, "running", 10, null);

                // 先合成该步语音讲解（best-effort），以其时长决定视频段长度 → 语音与画面轨迹同步
                int segDurInt = defaultSegmentSec;
                try {
                    double narrationSec = ttsService.synthesizeNarration(creationId, stepIndex, s);
                    if (narrationSec > 0) {
                        segDurInt = Math.max((int) Math.round(narrationSec + 0.8), defaultSegmentSec);
                    }
                } catch (Exception e) {
                    log.warn("[酿味] 步骤讲解合成失败 step={}: {}", stepIndex, e.getMessage());
                }
                double dur;
                // 缓存复用：相同 prompt 已生成过则直接复用片段（省成本）
                AiVideoTask cached = promptCache(prompt);
                if (cached != null) {
                    Files.copy(Path.of(cached.getVideoPath()), segFile,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    // 缓存片段同样以实际文件时长为准（历史缓存 durationSec 可能为写死默认值，导致时间戳漂移）
                    dur = probeDuration(segFile);
                    if (dur <= 0) {
                        dur = cached.getDurationSec() != null
                                ? cached.getDurationSec().doubleValue() : segDurInt;
                    }
                    log.info("[酿味] 命中片段缓存 step={} cachedTaskId={}", stepIndex, cached.getId());
                } else {
                    String label = "步骤" + stepIndex + " "
                            + (s.get("summary") == null ? "" : (String) s.get("summary"));
                    VideoGenTaskRef ref = videoGateway.submit(prompt, segDurInt, label);
                    task.setTaskRef(ref.taskRef());
                    taskRepository.updateById(task);
                    VideoGenResult res = videoGateway.poll(ref.taskRef());
                    videoGateway.download(res.resultUrl(), segFile.toString());
                    // 段长以实际文件探测为准：真实引擎（如 Wan）产出时长可能与请求不一致，
                    // 若沿用写死默认值会导致步骤时间戳漂移（Mock 网关已探测，Wan 网关补此兜底）
                    dur = probeDuration(segFile);
                    if (dur <= 0) {
                        dur = res.durationSec() > 0 ? res.durationSec() : segDurInt;
                    }
                }
                task.setStatus(AiVideoTask.STATUS_COMPLETED);
                task.setProgress(100);
                task.setVideoPath(segFile.toString());
                task.setDurationSec(BigDecimal.valueOf(dur));
                task.setCompletedAt(LocalDateTime.now());
                taskRepository.updateById(task);

                Map<String, Object> ts = new LinkedHashMap<>();
                ts.put("index", stepIndex);
                ts.put("start", round2(cursor));
                ts.put("end", round2(cursor + dur));
                ts.put("summary", s.get("summary"));
                timestamps.add(ts);
                cursor += dur;
                progressHandler.sendProgress(String.valueOf(creationId), task.getId(), stepIndex, "completed", 100, dur);
            } catch (Exception e) {
                log.error("[酿味] 片段生成失败 creationId={} step={}: {}", creationId, stepIndex, e.getMessage());
                task.setStatus(AiVideoTask.STATUS_FAILED);
                task.setErrorMessage(e.getMessage());
                taskRepository.updateById(task);
                progressHandler.sendProgress(String.valueOf(creationId), task.getId(), stepIndex, "failed", 100, null);
            }
        }

        if (timestamps.isEmpty()) {
            video.setStatus(AiCreationVideo.STATUS_FAILED);
            videoRepository.updateById(video);
            throw new IllegalArgumentException("所有片段生成失败，请重试");
        }

        // FFmpeg concat 拼接
        Path outVideo = dir.resolve("video.mp4");
        try {
            concat(segDir, timestamps, outVideo);
        } catch (Exception e) {
            video.setStatus(AiCreationVideo.STATUS_FAILED);
            video.setErrorMessage(e.getMessage());
            videoRepository.updateById(video);
            throw new IllegalArgumentException("视频拼接失败：" + e.getMessage());
        }

        video.setVideoPath(outVideo.toString());
        video.setDurationSec(BigDecimal.valueOf(round2(cursor)));
        video.setStepsJson(CreationJsonUtil.toJson(timestamps));
        video.setStatus(AiCreationVideo.STATUS_READY);
        videoRepository.updateById(video);

        // 按秒计费（仅真实引擎；mock 免费）——断点续传只记录本次新生成的段，不重复计费
        if (billable) {
            int billed = resume ? newGenerated : segments;
            if (billed > 0) {
                quotaService.record(userId, creationId, billed, defaultSegmentSec);
            }
        }

        // 会话状态推进 RECIPE_READY→VIDEO_GEN→VIDEO_READY（best-effort）
        updateSessionStatus(userId, creation);

        // 语音讲解：视频就绪后后台自动合成（TTS 0.8 元/万字符，成本极低；不阻塞视频生成返回）
        CompletableFuture.runAsync(() -> {
            try {
                ttsService.generateNarrations(userId, creationId);
            } catch (Exception e) {
                log.warn("[酿味] 自动生成语音讲解失败 creationId={}: {}", creationId, e.getMessage());
            }
        });

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("videoId", video.getId());
        r.put("creationId", creationId);
        r.put("status", video.getStatus());
        r.put("durationSec", video.getDurationSec());
        r.put("steps", timestamps);
        return r;
    }

    /** 成本预估（付费引擎确认前返回） */
    private Map<String, Object> estimate(Long creationId, int segments, int segmentSec) {
        long seconds = (long) segments * segmentSec;
        double cost = seconds * costPerSecondUnit;
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("needConfirm", true);
        r.put("creationId", creationId);
        r.put("segments", segments);
        r.put("seconds", seconds);
        r.put("perSecondUnit", costPerSecondUnit);
        r.put("estimatedCost", round2(cost));
        return r;
    }

    /** 相同 prompt 已生成完成的片段缓存命中 */
    private AiVideoTask promptCache(String prompt) {
        if (prompt == null || prompt.isBlank()) return null;
        AiVideoTask cached = taskRepository.findCachedByPrompt(prompt);
        if (cached != null && cached.getVideoPath() != null
                && Files.exists(Path.of(cached.getVideoPath()))) {
            return cached;
        }
        return null;
    }

    /** ffprobe 探测片段真实时长（秒），失败返回 0（真实引擎产出时长可能与请求不一致） */
    private double probeDuration(Path file) {
        try {
            String probe = (ffmpegPath == null || ffmpegPath.isBlank())
                    ? "ffprobe" : ffmpegPath.replace("ffmpeg", "ffprobe");
            ProcessBuilder pb = new ProcessBuilder(probe, "-v", "error",
                    "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1", file.toString());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean ok = p.waitFor(15, TimeUnit.SECONDS);
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line = r.readLine();
                if (ok && line != null) {
                    double d = Double.parseDouble(line.trim());
                    return d > 0 ? d : 0;
                }
            }
        } catch (Exception e) {
            log.warn("[CreationVideoService] probe 失败 {}: {}", file, e.getMessage());
        }
        return 0;
    }

    private void concat(Path segDir, List<Map<String, Object>> timestamps, Path outVideo) throws Exception {
        Path dir = segDir.getParent();
        Path listFile = dir.resolve("concat.txt");
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> ts : timestamps) {
            lines.add("file 'segments/step_" + ts.get("index") + ".mp4'");
        }
        Files.write(listFile, lines, StandardCharsets.UTF_8);
        ProcessBuilder pb = new ProcessBuilder(ffmpegPath, "-y",
                "-f", "concat", "-safe", "0",
                "-i", "concat.txt",
                "-c", "copy", "-movflags", "+faststart",
                outVideo.toString());
        pb.directory(dir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) log.debug("[酿味-拼接] {}", line);
        }
        if (!p.waitFor(120, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new RuntimeException("ffmpeg concat 退出码 " + p.exitValue());
        }
    }

    /**
     * 追加禁止人声旁白约束：Wan 生成视频易带人声旁白/解说，会与 TTS 同步讲解混音；
     * 统一在提交前追加，保证生成的视频无人说话（已含禁人声关键词则不重复追加）。
     */
    static String appendNoNarration(String prompt) {
        if (prompt == null || prompt.isBlank()) return prompt;
        if (prompt.contains("无声") || prompt.contains("旁白") || prompt.contains("无人说话")
                || prompt.contains("禁止人声") || prompt.contains("无对白") || prompt.contains("无人声")) {
            return prompt;
        }
        return prompt + "。画面全程静默：禁止任何人说话，无人声旁白、无解说、无对白，仅烹饪画面与操作。";
    }

    /**
     * 解析指定步骤的视频分段文件（鉴权：属主 + 视频就绪）。供单段预览流式播放。
     */
    public Path resolveSegmentFile(Long userId, Long creationId, int stepIndex) {
        chatService.getOwnedCreation(userId, creationId);
        AiCreationVideo v = videoRepository.findByCreationId(creationId);
        if (v == null || !AiCreationVideo.STATUS_READY.equals(v.getStatus())) {
            throw new IllegalArgumentException("该创作视频未就绪，无法预览分段");
        }
        Path seg = Path.of(basePath, "creations", String.valueOf(creationId),
                "segments", "step_" + stepIndex + ".mp4");
        if (!Files.exists(seg)) {
            throw new IllegalArgumentException("步骤 " + stepIndex + " 视频分段不存在");
        }
        return seg;
    }

    /** 生成开始时将会话置为「视频生成中」（VIDEO_GEN），保证列表徽标在生成期间正确显示 */
    private void markSessionVideoGen(Long userId, AiCreation creation) {
        Long sid = creation.getSessionId();
        if (sid == null) return;
        try {
            AiCreationSession s = sessionService.getOwnedSession(userId, sid);
            if (AiCreationSession.STATUS_RECIPE_READY.equals(s.getStatus())
                    || AiCreationSession.STATUS_CONFIRMED.equals(s.getStatus())
                    || AiCreationSession.STATUS_VIDEO_READY.equals(s.getStatus())) {
                sessionService.updateStatus(userId, sid, AiCreationSession.STATUS_VIDEO_GEN);
            }
        } catch (Exception e) {
            log.warn("[酿味] 会话标记视频生成中失败 sid={}: {}", sid, e.getMessage());
        }
    }

    private void updateSessionStatus(Long userId, AiCreation creation) {
        Long sid = creation.getSessionId();
        if (sid == null) return;
        try {
            AiCreationSession s = sessionService.getOwnedSession(userId, sid);
            if (AiCreationSession.STATUS_RECIPE_READY.equals(s.getStatus())
                    || AiCreationSession.STATUS_CONFIRMED.equals(s.getStatus())
                    || AiCreationSession.STATUS_VIDEO_READY.equals(s.getStatus())) {
                sessionService.updateStatus(userId, sid, AiCreationSession.STATUS_VIDEO_GEN);
            }
            if (AiCreationSession.STATUS_VIDEO_GEN.equals(s.getStatus())) {
                sessionService.updateStatus(userId, sid, AiCreationSession.STATUS_VIDEO_READY);
            }
        } catch (Exception e) {
            log.warn("[酿味] 会话状态推进失败 sid={}: {}", sid, e.getMessage());
        }
    }

    /** 视频生成进度（任务列表 + 视频状态） */
    public Map<String, Object> videoStatus(Long userId, Long creationId) {
        chatService.getOwnedCreation(userId, creationId);
        List<Map<String, Object>> tasks = new ArrayList<>();
        for (AiVideoTask t : taskRepository.findByCreationId(creationId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("taskId", t.getId());
            m.put("stepIndex", t.getStepIndex());
            m.put("provider", t.getProvider());
            m.put("status", t.getStatus());
            m.put("progress", t.getProgress());
            m.put("durationSec", t.getDurationSec());
            m.put("errorMessage", t.getErrorMessage());
            tasks.add(m);
        }
        AiCreationVideo v = videoRepository.findByCreationId(creationId);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("creationId", creationId);
        r.put("videoStatus", v == null ? null : v.getStatus());
        r.put("durationSec", v == null ? null : v.getDurationSec());
        r.put("steps", v == null ? null : CreationJsonUtil.parseSteps(v.getStepsJson()));
        r.put("videoPath", v == null ? null : v.getVideoPath());
        r.put("tasks", tasks);
        return r;
    }

    /** 返回拼接视频本地路径（播放鉴权由 controller 处理） */
    public Path resolveVideoFile(Long userId, Long creationId) {
        chatService.getOwnedCreation(userId, creationId);
        AiCreationVideo v = videoRepository.findByCreationId(creationId);
        if (v == null || v.getVideoPath() == null || !AiCreationVideo.STATUS_READY.equals(v.getStatus())) {
            throw new IllegalArgumentException("视频尚未生成");
        }
        Path p = Path.of(v.getVideoPath());
        if (!Files.exists(p)) {
            throw new IllegalArgumentException("视频文件缺失");
        }
        return p;
    }

    private int timerOf(Map<String, Object> s) {
        Object t = s.get("timer_min");
        return t == null ? 0 : ((Number) t).intValue();
    }

    private double round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
