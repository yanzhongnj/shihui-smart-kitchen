package com.videostep.locator.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("brand_configs")
public class BrandConfig {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orgId;
    private String appName;
    private String logoUrl;
    private String theme;
    private String splashText;
    private String aboutText;
    private Integer enabled;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
