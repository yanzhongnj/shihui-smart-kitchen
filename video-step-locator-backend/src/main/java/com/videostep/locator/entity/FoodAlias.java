package com.videostep.locator.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 食材同义词映射表（反查归一 + 营养计算匹配共用）。
 * 设计文档《拾味_食材反查与内容源_技术设计文档.md》§6.2。
 * source=manual（管理员/用户录入）|ai（AI 归并）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("food_aliases")
public class FoodAlias {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 别名（"里脊肉"/"土豆"） */
    private String alias;
    /** 规范名（"猪肉"/"马铃薯"） */
    private String canonical;
    /** manual|ai */
    private String source;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
