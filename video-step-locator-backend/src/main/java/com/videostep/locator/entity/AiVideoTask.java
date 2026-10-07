package com.videostep.locator.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 酿味视频生成任务表（每步骤一条，P2）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("ai_video_tasks")
public class AiVideoTask {

    public static final String STATUS_QUEUED = "queued";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long creationId;
    private Integer stepIndex;
    /** wan | seedance | mock */
    private String provider;
    /** 该步骤 video_prompt */
    private String prompt;
    private String status;
    private Integer progress;
    /** 厂商侧任务 ID */
    private String taskRef;
    /** 生成片段本地路径 */
    private String videoPath;
    /** 片段时长（秒） */
    private BigDecimal durationSec;
    private String errorMessage;
    private Integer retryCount;
    /** 重创轮次 */
    private Integer genRound;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
}
