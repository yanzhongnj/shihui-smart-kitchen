package com.videostep.locator.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 酿味创作视频表（拼接产物 + 步骤时间戳，P2）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("ai_creation_videos")
public class AiCreationVideo {

    public static final String STATUS_GENERATING = "generating";
    public static final String STATUS_READY = "ready";
    public static final String STATUS_FAILED = "failed";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long creationId;
    /** 拼接后完整视频 */
    private String videoPath;
    /** 总时长（秒） */
    private BigDecimal durationSec;
    /** 含每步 start/end 时间戳 JSON */
    private String stepsJson;
    private String status;
    private String errorMessage;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
