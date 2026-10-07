package com.videostep.locator.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("share_links")
public class ShareLink {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long videoId;
    /** 无视频菜谱的分享目标（recipes.id，与 video_id 二选一） */
    private Long recipeId;
    private String code;
    private Long createdBy;
    private LocalDateTime expiresAt;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
