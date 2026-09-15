# Third-Party Notices / 第三方组件声明

快夏（Kuaixia）使用了以下开源组件。感谢所有开源作者。

> **适用范围**
> 本文件列出的是**第三方组件**及其各自的许可证。
> 快夏**自身源码**采用 MIT License（见 [`LICENSE`](LICENSE)）；本文件不改变、也不替代任何第三方组件的许可条款。

- 更新日期：2026-09-15（对应版本 v1.0.0）

---

## 一、随 APK 分发的第三方资产与原生库

这些组件会被**打包进 APK**，属于分发物的一部分：

| 组件 | 版本 / 形态 | 许可证 | 说明 |
|------|------------|--------|------|
| **yt-dlp** | 可执行文件，位于 `android/app/src/main/assets/kuaixia/ytdlp-bin/yt-dlp` | **Unlicense**（公有领域） | 本地解析核心；源码：https://github.com/yt-dlp/yt-dlp |
| **youtubedl-android** | `io.github.junkfood02.youtubedl-android:library:0.18.1` | **GPL-3.0** | 提供 Android 上的 Python 运行时与 yt-dlp 调用封装；仓库：https://github.com/yausername/youtubedl-android |
| **FFmpegKit（ffmpeg-kit-full）** | `dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.7` | **LGPL-3.0** | 音视频合并；社区维护分支：https://github.com/ffmpegkit-maintained/ffmpeg-kit |
| **FFmpeg** | 8.1（随 FFmpegKit 打包） | **LGPL-2.1+**（取决于编译配置） | 上游：https://ffmpeg.org 。若使用 `-gpl` 变体或 `--enable-gpl` 构建，则整体转为 GPL；**本项目使用的是非 GPL 的 `full` 档**（同系列另有 `Full GPL` 档，本项目未使用） |
| **smart-exception-java / smart-exception-common** | `com.arthenica:smart-exception-java:0.2.1`、`com.arthenica:smart-exception-common:0.2.1` | **BSD-3-Clause** | FFmpegKit 运行时依赖（否则抛 `NoClassDefFoundError`）；作者 Taner Sener |

---

## 二、Android 运行时依赖

| 组件 | 版本 | 许可证 | 仓库 |
|------|------|--------|------|
| Kotlin（stdlib、Compose 编译器插件等） | 2.0.20 | Apache-2.0 | https://github.com/JetBrains/kotlin |
| kotlinx.coroutines | 1.8.1 | Apache-2.0 | https://github.com/Kotlin/kotlinx.coroutines |
| kotlinx.serialization | 1.7.3 | Apache-2.0 | https://github.com/Kotlin/kotlinx.serialization |
| **AndroidX Core KTX** | 1.13.1 | Apache-2.0 | https://github.com/androidx/androidx |
| **AndroidX Lifecycle**（runtime / compose / viewmodel-compose） | 2.8.6 | Apache-2.0 | https://github.com/androidx/androidx |
| **AndroidX Activity Compose** | 1.9.2 | Apache-2.0 | https://github.com/androidx/androidx |
| **Jetpack Compose**（BOM） | 2024.09.03 | Apache-2.0 | https://developer.android.com/jetpack/compose |
| **Compose Material 3 / material-icons-extended** | via BOM | Apache-2.0 | https://github.com/androidx/androidx |
| **AndroidX Navigation Compose** | 2.8.2 | Apache-2.0 | https://github.com/androidx/androidx |
| **AndroidX DataStore (Preferences)** | 1.1.1 | Apache-2.0 | https://github.com/androidx/androidx |
| **AndroidX Room**（runtime / ktx / compiler） | 2.6.1 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/room |
| Retrofit + kotlinx-serialization converter | 2.11.0 | Apache-2.0 | https://github.com/square/retrofit |
| OkHttp | 4.12.0 | Apache-2.0 | https://github.com/square/okhttp |
| Coil（coil-compose） | 2.7.0 | Apache-2.0 | https://github.com/coil-kt/coil |

### 构建工具链

| 组件 | 版本 | 许可证 |
|------|------|--------|
| Android Gradle Plugin | 8.5.2 | Apache-2.0 |
| Gradle | 8.9（wrapper） | Apache-2.0 |
| KSP（Kotlin Symbol Processing） | 2.0.20-1.0.25 | Apache-2.0 |
| JUnit 4（仅单元测试） | 4.13.2 | EPL-1.0 |
| org.json（仅单元测试） | 20240303 | JSON License |

---

## 三、Server 运行时依赖（Python）

| 组件 | 版本 | 许可证 | 仓库 |
|------|------|--------|------|
| FastAPI | ≥0.110 | MIT | https://github.com/fastapi/fastapi |
| Uvicorn | ≥0.29 | BSD-3-Clause | https://github.com/encode/uvicorn |
| Pydantic | ≥2.6 | MIT | https://github.com/pydantic/pydantic |
| httpx | ≥0.27 | BSD-3-Clause | https://github.com/encode/httpx |

---

## 四、许可证合规说明

1. **MIT 的适用范围**：快夏仓库内**自身编写**的源码采用 MIT License。第三方组件仍归其各自作者所有，并按其各自许可证分发。
2. **GPL-3.0 组件（重要）**：本地解析依赖 **youtubedl-android（GPL-3.0）**，该组件会随 APK 分发。GPL-3.0 属强 copyleft 许可：
   - 再分发包含该组件的 APK 时，须遵守 GPL-3.0 的相应义务（提供对应源代码、保留版权与许可声明等）；
   - 若计划以非 GPL 兼容条款分发整体应用，请先评估合规路径（替换该依赖、或取得相应授权），再对外发布。
3. **LGPL 组件**：FFmpegKit / FFmpeg 采用 LGPL（本项目使用非 GPL 档）。再分发时应保留其许可声明，并在修改其源码时遵守 LGPL 的相应要求。
4. **Unlicense 组件**：yt-dlp 属公有领域（Unlicense），无附加限制。
5. **建议**：分发前请核对各组件仓库中的最新许可条款；若许可证发生变更，以组件官方声明为准。

---

## 五、变更记录

| 日期 | 说明 |
|------|------|
| 2026-09-15 | 对齐 v1.0.0 实际依赖：补充 yt-dlp、FFmpegKit（ffmpeg-kit-full + FFmpeg）、smart-exception、AndroidX / Jetpack Compose 等条目；移除过期的阶段化描述；明确 MIT 适用范围与 GPL-3.0 合规提示 |
