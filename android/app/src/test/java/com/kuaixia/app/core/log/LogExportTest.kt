package com.kuaixia.app.core.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/** 日志 V2：导出（时间范围过滤 / 文件名 / 诊断 ZIP）测试。 */
class LogExportTest {

    private fun log(id: Long, ts: Long, msg: String = "m$id") = AppLog(
        id = id, timestamp = ts, level = LogLevel.INFO, tag = "Parser", message = msg,
    )

    @Test
    fun filterByRangeKeepsOnlyRecent() {
        val now = 1_700_000_000_000L
        val entries = listOf(
            log(1, now - 48 * 3_600_000L),  // 48h 前
            log(2, now - 5 * 3_600_000L),   // 5h 前
            log(3, now - 60_000L),          // 1 分钟前
        )
        assertEquals(3, LogExport.filterSince(entries, LogExport.sinceMsOf(now, LogExport.Range.ALL)).size)
        assertEquals(2, LogExport.filterSince(entries, LogExport.sinceMsOf(now, LogExport.Range.LAST_24H)).size)
        // 5h 前 + 1 分钟前 均落在 6h 窗口内
        assertEquals(2, LogExport.filterSince(entries, LogExport.sinceMsOf(now, LogExport.Range.LAST_6H)).size)
        assertEquals(1, LogExport.filterSince(entries, LogExport.sinceMsOf(now, LogExport.Range.LAST_1H)).size)
    }

    @Test
    fun fileNamesCarryTimestamp() {
        val now = 1_700_000_000_000L // 2023-11-15 (UTC+8 时区差异不校验具体日)
        val txt = LogExport.textFileName(now)
        val zip = LogExport.zipFileName(now)
        assertTrue(txt.startsWith("kuaixia_log_"))
        assertTrue(txt.endsWith(".txt"))
        assertTrue(zip.startsWith("kuaixia_diagnostic_"))
        assertTrue(zip.endsWith(".zip"))
        assertEquals(txt.removePrefix("kuaixia_log_").removeSuffix(".txt").length, 15) // yyyyMMdd_HHmmss
    }

    @Test
    fun zipContainsLogDeviceAndReadme() {
        val out = ByteArrayOutputStream()
        LogExport.buildZip(out, logText = "[00:00:00.000] INFO/Parser hello", deviceText = "sdk=33")
        val names = mutableListOf<String>()
        var readme = ""
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zin ->
            var e = zin.nextEntry
            while (e != null) {
                names += e.name
                val body = zin.readBytes().toString(Charsets.UTF_8)
                if (e.name == "README.txt") readme = body
                e = zin.nextEntry
            }
        }
        assertEquals(listOf("kuaixia.log", "device.txt", "README.txt"), names)
        assertTrue(readme.contains("此文件由快夏自动生成"))
        assertTrue(readme.contains("脱敏"))
    }

    @Test
    fun zipDoesNotContainCookieValues() {
        // 日志已经过脱敏：ZIP 内不得出现 Cookie 明文（此处验证「只打包脱敏后的文本」这一约定）
        val sanitized = LogSanitizer.sanitize("Cookie: sessionid=ss; sid_tt=tt")
        assertFalse(sanitized.contains("ss"))
        val out = ByteArrayOutputStream()
        LogExport.buildZip(out, logText = sanitized, deviceText = "sdk=33")
        val all = out.toByteArray().toString(Charsets.ISO_8859_1)
        assertFalse(all.contains("sessionid=ss"))
    }
}
