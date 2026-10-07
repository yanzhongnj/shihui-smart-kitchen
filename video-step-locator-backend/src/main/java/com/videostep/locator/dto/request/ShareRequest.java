package com.videostep.locator.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ShareRequest {
    @NotNull(message = "被分享用户ID不能为空")
    private Long sharedWithUserId;

    @NotNull(message = "权限类型不能为空")
    private String permission; // view / view_steps
}
