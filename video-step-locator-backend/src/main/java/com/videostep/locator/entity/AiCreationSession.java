package com.videostep.locator.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 酿味创作会话表（一个创作 = 一个 session）。
 * status 状态机：CREATED → GATHERING → CONFIRMED → GENERATING → RECIPE_READY →（P2 起 VIDEO_GEN → VIDEO_READY）→ DONE。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("ai_creation_sessions")
public class AiCreationSession {

    public static final String STATUS_CREATED = "CREATED";
    public static final String STATUS_GATHERING = "GATHERING";
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    public static final String STATUS_GENERATING = "GENERATING";
    public static final String STATUS_RECIPE_READY = "RECIPE_READY";
    public static final String STATUS_VIDEO_GEN = "VIDEO_GEN";
    public static final String STATUS_VIDEO_READY = "VIDEO_READY";
    public static final String STATUS_DONE = "DONE";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String title;
    private String status;
    /** 当前意图约束快照 JSON */
    private String intentJson;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
