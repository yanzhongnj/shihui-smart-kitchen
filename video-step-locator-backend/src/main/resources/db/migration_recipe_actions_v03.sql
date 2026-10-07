-- ============================================================
-- 菜谱级 收藏/评价/分享 迁移 v0.3（支持无视频菜谱）
-- 背景：user_favorites / video_reviews / share_links 原仅绑定 video_id，
--       图文录入(ARTICLE_LINK)/手动录入(USER)/AI创作(AI_CREATE) 菜谱无视频，
--       APP 菜谱详情页的 分享/收藏/评价 被「无关联视频」拦截。
-- 方案：三表新增可空 recipe_id（recipes.id）。有视频菜谱继续走 video_id（既有行为不变）；
--       无视频菜谱用 recipe_id。recipe_id 与 video_id 二选一。
-- 执行：mysql -uroot -proot --default-character-set=utf8mb4 video_step_locator < 本文件
-- ============================================================

ALTER TABLE user_favorites
  MODIFY COLUMN video_id BIGINT DEFAULT NULL COMMENT '关联视频（有视频菜谱/视频收藏用，与 recipe_id 二选一）',
  ADD COLUMN recipe_id BIGINT DEFAULT NULL COMMENT '无视频菜谱的收藏目标（recipes.id，与 video_id 二选一）' AFTER video_id;

ALTER TABLE video_reviews
  MODIFY COLUMN video_id BIGINT DEFAULT NULL COMMENT '关联视频（有视频菜谱/视频评价用，与 recipe_id 二选一）',
  ADD COLUMN recipe_id BIGINT DEFAULT NULL COMMENT '无视频菜谱的评价目标（recipes.id，与 video_id 二选一）' AFTER video_id;

ALTER TABLE share_links
  MODIFY COLUMN video_id BIGINT DEFAULT NULL COMMENT '关联视频（有视频菜谱/视频分享用，与 recipe_id 二选一）',
  ADD COLUMN recipe_id BIGINT DEFAULT NULL COMMENT '无视频菜谱的分享目标（recipes.id，与 video_id 二选一）' AFTER video_id;

-- 检索索引
CREATE INDEX idx_user_favorites_recipe ON user_favorites (user_id, recipe_id);
CREATE INDEX idx_video_reviews_recipe ON video_reviews (recipe_id);
CREATE INDEX idx_share_links_recipe ON share_links (recipe_id);
