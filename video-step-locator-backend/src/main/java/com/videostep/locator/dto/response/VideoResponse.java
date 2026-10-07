package com.videostep.locator.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VideoResponse {
    private Long id;
    private Long userId;
    private String username;
    private String title;
    private String filename;
    private Long fileSize;
    private BigDecimal duration;
    private String format;
    private String status;
    private String visibility;
    private String reviewStatus;
    private String reviewComment;
    private Integer uploadProgress;
    private Integer stepCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
