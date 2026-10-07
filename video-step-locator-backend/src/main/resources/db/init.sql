-- ============================================================
-- 视频定位后台处理系统 - 数据库初始化脚本
-- 数据库: MySQL 8.0+
-- ============================================================

CREATE DATABASE IF NOT EXISTS video_step_locator
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE video_step_locator;

-- -----------------------------------------------------------
-- 1. 用户表
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE COMMENT '用户名',
    email VARCHAR(100) NOT NULL UNIQUE COMMENT '邮箱',
    password_hash VARCHAR(255) NOT NULL COMMENT 'BCrypt加密密码',
    nickname VARCHAR(50) DEFAULT NULL COMMENT '昵称',
    avatar_url VARCHAR(500) DEFAULT NULL COMMENT '头像路径',
    status TINYINT DEFAULT 1 COMMENT '0=禁用 1=正常',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- -----------------------------------------------------------
-- 2. 视频主表
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS videos (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '上传者ID',
    org_id BIGINT DEFAULT NULL COMMENT '企业ID（B端；空=个人内容）',
    title VARCHAR(255) NOT NULL COMMENT '视频标题',
    filename VARCHAR(255) NOT NULL COMMENT '原始文件名',
    file_path VARCHAR(500) NOT NULL COMMENT 'NAS存储路径',
    file_size BIGINT DEFAULT NULL COMMENT '文件大小(字节)',
    duration DECIMAL(10,2) DEFAULT NULL COMMENT '视频时长(秒)',
    format VARCHAR(20) DEFAULT NULL COMMENT '文件格式',
    thumbnail VARCHAR(500) DEFAULT NULL COMMENT '缩略图路径',
    finish_frame VARCHAR(500) DEFAULT NULL COMMENT '成品帧图(相对路径, M2 纠偏参照缓存)',
    status ENUM('pending','processing','completed','failed','uploaded') DEFAULT 'pending' COMMENT '处理状态(uploaded=已入库待手动处理)',
    visibility ENUM('private','shared','public') DEFAULT 'private' COMMENT '可见性',
    md5_hash VARCHAR(64) DEFAULT NULL COMMENT '文件MD5',
    upload_progress INT DEFAULT 0 COMMENT '上传进度0-100',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_user_id (user_id),
    INDEX idx_org_id (org_id),
    INDEX idx_status (status),
    CONSTRAINT fk_videos_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='视频主表';

-- -----------------------------------------------------------
-- 3. 视频共享表
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS video_shares (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    video_id BIGINT NOT NULL COMMENT '视频ID',
    shared_by BIGINT NOT NULL COMMENT '分享者ID',
    shared_with BIGINT NOT NULL COMMENT '被分享者ID',
    permission ENUM('view','view_steps') DEFAULT 'view' COMMENT '权限级别',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at DATETIME DEFAULT NULL COMMENT '过期时间(null=永不过期)',
    UNIQUE KEY uk_video_share (video_id, shared_with),
    CONSTRAINT fk_shares_video FOREIGN KEY (video_id) REFERENCES videos(id) ON DELETE CASCADE,
    CONSTRAINT fk_shares_by FOREIGN KEY (shared_by) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_shares_with FOREIGN KEY (shared_with) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='视频共享表';

-- -----------------------------------------------------------
-- 4. 视频步骤表
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS video_steps (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    video_id BIGINT NOT NULL COMMENT '视频ID',
    step_index INT NOT NULL COMMENT '步骤序号',
    summary TEXT COMMENT '步骤摘要',
    start_time DECIMAL(10,2) DEFAULT NULL COMMENT '开始时间(秒)',
    end_time DECIMAL(10,2) DEFAULT NULL COMMENT '结束时间(秒)',
    original_text TEXT COMMENT '原始文本',
    confidence ENUM('high','medium','low') DEFAULT 'low' COMMENT '置信度',
    loop_count INT DEFAULT 0 COMMENT '循环次数',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_video_step (video_id, step_index),
    CONSTRAINT fk_steps_video FOREIGN KEY (video_id) REFERENCES videos(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='视频步骤表';

-- -----------------------------------------------------------
-- 5. 处理任务表
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS processing_tasks (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    video_id BIGINT NOT NULL COMMENT '视频ID',
    org_id BIGINT DEFAULT NULL COMMENT '企业ID（B端；空=个人）',
    task_type VARCHAR(50) DEFAULT 'pipeline' COMMENT '任务类型',
    status ENUM('queued','running','paused','completed','failed') NOT NULL COMMENT '任务状态',
    progress INT DEFAULT 0 COMMENT '进度0-100',
    current_phase VARCHAR(50) DEFAULT NULL COMMENT '当前处理阶段',
    error_message TEXT COMMENT '错误信息',
    retry_count INT DEFAULT 0 COMMENT '重试次数',
    max_retries INT DEFAULT 3 COMMENT '最大重试次数',
    worker_id VARCHAR(100) DEFAULT NULL COMMENT '处理节点标识',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at DATETIME DEFAULT NULL COMMENT '开始时间',
    completed_at DATETIME DEFAULT NULL COMMENT '完成时间',
    INDEX idx_task_status (status, created_at),
    INDEX idx_task_org (org_id),
    CONSTRAINT fk_tasks_video FOREIGN KEY (video_id) REFERENCES videos(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='处理任务表';

-- -----------------------------------------------------------
-- 6. 语音命令日志表
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS voice_commands_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    video_id BIGINT DEFAULT NULL COMMENT '视频ID',
    user_id BIGINT DEFAULT NULL COMMENT '操作用户ID',
    session_id VARCHAR(100) DEFAULT NULL COMMENT 'WebSocket会话ID',
    command_text VARCHAR(255) DEFAULT NULL COMMENT '识别到的命令文本',
    action VARCHAR(50) DEFAULT NULL COMMENT '映射动作',
    video_timestamp DECIMAL(10,2) DEFAULT NULL COMMENT '触发时视频时间点',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_voice_video FOREIGN KEY (video_id) REFERENCES videos(id) ON DELETE SET NULL,
    CONSTRAINT fk_voice_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='语音命令日志表';

-- -----------------------------------------------------------
-- 7. 视频下载任务表（yt-dlp 链接解析下载）
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS download_tasks (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '发起下载的用户ID',
    org_id BIGINT DEFAULT NULL COMMENT '企业ID（B端；空=个人）',
    source_url VARCHAR(1000) NOT NULL COMMENT '来源视频URL(条目URL)',
    page_url VARCHAR(1000) DEFAULT NULL COMMENT '原始页面/合集URL',
    referer VARCHAR(1000) DEFAULT NULL COMMENT '直链下载防盗链Referer',
    title VARCHAR(255) DEFAULT NULL COMMENT '视频标题',
    uploader VARCHAR(255) DEFAULT NULL COMMENT 'UP主/上传者',
    duration DECIMAL(10,2) DEFAULT NULL COMMENT '时长(秒)',
    thumbnail VARCHAR(1000) DEFAULT NULL COMMENT '封面URL',
    file_path VARCHAR(500) DEFAULT NULL COMMENT '下载后的正式存储路径',
    file_size BIGINT DEFAULT NULL COMMENT '文件大小(字节)',
    format VARCHAR(20) DEFAULT NULL COMMENT '实际文件格式',
    auto_cloud TINYINT NOT NULL DEFAULT 1 COMMENT '下载完成是否自动建云端视频(1=是[Web],0=否[APP显式上传才建])',
    status ENUM('queued','running','completed','failed','cancelled') DEFAULT 'queued' COMMENT '状态',
    progress INT DEFAULT 0 COMMENT '下载进度0-100',
    error_message TEXT COMMENT '错误信息',
    video_id BIGINT DEFAULT NULL COMMENT '入库后关联的视频ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at DATETIME DEFAULT NULL COMMENT '开始时间',
    completed_at DATETIME DEFAULT NULL COMMENT '完成时间',
    INDEX idx_dl_user_status (user_id, status),
    INDEX idx_dl_video (video_id),
    INDEX idx_dl_org (org_id),
    CONSTRAINT fk_dl_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_dl_video FOREIGN KEY (video_id) REFERENCES videos(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='视频下载任务表';

-- -----------------------------------------------------------
-- 8. 兼容迁移（幂等，可重复执行）：
--    上传/下载的视频仅入库(status='uploaded')，不自动进入处理队列
-- -----------------------------------------------------------
ALTER TABLE videos MODIFY COLUMN status
    ENUM('pending','processing','completed','failed','uploaded')
    DEFAULT 'pending' COMMENT '处理状态(uploaded=已入库待手动处理)';

-- -----------------------------------------------------------
-- 9. 寻味长期记忆设置表（偏好自进化开关）
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS xunwei_memory_settings (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '用户ID',
    auto_evolve TINYINT NOT NULL DEFAULT 1 COMMENT '1=开启偏好自进化(自动抽取/更新画像) 0=暂停',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user (user_id),
    CONSTRAINT fk_mem_settings_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='寻味长期记忆设置';

-- -----------------------------------------------------------
-- 10. 寻味长期记忆表（偏好自进化画像：置信度/来源/命中次数/软删除）
--     注：生产库旧表已通过 ALTER 补齐列（见 docs/寻味偏好自进化记忆系统_技术设计文档.md）
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS xunwei_memory (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '用户ID',
    mem_key VARCHAR(50) NOT NULL COMMENT '画像键：taste/taboo/cuisine/skill/scene/liked_videos...',
    mem_value TEXT NOT NULL COMMENT '画像值',
    mem_type VARCHAR(20) NOT NULL DEFAULT 'profile' COMMENT 'profile=画像 behavior=行为 entity=实体',
    confidence INT NOT NULL DEFAULT 60 COMMENT '置信度0-100',
    source VARCHAR(30) DEFAULT NULL COMMENT 'explicit/chat/download/favorite/review/vision',
    ref_id BIGINT DEFAULT NULL COMMENT '关联实体id',
    hit_count INT NOT NULL DEFAULT 1 COMMENT '提及/命中次数',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '0=正常 1=软删除',
    deleted_at DATETIME DEFAULT NULL COMMENT '软删除时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_key (user_id, mem_key),
    INDEX idx_mem_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='寻味长期记忆（用户画像）';

-- -----------------------------------------------------------
-- 11. 炒菜打卡表（Keep 式习惯记录，P0 打卡/等级/徽章）
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS cooking_checkins (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '用户ID',
    checkin_date DATE NOT NULL COMMENT '打卡日期',
    video_id BIGINT DEFAULT NULL COMMENT '关联视频ID（跟做的视频）',
    recipe_title VARCHAR(255) DEFAULT NULL COMMENT '菜名（从菜谱带出或手填）',
    meal_type VARCHAR(20) DEFAULT 'dinner' COMMENT '餐次: breakfast/lunch/dinner/snack',
    mood VARCHAR(20) DEFAULT 'normal' COMMENT '心情: success/fail/normal',
    note VARCHAR(500) DEFAULT NULL COMMENT '备注',
    calories INT DEFAULT NULL COMMENT '热量(千卡, 从菜谱带出或手填)',
    source VARCHAR(20) DEFAULT 'manual' COMMENT '来源: manual/auto',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '0=正常 1=软删除',
    deleted_at DATETIME DEFAULT NULL COMMENT '软删除时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_checkin_user_date (user_id, checkin_date),
    CONSTRAINT fk_checkin_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_checkin_video FOREIGN KEY (video_id) REFERENCES videos(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='炒菜打卡表';

-- -----------------------------------------------------------
-- 12. 经验值记录表（厨艺成长等级 EXP，可追溯来源）
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS user_exp_logs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '用户ID',
    delta INT NOT NULL COMMENT '经验值变化',
    reason VARCHAR(50) NOT NULL COMMENT '来源: checkin/recipe_done/work/badge',
    ref_id BIGINT DEFAULT NULL COMMENT '关联ID(打卡/作品ID)',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_exp_user (user_id, created_at),
    CONSTRAINT fk_exp_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='经验值记录表';

-- -----------------------------------------------------------
-- 13. 成就徽章表（Keep 式成就，rule 在代码侧）
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS user_badges (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '用户ID',
    badge_code VARCHAR(50) NOT NULL COMMENT '徽章代码',
    awarded_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_badge (user_id, badge_code),
    CONSTRAINT fk_badge_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='成就徽章表';

-- -----------------------------------------------------------
-- 14. 做菜作品表（Keep 式作品记录：成品图 + 关联菜谱，P1）
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS user_works (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '用户ID',
    video_id BIGINT DEFAULT NULL COMMENT '关联菜谱视频ID',
    recipe_title VARCHAR(255) DEFAULT NULL COMMENT '菜名（从菜谱带出或手填）',
    title VARCHAR(255) DEFAULT NULL COMMENT '作品标题（缺省用菜名）',
    description VARCHAR(1000) DEFAULT NULL COMMENT '描述',
    images VARCHAR(2000) DEFAULT NULL COMMENT '图片相对路径 JSON 数组',
    status VARCHAR(20) DEFAULT 'public' COMMENT '可见性: public/private',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '0=正常 1=软删除',
    deleted_at DATETIME DEFAULT NULL COMMENT '软删除时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_work_user (user_id),
    CONSTRAINT fk_work_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_work_video FOREIGN KEY (video_id) REFERENCES videos(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='做菜作品表';

-- -----------------------------------------------------------
-- 15. 做饭计划表（Keep 式训练计划：每周/每日菜谱计划，P3）
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS cooking_plans (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '用户ID',
    title VARCHAR(100) NOT NULL DEFAULT 'Weekly Plan' COMMENT '计划标题(服务端默认中文"本周计划")',
    plan_type VARCHAR(20) NOT NULL DEFAULT 'weekly' COMMENT 'weekly/daily',
    start_date DATE NOT NULL COMMENT '计划起始日（周一）',
    menu_id BIGINT DEFAULT NULL COMMENT '来源菜单 cooking_menus.id（AI 生成时非空，v0.3）',
    generated_by VARCHAR(20) DEFAULT 'manual' COMMENT 'manual|ai（v0.3）',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '0=正常 1=软删除',
    deleted_at DATETIME DEFAULT NULL COMMENT '软删除时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_plan_user (user_id, start_date),
    CONSTRAINT fk_plan_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='做饭计划表';

-- -----------------------------------------------------------
-- 16. 做饭计划条目表（哪天做哪道菜）
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS cooking_plan_items (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id BIGINT NOT NULL COMMENT '计划ID',
    day_offset INT NOT NULL DEFAULT 0 COMMENT '周内第几天 0=周一',
    video_id BIGINT DEFAULT NULL COMMENT '关联菜谱视频ID',
    recipe_id BIGINT DEFAULT NULL COMMENT '关联 recipes.id（AI 菜单写入；手工条目为 NULL，v0.3）',
    recipe_title VARCHAR(255) DEFAULT NULL COMMENT '菜名',
    meal_type VARCHAR(20) DEFAULT 'dinner' COMMENT '餐次: breakfast/lunch/dinner/snack',
    dish_type VARCHAR(10) DEFAULT NULL COMMENT '荤素 meat/veg（AI 菜单带入，APP 徽标展示，v0.6）',
    exec_mode VARCHAR(20) DEFAULT NULL COMMENT 'appliance(线路A)|self(线路B)（v0.3）',
    schedule_json JSON DEFAULT NULL COMMENT '厨电时序编排结果（线路A，P1，v0.3）',
    portion_scale DECIMAL(3,2) DEFAULT 1.00 COMMENT '份量系数 0.5/1.0/1.5（v0.3）',
    portion_locked TINYINT DEFAULT 0 COMMENT '份量是否已锁定（打卡后置1，v0.3）',
    checkin_id BIGINT DEFAULT NULL COMMENT '自动打卡关联 cooking_checkins.id（v0.3）',
    diet_record_id BIGINT DEFAULT NULL COMMENT '自动饮食记录关联 diet_records.id（v0.3）',
    done TINYINT NOT NULL DEFAULT 0 COMMENT '0=未做 1=已跟做',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_item_plan (plan_id),
    CONSTRAINT fk_item_plan FOREIGN KEY (plan_id) REFERENCES cooking_plans(id) ON DELETE CASCADE,
    CONSTRAINT fk_item_video FOREIGN KEY (video_id) REFERENCES videos(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='做饭计划条目表';

-- -----------------------------------------------------------
-- 17. 成品纠偏记录表（M2：纠偏报告落库，供月度报告纠偏分析/易犯偏差画像 M3）
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS user_corrections (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '用户ID',
    video_id BIGINT DEFAULT NULL COMMENT '关联菜谱视频ID',
    recipe_title VARCHAR(255) DEFAULT NULL COMMENT '菜名',
    summary VARCHAR(1000) DEFAULT NULL COMMENT '一句话总评',
    dimensions VARCHAR(3000) DEFAULT NULL COMMENT '维度结论 JSON 数组 [{name,conclusion,severity,advice}]',
    reference_source VARCHAR(20) DEFAULT NULL COMMENT '参照来源: manual/frame/thumbnail',
    exp_gained INT DEFAULT 0 COMMENT '本次纠偏 EXP',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_corr_user_time (user_id, created_at),
    CONSTRAINT fk_corr_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_corr_video FOREIGN KEY (video_id) REFERENCES videos(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='成品纠偏记录表';

-- ============================================================
-- 面向 B 端（P0 底座）：企业/组织 + 成员 + 套餐 + 订阅 + 账单
-- 详见 docs/拾味_面向B端_技术设计文档.md（B→A→C 顺序，方向 B 优先）
-- 兼容迁移：生产库已存在的 videos/processing_tasks/download_tasks/recipe_metadata 需手工
--   ALTER TABLE xxx ADD COLUMN org_id BIGINT DEFAULT NULL COMMENT '企业ID（B端；空=个人）',
--   ADD INDEX idx_org (org_id);  （MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，勿在 init.sql 重复执行）
-- ============================================================

-- -----------------------------------------------------------
-- 18. 企业/组织表（B端租户）
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS orgs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL COMMENT '企业名称',
    code VARCHAR(50) NOT NULL UNIQUE COMMENT '唯一编码',
    owner_id BIGINT NOT NULL COMMENT '创建人/owner 用户ID',
    logo VARCHAR(500) DEFAULT NULL COMMENT '企业Logo',
    plan_type VARCHAR(20) NOT NULL DEFAULT 'free' COMMENT 'free|basic|pro|enterprise',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '0=禁用 1=正常 2=欠费停服',
    quota JSON DEFAULT NULL COMMENT '配额：video_limit/task_limit/storage_bytes/member_limit/download_limit',
    expired_at DATETIME DEFAULT NULL COMMENT '试用/套餐到期时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_owner (owner_id),
    CONSTRAINT fk_org_owner FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='企业/组织表';

-- -----------------------------------------------------------
-- 19. 组织成员表
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS org_members (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    org_id BIGINT NOT NULL COMMENT 'orgs.id',
    user_id BIGINT NOT NULL COMMENT 'users.id',
    role VARCHAR(20) NOT NULL DEFAULT 'member' COMMENT 'owner|admin|operator|member',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '0=已移除 1=在职',
    joined_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_org_user (org_id, user_id),
    INDEX idx_user (user_id),
    CONSTRAINT fk_member_org FOREIGN KEY (org_id) REFERENCES orgs(id) ON DELETE CASCADE,
    CONSTRAINT fk_member_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='组织成员表';

-- -----------------------------------------------------------
-- 20. B端套餐定义表（平台内置，Admin 维护）
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS org_plans (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(30) NOT NULL UNIQUE COMMENT 'free|basic|pro|enterprise',
    name VARCHAR(50) NOT NULL COMMENT '套餐名',
    price_monthly DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '月费（元）',
    quota JSON NOT NULL COMMENT '配额模板：video_limit/task_limit/storage_bytes/member_limit/download_limit',
    features JSON DEFAULT NULL COMMENT '功能开关：white_label/device/openapi 等',
    status TINYINT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='B端套餐定义表';

-- 内置套餐（幂等种子数据）
INSERT IGNORE INTO org_plans (code, name, price_monthly, quota, features, status) VALUES
('free', '免费版', 0,
 '{"video_limit":50,"task_limit":100,"storage_bytes":5368709120,"member_limit":5,"download_limit":50}',
 '{}', 1),
('basic', '基础版', 99,
 '{"video_limit":200,"task_limit":500,"storage_bytes":21474836480,"member_limit":10,"download_limit":200}',
 '{}', 1),
('pro', '专业版', 299,
 '{"video_limit":1000,"task_limit":2000,"storage_bytes":107374182400,"member_limit":30,"download_limit":1000}',
 '{"white_label":false,"device":false,"openapi":false}', 1),
('enterprise', '企业版', 999,
 '{"video_limit":5000,"task_limit":10000,"storage_bytes":536870912000,"member_limit":100,"download_limit":5000}',
 '{"white_label":true,"device":true,"openapi":true}', 1);

-- -----------------------------------------------------------
-- 21. 企业订阅记录表
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS org_subscriptions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    org_id BIGINT NOT NULL,
    plan_code VARCHAR(30) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'active' COMMENT 'active|expired|canceled',
    started_at DATETIME NOT NULL,
    expired_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_org (org_id),
    CONSTRAINT fk_sub_org FOREIGN KEY (org_id) REFERENCES orgs(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='企业订阅记录表';

-- -----------------------------------------------------------
-- 22. 企业账单表
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS invoices (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    org_id BIGINT NOT NULL,
    user_id BIGINT DEFAULT NULL COMMENT '操作人',
    amount DECIMAL(10,2) NOT NULL,
    currency VARCHAR(10) NOT NULL DEFAULT 'CNY',
    status VARCHAR(20) NOT NULL DEFAULT 'pending' COMMENT 'pending|paid|overdue|canceled',
    description VARCHAR(255) DEFAULT NULL COMMENT '账单说明',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    paid_at DATETIME DEFAULT NULL,
    INDEX idx_org (org_id),
    CONSTRAINT fk_inv_org FOREIGN KEY (org_id) REFERENCES orgs(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='企业账单表';

-- -----------------------------------------------------------
-- 23. 租户品牌配置表（方向 A1 白标化）
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS brand_configs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    org_id BIGINT NOT NULL COMMENT '企业/厂商租户',
    app_name VARCHAR(50) NOT NULL DEFAULT '' COMMENT '品牌显示名，如「某品牌厨电」',
    logo_url VARCHAR(500) DEFAULT NULL COMMENT '品牌 Logo URL',
    theme JSON DEFAULT NULL COMMENT '主题色/深色模式',
    splash_text VARCHAR(200) DEFAULT NULL COMMENT '启动页副标题',
    about_text TEXT DEFAULT NULL COMMENT '关于页文案',
    enabled TINYINT NOT NULL DEFAULT 1 COMMENT '1=启用（覆盖默认品牌）',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_brand_org (org_id),
    CONSTRAINT fk_brand_org FOREIGN KEY (org_id) REFERENCES orgs(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='租户品牌配置表';

-- -----------------------------------------------------------
-- 24. 厂商合同表（方向 A2：厨电厂商 B2B2C 授权）
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS vendor_contracts (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    org_id BIGINT NOT NULL COMMENT '厂商租户（orgs.id）',
    contract_no VARCHAR(50) NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL DEFAULT 'active' COMMENT 'active|expired|suspended',
    content_scopes JSON DEFAULT NULL COMMENT '授权内容范围（菜谱分组ID数组）',
    device_quota INT NOT NULL DEFAULT 0 COMMENT '授权激活设备数上限',
    started_at DATETIME NOT NULL,
    expired_at DATETIME DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_contract_org (org_id),
    CONSTRAINT fk_contract_org FOREIGN KEY (org_id) REFERENCES orgs(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='厂商合同表';

-- -----------------------------------------------------------
-- 25. 设备 SKU 定义表（厂商设备型号与能力集）
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS device_skus (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    vendor_org_id BIGINT NOT NULL COMMENT '厂商租户',
    sku VARCHAR(100) NOT NULL COMMENT '型号/货号',
    device_type VARCHAR(30) NOT NULL COMMENT 'oven|steamer|cooktop|airfryer',
    capabilities JSON DEFAULT NULL COMMENT '能力集',
    default_recipe_group VARCHAR(50) DEFAULT NULL COMMENT '默认菜谱分组',
    status TINYINT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_vendor_sku (vendor_org_id, sku),
    CONSTRAINT fk_sku_vendor FOREIGN KEY (vendor_org_id) REFERENCES orgs(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='设备SKU定义表';

-- -----------------------------------------------------------
-- 26. 设备出厂绑定表（绑定码 → C 端用户扫码激活）
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS device_bindings (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sku_id BIGINT NOT NULL COMMENT 'device_skus.id',
    bind_code VARCHAR(50) NOT NULL UNIQUE COMMENT '出厂激活码（扫码绑定）',
    vendor_org_id BIGINT NOT NULL,
    user_id BIGINT DEFAULT NULL COMMENT '激活绑定的 C 端用户（NULL=未激活）',
    status VARCHAR(20) NOT NULL DEFAULT 'unbound' COMMENT 'unbound|bound|unbound_released',
    bound_at DATETIME DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_code (bind_code),
    INDEX idx_sku (sku_id),
    CONSTRAINT fk_bind_sku FOREIGN KEY (sku_id) REFERENCES device_skus(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='设备出厂绑定表';

-- -----------------------------------------------------------
-- 27. 菜谱内容授权表（厂商可见的菜谱分组）
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS content_licenses (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    vendor_org_id BIGINT NOT NULL,
    content_type VARCHAR(20) NOT NULL DEFAULT 'recipe_group' COMMENT 'recipe_group|ai_creation',
    recipe_group VARCHAR(50) NULL COMMENT '菜谱分组（content_type=recipe_group）',
    creation_id BIGINT NULL COMMENT 'AI 创作 id（content_type=ai_creation）',
    status VARCHAR(20) NOT NULL DEFAULT 'active' COMMENT 'active|disabled',
    licensed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_vendor_group (vendor_org_id, recipe_group),
    UNIQUE KEY uk_vendor_creation (vendor_org_id, creation_id),
    CONSTRAINT fk_lic_vendor FOREIGN KEY (vendor_org_id) REFERENCES orgs(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='菜谱内容授权表（分组级 + AI 创作单品级）';

-- ============================================================
-- 智能厨电中控（A3 / 《拾味_智能厨电中控_技术设计文档》P0–P3）
-- P0：模拟链路（MockGateway）即可验收；Tuya/米家后续接入
-- ============================================================

-- -----------------------------------------------------------
-- 28. 用户设备绑定表
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS kitchen_devices (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '所属用户',
    org_id BIGINT DEFAULT NULL COMMENT '企业ID（B端；空=个人）',
    vendor VARCHAR(20) NOT NULL COMMENT 'tuya|mijia|mock',
    vendor_device_id VARCHAR(100) NOT NULL COMMENT '厂商侧设备ID',
    nickname VARCHAR(100) DEFAULT NULL COMMENT '昵称，如"厨房的烤箱"',
    device_type VARCHAR(30) NOT NULL COMMENT 'oven|steamer|cooktop|airfryer',
    capabilities JSON DEFAULT NULL COMMENT '支持的能力集 JSON',
    online TINYINT NOT NULL DEFAULT 1 COMMENT '在线状态',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_vendor_dev (user_id, vendor, vendor_device_id),
    INDEX idx_user (user_id),
    INDEX idx_org (org_id),
    CONSTRAINT fk_device_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户设备绑定表';

-- -----------------------------------------------------------
-- 28.1 统一菜谱主表（路线甲：由 recipe_metadata 演进 RENAME 而来；全新安装直接建本表）
-- 设计依据：docs/拾味_菜谱来源体系与工业级精确化_设计文档.md（v0.2）
-- 生产库升级路径见 db/migration_recipe_unified_v02.sql（勿重复执行）
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS recipes (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    video_id BIGINT DEFAULT NULL COMMENT '关联视频ID（视频/音频提取来源）',
    org_id BIGINT DEFAULT NULL COMMENT '归属企业',
    user_id BIGINT DEFAULT NULL COMMENT '创建者（AI创作/用户录入）或提取者（视频/图文）',
    title VARCHAR(200) NOT NULL COMMENT '菜名',
    summary TEXT DEFAULT NULL COMMENT '摘要',
    recipe_type VARCHAR(20) NOT NULL DEFAULT 'HOME' COMMENT '派生标签：HOME家常型|INDUSTRIAL工业型（由 precision_score 阈值≥0.7 派生）',
    source VARCHAR(20) NOT NULL DEFAULT 'VIDEO_LINK' COMMENT 'VIDEO_LINK|ARTICLE_LINK|USER|AI_CREATE',
    precision_score DECIMAL(3,2) DEFAULT NULL COMMENT '精确化评分 0~1（§7.0 模型）',
    source_url VARCHAR(1000) DEFAULT NULL COMMENT '来源URL（video/article 必填；ai_create/user 可空）',
    ref_id BIGINT DEFAULT NULL COMMENT '关联资源ID（video_id/article_id/ai_creation_id）',
    extractor_version VARCHAR(50) DEFAULT NULL COMMENT '提取流水线版本',
    extractor_engine VARCHAR(50) DEFAULT NULL COMMENT '提取引擎标识',
    extracted_at DATETIME DEFAULT NULL COMMENT '提取时间',
    raw_digest TEXT DEFAULT NULL COMMENT '原始内容摘要（转录/正文/创作 prompt 摘要，审计用）',
    confidence DECIMAL(3,2) DEFAULT NULL COMMENT '提取置信度 0~1（与 precision_score 分离）',
    parent_recipe_id BIGINT DEFAULT NULL COMMENT '衍生来源 recipes.id（derive 时保留谱系）',
    derivation_note VARCHAR(200) DEFAULT NULL COMMENT '衍生说明',
    ingredients_json JSON DEFAULT NULL COMMENT '食材快照 [{name,amount,unit,is_main,tolerance}]（展示用，权威在 recipe_ingredients）',
    steps_json JSON DEFAULT NULL COMMENT '步骤 [{index,summary,detail,timer_min,temperature,mode}]',
    nutrition_json JSON DEFAULT NULL COMMENT '营养 {calories,protein,fat,carbs,sodium,source}',
    taste_tags VARCHAR(100) DEFAULT NULL COMMENT '口味标签（兼容旧字段名）',
    allergens VARCHAR(255) DEFAULT NULL COMMENT '过敏原',
    spice_level TINYINT DEFAULT 0 COMMENT '辛辣度 0-10',
    cuisine VARCHAR(50) DEFAULT NULL COMMENT '菜系',
    estimated_time INT DEFAULT NULL COMMENT '预计耗时（分钟）',
    calories INT DEFAULT NULL COMMENT '预估热量',
    difficulty VARCHAR(20) DEFAULT 'medium',
    servings INT DEFAULT NULL COMMENT '基准份量（人份）',
    device_params JSON DEFAULT NULL COMMENT '厨电参数（工业型必填）',
    precision_note VARCHAR(200) DEFAULT NULL COMMENT '精确化说明/限制',
    main_ingredients VARCHAR(1000) DEFAULT NULL COMMENT '主料（历史兼容，逗号串）',
    auxiliary_ingredients VARCHAR(1000) DEFAULT NULL COMMENT '辅料/调料（历史兼容）',
    raw_llm_json TEXT DEFAULT NULL COMMENT '原始 LLM 输出（审计）',
    source_type VARCHAR(20) DEFAULT NULL COMMENT '历史：AI 创作镜像=ai_creation',
    creation_id BIGINT DEFAULT NULL COMMENT 'AI 创作镜像回链 ai_creations.id',
    version INT DEFAULT 1 COMMENT '版本（迭代/升级）',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_video (video_id),
    INDEX idx_user (user_id),
    INDEX idx_source (source),
    INDEX idx_type (recipe_type),
    INDEX idx_title (title)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='统一菜谱主表';

-- -----------------------------------------------------------
-- 28.2 菜谱食材明细表（工业型精确用量权威落点；展示快照在 recipes.ingredients_json）
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS recipe_ingredients (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    recipe_id BIGINT NOT NULL COMMENT '关联 recipes.id',
    seq INT NOT NULL DEFAULT 0 COMMENT '顺序',
    name VARCHAR(100) NOT NULL COMMENT '食材名',
    amount DECIMAL(10,2) DEFAULT NULL COMMENT '用量数值（可空=定性）',
    unit VARCHAR(20) DEFAULT NULL COMMENT '单位：g/ml/个/勺/片/瓣/人份',
    tolerance DECIMAL(10,2) DEFAULT NULL COMMENT '误差 ±（可空）',
    is_main TINYINT DEFAULT 0 COMMENT '是否主料 1/0',
    note VARCHAR(200) DEFAULT NULL COMMENT '备注（可替换等）',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_recipe (recipe_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='菜谱食材明细表';

-- -----------------------------------------------------------
-- 28.3 菜谱来源流水表（溯源审计：每次提取/迭代/升级/衍生一条）
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS recipe_sources (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    recipe_id BIGINT NOT NULL COMMENT '关联 recipes.id',
    source VARCHAR(20) NOT NULL COMMENT 'VIDEO_LINK|ARTICLE_LINK|USER|AI_CREATE',
    source_url VARCHAR(1000) DEFAULT NULL COMMENT '来源URL（ai_create/user 可空）',
    ref_id BIGINT DEFAULT NULL COMMENT '关联资源ID',
    extractor_version VARCHAR(50) DEFAULT NULL COMMENT '提取流水线版本',
    extractor_engine VARCHAR(50) DEFAULT NULL COMMENT '提取引擎标识',
    raw_digest TEXT DEFAULT NULL COMMENT '原始内容摘要（审计用）',
    confidence DECIMAL(3,2) DEFAULT NULL COMMENT '提取置信度',
    extracted_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '提取时间',
    INDEX idx_recipe (recipe_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='菜谱来源流水表';

-- -----------------------------------------------------------
-- 29. 菜谱-设备参数表（流水线 device_params → 播放页按步骤匹配）
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS recipe_device_params (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    recipe_id BIGINT NOT NULL COMMENT '关联 recipes.id',
    step_index INT NOT NULL DEFAULT 0 COMMENT '关联第几步（0=全程）',
    device_type VARCHAR(30) NOT NULL COMMENT 'oven|steamer|cooktop|airfryer',
    mode VARCHAR(50) DEFAULT NULL COMMENT '烘焙/蒸/空气炸/烧烤等模式',
    temperature INT DEFAULT NULL COMMENT '温度 ℃',
    duration_min INT DEFAULT NULL COMMENT '时长（分钟）',
    note VARCHAR(200) DEFAULT NULL COMMENT '备注，如"预热至200℃"',
    extra JSON DEFAULT NULL COMMENT '扩展参数：风扇/层架/预热等',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_recipe (recipe_id),
    INDEX idx_recipe_step (recipe_id, step_index)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='菜谱设备参数表';

-- -----------------------------------------------------------
-- 30. 指令下发日志表
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS device_command_logs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    device_id BIGINT NOT NULL COMMENT 'kitchen_devices.id',
    param_id BIGINT DEFAULT NULL COMMENT 'recipe_device_params.id',
    command JSON NOT NULL COMMENT '下发指令内容',
    status VARCHAR(20) NOT NULL DEFAULT 'queued' COMMENT 'queued|sent|success|failed',
    result TEXT DEFAULT NULL COMMENT '厂商返回结果',
    error_message TEXT DEFAULT NULL COMMENT '失败原因',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at DATETIME DEFAULT NULL,
    INDEX idx_device (device_id),
    INDEX idx_status (status),
    CONSTRAINT fk_cmd_device FOREIGN KEY (device_id) REFERENCES kitchen_devices(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='设备指令下发日志表';

-- -----------------------------------------------------------
-- 31. 设备事件流表（事件入库可回放/审计）
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS device_events (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    device_id BIGINT NOT NULL COMMENT 'kitchen_devices.id',
    vendor_org_id BIGINT DEFAULT NULL COMMENT '厂商租户（A2 联动，可选）',
    event_type VARCHAR(30) NOT NULL COMMENT 'preheat_done|work_started|work_done|offline|online',
    payload JSON DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_device_time (device_id, created_at),
    CONSTRAINT fk_evt_device FOREIGN KEY (device_id) REFERENCES kitchen_devices(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='设备事件流表';

-- -----------------------------------------------------------
-- 32. 支付单表（B3 支付网关；当前 channel=mock 模拟支付）
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS payment_orders (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    invoice_id BIGINT NOT NULL COMMENT '关联 invoices.id',
    org_id BIGINT NOT NULL COMMENT '企业',
    user_id BIGINT NOT NULL COMMENT '支付人',
    amount DECIMAL(10,2) NOT NULL COMMENT '支付金额',
    channel VARCHAR(20) NOT NULL DEFAULT 'mock' COMMENT 'mock|alipay|wechat（当前模拟）',
    status VARCHAR(20) NOT NULL DEFAULT 'created' COMMENT 'created|success|failed|canceled',
    pay_no VARCHAR(64) DEFAULT NULL COMMENT '支付流水号',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    paid_at DATETIME DEFAULT NULL,
    INDEX idx_org (org_id),
    INDEX idx_invoice (invoice_id),
    CONSTRAINT fk_pay_invoice FOREIGN KEY (invoice_id) REFERENCES invoices(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='支付单表';

-- ============================================================
-- 方向 C：开放平台（OpenAPI）
-- ============================================================

-- -----------------------------------------------------------
-- 33. 第三方应用表（API Key/Secret + 授权范围 + 限流）
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS api_clients (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    org_id BIGINT DEFAULT NULL COMMENT '归属企业（个人开发者可空）',
    user_id BIGINT NOT NULL COMMENT '创建者',
    app_key VARCHAR(64) NOT NULL UNIQUE,
    app_secret VARCHAR(128) NOT NULL COMMENT '签名密钥（存明文以便 HMAC 校验；生产可加密）',
    name VARCHAR(100) NOT NULL COMMENT '应用名',
    scopes JSON NOT NULL COMMENT '授权能力：recipe/search/device/voice',
    rate_limit INT NOT NULL DEFAULT 60 COMMENT '每分钟请求上限',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '0=禁用 1=启用',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_org (org_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='第三方应用表';

-- -----------------------------------------------------------
-- 34. 开放平台调用计量表
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS api_usage_records (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    client_id BIGINT NOT NULL,
    api_path VARCHAR(200) NOT NULL,
    bytes BIGINT NOT NULL DEFAULT 0 COMMENT '流量',
    status INT NOT NULL DEFAULT 200,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_client_time (client_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='开放平台调用记录表';

-- ============================================================
-- 「酿味」AI 菜品创作模块（P0）— 详见 docs/拾味_酿味AI菜品创作_技术设计文档.md（v0.4）
-- P0 建 5 张表；ai_video_tasks / ai_creation_videos 属 P2 再建（勿提前建空表）
-- ⚠️ MyBatis-Plus 无自动 DDL：除本脚本外需对 dev/prod 库手工执行一次
-- ============================================================

-- -----------------------------------------------------------
-- 20. 酿味创作主表
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_creations (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '所属用户',
    title VARCHAR(100) NOT NULL COMMENT '菜名（AI 起名）',
    summary VARCHAR(500) DEFAULT NULL COMMENT '创作理念一句话',
    inspiration VARCHAR(20) DEFAULT 'chat' COMMENT 'chat|photo|profile',
    main_ingredients VARCHAR(2000) DEFAULT NULL COMMENT '主料 JSON 数组',
    auxiliary_ingredients VARCHAR(2000) DEFAULT NULL COMMENT '辅料/调料 JSON 数组',
    taste_tags VARCHAR(1000) DEFAULT NULL COMMENT '口味标签 JSON 数组',
    allergens VARCHAR(1000) DEFAULT NULL COMMENT '过敏原 JSON 数组',
    spice_level INT DEFAULT 0 COMMENT '辛辣度 0-10',
    calories INT DEFAULT NULL COMMENT '预估热量（千卡）',
    estimated_time INT DEFAULT NULL COMMENT '预计耗时（分钟）',
    difficulty VARCHAR(20) DEFAULT 'medium' COMMENT 'easy|medium|hard',
    steps TEXT NOT NULL COMMENT '图文步骤 JSON [{index,summary,detail,timer_min,video_prompt}]',
    device_params VARCHAR(2000) DEFAULT NULL COMMENT '厨电中控设备参数 JSON（可选）',
    sauce_json JSON DEFAULT NULL COMMENT '酱汁配方（SauceDesignService 输出）',
    effect_image VARCHAR(500) DEFAULT NULL COMMENT 'AI 效果图路径（effect/ 相对路径）',
    effect_image_style VARCHAR(20) DEFAULT NULL COMMENT '效果图风格（realistic|plated|illustration）',
    source_type VARCHAR(20) DEFAULT 'ai_creation' COMMENT '内容来源标记（v0.4 预留，P2 升 B 用）',
    creation_id BIGINT DEFAULT NULL COMMENT '自关联：衍生创作指向来源 ai_creations.id',
    session_id BIGINT DEFAULT NULL COMMENT '生成该创作的会话 ai_creation_sessions.id',
    version INT DEFAULT 1 COMMENT '菜谱版本号',
    iter_count INT DEFAULT 0 COMMENT '已迭代次数',
    status VARCHAR(20) DEFAULT 'published' COMMENT 'draft|published|archived',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_creation_user (user_id),
    INDEX idx_creation_source (source_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='酿味创作主表';

-- -----------------------------------------------------------
-- 21. 酿味创作会话表
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_creation_sessions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '所属用户',
    title VARCHAR(100) DEFAULT 'New Creation' COMMENT '会话标题（客户端默认显示「新创作」）',
    status VARCHAR(20) NOT NULL DEFAULT 'CREATED' COMMENT 'CREATED|GATHERING|CONFIRMED|GENERATING|RECIPE_READY|VIDEO_GEN|VIDEO_READY|DONE',
    intent_json TEXT DEFAULT NULL COMMENT '当前意图约束快照 JSON',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_creation_session_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='酿味创作会话表';

-- -----------------------------------------------------------
-- 22. 酿味会话消息表
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_creation_messages (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT NOT NULL COMMENT '关联 ai_creation_sessions.id',
    role VARCHAR(10) NOT NULL COMMENT 'user|assistant',
    content TEXT DEFAULT NULL COMMENT '文本内容',
    attachments TEXT DEFAULT NULL COMMENT '附件 JSON（P1 启用）',
    intent_json TEXT DEFAULT NULL COMMENT 'assistant 消息附带的意图快照 JSON',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_creation_msg_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='酿味会话消息表';

-- -----------------------------------------------------------
-- 23. 酿味菜谱版本快照表
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_creation_versions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    creation_id BIGINT NOT NULL COMMENT '关联 ai_creations.id',
    version INT NOT NULL COMMENT '版本号，1 起递增',
    steps_json TEXT NOT NULL COMMENT '该版本的菜谱步骤快照 JSON',
    delta_note VARCHAR(300) DEFAULT NULL COMMENT '相对上一版的修改说明',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_creation_version (creation_id, version),
    INDEX idx_creation_ver_creation (creation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='酿味菜谱版本快照表';

-- -----------------------------------------------------------
-- 24. 酿味口味反馈表
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_creation_feedbacks (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    creation_id BIGINT NOT NULL COMMENT '关联 ai_creations.id',
    user_id BIGINT NOT NULL COMMENT '反馈用户',
    rating VARCHAR(10) NOT NULL COMMENT 'delicious|ok|failed',
    comment VARCHAR(200) DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_creation_fb_creation (creation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='酿味口味反馈表';

-- -----------------------------------------------------------
-- 25. 酿味视频生成任务表（每步骤一条；P2）
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_video_tasks (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    creation_id BIGINT NOT NULL COMMENT '关联 ai_creations.id',
    step_index INT NOT NULL COMMENT '对应步骤序号',
    provider VARCHAR(20) NOT NULL COMMENT 'wan|seedance|mock',
    prompt TEXT DEFAULT NULL COMMENT '该步骤 video_prompt',
    status VARCHAR(20) DEFAULT 'queued' COMMENT 'queued|running|completed|failed',
    progress INT DEFAULT 0,
    task_ref VARCHAR(200) DEFAULT NULL COMMENT '厂商侧任务ID',
    video_path VARCHAR(500) DEFAULT NULL COMMENT '生成片段本地路径',
    duration_sec DECIMAL(10,2) DEFAULT NULL COMMENT '片段时长（秒）',
    error_message TEXT DEFAULT NULL,
    retry_count INT DEFAULT 0,
    gen_round INT DEFAULT 1 COMMENT '重创轮次',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at DATETIME DEFAULT NULL,
    INDEX idx_vt_creation (creation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='酿味视频生成任务表';

-- -----------------------------------------------------------
-- 25.5 酿味文生图任务表（酱汁效果图，单张整菜；P1）
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_image_tasks (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    creation_id BIGINT NOT NULL COMMENT '关联 ai_creations.id',
    user_id BIGINT NOT NULL COMMENT '所属用户',
    provider VARCHAR(20) NOT NULL COMMENT 'wan_image|mock',
    prompt TEXT DEFAULT NULL COMMENT '文生图提示词（菜名+描述+主辅料+风格）',
    style VARCHAR(20) DEFAULT 'realistic' COMMENT 'realistic|plated|illustration',
    status VARCHAR(20) DEFAULT 'queued' COMMENT 'queued|running|completed|failed',
    progress INT DEFAULT 0,
    task_ref VARCHAR(200) DEFAULT NULL COMMENT '厂商侧任务ID',
    image_path VARCHAR(500) DEFAULT NULL COMMENT '结果图片相对路径（effect/...）',
    cost INT DEFAULT 0 COMMENT '本次消耗（分，usage_records 关联）',
    error_message TEXT DEFAULT NULL,
    retry_count INT DEFAULT 0,
    gen_round INT DEFAULT 1 COMMENT '重生成轮次',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at DATETIME DEFAULT NULL,
    INDEX idx_it_creation (creation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='酿味文生图任务表（酱汁效果图）';

-- -----------------------------------------------------------
-- 26. 酿味创作视频表（拼接产物 + 步骤时间戳；P2）
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_creation_videos (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    creation_id BIGINT NOT NULL UNIQUE COMMENT '关联 ai_creations.id',
    video_path VARCHAR(500) DEFAULT NULL COMMENT '拼接后完整视频',
    duration_sec DECIMAL(10,2) DEFAULT NULL COMMENT '总时长（秒）',
    steps_json TEXT DEFAULT NULL COMMENT '含每步 start/end 时间戳 JSON',
    status VARCHAR(20) DEFAULT 'generating' COMMENT 'generating|ready|failed',
    error_message TEXT DEFAULT NULL COMMENT '失败原因',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_cv_creation (creation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='酿味创作视频表';

-- -----------------------------------------------------------
-- 28. 酿味创作分享表（站外只读分享，P4）
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_creation_shares (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(16) NOT NULL UNIQUE COMMENT '分享码',
    creation_id BIGINT NOT NULL COMMENT '关联 ai_creations.id',
    created_by BIGINT NOT NULL COMMENT '创建者 user_id',
    expires_at DATETIME DEFAULT NULL COMMENT '过期时间（空=永久）',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_shares_creation (creation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='酿味创作分享表';

-- -----------------------------------------------------------
-- 27. 升 B（v0.4）：AI 创作镜像标记（source_type/creation_id）——已并入统一菜谱主表 recipes 的 DDL
-- （recipe_metadata 已演进为 recipes，见 28.1；生产库升级见 db/migration_recipe_unified_v02.sql）
-- -----------------------------------------------------------

-- -----------------------------------------------------------
-- 32. 营养健康模块 P0：食物营养库表（设计文档《拾味_营养健康模块_技术设计文档.md》§4.2）
-- 种子数据由后端启动时 FoodLibraryService 按 health.nutrition.food-db-init 初始化（幂等），此处仅建表。
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS nutrition_food_db (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL COMMENT '食材名（中文）',
    name_en VARCHAR(100) DEFAULT NULL COMMENT '英文名',
    category VARCHAR(50) NOT NULL COMMENT '主食/蔬菜/水果/肉类/海鲜/蛋奶/豆类/坚果/饮品/其他',
    calories DECIMAL(6,1) DEFAULT 0 COMMENT '热量 kcal/100g',
    protein DECIMAL(6,1) DEFAULT 0 COMMENT '蛋白 g/100g',
    fat DECIMAL(6,1) DEFAULT 0 COMMENT '脂肪 g/100g',
    carbs DECIMAL(6,1) DEFAULT 0 COMMENT '碳水 g/100g',
    fiber DECIMAL(6,1) DEFAULT 0 COMMENT '纤维 g/100g',
    sodium DECIMAL(6,1) DEFAULT 0 COMMENT '钠 mg/100g（可扩展）',
    is_core TINYINT DEFAULT 0 COMMENT '是否内置常用（核心 83 种）',
    source VARCHAR(20) DEFAULT 'seed' COMMENT 'seed|user|ai',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_name (name),
    INDEX idx_category (category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='食物营养库';

-- -----------------------------------------------------------
-- 33. 营养健康模块 P1：健康档案表（TDEE 个性化营养目标）
-- 设计文档《拾味_营养健康模块_技术设计文档.md》§4.1；Mifflin-St Jeor + 15/25/60 宏量分解
-- -----------------------------------------------------------
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
    medications VARCHAR(500) DEFAULT NULL COMMENT '用药情况（自愿，AES 密文，仅本人可见）',
    conditions VARCHAR(500) DEFAULT NULL COMMENT '疾病情况（痛风/糖尿病/高血压/孕产等，自愿，AES 密文，仅本人可见）',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='健康档案表';

-- -----------------------------------------------------------
-- 34. 营养健康模块 P1：饮食记录表（跟做即记录 + 手动录入）
-- 设计文档《拾味_营养健康模块_技术设计文档.md》§4.3；checkin_id 弱关联打卡（级联删除）
-- -----------------------------------------------------------
-- -----------------------------------------------------------
-- 34a. 软删除迁移（2026-08-18 r114/r115）：cooking_checkins / cooking_plans / diet_records / user_works / xunwei_memory 新增 deleted 列
--      已同步进上方各 CREATE TABLE；对已存在的旧库执行下面语句即可（幂等，已存在会报错可忽略）：
--      ALTER TABLE cooking_checkins ADD COLUMN deleted TINYINT NOT NULL DEFAULT 0 COMMENT '0=正常 1=软删除';
--      ALTER TABLE cooking_plans    ADD COLUMN deleted TINYINT NOT NULL DEFAULT 0 COMMENT '0=正常 1=软删除';
--      ALTER TABLE diet_records     ADD COLUMN deleted TINYINT NOT NULL DEFAULT 0 COMMENT '0=正常 1=软删除';
--      ALTER TABLE user_works      ADD COLUMN deleted TINYINT NOT NULL DEFAULT 0 COMMENT '0=正常 1=软删除';
-- 34b. 回收站删除时间（2026-08-18 r115）：五表新增 deleted_at（软删除时间，回收站列表/恢复用）：
--      ALTER TABLE cooking_checkins ADD COLUMN deleted_at DATETIME DEFAULT NULL COMMENT '软删除时间';
--      ALTER TABLE cooking_plans    ADD COLUMN deleted_at DATETIME DEFAULT NULL COMMENT '软删除时间';
--      ALTER TABLE diet_records     ADD COLUMN deleted_at DATETIME DEFAULT NULL COMMENT '软删除时间';
--      ALTER TABLE user_works      ADD COLUMN deleted_at DATETIME DEFAULT NULL COMMENT '软删除时间';
--      ALTER TABLE xunwei_memory   ADD COLUMN deleted_at DATETIME DEFAULT NULL COMMENT '软删除时间';
-- 34c. 归档表（回收站「彻底删除」自动归档）：见文末 40 节
-- -----------------------------------------------------------
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
    photo_id BIGINT DEFAULT NULL COMMENT '来源照片（拍照打卡时非空）',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '0=正常 1=软删除',
    deleted_at DATETIME DEFAULT NULL COMMENT '软删除时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_date (user_id, record_date),
    INDEX idx_user_meal (user_id, meal_type),
    INDEX idx_checkin (checkin_id),
    INDEX idx_photo (photo_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='饮食记录表';

-- -----------------------------------------------------------
-- 35. 营养健康模块 P3：体重记录表（每日一条，同人同日 upsert）
-- 设计文档《拾味_营养健康模块_技术设计文档.md》§4.4；联动 health_profiles.target_weight
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS weight_logs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    weight DECIMAL(5,1) NOT NULL COMMENT '体重 kg',
    log_date DATE NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_date (user_id, log_date),
    INDEX idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='体重记录表';

-- -----------------------------------------------------------
-- 36. 食材反查模块 P0：食材标签索引 + 同义词映射（2026-08-14）
-- 设计文档《拾味_食材反查与内容源_技术设计文档.md》§6
-- 说明：仅建表；ingredient_tags 由后端 IngredientIndexService 重建（幂等）。
--       已执行迁移：db/migration_ingredient_reverse_v01.sql
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS ingredient_tags (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL COMMENT '食材名（归一化后）',
    canonical_name VARCHAR(100) DEFAULT NULL COMMENT '规范名（同义归并后）',
    category VARCHAR(50) DEFAULT NULL COMMENT '分类（联动 nutrition_food_db.category）',
    frequency INT DEFAULT 0 COMMENT '出现频次（菜谱数）',
    is_main_only TINYINT DEFAULT 0 COMMENT '是否仅主料维度统计',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_name (name),
    INDEX idx_frequency (frequency),
    INDEX idx_category (category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='食材标签索引';

CREATE TABLE IF NOT EXISTS food_aliases (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    alias VARCHAR(100) NOT NULL COMMENT '别名（"里脊肉"/"土豆"）',
    canonical VARCHAR(100) NOT NULL COMMENT '规范名（"猪肉"/"马铃薯"）',
    source VARCHAR(20) DEFAULT 'manual' COMMENT 'manual|ai',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_alias (alias),
    INDEX idx_canonical (canonical)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='食材同义词映射';

-- -----------------------------------------------------------
-- 40. 回收站归档表（2026-08-18 r115）：回收站「彻底删除」自动归档审计副本
--     设计文档《拾味Web端管理架构改造设计文档_双壳分离与个人用户管理.md》§3.3
--     结构 = 原表字段 + original_id（原表ID）+ archived_at（归档时间）+ archived_by（操作管理员ID）
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS cooking_checkins_archive (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    original_id BIGINT NOT NULL COMMENT '原表ID',
    user_id BIGINT NOT NULL,
    checkin_date DATE NOT NULL,
    video_id BIGINT DEFAULT NULL,
    recipe_title VARCHAR(255) DEFAULT NULL,
    meal_type VARCHAR(20) DEFAULT 'dinner',
    mood VARCHAR(20) DEFAULT 'normal',
    note VARCHAR(500) DEFAULT NULL,
    calories INT DEFAULT NULL,
    source VARCHAR(20) DEFAULT 'manual',
    created_at DATETIME DEFAULT NULL,
    deleted_at DATETIME DEFAULT NULL,
    archived_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '归档时间',
    archived_by BIGINT DEFAULT NULL COMMENT '操作管理员ID',
    INDEX idx_arch_user_date (user_id, checkin_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='打卡归档（回收站彻底删除自动归档）';

CREATE TABLE IF NOT EXISTS cooking_plans_archive (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    original_id BIGINT NOT NULL COMMENT '原表ID',
    user_id BIGINT NOT NULL,
    title VARCHAR(100) NOT NULL DEFAULT 'Weekly Plan',
    plan_type VARCHAR(20) NOT NULL DEFAULT 'weekly',
    start_date DATE NOT NULL,
    created_at DATETIME DEFAULT NULL,
    deleted_at DATETIME DEFAULT NULL,
    archived_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    archived_by BIGINT DEFAULT NULL,
    INDEX idx_arch_user (user_id, start_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='做饭计划归档';

CREATE TABLE IF NOT EXISTS diet_records_archive (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    original_id BIGINT NOT NULL COMMENT '原表ID',
    user_id BIGINT NOT NULL,
    record_date DATE NOT NULL,
    meal_type VARCHAR(20) NOT NULL,
    recipe_id BIGINT DEFAULT NULL,
    checkin_id BIGINT DEFAULT NULL,
    items JSON NOT NULL,
    calories DECIMAL(8,1) DEFAULT 0,
    protein DECIMAL(8,1) DEFAULT 0,
    fat DECIMAL(8,1) DEFAULT 0,
    carbs DECIMAL(8,1) DEFAULT 0,
    portion_factor DECIMAL(3,2) DEFAULT 1.00,
    estimated TINYINT DEFAULT 0,
    source VARCHAR(20) DEFAULT 'cook',
    note VARCHAR(200) DEFAULT NULL,
    photo_id BIGINT DEFAULT NULL,
    created_at DATETIME DEFAULT NULL,
    deleted_at DATETIME DEFAULT NULL,
    archived_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    archived_by BIGINT DEFAULT NULL,
    INDEX idx_arch_user_date (user_id, record_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='饮食记录归档';

CREATE TABLE IF NOT EXISTS user_works_archive (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    original_id BIGINT NOT NULL COMMENT '原表ID',
    user_id BIGINT NOT NULL,
    video_id BIGINT DEFAULT NULL,
    recipe_title VARCHAR(255) DEFAULT NULL,
    title VARCHAR(255) DEFAULT NULL,
    description VARCHAR(1000) DEFAULT NULL,
    images VARCHAR(2000) DEFAULT NULL COMMENT '图片相对路径 JSON（彻底删除时磁盘图一并删，归档保留路径）',
    status VARCHAR(20) DEFAULT 'public',
    created_at DATETIME DEFAULT NULL,
    deleted_at DATETIME DEFAULT NULL,
    archived_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    archived_by BIGINT DEFAULT NULL,
    INDEX idx_arch_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='做菜作品归档（含图片路径）';

-- -----------------------------------------------------------
-- 41. 寻味记忆归档（P2：回收站把 xunwei_memory 纳入治理）
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS xunwei_memory_archive (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    original_id BIGINT NOT NULL COMMENT '原表ID',
    user_id BIGINT NOT NULL,
    mem_key VARCHAR(50) NOT NULL,
    mem_value TEXT NOT NULL,
    mem_type VARCHAR(20) NOT NULL DEFAULT 'profile',
    confidence INT NOT NULL DEFAULT 60,
    source VARCHAR(30) DEFAULT NULL,
    ref_id BIGINT DEFAULT NULL,
    hit_count INT NOT NULL DEFAULT 1,
    updated_at DATETIME DEFAULT NULL,
    deleted_at DATETIME DEFAULT NULL,
    archived_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    archived_by BIGINT DEFAULT NULL,
    INDEX idx_arch_mem_user (user_id, mem_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='寻味记忆归档';

-- -----------------------------------------------------------
-- 42. 回收站管理审计日志（P2：记录恢复/彻底删除操作留痕）
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS admin_audit_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    operator_id BIGINT DEFAULT NULL COMMENT '操作管理员ID（系统任务为NULL）',
    action VARCHAR(20) NOT NULL COMMENT 'restore/purge',
    target_type VARCHAR(20) NOT NULL COMMENT 'checkins/diet/plans/works/memories',
    target_id BIGINT NOT NULL,
    detail VARCHAR(500) DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_audit_created (created_at),
    INDEX idx_audit_type (target_type, target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='回收站管理审计日志';

-- -----------------------------------------------------------
-- 42.1 回收站超期自动清理配置（2026-08-23 r159）：管理员可查看/修改保留期与执行 cron、启停、手动触发
--     单行配置（id=1）；运行时读取，改配置即时生效；迁移脚本：db/migration_recycle_settings_v04.sql
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS recycle_settings (
    id BIGINT PRIMARY KEY COMMENT '固定 1（单行配置）',
    retention_days INT NOT NULL DEFAULT 90 COMMENT '软删保留天数，超过后自动归档+彻底删除',
    purge_cron VARCHAR(40) NOT NULL DEFAULT '0 30 3 * * *' COMMENT '每日执行 cron',
    enabled TINYINT NOT NULL DEFAULT 1 COMMENT '1=启用自动清理 0=停用（手动触发不受影响）',
    last_run_at DATETIME DEFAULT NULL COMMENT '最近一次执行时间',
    last_run_count INT DEFAULT 0 COMMENT '最近一次清理条数',
    last_run_detail VARCHAR(500) DEFAULT NULL COMMENT '最近一次清理明细',
    updated_by BIGINT DEFAULT NULL COMMENT '最近更新人',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='回收站超期自动清理配置';

INSERT INTO recycle_settings (id, retention_days, purge_cron, enabled) VALUES (1, 90, '0 30 3 * * *', 1)
    ON DUPLICATE KEY UPDATE id = id;

-- -----------------------------------------------------------
-- 43. 食物安全提醒知识库 P0：安全规则表（设计文档《拾味_食物安全提醒知识库_技术设计文档.md》§5/§13/§14）
-- 52 条种子数据由 SafetyRuleSeedData 代码种子启动幂等灌入（SafetyCheckService.seedIfEmpty）
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS food_safety_rules (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    category VARCHAR(20) NOT NULL COMMENT 'MED|PROC|IND|ABS|MYTH（辟谣）',
    risk_level VARCHAR(10) NOT NULL COMMENT 'RED|YELLOW|GREEN（MYTH 为 GREEN 辟谣）',
    trigger_type VARCHAR(20) NOT NULL COMMENT 'ingredient(食材)|combination(组合)|user_condition(用户条件)',
    trigger_value VARCHAR(200) NOT NULL COMMENT '触发值：食材名/组合/用户条件（逗号/斜杠分隔多值）',
    related_food VARCHAR(200) DEFAULT NULL COMMENT '关联食物（组合另一侧 / 忌口食物展示）',
    title VARCHAR(100) NOT NULL COMMENT '规则标题',
    advice TEXT NOT NULL COMMENT '建议/提示文案（服务层附加免责声明）',
    source VARCHAR(100) DEFAULT NULL COMMENT '权威出处（机构/资料类型）',
    source_url VARCHAR(300) DEFAULT NULL COMMENT '精确出处 URL（采集管道阶段回填）',
    confidence INT DEFAULT 70 COMMENT '置信度 1-100（L3 灰度，扩充集 50）',
    version INT DEFAULT 1 COMMENT '规则版本（支持回滚）',
    enabled TINYINT DEFAULT 1 COMMENT '是否启用',
    deleted TINYINT DEFAULT 0 COMMENT '软删除 0/1',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_category (category),
    INDEX idx_trigger (trigger_type, trigger_value),
    INDEX idx_deleted (deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='食物安全规则库';

-- -----------------------------------------------------------
-- 44. 食物安全规则候选/审核队列（采集管道：LLM 提取候选 + 人工审核后入库）
-- 设计文档《拾味_食物安全提醒知识库_技术设计文档.md》§1.2/§1.3
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS food_safety_rule_candidates (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    category VARCHAR(20) NOT NULL COMMENT 'MED|PROC|IND|ABS|MYTH',
    risk_level VARCHAR(10) NOT NULL COMMENT 'RED|YELLOW|GREEN',
    trigger_type VARCHAR(20) NOT NULL COMMENT 'ingredient|combination|user_condition',
    trigger_value VARCHAR(200) NOT NULL COMMENT '触发值',
    related_food VARCHAR(200) DEFAULT NULL,
    title VARCHAR(100) NOT NULL,
    advice TEXT NOT NULL,
    source VARCHAR(100) DEFAULT NULL,
    source_url VARCHAR(300) DEFAULT NULL,
    source_text TEXT COMMENT '原始资料摘要（溯源）',
    llm_json TEXT COMMENT 'LLM 原始返回（审计）',
    status VARCHAR(20) DEFAULT 'pending' COMMENT 'pending|approved|rejected',
    confidence INT DEFAULT 70,
    reviewed_at DATETIME DEFAULT NULL,
    reviewed_by BIGINT DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_status (status),
    INDEX idx_trigger (trigger_type, trigger_value)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='食物安全规则候选/审核队列';

-- -----------------------------------------------------------
-- 45. 食物安全规则用户反馈（反馈闭环：有用/无用/纠错 → 降级/人工复核）
-- 设计文档《拾味_食物安全提醒知识库_技术设计文档.md》§1.5
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS food_safety_rule_feedback (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    rule_id BIGINT NOT NULL COMMENT 'food_safety_rules.id',
    user_id BIGINT NOT NULL,
    useful TINYINT DEFAULT NULL COMMENT '1 有用 / 0 无用',
    comment VARCHAR(500) DEFAULT NULL COMMENT '纠错/建议',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_rule (rule_id),
    INDEX idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='食物安全规则反馈';

-- -----------------------------------------------------------
-- 44. 拍照打卡 P0（设计文档《拾味_拍照打卡_技术设计文档.md》v0.2 §5）
--     meal_photos（含软删/回收站列）+ meal_photos_archive 归档表 + diet_records.photo_id 扩展
--     迁移脚本：db/migration_photo_checkin_v01.sql
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS meal_photos (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    photo_path VARCHAR(500) NOT NULL COMMENT '照片存储路径 photos/yyyy/MM/dd/<userId>_<ts>_<seq>.jpg（相对路径）',
    meal_type VARCHAR(20) NOT NULL COMMENT 'breakfast|lunch|dinner|snack',
    record_date DATE NOT NULL COMMENT '记录日期',
    recognition_json JSON COMMENT '多菜识别原始结果 [{dishName, confidence, source:vision}]',
    portion_json JSON COMMENT '份量/营养估算 [{dishName, portionGrams, method:default|manual, calories, protein, fat, carbs, estimated}]',
    status VARCHAR(20) DEFAULT 'pending' COMMENT 'pending(待确认)|confirmed(已确认)|corrected(已修正)',
    diet_record_id BIGINT DEFAULT NULL COMMENT '确认后关联的 diet_records.id',
    corrected_at DATETIME DEFAULT NULL,
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '0=正常 1=软删除（回收站）',
    deleted_at DATETIME DEFAULT NULL COMMENT '软删除时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_date (user_id, record_date),
    INDEX idx_photo_diet (diet_record_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='拍照打卡记录表';

CREATE TABLE IF NOT EXISTS meal_photos_archive (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    original_id BIGINT NOT NULL COMMENT '原表ID',
    user_id BIGINT NOT NULL,
    photo_path VARCHAR(500) NOT NULL,
    meal_type VARCHAR(20) NOT NULL,
    record_date DATE NOT NULL,
    recognition_json JSON,
    portion_json JSON,
    status VARCHAR(20),
    diet_record_id BIGINT DEFAULT NULL,
    corrected_at DATETIME DEFAULT NULL,
    deleted_at DATETIME DEFAULT NULL,
    archived_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    archived_by BIGINT DEFAULT NULL,
    INDEX idx_arch_user_date (user_id, record_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='拍照打卡归档（回收站彻底删除自动归档）';

-- diet_records 扩展 photo_id（对已存在的旧库执行；新库请同步上方 diet_records DDL 加列。幂等，重复执行报错可忽略）
-- ALTER TABLE diet_records ADD COLUMN photo_id BIGINT DEFAULT NULL COMMENT '来源照片（拍照打卡时非空）', ADD INDEX idx_photo (photo_id);
-- ALTER TABLE diet_records_archive ADD COLUMN photo_id BIGINT DEFAULT NULL COMMENT '来源照片（归档审计同步）';

-- -----------------------------------------------------------
-- 45. 拍照打卡 P1：菜品级营养库 dish_nutrition_db（设计文档 v0.2 §5.3）
--     种子（100~200 人工复核核心家常菜）由 DishNutritionSeedData 代码幂等灌入
--     迁移脚本：db/migration_photo_checkin_v02.sql；软删/回收站：db/migration_dish_recycle_v03.sql
-- -----------------------------------------------------------
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
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '0=正常 1=软删除（回收站）',
    deleted_at DATETIME DEFAULT NULL COMMENT '软删除时间',
    UNIQUE KEY uk_dish (dish_name),
    INDEX idx_hit (hit_count)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='菜品级营养库';

-- 45.1 菜品级营养库归档表（回收站「彻底删除」自动归档，对齐 meal_photos_archive）
CREATE TABLE IF NOT EXISTS dish_nutrition_db_archive (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    original_id BIGINT NOT NULL COMMENT '原表ID',
    dish_name VARCHAR(100) NOT NULL,
    aliases JSON,
    cuisine VARCHAR(20),
    main_ingredients JSON,
    nutrition_per_serving JSON NOT NULL,
    serving_grams INT DEFAULT 300,
    is_verified TINYINT DEFAULT 0,
    source VARCHAR(20),
    hit_count INT DEFAULT 0,
    deleted_at DATETIME DEFAULT NULL,
    archived_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    archived_by BIGINT DEFAULT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='菜品级营养库归档表（回收站彻底删除）';

-- -----------------------------------------------------------
-- 46. AI 模型配置中心（设计文档《拾味_AI模型配置中心_技术设计文档_v0.3.md》§4）
--     三表：ai_provider_configs（引擎配置）/ ai_config_change_logs（变更日志，api_key 打码）
--           / ai_config_settings（系统级设置，加密总钥匙）
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_provider_configs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    provider_type VARCHAR(20) NOT NULL COMMENT 'llm|vision|video_gen|image_gen|asr|tts',
    provider_key VARCHAR(50) NOT NULL COMMENT 'llm.xunwei/vision.qwen-vl/video_gen.wan/video_gen.seedance/image_gen.wan_image/asr.aliyun/asr.funasr/tts.qwen3',
    display_name VARCHAR(100) COMMENT '展示名（如 通义万相 Wan / 语音讲解 qwen3-tts）',
    endpoint VARCHAR(500) COMMENT 'API 地址（base-url）',
    api_key VARCHAR(500) COMMENT '密钥（AiConfigCipher AES 加密存储）',
    model VARCHAR(100) COMMENT '模型名',
    extra JSON COMMENT '扩展参数 {temperature,timeout_ms,size,voice,format,sample_rate,query_endpoint,engine,host,port,resolution,style,...}',
    mock_enabled TINYINT DEFAULT 0 COMMENT '统一 mock 开关（对应各引擎独立 mock 标志）',
    is_default TINYINT DEFAULT 0 COMMENT '是否默认启用',
    enabled TINYINT DEFAULT 1 COMMENT '是否启用',
    status VARCHAR(20) DEFAULT 'untested' COMMENT 'untested|healthy|unhealthy',
    updated_by BIGINT DEFAULT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_provider (provider_type, provider_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 引擎配置表';

CREATE TABLE IF NOT EXISTS ai_config_change_logs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    provider_key VARCHAR(50) NOT NULL,
    action VARCHAR(20) NOT NULL COMMENT 'create|update|enable|disable|restore_default|test',
    before_json JSON COMMENT '变更前配置快照（api_key 已打码）',
    after_json JSON COMMENT '变更后配置快照（api_key 已打码）',
    test_result VARCHAR(500) COMMENT '连通性测试结果（含耗时/错误信息）',
    operator BIGINT COMMENT '操作人（null=系统）',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_provider (provider_key, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 配置变更日志';

CREATE TABLE IF NOT EXISTS ai_config_settings (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    config_key VARCHAR(64) NOT NULL COMMENT '如 encryption.master_key',
    config_value VARCHAR(1024) COMMENT '值（总钥匙为明文字符串，见 v0.3 §5.3 威胁模型）',
    description VARCHAR(255),
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_key (config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 配置系统级设置（总钥匙等）';

-- -----------------------------------------------------------
-- 38. 做饭计划执行闭环 P0（v0.3，2026-08-28）
-- 设计文档《拾味_做饭计划执行闭环_技术设计文档.md》§4；迁移：db/migration_plan_execution_v01.sql
-- 38a. cooking_menus — AI 菜单生成记录表
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS cooking_menus (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  plan_id BIGINT DEFAULT NULL COMMENT '关联 cooking_plans（确认后写入）',
  user_id BIGINT NOT NULL,
  menu_week DATE NOT NULL COMMENT '菜单起始日（起始日，v0.4 多天可配）',
  days INT DEFAULT 7 COMMENT '菜单天数（1~N，v0.4）',
  title VARCHAR(100) DEFAULT 'AI 周菜单' COMMENT '计划标题（确认写入 cooking_plans.title，v0.4）',
  generated_by VARCHAR(20) DEFAULT 'ai' COMMENT 'ai|manual',
  constraints_json JSON COMMENT '生成约束 {calories_target, tastes[], taboos[], medications[], cuisine[], max_cook_time, generate_mode, respect_taboos, respect_medications, pattern, meals, days, title}',
  items_json JSON NOT NULL COMMENT '[{day_offset, meal_type, dish_name, recipe_id, nutrition, note, device_type, dish_type}]',
  status VARCHAR(20) DEFAULT 'preview' COMMENT 'preview(预览)|confirmed(已写入计划)|discarded',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_user_week (user_id, menu_week)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 菜单生成记录';

-- -----------------------------------------------------------
-- 38b. shopping_lists — 购物清单表
-- -----------------------------------------------------------
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