package com.videostep.locator.service;

import com.videostep.locator.entity.ProcessingTask;
import com.videostep.locator.entity.Video;
import com.videostep.locator.repository.ProcessingTaskRepository;
import com.videostep.locator.repository.VideoRepository;
import com.videostep.locator.service.pipeline.PipelineProgressParser;
import com.videostep.locator.service.pipeline.PythonPipelineRunner;
import com.videostep.locator.service.pipeline.StepResultParser;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class TaskSchedulerService {

    private final VideoRepository videoRepository;
    private final ProcessingTaskRepository taskRepository;
    private final PythonPipelineRunner pipelineRunner;
    private final PipelineProgressParser progressParser;
    private final StepResultParser stepResultParser;
    private final com.videostep.locator.service.pipeline.RecipeResultParser recipeResultParser;
    private final FileStorageService fileStorageService;
    private final ProcessingLogService logService;

    @Qualifier("pipelineExecutor")
    private final ThreadPoolTaskExecutor pipelineExecutor;

    @Value("${video.processing.max-concurrent}")
    private int maxConcurrent;

    @Value("${video.processing.output-dir}")
    private String outputDir;

    private final String workerId = UUID.randomUUID().toString().substring(0, 8);

    @Scheduled(fixedRate = 30000)
    public void scanAndSchedule() {
        List<Video> pendingVideos = videoRepository.findByStatus(Video.STATUS_PENDING);

        for (Video video : pendingVideos) {
            long runningCount = taskRepository.countByStatus(ProcessingTask.STATUS_RUNNING);
            if (runningCount >= maxConcurrent) {
                log.debug("Max concurrent tasks reached ({}/{}), skipping",
                        runningCount, maxConcurrent);
                return;
            }

            createAndExecuteTask(video);
        }
    }

    public void createAndExecuteTask(Video video) {
        // 保证一个视频同时只有一个活跃任务（排队/运行中），避免同一视频重复创建任务
        for (ProcessingTask t : taskRepository.findByVideoIdOrderByCreatedAtDesc(video.getId())) {
            if (ProcessingTask.STATUS_QUEUED.equals(t.getStatus())
                    || ProcessingTask.STATUS_RUNNING.equals(t.getStatus())) {
                log.info("Video {} already has active task {}, skip creating duplicate",
                        video.getId(), t.getId());
                return;
            }
        }

        ProcessingTask task = ProcessingTask.builder()
                .videoId(video.getId())
                .orgId(video.getOrgId())
                .taskType("pipeline")
                .status(ProcessingTask.STATUS_QUEUED)
                .progress(0)
                .maxRetries(3)
                .workerId(workerId)
                .build();
        taskRepository.insert(task);
        logService.info(task.getId(), "任务已创建，等待执行");

        video.setStatus(Video.STATUS_PROCESSING);
        videoRepository.updateById(video);

        executeTask(task);
    }

    private void executeTask(ProcessingTask task) {
        Video video = videoRepository.selectById(task.getVideoId());
        Long taskId = task.getId();

        logService.info(taskId, "开始执行处理流水线");

        CompletableFuture<Void> future = pipelineRunner.runPipeline(
                video.getId(),
                video.getFilePath(),
                outputDir,
                progress -> {
                    progressParser.updateProgress(taskId,
                            progress.getProgress(), progress.getPhase());
                    logService.info(taskId, "进度: " + progress.getProgress() + "% - " + progress.getPhase());
                }
        ).thenAccept(result -> {
            stepResultParser.saveSteps(video.getId(), result);
            recipeResultParser.saveRecipe(video.getId(), video.getFilePath(), outputDir);
            progressParser.updateProgress(taskId, 100, "处理完成");
            logService.info(taskId, "处理完成，共保存 " + (result.getSteps() != null ? result.getSteps().size() : 0) + " 个步骤");
            log.info("Video {} processed successfully", video.getId());
        }).exceptionally(e -> {
            // 任务若已被暂停或取消，保持该状态，不再覆盖为失败
            if (isTaskStoppedByUser(taskId)) {
                log.info("Task {} skipped failure marking (paused/cancelled by user)", taskId);
                return null;
            }
            progressParser.markFailed(taskId, e.getMessage());
            video.setStatus(Video.STATUS_FAILED);
            videoRepository.updateById(video);
            logService.error(taskId, "处理失败: " + e.getMessage());
            log.error("Video {} processing failed: {}", video.getId(), e.getMessage());
            return null;
        });

        pipelineExecutor.submit(() -> {
            try {
                future.get(30, TimeUnit.MINUTES);
            } catch (Exception e) {
                if (isTaskStoppedByUser(taskId)) {
                    return; // 已暂停/取消
                }
                log.error("Task {} timed out", taskId);
                progressParser.markFailed(taskId, "处理超时（30分钟）");
                video.setStatus(Video.STATUS_FAILED);
                videoRepository.updateById(video);
                logService.error(taskId, "处理超时");
            }
        });
    }

    /** 判断任务是否已被用户暂停或取消（此时不应再标记为失败） */
    private boolean isTaskStoppedByUser(Long taskId) {
        ProcessingTask current = taskRepository.selectById(taskId);
        if (current == null) return false;
        String status = current.getStatus();
        return !ProcessingTask.STATUS_RUNNING.equals(status)
                && !ProcessingTask.STATUS_QUEUED.equals(status);
    }

    public void pauseTask(Long taskId) {
        ProcessingTask task = taskRepository.selectById(taskId);
        if (task != null && (task.getStatus().equals(ProcessingTask.STATUS_RUNNING)
                || task.getStatus().equals(ProcessingTask.STATUS_QUEUED))) {
            task.setStatus(ProcessingTask.STATUS_PAUSED);
            taskRepository.updateById(task);
            logService.warn(taskId, "任务已被用户暂停");
            // 真正终止流水线进程，避免后台继续运行
            Video video = videoRepository.selectById(task.getVideoId());
            if (video != null) {
                pipelineRunner.cancel(video.getId());
                video.setStatus(Video.STATUS_UPLOADED); // 回到待处理，可重新开始处理
                videoRepository.updateById(video);
            }
        }
    }

    public void cancelTask(Long taskId) {
        ProcessingTask task = taskRepository.selectById(taskId);
        if (task != null) {
            task.setStatus(ProcessingTask.STATUS_FAILED);
            task.setErrorMessage("用户取消了任务");
            taskRepository.updateById(task);
            logService.warn(taskId, "用户取消了任务");
            // 真正终止流水线进程
            Video video = videoRepository.selectById(task.getVideoId());
            if (video != null) {
                pipelineRunner.cancel(video.getId());
                // 视频仍可正常查看，状态回到待处理，便于重新发起处理
                video.setStatus(Video.STATUS_UPLOADED);
                videoRepository.updateById(video);
            }
        }
    }

    public void retryTask(Long taskId) {
        ProcessingTask task = taskRepository.selectById(taskId);
        if (task == null) {
            throw new RuntimeException("Task not found: " + taskId);
        }

        // 正在处理中（排队/运行）的任务不允许重复启动，避免同一任务并发跑两条流水线
        if (ProcessingTask.STATUS_QUEUED.equals(task.getStatus())
                || ProcessingTask.STATUS_RUNNING.equals(task.getStatus())) {
            throw new RuntimeException("任务正在处理中，不能重复启动");
        }
        if (task.getRetryCount() >= task.getMaxRetries()) {
            throw new RuntimeException("Max retries reached for task " + taskId);
        }

        task.setRetryCount(task.getRetryCount() + 1);
        task.setStatus(ProcessingTask.STATUS_QUEUED);
        task.setProgress(0);
        task.setErrorMessage(null);
        task.setCurrentPhase(null);
        task.setStartedAt(null);
        task.setCompletedAt(null);
        taskRepository.updateById(task);

        logService.info(taskId, "任务已重新启动 (重试 " + task.getRetryCount() + "/" + task.getMaxRetries() + ")");

        Video video = videoRepository.selectById(task.getVideoId());
        video.setStatus(Video.STATUS_PROCESSING);
        videoRepository.updateById(video);

        // 直接复用当前任务重新执行，不再交给调度器新建重复任务
        executeTask(task);
    }

    @PreDestroy
    public void shutdown() {
        pipelineExecutor.shutdown();
    }
}
