package com.videostep.locator.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 酿味文生图任务表（酱汁效果图，单张整菜；仿 ai_video_tasks，无 step_index，加 user_id/style/cost）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("ai_image_tasks")
public class AiImageTask {

    public static final String STATUS_QUEUED = "queued";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long creationId;
    private Long userId;
    /** wan_image | mock */
    private String provider;
    /** 文生图提示词 */
    private String prompt;
    /** realistic | plated | illustration */
    private String style;
    private String status;
    private Integer progress;
    /** 厂商侧任务 ID */
    private String taskRef;
    /** 结果图片相对路径（effect/...） */
    private String imagePath;
    /** 本次消耗（分） */
    private Integer cost;
    private String errorMessage;
    private Integer retryCount;
    /** 重生成轮次 */
    private Integer genRound;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
}
