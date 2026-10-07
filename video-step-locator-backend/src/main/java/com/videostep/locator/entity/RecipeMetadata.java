package com.videostep.locator.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 统一菜谱主表（原 recipe_metadata，2026-08-14 路线甲演进 RENAME → recipes）。
 * 四类来源（VIDEO_LINK/ARTICLE_LINK/USER/AI_CREATE）全部汇聚本表；
 * 精确化用连续 precision_score（0~1）度量，recipe_type 由阈值(≥0.7)派生。
 * 设计依据：docs/拾味_菜谱来源体系与工业级精确化_设计文档.md（v0.2）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("recipes")
public class RecipeMetadata {

    public static final String SOURCE_VIDEO_LINK = "VIDEO_LINK";
    public static final String SOURCE_ARTICLE_LINK = "ARTICLE_LINK";
    public static final String SOURCE_USER = "USER";
    public static final String SOURCE_AI_CREATE = "AI_CREATE";

    public static final String TYPE_HOME = "HOME";
    public static final String TYPE_INDUSTRIAL = "INDUSTRIAL";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long videoId;
    private Long orgId;
    /** 创建者（AI 创作/用户录入）或提取者（视频/图文）；可由 video.user_id / ai_creations.user_id 回填 */
    private Long userId;
    private String title;
    private String summary;
    /** 派生类型标签：HOME 家常型 | INDUSTRIAL 工业型（由 precision_score 阈值派生） */
    private String recipeType;
    /** 来源枚举：VIDEO_LINK|ARTICLE_LINK|USER|AI_CREATE */
    private String source;
    /** 精确化评分 0~1（权威连续度量，§7.0 模型） */
    private BigDecimal precisionScore;
    /** 来源URL（video/article 必填；ai_create/user 可空） */
    private String sourceUrl;
    /** 关联资源ID（video_id/article_id/ai_creation_id） */
    private Long refId;
    private String extractorVersion;
    private String extractorEngine;
    private LocalDateTime extractedAt;
    /** 原始内容摘要（转录/正文/创作 prompt 摘要，审计用） */
    private String rawDigest;
    /** 提取置信度 0~1（与 precision_score 分离） */
    private BigDecimal confidence;
    /** 衍生来源 recipes.id（derive 时保留谱系链） */
    private Long parentRecipeId;
    private String derivationNote;
    /** 食材快照 JSON（展示用，权威在 recipe_ingredients） */
    private String ingredientsJson;
    private String stepsJson;
    private String nutritionJson;
    private Integer servings;
    private String precisionNote;
    /** 版本（迭代/升级） */
    private Integer version;
    // ---- 以下为历史兼容字段（保留只读，权威精确数据进新字段/recipe_ingredients） ----
    private String mainIngredients;
    private String auxiliaryIngredients;
    private String tasteTags;
    private String allergens;
    private Integer spiceLevel;
    private String cuisine;
    private Integer estimatedTime;
    private Integer calories;
    private String rawLlmJson;
    /** 内容来源标记（历史：AI 创作镜像='ai_creation'，真实菜谱=null） */
    private String sourceType;
    /** 关联 ai_creations.id（AI 创作镜像回链） */
    private Long creationId;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
