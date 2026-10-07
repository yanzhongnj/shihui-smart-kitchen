-- 食物安全提醒知识库 P1：health_profiles 增敏感字段（用药/疾病，AES 密文存储，仅本人可见）
USE video_step_locator;

ALTER TABLE health_profiles
  ADD COLUMN medications VARCHAR(500) DEFAULT NULL COMMENT '用药情况（自愿，AES 密文，仅本人可见）' AFTER target_carbs,
  ADD COLUMN conditions VARCHAR(500) DEFAULT NULL COMMENT '疾病情况（痛风/糖尿病/高血压/孕产等，自愿，AES 密文，仅本人可见）' AFTER medications;
