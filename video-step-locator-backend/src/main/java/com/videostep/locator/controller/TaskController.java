package com.videostep.locator.controller;

import com.videostep.locator.dto.response.ApiResponse;
import com.videostep.locator.dto.response.TaskResponse;
import com.videostep.locator.entity.ProcessingLog;
import com.videostep.locator.entity.ProcessingTask;
import com.videostep.locator.entity.Video;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.videostep.locator.entity.User;
import com.videostep.locator.repository.ProcessingTaskRepository;
import com.videostep.locator.repository.VideoRepository;
import com.videostep.locator.service.ProcessingLogService;
import com.videostep.locator.service.TaskSchedulerService;
import com.videostep.locator.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class TaskController {

    private final ProcessingTaskRepository taskRepository;
    private final VideoRepository videoRepository;
    private final TaskSchedulerService taskSchedulerService;
    private final ProcessingLogService logService;
    private final UserService userService;
    private final com.videostep.locator.service.OrgAccessService orgAccessService;
    private final com.videostep.locator.service.QuotaService quotaService;

    /** 操作者是否为管理员 */
    private boolean isAdmin(Long userId) {
        User u = userService.getById(userId);
        return u != null && "ADMIN".equals(u.getRole());
    }

    /** 校验操作权限：管理员 / 视频属主 / 视频所属企业成员 */
    private void checkManage(Long operatorId, Long videoId) {
        if (isAdmin(operatorId)) return;
        Video v = videoRepository.selectById(videoId);
        if (v == null || !canManage(operatorId, v)) {
            throw new SecurityException("无权操作该任务");
        }
    }

    /** 是否可管理：属主，或视频归属企业的在职成员（B 端企业协作） */
    private boolean canManage(Long operatorId, Video v) {
        if (operatorId.equals(v.getUserId())) return true;
        if (v.getOrgId() != null) return orgAccessService.isMember(operatorId, v.getOrgId());
        return false;
    }

    @PostMapping("/videos/{videoId}/process")
    public ApiResponse<Map<String, Object>> triggerProcess(
            @RequestAttribute("userId") Long userId,
            @PathVariable Long videoId) {
        Video video = videoRepository.selectById(videoId);
        if (video == null) return ApiResponse.error(404, "视频不存在");
        if (!canManage(userId, video)) return ApiResponse.error(403, "无权操作");
        if (video.getOrgId() != null) {
            try {
                quotaService.checkTaskQuota(video.getOrgId());
            } catch (IllegalArgumentException e) {
                return ApiResponse.error(400, e.getMessage());
            }
        }
        List<ProcessingTask> existing = taskRepository.findByVideoIdOrderByCreatedAtDesc(videoId);
        for (ProcessingTask t : existing) {
            if (t.getStatus().equals(ProcessingTask.STATUS_QUEUED)
                    || t.getStatus().equals(ProcessingTask.STATUS_RUNNING))
                return ApiResponse.error(400, "该视频已有正在处理的任务");
        }
        taskSchedulerService.createAndExecuteTask(video);
        return ApiResponse.success(Map.of("videoId", videoId, "message", "已开始处理"));
    }

    @PostMapping("/tasks/{id}/start")
    public ApiResponse<Map<String, Object>> startTask(
            @RequestAttribute("userId") Long userId,
            @PathVariable Long id) {
        ProcessingTask task = taskRepository.selectById(id);
        if (task == null) return ApiResponse.error(404, "任务不存在");
        checkManage(userId, task.getVideoId());
        if (!task.getStatus().equals(ProcessingTask.STATUS_PAUSED)
                && !task.getStatus().equals(ProcessingTask.STATUS_FAILED))
            return ApiResponse.error(400, "只能启动已暂停或失败的任务");
        taskSchedulerService.retryTask(id);
        return ApiResponse.success(Map.of("message", "任务已启动"));
    }

    @PostMapping("/tasks/{id}/pause")
    public ApiResponse<Map<String, Object>> pauseTask(
            @RequestAttribute("userId") Long userId,
            @PathVariable Long id) {
        ProcessingTask task = taskRepository.selectById(id);
        if (task == null) return ApiResponse.error(404, "任务不存在");
        checkManage(userId, task.getVideoId());
        if (!task.getStatus().equals(ProcessingTask.STATUS_RUNNING))
            return ApiResponse.error(400, "只能暂停运行中的任务");
        taskSchedulerService.pauseTask(id);
        logService.warn(id, "用户暂停了任务");
        return ApiResponse.success(Map.of("message", "任务已暂停"));
    }

    @DeleteMapping("/tasks/{id}")
    public ApiResponse<Map<String, Object>> deleteTask(
            @RequestAttribute("userId") Long userId,
            @PathVariable Long id) {
        ProcessingTask task = taskRepository.selectById(id);
        if (task == null) return ApiResponse.error(404, "任务不存在");
        checkManage(userId, task.getVideoId());
        taskRepository.deleteById(id);
        return ApiResponse.success(Map.of("message", "任务已删除"));
    }

    /** 任务列表：管理员看全部，普通用户只看自己视频的任务；支持分页/筛选/排序 */
    @GetMapping("/tasks")
    public ApiResponse<Map<String, Object>> getTasks(
            @RequestAttribute("userId") Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String order) {
        boolean admin = isAdmin(userId);
        List<ProcessingTask> tasks;
        Long orgId = orgAccessService.effectiveOrgId(userId);
        if (admin) {
            tasks = taskRepository.selectList(null);
        } else if (orgId != null) {
            // B 端企业协作：企业成员看到整个企业的处理任务
            tasks = taskRepository.selectList(
                    new LambdaQueryWrapper<ProcessingTask>().eq(ProcessingTask::getOrgId, orgId));
        } else {
            List<Video> myVideos = videoRepository.selectList(
                    new LambdaQueryWrapper<Video>().eq(Video::getUserId, userId).isNull(Video::getOrgId));
            if (myVideos.isEmpty()) {
                tasks = new ArrayList<>();
            } else {
                List<Long> videoIds = myVideos.stream().map(Video::getId).toList();
                tasks = taskRepository.selectList(
                        new LambdaQueryWrapper<ProcessingTask>()
                                .in(ProcessingTask::getVideoId, videoIds));
            }
        }

        // 一个视频只显示一条任务记录：按 videoId 去重，保留最近创建的一条（id 大的最新），再按状态/关键字过滤
        tasks.sort(Comparator.comparing(ProcessingTask::getId, Comparator.reverseOrder()));
        // 批量预取视频（消除 toResponse 逐条 selectById 的 N+1）
        Map<Long, Video> videoMap = fetchVideos(tasks);
        List<TaskResponse> deduped = new ArrayList<>();
        Set<Long> seenVideoIds = new HashSet<>();
        for (ProcessingTask t : tasks) {
            if (!seenVideoIds.add(t.getVideoId())) continue;
            TaskResponse r = toResponse(t, videoMap);
            if (status != null && !status.isBlank() && !status.equals(r.getStatus())) continue;
            if (keyword != null && !keyword.isBlank()) {
                String title = r.getVideoTitle();
                if (title == null || !title.toLowerCase().contains(keyword.toLowerCase())) continue;
            }
            deduped.add(r);
        }

        // 排序（在内存列表上）
        boolean asc = "asc".equalsIgnoreCase(order);
        Comparator<TaskResponse> cmp = Comparator.comparing(TaskResponse::getCreatedAt,
                Comparator.nullsLast(java.time.LocalDateTime::compareTo));
        if (sortBy != null && !sortBy.isBlank()) {
            switch (sortBy) {
                case "title" -> cmp = Comparator.comparing(
                        r -> r.getVideoTitle() == null ? "" : r.getVideoTitle(),
                        String.CASE_INSENSITIVE_ORDER);
                case "progress" -> cmp = Comparator.comparing(TaskResponse::getProgress,
                        Comparator.nullsLast(Integer::compareTo));
                case "status" -> cmp = Comparator.comparing(TaskResponse::getStatus,
                        Comparator.nullsLast(String::compareTo));
                case "completedAt" -> cmp = Comparator.comparing(TaskResponse::getCompletedAt,
                        Comparator.nullsLast(java.time.LocalDateTime::compareTo));
                default -> cmp = Comparator.comparing(TaskResponse::getCreatedAt,
                        Comparator.nullsLast(java.time.LocalDateTime::compareTo));
            }
        }
        deduped.sort(asc ? cmp : cmp.reversed());

        // 分页
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        int total = deduped.size();
        int from = Math.min(safePage * safeSize, total);
        int to = Math.min(from + safeSize, total);
        List<TaskResponse> items = deduped.subList(from, to);
        return ApiResponse.success(Map.of("items", items, "total", (long) total));
    }

    @GetMapping("/tasks/{id}")
    public ApiResponse<TaskResponse> getTask(@PathVariable Long id) {
        ProcessingTask task = taskRepository.selectById(id);
        if (task == null) return ApiResponse.error(404, "任务不存在");
        return ApiResponse.success(toResponse(task));
    }

    @GetMapping("/tasks/{id}/logs")
    public ApiResponse<List<Map<String, Object>>> getTaskLogs(@PathVariable Long id) {
        List<ProcessingLog> logs = logService.getTaskLogs(id);
        List<Map<String, Object>> result = logs.stream().<Map<String, Object>>map(log -> {
            Map<String, Object> m = new java.util.HashMap<>();
            m.put("id", log.getId());
            m.put("level", log.getLevel());
            m.put("message", log.getMessage());
            m.put("createdAt", log.getCreatedAt());
            return m;
        }).collect(Collectors.toList());
        return ApiResponse.success(result);
    }

    @PostMapping("/tasks/{id}/retry")
    public ApiResponse<Map<String, Object>> retryTask(
            @RequestAttribute("userId") Long userId,
            @PathVariable Long id) {
        ProcessingTask task = taskRepository.selectById(id);
        if (task == null) return ApiResponse.error(404, "任务不存在");
        checkManage(userId, task.getVideoId());
        taskSchedulerService.retryTask(id);
        return ApiResponse.success(Map.of("taskId", id, "message", "任务已重新加入队列"));
    }

    @PostMapping("/tasks/{id}/cancel")
    public ApiResponse<Map<String, Object>> cancelTask(
            @RequestAttribute("userId") Long userId,
            @PathVariable Long id) {
        ProcessingTask task = taskRepository.selectById(id);
        if (task == null) return ApiResponse.error(404, "任务不存在");
        checkManage(userId, task.getVideoId());
        taskSchedulerService.cancelTask(id);
        return ApiResponse.success(Map.of("message", "任务已取消"));
    }

    @GetMapping("/videos/{videoId}/tasks")
    public ApiResponse<List<TaskResponse>> getVideoTasks(@PathVariable Long videoId) {
        return ApiResponse.success(taskRepository.findByVideoIdOrderByCreatedAtDesc(videoId)
                .stream().map(this::toResponse).collect(Collectors.toList()));
    }

    private TaskResponse toResponse(ProcessingTask task) {
        return toResponse(task, null);
    }

    /** toResponse（支持批量预取视频，消除逐条 selectById 的 N+1） */
    private TaskResponse toResponse(ProcessingTask task, Map<Long, Video> videoMap) {
        Video video = videoMap != null ? videoMap.get(task.getVideoId()) : videoRepository.selectById(task.getVideoId());
        return TaskResponse.builder()
                .id(task.getId()).videoId(task.getVideoId())
                .userId(video != null ? video.getUserId() : null)
                .videoTitle(video != null ? video.getTitle() : null)
                .taskType(task.getTaskType()).status(task.getStatus())
                .progress(task.getProgress()).currentPhase(task.getCurrentPhase())
                .errorMessage(task.getErrorMessage()).retryCount(task.getRetryCount())
                .createdAt(task.getCreatedAt()).startedAt(task.getStartedAt())
                .completedAt(task.getCompletedAt()).build();
    }

    /** 批量预取视频（避免逐条 selectById 的 N+1）；返回 HashMap（get(null) 安全） */
    private Map<Long, Video> fetchVideos(List<ProcessingTask> tasks) {
        Set<Long> ids = tasks.stream()
                .map(ProcessingTask::getVideoId).filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) return new java.util.HashMap<>();
        return videoRepository.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Video::getId, v -> v));
    }
}
