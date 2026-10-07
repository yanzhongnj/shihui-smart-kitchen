package com.videostep.locator.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 成就徽章（Keep 式成就，规则定义在 GrowthService 代码侧）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("user_badges")
public class UserBadge {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String badgeCode;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime awardedAt;
}
