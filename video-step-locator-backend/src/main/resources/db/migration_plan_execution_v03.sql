-- 做饭计划执行闭环 v0.6：cooking_plan_items 加荤素标记（AI 菜单带入，APP 徽标展示）
-- 设计文档《拾味_做饭计划执行闭环_技术设计文档.md》v0.6（2026-08-28）
-- 用法：mysql -u root -p < migration_plan_execution_v03.sql（或客户端粘贴执行）
USE video_step_locator;

-- 幂等：重复执行报 Duplicate column 可忽略
ALTER TABLE cooking_plan_items
  ADD COLUMN dish_type VARCHAR(10) DEFAULT NULL COMMENT '荤素 meat/veg（AI 菜单带入，APP 徽标展示，v0.6）' AFTER meal_type;
