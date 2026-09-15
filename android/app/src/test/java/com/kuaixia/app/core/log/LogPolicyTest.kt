package com.kuaixia.app.core.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日志 V2：标准 / 详细分级与滚动策略测试。
 * - 标准（verbose=false）：详细类 tag（WebView）与 DEBUG 全部过滤；INFO+ 保留。
 * - 详细（verbose=true）：全部保留。
 * - 队列/文件策略常量必须为有界且有上限（防日志无限增长）。
 */
class LogPolicyTest {

    @Test
    fun standardModeDropsVerboseTags() {
        assertFalse(LogPolicy.shouldLog(LogLevel.INFO, LogTags.WEBVIEW, verboseEnabled = false))
        assertFalse(LogPolicy.shouldLog(LogLevel.DEBUG, LogTags.WEBVIEW, verboseEnabled = false))
        assertTrue("Parser 的 INFO 属标准日志", LogPolicy.shouldLog(LogLevel.INFO, LogTags.PARSER, false))
        assertTrue(LogPolicy.shouldLog(LogLevel.WARN, LogTags.DOWNLOAD, false))
        assertTrue(LogPolicy.shouldLog(LogLevel.ERROR, LogTags.CRASH, false))
    }

    @Test
    fun standardModeDropsDebugForNormalTags() {
        assertFalse(LogPolicy.shouldLog(LogLevel.DEBUG, LogTags.PARSER, verboseEnabled = false))
    }

    @Test
    fun verboseModeKeepsEverything() {
        assertTrue(LogPolicy.shouldLog(LogLevel.DEBUG, LogTags.WEBVIEW, verboseEnabled = true))
        assertTrue(LogPolicy.shouldLog(LogLevel.DEBUG, LogTags.PARSER, verboseEnabled = true))
        assertTrue(LogPolicy.shouldLog(LogLevel.INFO, LogTags.PARSER, verboseEnabled = true))
    }

    @Test
    fun effectiveMinLevelFollowsMode() {
        assertEquals(LogLevel.INFO, LogPolicy.effectiveMinLevel(false))
        assertEquals(LogLevel.DEBUG, LogPolicy.effectiveMinLevel(true))
    }

    @Test
    fun fileAndQueueLimitsAreBounded() {
        assertTrue("单文件必须有上限", LogPolicy.MAX_FILE_BYTES in 1..(20L * 1024 * 1024))
        assertTrue("历史份数必须有界", LogPolicy.MAX_FILES in 1..10)
        assertTrue("写盘队列必须有界", LogPolicy.QUEUE_CAPACITY in 64..65_536)
        assertTrue("内存缓冲必须有界", LogPolicy.MAX_IN_MEMORY in 100..10_000)
        assertEquals("kuaixia.log", LogPolicy.LOG_FILE)
    }
}
