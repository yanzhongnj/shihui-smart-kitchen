package com.videostep.locator.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("voice_commands_log")
public class VoiceCommandLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long videoId;
    private Long userId;
    private String sessionId;
    private String commandText;
    private String action;
    private BigDecimal videoTimestamp;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}