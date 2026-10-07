package com.videostep.locator.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 菜谱来源流水（溯源审计，设计文档 §5.2 表 3）。
 * 每次提取/迭代/升级/衍生记录一条，支撑"这道菜从哪来、何时、用什么版本提取"的审计与回放。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("recipe_sources")
public class RecipeSource {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long recipeId;
    private String source;
    private String sourceUrl;
    private Long refId;
    private String extractorVersion;
    private String extractorEngine;
    private String rawDigest;
    private BigDecimal confidence;
    private LocalDateTime extractedAt;
}
