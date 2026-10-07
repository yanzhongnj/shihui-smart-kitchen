# 智能菜谱视频步骤定位系统 - 部署运行环境说明

## 1. 系统概述

本系统实现从做菜视频中自动识别语音、抽取结构化菜谱步骤，并将步骤精确映射到视频时间戳，支持"点击步骤，视频自动跳转"的交互体验。

### 系统架构

```
[视频文件] ──FFmpeg──▶ [音频] ──WhisperX──▶ [转录+对齐]
                        │                        │
                        └── 离线处理流水线 ──────┘
                                                   │
                                              ┌─────┘
                                              ▼
                                       [LLM 步骤抽取]
                                              │
                                              ▼
                                       [三级时间戳映射]
                                              │
                                              ▼
                                       [steps.json] ──▶ [Web 前端播放器]
```

---

## 2. 运行环境要求

### 2.1 硬件要求

| 组件 | 最低配置 | 推荐配置 |
|------|---------|---------|
| CPU | 4 核 2.0 GHz | 8 核 3.0 GHz+ |
| 内存 | 8 GB | 16 GB+ |
| GPU | NVIDIA 显卡 6 GB 显存 | NVIDIA 显卡 8 GB+ 显存 |
| 磁盘空间 | 10 GB（含模型缓存） | 20 GB+（含模型缓存） |
| 网络 | 可访问 HuggingFace / hf-mirror.com | 宽带连接 |

> **注意**：GPU 为可选但强烈推荐。CPU 模式下 WhisperX 转录速度会慢 5-10 倍。

### 2.2 支持的操作系统

| 系统 | 状态 | 说明 |
|------|------|------|
| Windows 10/11 | ✅ 已验证 | 本项目的开发和运行环境 |
| Ubuntu 20.04+ | ✅ 已验证 | 需自行安装依赖 |
| macOS 12+ | ⚠️ 理论支持 | 未在 CI 中测试 |

### 2.3 软件依赖

| 组件 | 版本要求 | 说明 |
|------|---------|------|
| Python | 3.10 ~ 3.12 | **不支持 3.13+**（WhisperX 兼容性） |
| FFmpeg | ≥ 4.4 | 音频提取（通过 imageio-ffmpeg 自动安装） |
| CUDA | 11.8+ | GPU 加速（可选） |
| cuDNN | 8.7+ | GPU 加速（可选） |

### 2.4 Python 依赖包

| 包名 | 版本 | 用途 |
|-----|------|------|
| whisperx | ≥ 3.1.0 | 语音识别 + 单词级时间戳对齐 |
| openai | ≥ 1.0.0 | LLM API 客户端（支持 DeepSeek / GPT） |
| sentence-transformers | ≥ 2.2.0 | 语义匹配回退路径 |
| torch | ≥ 2.0.0、**推荐 2.6.0+** | 深度学习框架 |
| torchvision | 与 torch 版本匹配 | torch 依赖 |
| torchaudio | 与 torch 版本匹配 | torch 依赖 |
| ffmpeg-python | ≥ 0.2.0 | FFmpeg Python 绑定 |
| imageio-ffmpeg | (自动安装) | 内置 FFmpeg 二进制 |
| numpy | ≥ 1.24 | 数值计算 |
| transformers | ≥ 4.30 | WhisperX/HuggingFace 模型加载 |

> **重要**：torch、torchvision、torchaudio 三个包的版本必须互相兼容。推荐使用 torch 2.6.0 + torchvision 0.21.0 + torchaudio 2.6.0（CPU 版）。

---

## 3. 环境搭建步骤

### 3.1 安装 Python 3.12

```bash
# Windows (winget)
winget install Python.Python.3.12

# 或从官网下载: https://www.python.org/downloads/release/python-31210/
# 安装时务必勾选 "Add Python to PATH"
```

验证安装：
```bash
python --version   # 应输出 Python 3.12.x
```

### 3.2 创建虚拟环境

