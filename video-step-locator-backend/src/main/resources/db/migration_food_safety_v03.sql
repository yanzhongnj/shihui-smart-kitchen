-- 食物安全提醒知识库 运营增强（D5）：候选/审核队列 + 用户反馈
USE video_step_locator;

CREATE TABLE IF NOT EXISTS food_safety_rule_candidates (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    category VARCHAR(20) NOT NULL COMMENT 'MED|PROC|IND|ABS|MYTH',
    risk_level VARCHAR(10) NOT NULL COMMENT 'RED|YELLOW|GREEN',
    trigger_type VARCHAR(20) NOT NULL COMMENT 'ingredient|combination|user_condition',
    trigger_value VARCHAR(200) NOT NULL COMMENT '触发值',
    related_food VARCHAR(200) DEFAULT NULL,
    title VARCHAR(100) NOT NULL,
    advice TEXT NOT NULL,
    source VARCHAR(100) DEFAULT NULL,
    source_url VARCHAR(300) DEFAULT NULL,
    source_text TEXT COMMENT '原始资料摘要（溯源）',
    llm_json TEXT COMMENT 'LLM 原始返回（审计）',
    status VARCHAR(20) DEFAULT 'pending' COMMENT 'pending|approved|rejected',
    confidence INT DEFAULT 70,
    reviewed_at DATETIME DEFAULT NULL,
    reviewed_by BIGINT DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_status (status),
    INDEX idx_trigger (trigger_type, trigger_value)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='食物安全规则候选/审核队列';

CREATE TABLE IF NOT EXISTS food_safety_rule_feedback (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    rule_id BIGINT NOT NULL COMMENT 'food_safety_rules.id',
    user_id BIGINT NOT NULL,
    useful TINYINT DEFAULT NULL COMMENT '1 有用 / 0 无用',
    comment VARCHAR(500) DEFAULT NULL COMMENT '纠错/建议',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_rule (rule_id),
    INDEX idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='食物安全规则反馈';
