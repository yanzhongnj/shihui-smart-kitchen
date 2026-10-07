-- 食物安全提醒知识库 P0：安全规则表（设计文档《拾味_食物安全提醒知识库_技术设计文档.md》§5/§13/§14）
-- 建表幂等；52 条种子数据由 SafetyRuleSeedData 代码种子启动灌入（见 SafetyCheckService.seedIfEmpty）
USE video_step_locator;

CREATE TABLE IF NOT EXISTS food_safety_rules (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    category VARCHAR(20) NOT NULL COMMENT 'MED|PROC|IND|ABS|MYTH（辟谣）',
    risk_level VARCHAR(10) NOT NULL COMMENT 'RED|YELLOW|GREEN（MYTH 为 GREEN 辟谣）',
    trigger_type VARCHAR(20) NOT NULL COMMENT 'ingredient(食材)|combination(组合)|user_condition(用户条件)',
    trigger_value VARCHAR(200) NOT NULL COMMENT '触发值：食材名/组合/用户条件（逗号/斜杠分隔多值）',
    related_food VARCHAR(200) DEFAULT NULL COMMENT '关联食物（组合另一侧 / 忌口食物展示）',
    title VARCHAR(100) NOT NULL COMMENT '规则标题',
    advice TEXT NOT NULL COMMENT '建议/提示文案（服务层附加免责声明）',
    source VARCHAR(100) DEFAULT NULL COMMENT '权威出处（机构/资料类型）',
    source_url VARCHAR(300) DEFAULT NULL COMMENT '精确出处 URL（采集管道阶段回填）',
    confidence INT DEFAULT 70 COMMENT '置信度 1-100（L3 灰度，扩充集 50）',
    version INT DEFAULT 1 COMMENT '规则版本（支持回滚）',
    enabled TINYINT DEFAULT 1 COMMENT '是否启用',
    deleted TINYINT DEFAULT 0 COMMENT '软删除 0/1',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_category (category),
    INDEX idx_trigger (trigger_type, trigger_value),
    INDEX idx_deleted (deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='食物安全规则库';
