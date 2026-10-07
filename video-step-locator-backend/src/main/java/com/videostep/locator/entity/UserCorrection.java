package com.videostep.locator.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 成品纠偏记录（M2：纠偏报告落库，供月度报告纠偏分析 / M3 易犯偏差画像）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("user_corrections")
public class UserCorrection {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long videoId;
    private String recipeTitle;
    private String summary;
    /** 维度结论 JSON 数组 [{name,conclusion,severity,advice}] */
    private String dimensions;
    /** 参照来源: manual/frame/thumbnail */
    private String referenceSource;
    private Integer expGained;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
