-- ============================================================
-- 营养健康模块 P1 迁移（2026-08-14）
-- 设计文档：《docs/拾味_营养健康模块_技术设计文档.md》§4.1/§4.3
-- 说明：仅建表（health_profiles / diet_records）。种子数据见 v01。
-- 对已有库：手动执行本文件即可（CREATE TABLE IF NOT EXISTS 幂等安全）。
-- ============================================================
USE video_step_locator;

CREATE TABLE IF NOT EXISTS health_profiles (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL UNIQUE,
    height DECIMAL(5,1) DEFAULT NULL COMMENT '身高 cm',
    weight DECIMAL(5,1) DEFAULT NULL COMMENT '体重 kg',
    target_weight DECIMAL(5,1) DEFAULT NULL COMMENT '目标体重 kg',
    gender VARCHAR(10) DEFAULT NULL COMMENT 'male|female',
    age INT DEFAULT NULL COMMENT '年龄',
    activity_level VARCHAR(20) DEFAULT 'moderate' COMMENT 'sedentary|light|moderate|active|very_active',
    goal VARCHAR(20) DEFAULT 'maintain' COMMENT 'lose_weight|gain_muscle|maintain',
    bmr INT DEFAULT NULL COMMENT '基础代谢 kcal',
    tdee INT DEFAULT NULL COMMENT '每日总消耗 kcal',
    target_calories INT DEFAULT NULL COMMENT '目标热量 kcal',
    target_protein INT DEFAULT NULL COMMENT '目标蛋白 g',
    target_fat INT DEFAULT NULL COMMENT '目标脂肪 g',
    target_carbs INT DEFAULT NULL COMMENT '目标碳水 g',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='健康档案表';

CREATE TABLE IF NOT EXISTS diet_records (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    record_date DATE NOT NULL COMMENT '记录日期',
    meal_type VARCHAR(20) NOT NULL COMMENT 'breakfast|lunch|dinner|snack',
    recipe_id BIGINT DEFAULT NULL COMMENT '关联 recipes（跟做自动记时非空）',
    checkin_id BIGINT DEFAULT NULL COMMENT '关联打卡（跟做自动记时非空，删打卡级联删）',
    items JSON NOT NULL COMMENT '[{name,amount(g),calories,protein,fat,carbs}]',
    calories DECIMAL(8,1) DEFAULT 0,
    protein DECIMAL(8,1) DEFAULT 0,
    fat DECIMAL(8,1) DEFAULT 0,
    carbs DECIMAL(8,1) DEFAULT 0,
    portion_factor DECIMAL(3,2) DEFAULT 1.00 COMMENT '份量系数 0.5/1/1.5',
    estimated TINYINT DEFAULT 0 COMMENT '是否估算（菜谱无精确食材时）',
    source VARCHAR(20) DEFAULT 'cook' COMMENT 'cook(跟做)|manual|scan',
    note VARCHAR(200) DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_date (user_id, record_date),
    INDEX idx_user_meal (user_id, meal_type),
    INDEX idx_checkin (checkin_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='饮食记录表';
