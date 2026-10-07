-- 做饭计划执行闭环 v0.4：AI 菜单支持多天/起始日/标题配置
-- cooking_menus 增加 days（天数）与 title（计划标题）；menu_week 语义改为「起始日」
-- 用法：mysql -u root -p < migration_plan_execution_v02.sql（或客户端粘贴执行）
USE video_step_locator;

ALTER TABLE cooking_menus
  ADD COLUMN days INT DEFAULT 7 COMMENT '菜单天数（1~N，v0.4 多天可配；menu_week 为起始日）',
  ADD COLUMN title VARCHAR(100) DEFAULT 'AI 周菜单' COMMENT '计划标题（确认时写入 cooking_plans.title）';
