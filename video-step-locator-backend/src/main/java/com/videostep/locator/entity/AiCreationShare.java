package com.videostep.locator.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 酿味创作分享表（站外只读分享，P4）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("ai_creation_shares")
public class AiCreationShare {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private Long creationId;
    private Long createdBy;
    private LocalDateTime expiresAt;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
