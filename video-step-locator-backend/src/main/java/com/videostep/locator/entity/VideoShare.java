package com.videostep.locator.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("video_shares")
public class VideoShare {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long videoId;
    private Long sharedBy;
    private Long sharedWith;
    private String permission;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;

    public static final String PERMISSION_VIEW = "view";
    public static final String PERMISSION_VIEW_STEPS = "view_steps";

    public boolean isExpired() {
        return expiresAt != null && LocalDateTime.now().isAfter(expiresAt);
    }
}