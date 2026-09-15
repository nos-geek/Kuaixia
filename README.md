# 快夏 Kuaixia

快夏是一款 Android 媒体解析与下载工具：粘贴链接即可解析视频 / 图集信息，并下载到本地相册。

- 包名：`com.kuaixia.app`
- 当前版本：**v1.0.0**（支持 Android 8.0 及以上）
- 解析服务器协议：**Kuaixia Parser API Protocol**（见 [`docs/Kuaixia-Parser-API-Protocol.md`](docs/Kuaixia-Parser-API-Protocol.md)）

> **许可证**：本项目**源码**采用 [MIT License](LICENSE)；
> 依赖或打包的**第三方开源组件遵循其各自许可证**，完整清单见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

---

## 功能特性

- **链接解析**：本地解析（yt-dlp）优先，失败可回退到自建解析服务器；也可设定为「仅本地」或「仅服务器」。
- **多平台**：抖音、YouTube、Bilibili、小红书、微博（实际可用性取决于平台策略与网络环境）。
- **下载**：直链下载与 M3U8 流下载；音视频分离流自动调用 FFmpeg 合并；图集（多图作品）支持批量下载。
- **下载任务管理**：任务队列与进度、暂停 / 继续 / 重试 / 取消、按状态筛选（进行中 / 已完成 / 失败·已取消）、删除任务；前台服务 + 通知栏进度。
- **便捷入口**：系统分享（把链接分享给快夏）、打开 App 时自动识别剪贴板链接（可在设置中关闭）。
- **抖音网页会话**：内置登录入口获取 WebView Cookie，用于需要登录态的解析。
- **设置**：解析方式、多解析服务器管理与连通性检查、主题（浅色 / 深色）、语言（简体中文 / English）、详细日志开关。
- **诊断**：开发者选项内含 yt-dlp 诊断与运行日志页，便于自助排查问题。

## 支持平台

| 平台 | 说明 |
|------|------|
| 抖音 | 视频与图集；需网页会话（App 内可登录获取 Cookie） |
| YouTube | 本地解析（yt-dlp） |
| Bilibili | 本地解析（yt-dlp） |
| 小红书 | 依赖站点可用性与会话状态 |
| 微博 | 依赖站点可用性与会话状态 |

> 解析与下载的可用性受平台反爬策略、网络环境、账号状态影响；失败时 App 会给出明确提示，并可按提示切换解析方式或重新登录。

## 安装方式

### 普通用户（推荐）

1. 打开本仓库的 **Releases** 页面，下载最新版本的 `app-release.apk`。
2. 在手机上允许「安装未知来源应用」后完成安装。
3. 系统要求：**Android 8.0（API 26）及以上**；APK 体积约 100 MB（内含 `arm64-v8a` / `armeabi-v7a` / `x86` / `x86_64` 原生库与运行时资产）。
4. 首次启动会请求**通知权限**（用于下载进度通知，可拒绝，不影响下载功能本身）。
5. 完整性校验：Releases 页面提供该版本的 **APK SHA-256**，可用 `sha256sum app-release.apk`（Linux/macOS）或 `certutil -hashfile app-release.apk SHA256`（Windows）核对。

### 开发者（从源码构建）

环境：JDK 17 + Android SDK 34。

```bash
cd android

# Debug 包
./gradlew assembleDebug
# 产物：android/app/build/outputs/apk/debug/app-debug.apk

# Release 包
./gradlew assembleRelease
# 产物：android/app/build/outputs/apk/release/app-release.apk
```

也可以直接用 Android Studio 打开 `android/` 目录构建并运行到真机。

**发布签名**：正式签名凭据放在 `android/key.properties`（**不入版本库**，已在 `.gitignore` 中排除）：

```properties
storeFile=/absolute/path/to/your-release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

该文件不存在时，`assembleRelease` 会产出**未签名**包，不会阻塞其他环境的构建。

## 核心原则

```
本地解析优先 → 本地失败 → 解析服务器 → 获取媒体信息 → Android 直接下载
                                                    → 无法直连 → Server Proxy
