package com.videostep.locator.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("xunwei_messages")
public class XunweiMessage {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    /** user | assistant */
    private String role;
    private String content;
    /** assistant 消息附带的推荐视频 JSON（M3 使用） */
    private String videoJson;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
