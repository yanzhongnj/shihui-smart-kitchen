package com.videostep.locator.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 经验值流水（厨艺成长等级 EXP 来源追溯）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("user_exp_logs")
public class UserExpLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Integer delta;
    private String reason;
    private Long refId;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
