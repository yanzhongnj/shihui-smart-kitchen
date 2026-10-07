package com.videostep.locator.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("videos")
public class Video {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long orgId;
    private String deviceId;
    private String title;
    private String filename;
    private String filePath;
    private Long fileSize;
    private BigDecimal duration;
    private String format;
    private String thumbnail;
    private String finishFrame;
    private String status;
    private String visibility;
    private String reviewStatus;
    private String reviewComment;
    private String md5Hash;
    private Integer uploadProgress;
    private Integer stepCount;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_UPLOADED = "uploaded";  // 已入库待处理（上传/下载后不自动处理，需手动触发）
    public static final String STATUS_PROCESSING = "processing";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";

    public static final String REVIEW_PENDING = "pending";
    public static final String REVIEW_APPROVED = "approved";
    public static final String REVIEW_REJECTED = "rejected";

    public static final String VISIBILITY_PRIVATE = "private";
    public static final String VISIBILITY_SHARED = "shared";
    public static final String VISIBILITY_PUBLIC = "public";
}