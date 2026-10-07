-- =============================================================
-- v05: 酿味酱汁设计 + 菜谱效果图（P0/P1）
--  1) ai_creations 扩展：sauce_json / effect_image / effect_image_style
--  2) ai_image_tasks 建表（仿 ai_video_tasks，单张整菜，无 step_index）
-- 执行方式：MySQL 8，幂等（IF NOT EXISTS / 列存在性判断）
-- =============================================================

-- 1) ai_creations 扩展（酱汁配方 JSON + 效果图路径 + 风格）
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_creations'
      AND COLUMN_NAME = 'sauce_json'
);
SET @sql := IF(@col_exists = 0,
    'ALTER TABLE ai_creations ADD COLUMN sauce_json JSON DEFAULT NULL COMMENT ''酱汁配方（SauceDesignService 输出）''',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_creations'
      AND COLUMN_NAME = 'effect_image'
);
SET @sql := IF(@col_exists = 0,
    'ALTER TABLE ai_creations ADD COLUMN effect_image VARCHAR(500) DEFAULT NULL COMMENT ''AI 效果图路径（effect/ 相对路径）''',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_creations'
      AND COLUMN_NAME = 'effect_image_style'
);
SET @sql := IF(@col_exists = 0,
    'ALTER TABLE ai_creations ADD COLUMN effect_image_style VARCHAR(20) DEFAULT NULL COMMENT ''效果图风格（realistic|plated|illustration）''',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2) ai_image_tasks（文生图任务表，仿 ai_video_tasks；单张整菜无 step_index，加 user_id/style/cost）
CREATE TABLE IF NOT EXISTS ai_image_tasks (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    creation_id BIGINT NOT NULL COMMENT '关联 ai_creations.id',
    user_id BIGINT NOT NULL COMMENT '所属用户',
    provider VARCHAR(20) NOT NULL COMMENT 'wan_image|mock',
    prompt TEXT DEFAULT NULL COMMENT '文生图提示词（菜名+描述+主辅料+风格）',
    style VARCHAR(20) DEFAULT 'realistic' COMMENT 'realistic|plated|illustration',
    status VARCHAR(20) DEFAULT 'queued' COMMENT 'queued|running|completed|failed',
    progress INT DEFAULT 0,
    task_ref VARCHAR(200) DEFAULT NULL COMMENT '厂商侧任务ID',
    image_path VARCHAR(500) DEFAULT NULL COMMENT '结果图片相对路径（effect/...）',
    cost INT DEFAULT 0 COMMENT '本次消耗（分，usage_records 关联）',
    error_message TEXT DEFAULT NULL,
    retry_count INT DEFAULT 0,
    gen_round INT DEFAULT 1 COMMENT '重生成轮次',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at DATETIME DEFAULT NULL,
    INDEX idx_it_creation (creation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='酿味文生图任务表（酱汁效果图）';
