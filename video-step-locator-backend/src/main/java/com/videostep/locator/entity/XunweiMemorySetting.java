package com.videostep.locator.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 「寻味」长期记忆设置（偏好自进化开关）。
 * 表：xunwei_memory_settings
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("xunwei_memory_settings")
public class XunweiMemorySetting {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    /** 1=开启偏好自进化（自动从对话/行为抽取并更新画像），0=暂停 */
    private Integer autoEvolve;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
