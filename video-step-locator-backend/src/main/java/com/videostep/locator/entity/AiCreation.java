package com.videostep.locator.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 酿味创作主表（AI 生成新菜谱）。
 * 字段含 v0.4 预留 source_type / creation_id，供 P2 升 B（recipe_metadata 镜像/搜索降权）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("ai_creations")
public class AiCreation {

    public static final String STATUS_DRAFT = "draft";
    public static final String STATUS_PUBLISHED = "published";
    public static final String STATUS_ARCHIVED = "archived";

    public static final String SOURCE_TYPE_AI_CREATION = "ai_creation";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String title;
    private String summary;
    /** chat | photo | profile */
    private String inspiration;
    /** 主料 JSON 数组文本 */
    private String mainIngredients;
    /** 辅料/调料 JSON 数组文本 */
    private String auxiliaryIngredients;
    /** 口味标签 JSON 数组文本 */
    private String tasteTags;
    /** 过敏原 JSON 数组文本 */
    private String allergens;
    private Integer spiceLevel;
    private Integer calories;
    private Integer estimatedTime;
    /** easy | medium | hard */
    private String difficulty;
    /** 图文步骤 JSON [{index,summary,detail,timer_min,video_prompt}] */
    private String steps;
    /** 厨电中控设备参数 JSON（可选） */
    private String deviceParams;
    /** 酱汁配方 JSON（SauceDesignService 输出，P0） */
    private String sauceJson;
    /** AI 效果图路径（effect/ 相对路径，P1） */
    private String effectImage;
    /** 效果图风格（realistic|plated|illustration，P1） */
    private String effectImageStyle;
    /** 内容来源标记（v0.4 预留） */
    private String sourceType;
    /** 自关联：衍生创作指向来源 ai_creations.id（v0.4 预留） */
    private Long creationId;
    /** 生成该创作的会话 id */
    private Long sessionId;
    /** 菜谱版本号，1 起 */
    private Integer version;
    /** 已迭代次数 */
    private Integer iterCount;
    private String status;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
