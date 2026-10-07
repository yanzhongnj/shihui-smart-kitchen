package com.videostep.locator.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 酿味会话消息表（含多源附件，P0 仅文本，附件字段 P1 启用）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("ai_creation_messages")
public class AiCreationMessage {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    /** user | assistant */
    private String role;
    private String content;
    /** 附件 JSON [{type:text|audio|image|video_ref|url, path/refId, meta}]（P1 启用） */
    private String attachments;
    /** assistant 消息附带的意图快照 JSON */
    private String intentJson;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
