package com.videostep.locator.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("download_tasks")
public class DownloadTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long orgId;
    private String deviceId;
    private String sourceUrl;
    private String pageUrl;
    /** 直链下载防盗链 Referer（手机端解析直链后透传；非数据库字段时可为空） */
    private String referer;
    private String title;
    private String uploader;
    private BigDecimal duration;
    private String thumbnail;
    private String filePath;
    private Long fileSize;
    private String format;
    private String formatRequested;
    /** 下载完成是否自动建云端视频：1=是(Web/默认)，0=否(APP，显式上传才建云端) */
    private Integer autoCloud;
    private String status;
    private Integer progress;
    private String errorMessage;
    private Long videoId;
    /** 本次 start 命中去重（源地址已下载过，未重新下载）——非数据库字段 */
    @TableField(exist = false)
    private Boolean duplicate;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;

    public static final String STATUS_QUEUED = "queued";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_CANCELLED = "cancelled";
}