```bash
# 进入项目目录
cd video-step-locator

# 创建虚拟环境
python -m venv .venv

# 激活虚拟环境
# Windows PowerShell:
.\.venv\Scripts\Activate.ps1

# Windows CMD:
.\.venv\Scripts\activate.bat

# Linux / macOS:
source .venv/bin/activate
```

### 3.3 安装项目依赖

```bash
# 使用 setup_env.bat（Windows 推荐）
setup_env.bat

# 或手动执行：
pip install --upgrade pip setuptools wheel

# 安装核心依赖
pip install whisperx sentence-transformers openai ffmpeg-python imageio-ffmpeg

# 安装 PyTorch（CPU 版）
pip install torch==2.6.0+cpu torchvision==0.21.0+cpu torchaudio==2.6.0+cpu --index-url https://download.pytorch.org/whl/cpu

# 或者安装 CUDA 版（需先安装 CUDA 11.8+）
# pip install torch==2.6.0+cu118 torchvision==0.21.0+cu118 torchaudio==2.6.0+cu118 --index-url https://download.pytorch.org/whl/cu118
```

### 3.4 配置网络环境（中国大陆用户）

由于 HuggingFace 在中国大陆无法直接访问，需配置镜像：

```bash
# 方式一：设置环境变量
set HF_ENDPOINT=https://hf-mirror.com

# 方式二：在 run_pipeline.bat 中已自动配置
```

如果镜像下载失败（CAS 401 错误），可手动下载模型文件并放入缓存目录：
- 缓存目录：`%USERPROFILE%\.cache\huggingface\hub\`
- 需下载的模型：WhisperX tiny/large-v2、wav2vec2-large-xlsr-53-chinese-zh-cn

### 3.5 配置 LLM API

本系统需要 LLM API 来从转录文本中提取结构化步骤。支持任何 OpenAI 兼容 API。

**推荐：DeepSeek V4 Flash**

```bash
# Windows CMD
set LLM_API_KEY=sk-your-deepseek-api-key
set LLM_BASE_URL=https://api.deepseek.com/v1
set LLM_MODEL=deepseek-chat

# Windows PowerShell
$env:LLM_API_KEY = "sk-your-deepseek-api-key"
$env:LLM_BASE_URL = "https://api.deepseek.com/v1"
$env:LLM_MODEL = "deepseek-chat"
```

**备选：OpenAI GPT**

```bash
set LLM_API_KEY=sk-your-openai-api-key
set LLM_BASE_URL=https://api.openai.com/v1
set LLM_MODEL=gpt-4o
```

---

## 4. 项目目录结构

```
video-step-locator/
├── input/                   # 待处理视频文件（放入 .mp4）
├── output/                  # 处理结果输出
│   ├── audio/               # 提取的音频 (.wav)
│   ├── segments/            # WhisperX 转录结果 (.json, .txt)
│   ├── steps/               # 最终步骤时间映射 JSON
│   └── transcripts/         # 纯文本转录
├── logs/                    # 运行日志
├── scripts/                 # 后端 Python 处理脚本
│   ├── pipeline.py          # 完整处理流水线（入口）
│   ├── extract_audio.py     # 音频提取
│   ├── transcribe.py        # 语音识别 + 对齐
│   ├── extract_steps.py     # LLM 步骤抽取
│   └── match_timestamps.py  # 时间戳映射（三级匹配）
├── frontend/                # 前端 Web 界面
│   ├── index.html           # 主页面（Video.js 播放器）
│   ├── app.js               # 交互逻辑
│   └── style.css            # 样式
├── tests/                   # 测试用例
│   ├── test_extract_audio.py
│   ├── test_transcribe.py
│   ├── test_extract_steps.py
│   ├── test_match_timestamps.py
│   └── test_pipeline.py
├── run_pipeline.bat         # 后端处理批处理脚本
├── run_frontend.bat         # 前端启动批处理脚本
├── run_all.bat              # 一键运行（处理 + 前端）
├── setup_env.bat            # 环境自动安装脚本
├── requirements.txt         # Python 依赖
├── DEPLOY.md                # 本部署文档
└── README.md                # 项目说明
```

---

## 5. 运行方式

### 5.1 一键运行（推荐）

```bash
# 1. 先安装环境（仅首次）
setup_env.bat

