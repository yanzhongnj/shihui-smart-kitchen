package com.videostep.locator.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 酿味菜谱版本快照表（v0.3 迭代：每次重生成产生新版本，支持回退）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("ai_creation_versions")
public class AiCreationVersion {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long creationId;
    /** 版本号，1 起递增 */
    private Integer version;
    /** 该版本的菜谱步骤快照 JSON */
    private String stepsJson;
    /** 相对上一版的修改说明 */
    private String deltaNote;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
