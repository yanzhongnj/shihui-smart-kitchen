-- ============================================================
-- 营养健康模块 P3 迁移（2026-08-14）
-- 设计文档：《docs/拾味_营养健康模块_技术设计文档.md》§4.4
-- 说明：仅建表（weight_logs）。
-- 对已有库：手动执行本文件即可（CREATE TABLE IF NOT EXISTS 幂等安全）。
-- ============================================================
USE video_step_locator;

CREATE TABLE IF NOT EXISTS weight_logs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    weight DECIMAL(5,1) NOT NULL COMMENT '体重 kg',
    log_date DATE NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_date (user_id, log_date),
    INDEX idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='体重记录表';