# 2. 放入视频到 input/ 目录

# 3. 一键运行（处理视频 + 启动前端）
run_all.bat
```

### 5.2 分步运行

```bash
# 步骤 1：处理视频
run_pipeline.bat

# 步骤 2：启动前端
run_frontend.bat
```

### 5.3 手动运行后端管线

```bash
# 激活虚拟环境后：
python scripts/pipeline.py --input input/cooking.mp4 --output output ^
    --model tiny --device cpu ^
    --llm-api-key %LLM_API_KEY% ^
    --llm-base-url %LLM_BASE_URL% ^
    --llm-model %LLM_MODEL%
```

### 5.4 运行测试

```bash
# 运行所有测试
tests\run_tests.bat

# 或手动运行单个测试
python -m pytest tests\test_extract_audio.py -v
python -m pytest tests\test_match_timestamps.py -v
```

---

## 6. 输出结果说明

### 6.1 步骤 JSON 格式

```json
{
  "video": "cooking.mp4",        // 源视频文件名
  "duration": 208.94,            // 视频总时长（秒）
  "steps": [
    {
      "index": 1,                // 步骤编号
      "summary": "准备小料和配菜",  // 步骤摘要
      "start": 0.12,             // 开始时间（秒）
      "end": 53.02,              // 结束时间（秒）
      "text": "原文转录...",      // 对应的原文片段
      "confidence": "high"        // 时间匹配置信度
    }
  ]
}
```

### 6.2 置信度说明

| 置信度 | 含义 | 匹配策略 |
|--------|------|---------|
| `high` | 时间精确 | 一级：字符偏移定位 + 跨段合并 |
| `medium` | 基本准确 | 二级：编辑距离模糊匹配 |
| `low` | 仅供参考 | 三级：Sentence-BERT 语义匹配 |

---

## 7. 常见问题 FAQ

### Q1: torchcodec 警告信息？
```
torchcodec is not installed correctly so built-in audio decoding will fail.
```
这是无害警告，本系统使用 FFmpeg 提取音频而非 torchcodec，可忽略。

### Q2: CUDA 不可用 / GPU 无法识别？
检查 CUDA 版本是否匹配：
```bash
python -c "import torch; print(torch.cuda.is_available())"
```
如果返回 `False`，请使用 CPU 模式（`--device cpu`）。

### Q3: HuggingFace 模型下载失败？
中国大陆用户请确保设置了镜像：
```bash
set HF_ENDPOINT=https://hf-mirror.com
```
或使用 `setup_env.bat` 自动配置。

### Q4: whisperx 安装失败？
```bash
pip install --upgrade pip setuptools wheel
pip install whisperx --no-deps
pip install torch transformers pyannote.audio
```

### Q5: 提示 "Python 3.14 is not supported"？
WhisperX 不支持 Python 3.13+，请降级到 Python 3.12。

---

## 8. 性能参考

| 操作 | CPU (i7-12代) | GPU (RTX 3060) |
|------|-------------|----------------|
| 音频提取（3 分钟视频） | ~5 秒 | ~5 秒 |
| WhisperX 转录（tiny 模型） | ~30 秒 | ~8 秒 |
| WhisperX 转录（large-v2 模型） | ~15 分钟 | ~50 秒 |
| wav2vec2 对齐 | ~10 秒 | ~5 秒 |
| LLM 步骤抽取 | ~3 秒 | ~3 秒 |
| 时间戳映射 | ~2 秒 | ~2 秒 |
| **总计（tiny）** | **~50 秒** | **~23 秒** |
| **总计（large-v2）** | **~15 分钟** | **~65 秒** |
