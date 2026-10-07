# 智能菜谱视频步骤定位系统

输入一段做菜视频，自动识别语音讲解，抽取结构化菜谱步骤，并精确标定每个步骤在视频中的起止时间，实现"点击步骤，视频自动跳转"的交互体验。

## 快速开始（30 秒版）

```bash
# 1. 放入视频到 input/ 目录
# 2. 一键安装运行
run_all.bat
```

> 📖 详细部署文档请参阅 [DEPLOY.md](./DEPLOY.md)

---

## 系统架构

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

## 项目结构

```
video-step-locator/
├── input/                   # 待处理视频
├── output/                  # 输出产物
│   ├── audio/               # 提取的音频
│   ├── segments/            # WhisperX 转录结果
│   ├── transcripts/         # 纯文本转录
│   └── steps/               # 最终步骤-时间映射 JSON
├── logs/                    # 处理日志
├── scripts/
│   ├── extract_audio.py     # 音频提取模块
│   ├── transcribe.py        # 语音识别模块（WhisperX）
│   ├── extract_steps.py     # LLM 步骤抽取模块
│   ├── match_timestamps.py  # 时间戳映射模块（三级匹配）
│   └── pipeline.py          # 完整处理流水线
├── frontend/
│   ├── index.html           # 前端页面（Video.js 播放器）
│   ├── app.js               # 前端交互逻辑
│   └── style.css            # 前端样式
├── tests/                   # 测试用例
│   ├── test_extract_audio.py
│   ├── test_transcribe.py
│   ├── test_extract_steps.py
│   ├── test_match_timestamps.py
│   ├── test_pipeline.py
│   └── run_tests.bat        # 测试运行器
├── setup_env.bat            # 环境一键安装脚本
├── run_pipeline.bat         # 后端处理流水线脚本
├── run_frontend.bat         # 前端启动脚本
├── run_all.bat              # 一键运行（处理+前端）
├── requirements.txt         # Python 依赖
├── DEPLOY.md                # 详细部署文档
└── README.md                # 使用说明
```

## 环境要求

| 组件 | 要求 | 说明 |
|------|------|------|
| Python | **3.10 ~ 3.12** | ⚠️ 不支持 3.13+（WhisperX 兼容性） |
| GPU | NVIDIA 显卡 ≥ 6GB 显存 | 可选但强烈推荐 |
| FFmpeg | ≥ 4.4 | 通过 imageio-ffmpeg 自动安装 |
| CUDA | 11.8+ | GPU 加速（可选） |
| 系统 | Windows 10/11 / Linux | 已验证 |

## 快速开始（Windows）

### 方式一：一键运行（推荐）

```bash
# 1. 放入视频文件到 input\ 目录
# 2. 双击运行（自动安装环境 + 处理视频 + 启动前端）
run_all.bat
```

### 方式二：分步运行

```bash
# 1. 安装环境（仅首次运行）
setup_env.bat

# 2. 设置 LLM API 密钥
set LLM_API_KEY=sk-your-api-key

# 3. 处理视频
run_pipeline.bat

# 4. 启动前端界面
run_frontend.bat
```

### 方式三：手动运行

```bash
# 激活虚拟环境
.venv\Scripts\activate.bat

# 设置 HuggingFace 镜像（中国大陆）
set HF_ENDPOINT=https://hf-mirror.com

# 运行管线
python scripts/pipeline.py ^
    --input input/cooking.mp4 ^
    --output output ^
    --whisper-model tiny ^
    --device cpu ^
    --llm-api-key %LLM_API_KEY% ^
    --llm-base-url https://api.deepseek.com/v1 ^
    --llm-model deepseek-chat

# 启动前端
cd frontend
python -m http.server 8080
# 浏览器打开 http://localhost:8080
```

## 运行测试

```bash
# 方式一：使用测试运行器
tests\run_tests.bat

# 方式二：直接运行 pytest
python -m pytest tests\ -v --tb=short

# 方式三：生成测试覆盖率报告
python -m pytest tests\ --cov=scripts --cov-report=html:coverage_report

# 方式四：运行单个测试模块
python -m pytest tests\test_match_timestamps.py -v --tb=short
```

## 命令行参数

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `--input` | 输入视频文件路径 | **必填** |
| `--output` | 输出目录 | `./output` |
| `--whisper-model` | WhisperX 模型名称 | `large-v2` |
| `--device` | 计算设备 (cuda/cpu) | `cuda` |
| `--llm-api-key` | LLM API 密钥 | `""` |
| `--llm-base-url` | LLM API 地址 | `https://api.openai.com/v1` |
| `--llm-model` | LLM 模型名称 | `gpt-4o` |
| `--offset` | 前置偏移秒数 | `0.2` |
| `--skip-audio` | 跳过音频提取 | `false` |
| `--skip-transcribe` | 跳过语音转录 | `false` |

## 输出格式

```json
{
  "video": "cooking.mp4",
  "duration": 180.5,
  "steps": [
    {
      "index": 1,
      "summary": "切鸡丁并腌制",
      "start": 5.0,
      "end": 12.1,
      "text": "首先把鸡胸肉切成小丁，加点料酒和淀粉腌制一下",
      "confidence": "high"
    }
  ]
}
```

## 匹配策略说明

系统采用三级匹配流水线将 LLM 提取的步骤映射到视频时间：

| 优先级 | 策略 | 置信度 | 说明 |
|--------|------|--------|------|
| 一级 | 字符偏移定位 + 跨段合并 | `high` | 最精确，需要 LLM 输出 char_start/char_end |
| 二级 | 编辑距离模糊匹配（含滑动窗口） | `medium` | 容错路径，处理文本差异 |
| 三级 | Sentence-BERT 语义匹配 | `low` | 回退路径，理解语义相似度 |

## 技术栈

| 层次 | 技术 | 用途 |
|------|------|------|
| 音频处理 | FFmpeg (imageio-ffmpeg) | 从视频提取 WAV 音频 |
| 语音识别 | WhisperX (faster-whisper) | 带单词级时间戳的转录 |
| 语音对齐 | wav2vec2 (jonatasgrosman) | 中文语音-文本精确对齐 |
| 步骤抽取 | DeepSeek / GPT 等 LLM | 从转录提取结构化步骤 |
| 时间匹配 | 字符索引 + 编辑距离 + Sentence-BERT | 三级映射流水线 |
| 前端展示 | Video.js + 原生 JavaScript | 点击步骤跳转播放 |

## 测试覆盖

| 测试模块 | 覆盖内容 | 测试类型 |
|---------|---------|---------|
| `test_extract_audio.py` | FFmpeg 调用、异常处理、参数验证 | 单元 + 集成 |
| `test_transcribe.py` | WhisperX 调用、精度降级、对齐回退 | 单元 + Mock |
| `test_extract_steps.py` | 提示词构建、LLM 解析、JSON 回退 | 单元 + Mock |
| `test_match_timestamps.py` | 三级匹配算法、后处理、边界条件 | 单元 + 集成 |
| `test_pipeline.py` | 完整流程编排、跳过逻辑、输出格式 | Mock + 集成 |
