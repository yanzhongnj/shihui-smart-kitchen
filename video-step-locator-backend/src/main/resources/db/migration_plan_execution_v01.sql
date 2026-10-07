-- 做饭计划执行闭环 P0：cooking_menus + shopping_lists 新表 + cooking_plans/items 扩展列
-- 设计文档《拾味_做饭计划执行闭环_技术设计文档.md》v0.3 §4（2026-08-28）
-- 用法：mysql -u root -p < migration_plan_execution_v01.sql（或客户端粘贴执行）
USE video_step_locator;

-- 4.1 cooking_plans：加 AI 生成标记 + 关联菜单（幂等：重复执行报 Duplicate column 可忽略）
ALTER TABLE cooking_plans
  ADD COLUMN menu_id BIGINT DEFAULT NULL COMMENT '来源菜单 cooking_menus.id（AI 生成时非空）',
  ADD COLUMN generated_by VARCHAR(20) DEFAULT 'manual' COMMENT 'manual|ai';

-- 4.4 cooking_plan_items：加菜谱关联、执行状态、份量与打卡关联（幂等）
ALTER TABLE cooking_plan_items
  ADD COLUMN recipe_id BIGINT DEFAULT NULL COMMENT '关联 recipes.id（AI 菜单写入；手工条目为 NULL，清单生成时剔除并记 skippedItems）',
  ADD COLUMN exec_mode VARCHAR(20) DEFAULT NULL COMMENT 'appliance(线路A)|self(线路B)',
  ADD COLUMN schedule_json JSON DEFAULT NULL COMMENT '厨电时序编排结果（线路A，P1）',
  ADD COLUMN portion_scale DECIMAL(3,2) DEFAULT 1.00 COMMENT '份量系数 0.5/1.0/1.5（完成前可调，确认后锁定）',
  ADD COLUMN portion_locked TINYINT DEFAULT 0 COMMENT '份量是否已锁定（打卡后置1）',
  ADD COLUMN checkin_id BIGINT DEFAULT NULL COMMENT '自动打卡关联 cooking_checkins.id',
  ADD COLUMN diet_record_id BIGINT DEFAULT NULL COMMENT '自动饮食记录关联 diet_records.id';

-- 4.1 cooking_menus — 菜单生成记录表（新增）
CREATE TABLE IF NOT EXISTS cooking_menus (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  plan_id BIGINT DEFAULT NULL COMMENT '关联 cooking_plans（确认后写入）',
  user_id BIGINT NOT NULL,
  menu_week DATE NOT NULL COMMENT '菜单所属周（周一）',
  generated_by VARCHAR(20) DEFAULT 'ai' COMMENT 'ai|manual',
  constraints_json JSON COMMENT '生成约束 {calories_target, tastes[], taboos[], medications[], cuisine[], max_cook_time, generate_mode, respect_taboos, respect_medications}',
  items_json JSON NOT NULL COMMENT '[{day_offset, meal_type, dish_name, recipe_id, nutrition, note, device_type}]',
  status VARCHAR(20) DEFAULT 'preview' COMMENT 'preview(预览)|confirmed(已写入计划)|discarded',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_user_week (user_id, menu_week)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 菜单生成记录';

-- 4.2 shopping_lists — 购物清单表（新增）
CREATE TABLE IF NOT EXISTS shopping_lists (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  plan_id BIGINT DEFAULT NULL COMMENT '来源计划',
  menu_id BIGINT DEFAULT NULL COMMENT '来源菜单',
  items_json JSON NOT NULL COMMENT '[{ingredient, aliases[], amount_g, unit, category, checked, nutrition}]',
  total_amount DECIMAL(8,2) DEFAULT 0 COMMENT '参考金额（元，可选，P2）',
  persons INT DEFAULT 2 COMMENT '人数（换算基数）',
  source VARCHAR(20) DEFAULT 'menu' COMMENT 'menu|manual',
  status VARCHAR(20) DEFAULT 'draft' COMMENT 'draft|exported|submitted|ordered',
  export_text TEXT COMMENT '导出的清单文本（复制/分享用）',
  supplier_order_id BIGINT DEFAULT NULL COMMENT '关联供应商订单（P2）',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_user (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='购物清单';
