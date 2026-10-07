package com.videostep.locator.service;

import com.videostep.locator.entity.ProcessingLog;
import com.videostep.locator.repository.ProcessingLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProcessingLogService {

    private final ProcessingLogRepository logRepository;

    public void info(Long taskId, String message) {
        insert(taskId, "INFO", message);
    }

    public void warn(Long taskId, String message) {
        insert(taskId, "WARN", message);
    }

    public void error(Long taskId, String message) {
        insert(taskId, "ERROR", message);
    }

    private void insert(Long taskId, String level, String message) {
        ProcessingLog logEntry = ProcessingLog.builder()
                .taskId(taskId)
                .level(level)
                .message(message)
                .build();
        logRepository.insert(logEntry);
    }

    public List<ProcessingLog> getTaskLogs(Long taskId) {
        return logRepository.findByTaskIdOrderByCreatedAtAsc(taskId);
    }
}
