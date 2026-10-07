package com.videostep.locator.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 拍照打卡记录（拍照→识别→确认→写 diet_records）。
 * 设计文档《拾味_拍照打卡_技术设计文档.md》v0.2 §5.1。
 * recognition_json / portion_json 存 JSON 字符串（服务层用 ObjectMapper 读写）。
 * 软删/回收站对齐 r114 体系（deleted/deleted_at + meal_photos_archive 归档）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("meal_photos")
public class MealPhoto {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    /** 相对路径 photos/yyyy/MM/dd/<userId>_<ts>_<seq>.jpg */
    private String photoPath;
    /** breakfast|lunch|dinner|snack */
    private String mealType;
    private LocalDate recordDate;
    /** 多菜识别 [{dishName, confidence, source:vision}] */
    private String recognitionJson;
    /** 份量/营养估算 [{dishName, portionGrams, method, calories, protein, fat, carbs, estimated}] */
    private String portionJson;
    /** pending|confirmed|corrected */
    private String status;
    /** 确认后关联 diet_records.id */
    private Long dietRecordId;
    private LocalDateTime correctedAt;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    /** 逻辑删除：0 正常 / 1 已删除（软删除） */
    @TableLogic
    private Integer deleted;
    /** 软删除时间（回收站列表/恢复用） */
    private LocalDateTime deletedAt;
}
