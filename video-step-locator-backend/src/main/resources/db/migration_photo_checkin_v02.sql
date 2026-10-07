-- 拍照打卡 P1：dish_nutrition_db 菜品级营养库（设计文档《拾味_拍照打卡_技术设计文档.md》v0.2 §5.3）
-- 区别于食材级 nutrition_food_db；种子（100~200 人工复核核心家常菜）由 DishNutritionSeedData 代码幂等灌入。
-- 用法：mysql -u root -p < migration_photo_checkin_v02.sql
USE video_step_locator;

CREATE TABLE IF NOT EXISTS dish_nutrition_db (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    dish_name VARCHAR(100) NOT NULL COMMENT '菜名（中文，如 鱼香肉丝）',
    aliases JSON COMMENT '别名（复用 food_aliases 归一后补充）',
    cuisine VARCHAR(20) DEFAULT NULL COMMENT '菜系（川/粤/家常…，可选）',
    main_ingredients JSON COMMENT '主要食材 [{name, weight_g}]（衔接营养计算/安全提醒）',
    nutrition_per_serving JSON NOT NULL COMMENT '每份营养 {calories, protein, fat, carbs, fiber, sodium}',
    serving_grams INT DEFAULT 300 COMMENT '默认每份克数（份量估算基准）',
    is_verified TINYINT DEFAULT 0 COMMENT '是否人工复核（1=种子/已审核；0=AI 候选待审核）',
    source VARCHAR(20) DEFAULT 'ai' COMMENT 'seed|ai|user',
    hit_count INT DEFAULT 0 COMMENT '命中次数（热度，驱动扩充排序）',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_dish (dish_name),
    INDEX idx_hit (hit_count)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='菜品级营养库';
