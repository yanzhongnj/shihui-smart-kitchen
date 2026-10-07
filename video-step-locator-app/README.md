# 拾味 · 移动端 APP（Flutter）

「智能菜谱视频步骤定位系统」的移动端实现，提供视频播放 + 步骤时间轴交互、语音控制、离线缓存、拍照打卡与做饭计划等能力。

## 环境要求

| 组件 | 版本 |
|---|---|
| Flutter | 3.2+（建议 stable 最新版） |
| Dart | 3.2+ |
| JDK | **17**（Android Gradle Plugin 8.x 要求，非 17 会直接构建失败） |
| Android SDK | Platform 34 / 35 + Build-Tools |
| Xcode | iOS 构建需 macOS + Xcode 15+ |

## 运行

```bash
flutter pub get

# Android 模拟器（10.0.2.2 为模拟器访问宿主机的标准回环地址）
flutter run --dart-define=API_BASE=http://10.0.2.2:8080/api

# 真机（改为局域网 IP 或公网域名）
flutter run --dart-define=API_BASE=http://192.168.x.x:8080/api
```

打包：

```bash
flutter build apk --release --dart-define=API_BASE=https://your-domain.com/api
```

> `API_BASE` 未指定时默认 `http://10.0.2.2:8080/api`（见 `lib/services/api_service.dart`）。
> 生产环境请使用 HTTPS；`cleartext` 明文流量在 Android 9+ 默认被禁止。

## 目录结构

```
lib/
├── main.dart / app.dart        # 入口与全局配置
├── models/                     # 数据模型
├── pages/                      # 页面（登录、视频、播放器、我的、做饭计划…）
├── providers/                  # 状态管理（Provider）
├── router/                     # 路由
├── services/                   # API、缓存、下载、语音等基础服务
├── utils/                      # 工具类
└── widgets/                    # 通用组件
android/                        # Android 平台工程
assets/logo.png                 # 品牌图标
```

## 关键依赖说明

| 依赖 | 用途 | 许可 |
|---|---|---|
| `video_player` | 视频播放 | BSD-3-Clause |
| `record` | 录音（语音控制上行） | MIT |
| `audioplayers` | 语音播报 | MIT |
| `dio` | HTTP 客户端 | MIT |
| `cached_network_image` | 图片缓存 | MIT |
| `flutter_secure_storage` | Token 安全存储 | BSD-3-Clause |
| `flutter_hls_parser` | HLS master 清单解析（选流） | MIT |
| `ffmpeg_kit_flutter_new_https` | 本地下载 / 转码 / 合并 | **LGPL-3.0** |

> ⚠️ **LGPL 合规**：`ffmpeg_kit_flutter_new_https` 封装 FFmpegKit（LGPL-3.0）。若你分发本 APP 的二进制包（APK/IPA），需同时提供其许可声明与获取对应源码的方式。完整声明见仓库根目录 `NOTICE.md`。

## 常见问题

- **构建报 JDK 版本错误**：确认 Gradle 使用的是 JDK 17（`flutter config --jdk-dir "<JDK17 路径>"`），而非更高版本。
- **连不上后端**：确认 `API_BASE` 可被设备访问；模拟器用 `10.0.2.2`，真机用局域网 IP/域名；后端需允许相应来源。
- **首次 Gradle 同步慢**：可在 `android/gradle.properties` 配置国内镜像仓库，或为 Gradle 配置代理。
