package com.videostep.locator.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AsrResult {
    private String text;
    private String action;
    private Map<String, Object> params;
    private float confidence;
}
