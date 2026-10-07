-- 菜品级营养库软删/回收站对齐（2026-08-23，SVN r157）：dish_nutrition_db 加软删列 + 归档表
-- 对齐 r114/r144 体系（deleted/deleted_at + *_archive 归档），Admin 删除改软删进回收站。
-- 用法：mysql -u root -p < migration_dish_recycle_v03.sql（或客户端粘贴执行）
USE video_step_locator;

-- 1) dish_nutrition_db 加软删列（幂等：用 INFORMATION_SCHEMA 判断）
SET @has_deleted := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dish_nutrition_db' AND COLUMN_NAME = 'deleted');
SET @sql := IF(@has_deleted = 0,
    'ALTER TABLE dish_nutrition_db ADD COLUMN deleted TINYINT NOT NULL DEFAULT 0 COMMENT ''0=正常 1=软删除（回收站）'' AFTER updated_at',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_delat := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dish_nutrition_db' AND COLUMN_NAME = 'deleted_at');
SET @sql2 := IF(@has_delat = 0,
    'ALTER TABLE dish_nutrition_db ADD COLUMN deleted_at DATETIME DEFAULT NULL COMMENT ''软删除时间'' AFTER deleted',
    'SELECT 1');
PREPARE stmt2 FROM @sql2; EXECUTE stmt2; DEALLOCATE PREPARE stmt2;

-- 2) 归档表（回收站「彻底删除」自动归档，对齐 meal_photos_archive / diet_records_archive）
CREATE TABLE IF NOT EXISTS dish_nutrition_db_archive (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    original_id BIGINT NOT NULL COMMENT '原表ID',
    dish_name VARCHAR(100) NOT NULL,
    aliases JSON,
    cuisine VARCHAR(20),
    main_ingredients JSON,
    nutrition_per_serving JSON NOT NULL,
    serving_grams INT DEFAULT 300,
    is_verified TINYINT DEFAULT 0,
    source VARCHAR(20),
    hit_count INT DEFAULT 0,
    deleted_at DATETIME DEFAULT NULL,
    archived_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    archived_by BIGINT DEFAULT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='菜品级营养库归档表（回收站彻底删除）';
