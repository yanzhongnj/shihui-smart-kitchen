package com.videostep.locator.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("app_versions")
public class AppVersion {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String platform;
    private String version;
    private Integer build;
    private String minVersion;
    private Boolean forceUpdate;
    private String changelog;
    private String downloadUrl;
    private String md5;
    private Boolean enabled;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