```

**解析与下载完全分离；服务器默认只解析，不搬运视频；任何兼容协议的服务器无需改 APK 即可使用。**

## Monorepo 结构

```text
kuaixia/
├── android/   # Kotlin + Jetpack Compose 客户端
├── server/    # Python + FastAPI 解析服务器
└── docs/      # 协议文档
```

## 技术栈

- **Android**：Kotlin、Jetpack Compose（Material 3）、Coroutines / Flow、Navigation Compose、Retrofit + OkHttp + kotlinx.serialization、Room、DataStore、Coil
- **解析 / 下载**：yt-dlp（youtubedl-android，本地解析）、Android WebView（页面级解析与会话）、FFmpegKit（音视频合并）
- **Server**：Python、FastAPI、Pydantic、httpx

## 解析架构

统一解析接口 `VideoParser` → 统一领域模型 `VideoInfo` / `StreamInfo`：

```text
                UI
                 │
          ParserManager（解析方式 + fallback）
           ┌──────┴──────┐
     YtDlpParser      ServerParser
           │              │
     YtDlpEngine     ApiService
           │              │
      yt-dlp JSON    MediaParseResult
           └──────┬──────┘
             VideoInfo / StreamInfo
```

解析方式（设置 → 解析方式，默认「本地优先」）：

| 模式 | 行为 |
|------|------|
| 本地优先 | yt-dlp → 失败 → 默认服务器 |
| 服务器优先 | 默认服务器 → 失败 → yt-dlp |
| 仅本地 | 只 yt-dlp |
| 仅服务器 | 只默认服务器 |

> 任何模式都不会自动无限切换服务器；服务器失败即提示。

## 解析服务器（可选）

自建解析服务器可以补充本地解析失败的场景。服务器默认只做解析，不代理媒体流量（除非显式启用 Proxy 能力）。

```bash
cd server
python -m venv .venv
# Windows: .venv\Scripts\activate
pip install -r requirements.txt
python run.py   # 默认监听 0.0.0.0:8000
```

在 App 的「设置 → 解析服务器」中添加服务器地址：

```text
http://<服务器IP>:8000/
```

回到首页粘贴链接点「解析」，即可看到解析来源（本地解析 / 服务器名称）、标题 / 作者 / 平台 / 时长 / 封面 / 清晰度列表（点击清晰度可查看编码详情）。

## 本地解析说明

- 本地解析使用 [youtubedl-android](https://github.com/yausername/youtubedl-android)（`io.github.junkfood02.youtubedl-android:library:0.18.1`），内部走 `--dump-single-json` 输出 JSON。
- 首次解析会解压 Python 运行时（较慢），之后走缓存。
- 解析在 `Dispatchers.IO` 执行，支持取消（销毁 yt-dlp 进程）与超时。
- 无直接下载地址的流（DASH / M3U8）会走对应的下载与合并链路；音视频分离时由 FFmpeg 合并为单文件。

## 版本状态

- **v1.0.0（当前）**：首个正式版本。包含链接解析（本地 / 服务器 / 混合）、下载（直链 / M3U8 / 图集 / 音视频合并）、下载任务管理与通知、剪贴板与系统分享入口、解析服务器管理、深浅主题、简体中文 / English 双语、开发者诊断页。
- 后续版本的规划与变更将随开发进展在此更新（历史阶段划分不再单独维护）。

## 许可证

本项目**源码**采用 **MIT License**，见 [`LICENSE`](LICENSE)。

**第三方组件遵循其各自的许可证**，本项目不对其重新授权。分发、修改或以其他方式使用第三方组件时，请遵守对应组件的许可条款，尤其是 copyleft 类组件（如 GPL-3.0）。

## Third-party licenses

| 组件 | 版本 | 许可证 |
|------|------|--------|
| yt-dlp（随包分发的可执行文件） | — | Unlicense |
| youtubedl-android | 0.18.1 | **GPL-3.0** |
| ffmpeg-kit-full（ffmpegkit-maintained） | 8.1.7 | LGPL-3.0 |
| FFmpeg（FFmpegKit 内含） | 8.1 | LGPL-2.1+（取决于编译配置） |
| smart-exception-java / -common | 0.2.1 | BSD-3-Clause |
| AndroidX / Jetpack Compose | 见版本目录 | Apache-2.0 |
| Kotlin / kotlinx（coroutines、serialization） | 2.0.20 / 1.8.1 / 1.7.3 | Apache-2.0 |
| Retrofit / OkHttp | 2.11.0 / 4.12.0 | Apache-2.0 |
| Coil | 2.7.0 | Apache-2.0 |
| FastAPI / Pydantic | ≥0.110 / ≥2.6 | MIT |
| Uvicorn / httpx | ≥0.29 / ≥0.27 | BSD-3-Clause |

各组件完整许可文本、版权声明与版本细节见 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)。

## Acknowledgements

- [yt-dlp](https://github.com/yt-dlp/yt-dlp)
- [FFmpeg](https://ffmpeg.org)
- [Android Open Source Project](https://source.android.com)
- [Jetpack Compose](https://developer.android.com/jetpack/compose)
- [youtubedl-android](https://github.com/yausername/youtubedl-android)
