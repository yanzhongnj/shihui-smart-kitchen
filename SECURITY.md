# 安全策略

## 使用者的安全责任

本仓库**不包含任何真实 API 密钥**，所有密钥均需通过环境变量注入。请务必：

1. **不要**把填好密钥的 `.env`、`application-local.yml`、systemd `EnvironmentFile` 提交到任何版本库；
2. 生产环境将密钥文件权限设为 `600`，并使用独立的最小权限账号；
3. 上线前**必须**修改以下默认值：
   - `MYSQL_PASSWORD`（默认 `root`）
   - `JWT_SECRET`（默认示例串，必须替换为 `openssl rand -base64 48` 生成的随机串）
4. 若怀疑密钥已泄露，请**立即在服务商控制台吊销并轮换**，并检查账单是否存在异常调用。

## 已知安全设计说明

| 项 | 说明 |
|---|---|
| JWT | HS256，`app.jwt.expiration-ms` 默认 24 小时；`JWT_SECRET` 必须 ≥256bit |
| AI 配置中心密钥 | AES-GCM 加密落库，主钥存于同库 `ai_config_settings`。属**纵深防御**，不能抵御可读取数据库的攻击者 |
| 健康/敏感字段 | 使用独立密钥域（`SAFETY_CIPHER_KEY`）加密，与 AI 配置隔离 |
| 开放平台鉴权 | `X-Signature = HMAC-SHA256(appSecret, appKey + timestamp + nonce + body)`，时间戳窗口 5 分钟，限流 60 次/分钟 |
| 视频访问 | 支持分享链接与权限控制；生产建议由网关/Nginx 限制直链访问 |

## 支持的版本

安全修复仅针对默认分支的最新版本。
