-- ============================================================
-- 菜谱统一模型迁移 v0.2（路线甲：recipe_metadata 原地演进 + RENAME → recipes）
-- 配套设计：docs/拾味_菜谱来源体系与工业级精确化_设计文档.md（v0.2）
-- 步骤：备份 → RENAME → ALTER 加列 → 回填 source/ref_id/溯源/初算评分
-- 执行：mysql -uroot -proot --default-character-set=utf8mb4 video_step_locator < 本文件
-- ============================================================

-- 0. 安全备份（RENAME 可逆，但按项目纪律保留一份快照）
CREATE TABLE IF NOT EXISTS recipe_metadata_backup_v02 AS SELECT * FROM recipe_metadata;

-- 1. RENAME 到统一主表名 recipes
RENAME TABLE recipe_metadata TO recipes;

-- 2. ALTER ADD 新列（旧列全部保留；MySQL 8 无 ADD COLUMN IF NOT EXISTS，勿重复执行）
ALTER TABLE recipes
  ADD COLUMN recipe_type VARCHAR(20) NOT NULL DEFAULT 'HOME' COMMENT '派生标签：HOME家常型|INDUSTRIAL工业型（由 precision_score 阈值派生）' AFTER summary,
  ADD COLUMN source VARCHAR(20) NOT NULL DEFAULT 'VIDEO_LINK' COMMENT 'VIDEO_LINK|ARTICLE_LINK|USER|AI_CREATE' AFTER recipe_type,
  ADD COLUMN precision_score DECIMAL(3,2) DEFAULT NULL COMMENT '精确化评分 0~1（§7.0 模型）' AFTER source,
  ADD COLUMN source_url VARCHAR(1000) DEFAULT NULL COMMENT '来源URL（video/article 必填；ai_create/user 可空）' AFTER precision_score,
  ADD COLUMN ref_id BIGINT DEFAULT NULL COMMENT '关联资源ID（video_id/article_id/ai_creation_id）' AFTER source_url,
  ADD COLUMN extractor_version VARCHAR(50) DEFAULT NULL COMMENT '提取流水线版本' AFTER ref_id,
  ADD COLUMN extractor_engine VARCHAR(50) DEFAULT NULL COMMENT '提取引擎标识' AFTER extractor_version,
  ADD COLUMN extracted_at DATETIME DEFAULT NULL COMMENT '提取时间' AFTER extractor_engine,
  ADD COLUMN raw_digest TEXT DEFAULT NULL COMMENT '原始内容摘要（转录/正文/创作 prompt 摘要，审计用）' AFTER extracted_at,
  ADD COLUMN confidence DECIMAL(3,2) DEFAULT NULL COMMENT '提取置信度 0~1（与 precision_score 分离）' AFTER raw_digest,
  ADD COLUMN parent_recipe_id BIGINT DEFAULT NULL COMMENT '衍生来源 recipes.id（derive 时保留谱系）' AFTER confidence,
  ADD COLUMN derivation_note VARCHAR(200) DEFAULT NULL COMMENT '衍生说明（如"基于XX的少油版本"）' AFTER parent_recipe_id,
  ADD COLUMN ingredients_json JSON DEFAULT NULL COMMENT '食材快照 [{name,amount,unit,is_main,tolerance}]（展示用，权威在 recipe_ingredients）' AFTER derivation_note,
  ADD COLUMN steps_json JSON DEFAULT NULL COMMENT '步骤 [{index,summary,detail,timer_min,temperature,mode}]' AFTER ingredients_json,
  ADD COLUMN nutrition_json JSON DEFAULT NULL COMMENT '营养 {calories,protein,fat,carbs,sodium,source}' AFTER steps_json,
  ADD COLUMN servings INT DEFAULT NULL COMMENT '基准份量（人份）' AFTER nutrition_json,
  ADD COLUMN precision_note VARCHAR(200) DEFAULT NULL COMMENT '精确化说明/限制' AFTER servings,
  ADD COLUMN version INT DEFAULT 1 COMMENT '版本（迭代/升级）' AFTER precision_note;

-- 3. 回填 source / ref_id / 提取引擎 / 版本
--    source_type='ai_creation' → AI_CREATE（AI 镜像）；有 video_id → VIDEO_LINK；否则 USER
UPDATE recipes SET
  source = CASE
      WHEN source_type = 'ai_creation' THEN 'AI_CREATE'
      WHEN video_id IS NOT NULL THEN 'VIDEO_LINK'
      ELSE 'USER'
    END,
  ref_id = CASE
      WHEN source_type = 'ai_creation' THEN creation_id
      ELSE video_id
    END,
  extractor_engine = CASE
      WHEN source_type = 'ai_creation' THEN 'creation'
      WHEN video_id IS NOT NULL THEN 'whisperx+deepseek'
      ELSE 'user'
    END,
  extractor_version = 'pipeline-v1.x';

-- 4. 回填 source_url（视频来源从 download_tasks 取源地址/落地页；AI 创作/用户为 null）
UPDATE recipes r
LEFT JOIN download_tasks d ON d.video_id = r.video_id AND d.source_url IS NOT NULL
SET r.source_url = COALESCE(d.source_url, d.page_url)
WHERE r.source = 'VIDEO_LINK';

-- 4.1 补充归属 user_id（创建者/提取者），从 videos / ai_creations 回填
ALTER TABLE recipes ADD COLUMN user_id BIGINT DEFAULT NULL COMMENT '创建者（AI创作/用户录入）或提取者（视频/图文）' AFTER org_id;
UPDATE recipes r LEFT JOIN videos v ON v.id = r.video_id SET r.user_id = v.user_id WHERE r.source = 'VIDEO_LINK';
UPDATE recipes r LEFT JOIN ai_creations a ON a.id = r.creation_id SET r.user_id = a.user_id WHERE r.source = 'AI_CREATE';

-- 5. 已有行初算 precision_score（旧数据无精确用量/步骤参数，仅按 营养可算(0.15) + 溯源完整(0.10) 计分，均 < 0.7 → HOME）
UPDATE recipes SET
  precision_score = ROUND(
      0.15 * (CASE WHEN calories IS NOT NULL THEN 1 ELSE 0 END)
    + 0.10 * (CASE WHEN source_url IS NOT NULL THEN 1 ELSE 0 END), 2);
UPDATE recipes SET recipe_type = CASE WHEN precision_score >= 0.7 THEN 'INDUSTRIAL' ELSE 'HOME' END;

-- ============================================================
-- 迁移结束。校验：SHOW TABLES LIKE 'recipes'; DESCRIBE recipes;
-- 同步代码改动：RecipeMetadata 实体 @TableName("recipes")、
--   RecipeMetadataRepository / CookingCheckinRepository 原生 SQL 表名
-- ============================================================
