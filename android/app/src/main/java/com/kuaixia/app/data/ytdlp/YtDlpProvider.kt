package com.kuaixia.app.data.ytdlp

/**
 * yt-dlp 提供者抽象 —— 为「在线自动更新」预留的最小接口。
 *
 * 本阶段**不实现网络更新**：只有 [BundledYtDlpProvider]（APK 内置），
 * YtDlpEngine 在初始化后据 [upgradeAssetName] 用 App assets 内的新版单文件
 * 覆盖库解压出的内置版（本地一次性升级 + marker，离线可用）。
 *
 * 未来阶段（不在本文件实现）：
 * ```
 * BundledYtDlpProvider → APK 内置稳定版
 * ManagedYtDlpProvider  → App 私有目录中的在线更新版（HTTPS + SHA-256 + 临时文件
 *                         校验成功后原子替换 + 保留旧版回滚；失败不破坏当前可用版本）
 * ```
 * 运行时策略：Managed 优先 → 不存在/损坏回退 Bundled。
 */
interface YtDlpProvider {

    /** 当前生效/内置的版本标识（如 "2026.08.19"）；未知返回 null。 */
    fun getBundledVersion(): String?

    /**
     * App assets 内携带的 yt-dlp 单文件（zipapp）路径；null = 不携带（用库内置版）。
     * 例：`kuaixia/ytdlp-bin/yt-dlp`。YtDlpEngine 在库初始化后据此执行本地升级。
     */
    fun upgradeAssetName(): String? = null
}

/** APK 内置提供者：携带 Bundled 固定版本，并随包携带该版本的 zipapp 用于离线替换。 */
class BundledYtDlpProvider : YtDlpProvider {

    override fun getBundledVersion(): String = BUNDLED_VERSION

    override fun upgradeAssetName(): String = UPGRADE_ASSET

    private companion object {
        const val BUNDLED_VERSION = "2026.08.19"
        const val UPGRADE_ASSET = "kuaixia/ytdlp-bin/yt-dlp"
    }
}
