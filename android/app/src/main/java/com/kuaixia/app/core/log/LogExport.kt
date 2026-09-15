package com.kuaixia.app.core.log

import androidx.annotation.StringRes
import com.kuaixia.app.R
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 日志导出（日志 V2，纯逻辑可 JVM 单测）：
 * - 导出范围过滤（最近 1 / 6 / 24 小时 / 全部）；
 * - 稳定文件名：`kuaixia_log_YYYYMMDD_HHmmss.txt` / `kuaixia_diagnostic_YYYYMMDD_HHmmss.zip`；
 * - ZIP 打包：kuaixia.log + device.txt + README.txt（日志与设备摘要均已经过统一脱敏，不含 Cookie）。
 *
 * 实际落盘位置由 UI 通过系统 SAF（ACTION_CREATE_DOCUMENT）选择，本对象只负责内容与命名。
 */
object LogExport {

    /** 导出范围枚举（UI 选择；null 小时 = 全部）。展示文案走资源（[labelRes]），由 UI 层翻译。 */
    enum class Range(@StringRes val labelRes: Int, val hours: Int?) {
        LAST_1H(R.string.log_range_last_1h, 1),
        LAST_6H(R.string.log_range_last_6h, 6),
        LAST_24H(R.string.log_range_last_24h, 24),
        ALL(R.string.log_range_all, null),
    }

    const val README_TXT = "此文件由快夏自动生成。\n" +
        "请勿上传包含私人信息的原始日志；日志系统已对 Cookie / token / 签名 / 设备标识等\n" +
        "敏感字段进行自动脱敏（替换为 <redacted>），URL 查询参数亦逐项脱敏。\n\n" +
        "包含文件：\n" +
        "- kuaixia.log   应用诊断日志（含 PARSE SUMMARY / DOWNLOAD SUMMARY）\n" +
        "- device.txt    设备与环境摘要（不含任何账号/隐私信息）\n"

    private val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA)

    fun textFileName(nowMs: Long): String = "kuaixia_log_${stamp.format(Date(nowMs))}.txt"

    fun zipFileName(nowMs: Long): String = "kuaixia_diagnostic_${stamp.format(Date(nowMs))}.zip"

    /** 范围起点时间戳（null = 全部）。 */
    fun sinceMsOf(nowMs: Long, range: Range): Long? =
        range.hours?.let { nowMs - it * 3_600_000L }

    /** 按时间范围过滤（保持原顺序）。 */
    fun filterSince(entries: List<AppLog>, sinceMs: Long?): List<AppLog> =
        if (sinceMs == null) entries else entries.filter { it.timestamp >= sinceMs }

    /**
     * 写出诊断 ZIP：kuaixia.log / device.txt / README.txt。
     * 纯 JVM 实现（java.util.zip），任何步骤失败由调用方兜底。
     */
    fun buildZip(
        out: OutputStream,
        logText: String,
        deviceText: String,
        readme: String = README_TXT,
    ) {
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("kuaixia.log"))
            zip.write(logText.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("device.txt"))
            zip.write(deviceText.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("README.txt"))
            zip.write(readme.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }
}
