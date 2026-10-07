-- ============================================================
-- 食材反查模块 P0 迁移（2026-08-14）
-- 设计文档：《docs/拾味_食材反查与内容源_技术设计文档.md》§6
-- 说明：仅建表。食材标签索引由后端 IngredientIndexService
--       从 recipes.ingredients_json 重建（幂等，启动懒重建 + 每日 cron）。
--       food_aliases 与营养模块共用（营养模块未建表，本迁移创建后两模块共用）。
-- 对已有库：手动执行本文件即可（MySQL 8 无 ADD COLUMN IF NOT EXISTS，
--       CREATE TABLE IF NOT EXISTS 幂等安全）。
-- ============================================================
USE video_step_locator;

-- 6.1 食材标签索引表
CREATE TABLE IF NOT EXISTS ingredient_tags (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL COMMENT '食材名（归一化后）',
    canonical_name VARCHAR(100) DEFAULT NULL COMMENT '规范名（同义归并后）',
    category VARCHAR(50) DEFAULT NULL COMMENT '分类（联动 nutrition_food_db.category）',
    frequency INT DEFAULT 0 COMMENT '出现频次（菜谱数）',
    is_main_only TINYINT DEFAULT 0 COMMENT '是否仅主料维度统计',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_name (name),
    INDEX idx_frequency (frequency),
    INDEX idx_category (category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='食材标签索引';

-- 6.2 食材同义词映射表（反查归一 + 营养计算匹配共用）
CREATE TABLE IF NOT EXISTS food_aliases (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    alias VARCHAR(100) NOT NULL COMMENT '别名（"里脊肉"/"土豆"）',
    canonical VARCHAR(100) NOT NULL COMMENT '规范名（"猪肉"/"马铃薯"）',
    source VARCHAR(20) DEFAULT 'manual' COMMENT 'manual|ai',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_alias (alias),
    INDEX idx_canonical (canonical)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='食材同义词映射';
