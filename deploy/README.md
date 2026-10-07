# 一键部署

## 环境要求

- Java 17+ ([下载](https://adoptium.net/))
- MySQL 8.0+ ([下载](https://dev.mysql.com/downloads/))
- Python 3.10 - 3.12 ([下载](https://www.python.org/downloads/))
- Node.js 18+ ([下载](https://nodejs.org/)) — 仅首次构建时需要
- FFmpeg ([下载](https://ffmpeg.org/download.html))
- DeepSeek API Key ([申请](https://platform.deepseek.com/))

## 快速开始

### 1. 准备数据库

```sql
CREATE DATABASE video_step_locator
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;
```

### 2. 一键初始化（以管理员身份运行）

```powershell
cd scripts
01-setup-env.bat     # 安装 Python 依赖 + 创建存储目录
02-build.bat          # 构建前端 + 后端
03-start.bat          # 启动服务（首次需输入 API Key）
```

### 3. 访问系统

浏览器打开 http://localhost:8080

### 4. 停止服务

```powershell
scripts\04-stop.bat
```

## 目录说明

```
deploy/
├── backend/           # 后端配置模板（application*.yml）
├── pipeline/          # Python 流水线（部署时由 video-step-locator/ 构建产物复制）
├── scripts/           # 部署脚本
│   ├── 01-setup-env.bat
│   ├── 02-build.bat
│   ├── 03-start.bat
│   ├── 04-stop.bat
│   └── 05-status.bat
└── logs/              # 运行日志（运行时生成）
```


