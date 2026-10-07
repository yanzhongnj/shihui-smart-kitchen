-- 回收站超期自动清理配置表（2026-08-23，SVN r159）：管理员可查看/修改保留期与执行 cron、启停、手动触发
-- 单行配置（id=1）；运行时读取，改配置即时生效（无需重启）
USE video_step_locator;

CREATE TABLE IF NOT EXISTS recycle_settings (
    id BIGINT PRIMARY KEY COMMENT '固定 1（单行配置）',
    retention_days INT NOT NULL DEFAULT 90 COMMENT '软删保留天数，超过后自动归档+彻底删除',
    purge_cron VARCHAR(40) NOT NULL DEFAULT '0 30 3 * * *' COMMENT '每日执行 cron',
    enabled TINYINT NOT NULL DEFAULT 1 COMMENT '1=启用自动清理 0=停用（手动触发不受影响）',
    last_run_at DATETIME DEFAULT NULL COMMENT '最近一次执行时间',
    last_run_count INT DEFAULT 0 COMMENT '最近一次清理条数',
    last_run_detail VARCHAR(500) DEFAULT NULL COMMENT '最近一次清理明细',
    updated_by BIGINT DEFAULT NULL COMMENT '最近更新人',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='回收站超期自动清理配置';

INSERT INTO recycle_settings (id, retention_days, purge_cron, enabled) VALUES (1, 90, '0 30 3 * * *', 1)
    ON DUPLICATE KEY UPDATE id = id;
