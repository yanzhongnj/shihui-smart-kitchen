-- -----------------------------------------------------------
-- 严格「下载不建云端、显式上传才建」（APP 端）：
-- download_tasks 增加 auto_cloud 列
--   1 = 下载完成自动建云端视频（Web 端 / 缺省保持原行为）
--   0 = 下载完成不建云端视频（APP 端，只落盘供拉取到手机本地，云端由用户显式上传创建）
-- 幂等：列已存在时忽略错误
-- -----------------------------------------------------------
ALTER TABLE download_tasks ADD COLUMN auto_cloud TINYINT NOT NULL DEFAULT 1
    COMMENT '下载完成是否自动建云端视频(1=是[Web],0=否[APP显式上传才建])' AFTER format;
