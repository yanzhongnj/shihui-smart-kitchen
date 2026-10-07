package com.videostep.locator.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 体重记录（每日一条）。设计文档《拾味_营养健康模块_技术设计文档.md》§4.4。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("weight_logs")
public class WeightLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    /** 体重 kg */
    private BigDecimal weight;
    private LocalDate logDate;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    public double weight() { return weight == null ? 0 : weight.doubleValue(); }
}
