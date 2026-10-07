-- ============================================================
-- 营养健康模块 P0 迁移（2026-08-14）
-- 设计文档：《docs/拾味_营养健康模块_技术设计文档.md》§4.2
-- 说明：仅建表。种子数据（83 核心 + 扩充 ~150）由后端启动时
--       FoodLibraryService 按 health.nutrition.food-db-init 幂等初始化。
-- 对已有库：手动执行本文件即可（MySQL 8 无 ADD COLUMN IF NOT EXISTS，
--       CREATE TABLE IF NOT EXISTS 幂等安全）。
-- ============================================================
USE video_step_locator;

CREATE TABLE IF NOT EXISTS nutrition_food_db (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL COMMENT '食材名（中文）',
    name_en VARCHAR(100) DEFAULT NULL COMMENT '英文名',
    category VARCHAR(50) NOT NULL COMMENT '主食/蔬菜/水果/肉类/海鲜/蛋奶/豆类/坚果/饮品/其他',
    calories DECIMAL(6,1) DEFAULT 0 COMMENT '热量 kcal/100g',
    protein DECIMAL(6,1) DEFAULT 0 COMMENT '蛋白 g/100g',
    fat DECIMAL(6,1) DEFAULT 0 COMMENT '脂肪 g/100g',
    carbs DECIMAL(6,1) DEFAULT 0 COMMENT '碳水 g/100g',
    fiber DECIMAL(6,1) DEFAULT 0 COMMENT '纤维 g/100g',
    sodium DECIMAL(6,1) DEFAULT 0 COMMENT '钠 mg/100g（可扩展）',
    is_core TINYINT DEFAULT 0 COMMENT '是否内置常用（核心 83 种）',
    source VARCHAR(20) DEFAULT 'seed' COMMENT 'seed|user|ai',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_name (name),
    INDEX idx_category (category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='食物营养库';
