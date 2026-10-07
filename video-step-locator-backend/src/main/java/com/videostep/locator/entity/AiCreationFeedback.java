package com.videostep.locator.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 酿味口味反馈表（驱动记忆进化；写回 ingestBehavior 留 P1）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("ai_creation_feedbacks")
public class AiCreationFeedback {

    public static final String RATING_DELICIOUS = "delicious";
    public static final String RATING_OK = "ok";
    public static final String RATING_FAILED = "failed";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long creationId;
    private Long userId;
    /** delicious | ok | failed */
    private String rating;
    private String comment;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
