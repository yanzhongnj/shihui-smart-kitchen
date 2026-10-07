-- -----------------------------------------------------------
-- 直链下载（手机端解析直链 → 后端直接下载）：
-- download_tasks 增加 referer 字段（防盗链站点下载必需）
-- 适用：百度 mr.baidu 短链等被页面级人机验证拦截、但可拿到真实视频直链的场景
-- 幂等：列已存在时忽略错误
-- -----------------------------------------------------------
ALTER TABLE download_tasks ADD COLUMN referer VARCHAR(1000) DEFAULT NULL
    COMMENT '直链下载防盗链Referer' AFTER page_url;
