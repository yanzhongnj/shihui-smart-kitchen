package com.videostep.locator.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 回收站超期自动清理配置（recycle_settings，单行 id=1）。
 * 管理员可查看/修改保留期与执行 cron、启停、手动触发；运行时读取，改配置即时生效。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("recycle_settings")
public class RecycleSetting {
    /** 固定 1（单行配置） */
    @TableId(type = IdType.INPUT)
    private Long id;
    /** 软删保留天数，超过后自动归档+彻底删除 */
    private Integer retentionDays;
    /** 每日执行 cron */
    private String purgeCron;
    /** 1=启用自动清理 0=停用（手动触发不受影响） */
    private Integer enabled;
    /** 最近一次执行时间 */
    private LocalDateTime lastRunAt;
    /** 最近一次清理条数 */
    private Integer lastRunCount;
    /** 最近一次清理明细 */
    private String lastRunDetail;
    /** 最近更新人 */
    private Long updatedBy;
    @TableField(fill = FieldFill.UPDATE)
    private LocalDateTime updatedAt;
}
