package com.videostep.locator.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("xunwei_memory")
public class XunweiMemory {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    /** 画像键：taste / taboo / cuisine / skill / scene / liked_videos ... */
    private String memKey;
    private String memValue;
    /** 记忆类型：profile=画像 / behavior=行为 / entity=实体 */
    private String memType;
    /** 置信度 0-100（显式设置=95，行为=90，对话抽取按命中次数累积） */
    private Integer confidence;
    /** 来源：explicit / chat / download / favorite / review / vision */
    private String source;
    /** 关联实体 id（如 video_id） */
    private Long refId;
    /** 提及/命中次数 */
    private Integer hitCount;
    /** 0=正常 1=软删除（用户删除后抑制重复抽取） */
    private Integer deleted;
    /** 软删除时间 */
    private LocalDateTime deletedAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
