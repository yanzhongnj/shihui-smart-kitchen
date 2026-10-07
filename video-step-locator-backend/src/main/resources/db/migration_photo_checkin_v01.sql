-- 拍照打卡 P0：meal_photos 记录表 + 归档表 + diet_records.photo_id 扩展（设计文档《拾味_拍照打卡_技术设计文档.md》v0.2 §5）
-- 建表幂等；软删/回收站对齐 r114 体系（deleted/deleted_at + *_archive 归档）。
-- 用法：mysql -u root -p < migration_photo_checkin_v01.sql（或客户端粘贴执行）
USE video_step_locator;

-- 5.1 拍照打卡记录表（含软删/回收站列）
CREATE TABLE IF NOT EXISTS meal_photos (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    photo_path VARCHAR(500) NOT NULL COMMENT '照片存储路径 photos/yyyy/MM/dd/<userId>_<ts>_<seq>.jpg（相对路径）',
    meal_type VARCHAR(20) NOT NULL COMMENT 'breakfast|lunch|dinner|snack',
    record_date DATE NOT NULL COMMENT '记录日期',
    recognition_json JSON COMMENT '多菜识别原始结果 [{dishName, confidence, source:vision}]',
    portion_json JSON COMMENT '份量/营养估算 [{dishName, portionGrams, method:default|manual, calories, protein, fat, carbs, estimated}]',
    status VARCHAR(20) DEFAULT 'pending' COMMENT 'pending(待确认)|confirmed(已确认)|corrected(已修正)',
    diet_record_id BIGINT DEFAULT NULL COMMENT '确认后关联的 diet_records.id',
    corrected_at DATETIME DEFAULT NULL,
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '0=正常 1=软删除（回收站）',
    deleted_at DATETIME DEFAULT NULL COMMENT '软删除时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_date (user_id, record_date),
    INDEX idx_photo_diet (diet_record_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='拍照打卡记录表';

-- 5.2 拍照打卡归档表（回收站「彻底删除」自动归档，对齐 diet_records_archive）
CREATE TABLE IF NOT EXISTS meal_photos_archive (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    original_id BIGINT NOT NULL COMMENT '原表ID',
    user_id BIGINT NOT NULL,
    photo_path VARCHAR(500) NOT NULL,
    meal_type VARCHAR(20) NOT NULL,
    record_date DATE NOT NULL,
    recognition_json JSON,
    portion_json JSON,
    status VARCHAR(20),
    diet_record_id BIGINT DEFAULT NULL,
    corrected_at DATETIME DEFAULT NULL,
    deleted_at DATETIME DEFAULT NULL,
    archived_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    archived_by BIGINT DEFAULT NULL,
    INDEX idx_arch_user_date (user_id, record_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='拍照打卡归档（回收站彻底删除自动归档）';

-- 5.4 diet_records 扩展：photo_id（拍照打卡来源；is_estimate 用现有 estimated 列）
-- 幂等：已执行过迁移会报 Duplicate column/index 错误，可忽略（对齐项目迁移风格）
ALTER TABLE diet_records
  ADD COLUMN photo_id BIGINT DEFAULT NULL COMMENT '来源照片（拍照打卡时非空）',
  ADD INDEX idx_photo (photo_id);
-- 归档表同步（保持归档审计完整）
ALTER TABLE diet_records_archive
  ADD COLUMN photo_id BIGINT DEFAULT NULL COMMENT '来源照片（归档审计同步）';
