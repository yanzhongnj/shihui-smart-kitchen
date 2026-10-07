package com.videostep.locator.dto.response;

import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DownloadTaskResponse {
    private Long id;
    private Long userId;
    private String sourceUrl;
    private String pageUrl;
    private String title;
    private String uploader;
    private BigDecimal duration;
    private String thumbnail;
    private String filePath;
    private Long fileSize;
    private String format;
    private String formatRequested;
    private String status;
    private Integer progress;
    private String errorMessage;
    private Long videoId;
    private Boolean duplicate;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
}
