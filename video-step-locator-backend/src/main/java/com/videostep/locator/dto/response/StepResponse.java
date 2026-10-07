package com.videostep.locator.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StepResponse {
    private Long id;
    private Integer stepIndex;
    private String summary;
    private BigDecimal startTime;
    private BigDecimal endTime;
    private String originalText;
    private String confidence;
    private boolean isLooping;
}
