package com.videostep.locator.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("processing_tasks")
public class ProcessingTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long videoId;
    private Long orgId;
    private String taskType;
    private String status;
    private Integer progress;
    private String currentPhase;
    private String errorMessage;
    private Integer retryCount;
    private Integer maxRetries;
    private String workerId;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;

    public static final String STATUS_QUEUED = "queued";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_PAUSED = "paused";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";
}