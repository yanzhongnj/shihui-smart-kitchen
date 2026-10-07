package com.videostep.locator.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("user_favorites")
public class UserFavorite {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long videoId;
    /** 无视频菜谱的收藏目标（recipes.id，与 video_id 二选一） */
    private Long recipeId;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
