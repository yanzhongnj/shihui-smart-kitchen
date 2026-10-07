package com.videostep.locator.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 购物清单（shopping_lists，做饭计划执行闭环 P0，v0.3）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("shopping_lists")
public class ShoppingList {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    /** 来源计划 */
    private Long planId;
    /** 来源菜单 */
    private Long menuId;
    /** 条目 JSON [{ingredient, aliases[], amount_g, unit, category, checked, nutrition}] */
    private String itemsJson;
    /** 参考金额（元，可选，P2） */
    private BigDecimal totalAmount;
    /** 人数（换算基数） */
    private Integer persons;
    private String source;        // menu|manual
    private String status;        // draft|exported|submitted|ordered
    /** 导出的清单文本（复制/分享用） */
    private String exportText;
    /** 关联供应商订单（P2） */
    private Long supplierOrderId;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
