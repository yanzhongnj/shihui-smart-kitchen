package com.videostep.locator.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 做菜作品（Keep 式作品记录：成品图 + 关联菜谱）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("user_works")
public class UserWork {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long videoId;
    private String recipeTitle;
    private String title;
    private String description;
    private String images;   // 图片相对路径 JSON 数组
    private String status;   // public/private
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    /** 逻辑删除：0 正常 / 1 已删除（软删除） */
    @TableLogic
    private Integer deleted;
    /** 软删除时间（回收站列表/恢复用） */
    private LocalDateTime deletedAt;
}
