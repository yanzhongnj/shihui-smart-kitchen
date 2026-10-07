package com.videostep.locator.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("video_steps")
public class VideoStep {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long videoId;
    private Integer stepIndex;
    private String summary;
    private BigDecimal startTime;
    private BigDecimal endTime;
    private String originalText;
    private String confidence;
    private Integer loopCount;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    public static final String CONFIDENCE_HIGH = "high";
    public static final String CONFIDENCE_MEDIUM = "medium";
    public static final String CONFIDENCE_LOW = "low";
}