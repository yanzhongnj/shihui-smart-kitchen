package com.videostep.locator.service.pipeline;

import com.videostep.locator.entity.ProcessingTask;
import com.videostep.locator.repository.ProcessingTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class PipelineProgressParser {

    private final ProcessingTaskRepository taskRepository;

    public void updateProgress(Long taskId, int progress, String phase) {
        ProcessingTask task = taskRepository.selectById(taskId);
        if (task == null) return;
        task.setProgress(progress);
        task.setCurrentPhase(phase);
        if (progress >= 100) {
            task.setStatus(ProcessingTask.STATUS_COMPLETED);
            task.setCompletedAt(LocalDateTime.now());
        } else if (ProcessingTask.STATUS_QUEUED.equals(task.getStatus())) {
            task.setStatus(ProcessingTask.STATUS_RUNNING);
            task.setStartedAt(LocalDateTime.now());
        }
        taskRepository.updateById(task);
        log.debug("Task {} progress: {}% - {}", taskId, progress, phase);
    }

    public void markFailed(Long taskId, String errorMessage) {
        ProcessingTask task = taskRepository.selectById(taskId);
        if (task == null) return;
        task.setStatus(ProcessingTask.STATUS_FAILED);
        task.setErrorMessage(errorMessage);
        task.setCompletedAt(LocalDateTime.now());
        taskRepository.updateById(task);
    }
}
