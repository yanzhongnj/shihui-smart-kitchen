package com.videostep.locator.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("content_licenses")
public class ContentLicense {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long vendorOrgId;
    /** recipe_group | ai_creation */
    private String contentType;
    private String recipeGroup;
    /** AI 创作 id（contentType=ai_creation） */
    private Long creationId;
    /** active | disabled */
    private String status;
    private LocalDateTime licensedAt;

    public static final String STATUS_ACTIVE = "active";
    public static final String CONTENT_TYPE_RECIPE_GROUP = "recipe_group";
    public static final String CONTENT_TYPE_AI_CREATION = "ai_creation";
}
