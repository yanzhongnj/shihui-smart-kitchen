# NOTICE — 第三方组件、数据与引用声明

本项目（下称「本软件」）在实现过程中使用、调用或参考了以下第三方开源项目、数据与商业服务。
本文件用于履行相应许可协议的署名与声明义务。**本软件不包含下表中任何密钥、二进制分发或模型权重**（除特别注明外）。

---

## 一、核心算法与模型组件

| 项目 | 官网 / 仓库 | 用途 | 许可 | 引入方式 |
|---|---|---|---|---|
| FunASR | https://github.com/modelscope/FunASR | 本地语音识别（WebSocket 服务） | MIT | 独立部署（官方 Docker 镜像），本软件仅以其协议调用；`deploy/ubuntu/docker-compose.funasr.yml` 引用官方镜像 `funasr-runtime-sdk-online-cpu-0.1.12` |
| WhisperX | https://github.com/m-bain/whisperX | 语音转录与词级强制对齐 | BSD-2-Clause | Python 依赖（`requirements.txt`） |
| faster-whisper | https://github.com/SYSTRAN/faster-whisper | Whisper 高效推理 | MIT | Python 传递依赖 |
| CTranslate2 | https://github.com/OpenNMT/CTranslate2 | 推理引擎 | MIT | Python 传递依赖 |
| OpenAI Whisper | https://github.com/openai/whisper | 语音识别模型与权重 | MIT（模型权重） | **运行时从 HuggingFace 下载，不随本仓库分发** |
| PyTorch | https://github.com/pytorch/pytorch | 深度学习框架 | BSD-3-Clause | Python 依赖 |
| sentence-transformers | https://github.com/UKPLab/sentence-transformers | 文本向量化（语义匹配） | Apache-2.0 | Python 依赖 |
| OpenAI Python SDK | https://github.com/openai/openai-python | LLM 调用客户端（兼容 DeepSeek） | Apache-2.0 | Python 依赖 |
| FFmpeg | https://ffmpeg.org/ | 音频抽取、视频切片、HLS 合并 | LGPL-2.1+ / GPL（随构建而定） | **外部程序，用户自行安装，不随本仓库分发** |

> **FFmpeg 合规提示**：FFmpeg 采用 LGPL（部分构建为 GPL）。本软件仅通过命令行调用，未静态/动态链接其代码，也未随仓库分发其二进制。若你在发行版中打包 FFmpeg，需自行满足 LGPL/GPL 的声明与源码提供义务。

## 二、内容数据源

| 数据源 | 地址 | 用途 | 许可 | 引入方式 |
|---|---|---|---|---|
| HowToCook | https://github.com/Anduin2017/HowToCook | 开源菜谱内容源（食材反查、菜谱同步） | Unlicense（公有领域） | **运行时浅克隆至本地目录（`HOWTOCOOK_DIR`），不随本仓库分发** |

## 三、后端依赖（Java）

| 组件 | 许可 |
|---|---|
| Spring Boot / Spring Security / Spring WebSocket / Spring Validation | Apache-2.0 |
| MyBatis-Plus | Apache-2.0 |
| MySQL Connector/J | GPL-2.0 with Universal FOSS Exception |
| JJWT（io.jsonwebtoken） | Apache-2.0 |
| springdoc-openapi | Apache-2.0 |
| Lombok | MIT |

## 四、Web 管理端依赖

| 组件 | 许可 |
|---|---|
| Vue 3 / Vue Router / Pinia | MIT |
| Vite / @vitejs/plugin-vue | MIT |
| Element Plus | MIT |
| video.js | Apache-2.0 |
| @videojs/http-streaming | Apache-2.0 |
| axios | MIT |
| qrcode | MIT |
| spark-md5 | WTFPL / MIT |

## 五、移动端依赖（Flutter / Dart）

| 组件 | 许可 |
|---|---|
| Flutter SDK | BSD-3-Clause |
| provider | MIT |
| dio | MIT |
| video_player | BSD-3-Clause |
| web_socket_channel | BSD-3-Clause |
| file_picker / image_picker | MIT / Apache-2.0 |
| path_provider | BSD-3-Clause |
| flutter_secure_storage | BSD-3-Clause |
| cached_network_image | MIT |
| record | MIT |
| audioplayers | MIT |
| crypto | BSD-3-Clause |
| flutter_hls_parser | MIT |
| **ffmpeg_kit_flutter_new_https** | **LGPL-3.0**（封装 FFmpegKit，移动端本地下载合并；分发 APK 时需提供 LGPL 声明与替换方式） |

## 六、工具链

| 工具 | 许可 | 说明 |
|---|---|---|
| yt-dlp | Unlicense | 视频链接解析下载，**用户自行安装，不随本仓库分发** |
| Docker / Docker Compose | Apache-2.0 | 部署 FunASR 服务 |
| Nginx | BSD-2-Clause | Ubuntu 反代（`deploy/ubuntu/nginx-*.conf`） |

## 七、外部商业服务（仅调用，不含其代码与密钥）

| 服务 | 用途 | 说明 |
|---|---|---|
| DeepSeek（`api.deepseek.com`） | 步骤抽取、对话、菜单生成、酱汁设计 | 使用者需自行申请 Key，遵守其服务条款 |
| 阿里云百炼 DashScope | Qwen-VL 拍照识菜、Qwen3-TTS 语音讲解、通义万相（Wan）文生图/文生视频 | 同上；配置变量名 `ALIYUN_ASR_APP_KEY` / `DASHSCOPE_API_KEY` 为历史沿用，实际承载 DashScope Key |
| 阿里云智能语音交互（NLS） | 云端流式语音识别（`wss://nls-gateway.aliyuncs.com`） | 同上 |
| 火山方舟 Seedance | 视频生成（可选模块） | 同上，独立 Key：`SEEDANCE_API_KEY` |
| 涂鸦 Tuya / 米家 MIJIA | 智能厨电中控（**仅预留配置项，未实现**） | 代码中无任何相关 SDK 或凭据 |
| HuggingFace / hf-mirror.com | 模型权重下载 | `HF_ENDPOINT=https://hf-mirror.com` 为第三方镜像，非本项目运营 |

## 八、参考的技术规范与公开资料

| 内容 | 来源 |
|---|---|
| 阿里云 DashScope HTTP/SSE 异步任务协议（文生图 / 文生视频：提交 → 轮询 → 下载） | 阿里云百炼官方 API 文档 |
| 阿里云智能语音交互 WebSocket 流式协议 | 阿里云官方文档 |
| FunASR Runtime WebSocket 2pass 协议与客户端参数 | FunASR 官方文档与仓库 |
| HLS (RFC 8216) m3u8 主/媒体清单解析 | IETF RFC 8216 |
| HMAC-SHA256 开放平台签名方案（`appKey + timestamp + nonce + body`） | 本项目自研设计，非第三方代码 |

> 上述协议与规范均为公开接口文档，本项目依据其**自行实现**客户端逻辑，未复制其源代码。

## 九、如何更新本文件

新增第三方依赖时，请同步更新：
1. 本文件的对应表格；
2. `README.md` 的「第三方引用与声明」摘要表；
3. 如涉及新增许可类型，检查是否与最终确定的开源许可协议兼容。
