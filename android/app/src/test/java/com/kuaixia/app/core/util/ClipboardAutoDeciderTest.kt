package com.kuaixia.app.core.util

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ClipboardAutoDeciderTest {

    private val decider = ClipboardAutoDecider
    private val URL_A = "https://v.douyin.com/AAA/"
    private val URL_B = "https://v.douyin.com/BBB/"
    private val CANON_A = "https://v.douyin.com/AAA"
    private val CANON_B = "https://v.douyin.com/BBB"

    @Test
    fun autoDisabledSkips() {
        assertEquals(
            ClipboardAutoDecider.Decision.AUTO_DISABLED,
            decider.decide(autoEnabled = false, canonicalUrl = CANON_A, inFlightCanonical = null, lastAutoParsedCanonical = null),
        )
    }

    @Test
    fun newUrlTriggers() {
        assertEquals(
            ClipboardAutoDecider.Decision.TRIGGER,
            decider.decide(autoEnabled = true, canonicalUrl = CANON_A, inFlightCanonical = null, lastAutoParsedCanonical = null),
        )
    }

    @Test
    fun sameUrlDuplicate() {
        assertEquals(
            ClipboardAutoDecider.Decision.DUPLICATE,
            decider.decide(autoEnabled = true, canonicalUrl = CANON_A, inFlightCanonical = null, lastAutoParsedCanonical = CANON_A),
        )
    }

    @Test
    fun sameUrlInFlight() {
        assertEquals(
            ClipboardAutoDecider.Decision.IN_FLIGHT,
            decider.decide(autoEnabled = true, canonicalUrl = CANON_A, inFlightCanonical = CANON_A, lastAutoParsedCanonical = null),
        )
    }

    @Test
    fun sequenceARunThenARepeatThenBTrigger() {
        var last: String? = null
        // URL-A first: trigger
        assertEquals(ClipboardAutoDecider.Decision.TRIGGER, decider.decide(true, CANON_A, null, last))
        last = CANON_A
        // URL-A again (intervening nothing): duplicate
        assertEquals(ClipboardAutoDecider.Decision.DUPLICATE, decider.decide(true, CANON_A, null, last))
        // New URL-B: trigger even though A was parsed
        assertEquals(ClipboardAutoDecider.Decision.TRIGGER, decider.decide(true, CANON_B, null, last))
        last = CANON_B
        // B repeat after processing: duplicate
        assertEquals(ClipboardAutoDecider.Decision.DUPLICATE, decider.decide(true, CANON_B, null, last))
    }

    @Test
    fun canonicalEqualsAcrossRawVariants() {
        // 同一作品不同分享参数（region/mid/previous_page）→ canonical 相同 → 视为已处理 DUPLICATE
        val canon = MediaUrlCanonicalizer.canonical(
            "https://www.douyin.com/video/999?region=CN&mid=8&previous_page=app_code_link",
        )
        assertEquals(
            ClipboardAutoDecider.Decision.DUPLICATE,
            decider.decide(
                autoEnabled = true,
                canonicalUrl = canon,
                inFlightCanonical = null,
                lastAutoParsedCanonical = canon,
            ),
        )
    }

    @Test
    fun blankUrlNoUrl() {
        assertEquals(
            ClipboardAutoDecider.Decision.NO_URL,
            decider.decide(true, "", null, null),
        )
        assertEquals(
            ClipboardAutoDecider.Decision.NO_URL,
            decider.decide(true, null, null, null),
        )
    }

    @Test
    fun settingsNotReadyMustWaitNotDefaultFalse() = runBlocking {
        // 模拟 DataStore 冷流：稍后产出 true；在产出前不得用默认 false 决策（这里直接验证“等待后按真值决策”）
        val coldFlow = flow {
            delay(80)
            emit(true)
        }
        val decision = ClipboardAutoDecider.decideWhenReady(
            autoEnabledFlow = coldFlow,
            canonicalUrl = CANON_A,
            inFlightCanonical = null,
            lastAutoParsedCanonical = null,
        )
        assertEquals(ClipboardAutoDecider.Decision.TRIGGER, decision)
    }

    @Test
    fun supportedDouyinTriggers() {
        assertEquals(
            ClipboardAutoDecider.Decision.TRIGGER,
            ClipboardAutoDecider.decide(true, CANON_A, null, null, supported = true),
        )
    }

    @Test
    fun supportedBilibiliTriggers() {
        val bili = MediaUrlCanonicalizer.canonical("https://www.bilibili.com/video/BV1xx411c7mD")
        assertEquals(
            ClipboardAutoDecider.Decision.TRIGGER,
            ClipboardAutoDecider.decide(true, bili, null, null, supported = true),
        )
    }

    @Test
    fun unknownPlatformSkips() {
        assertEquals(
            ClipboardAutoDecider.Decision.UNSUPPORTED,
            ClipboardAutoDecider.decide(true, CANON_A, null, null, supported = false),
        )
    }

    @Test
    fun douyinpicImageCdnSkips() {
        // 图片 CDN（douyinpic）→ 平台 UNKNOWN → supported=false → 不自动解析
        val cdn = MediaUrlCanonicalizer.canonical(
            "https://p11-sign.douyinpic.com/tos-cn-i-0813/oAA_~tplv-dy-water-v2.jpeg?x-signature=abc",
        )
        assertEquals(
            ClipboardAutoDecider.Decision.UNSUPPORTED,
            ClipboardAutoDecider.decide(true, cdn, null, null, supported = false),
        )
    }

    @Test
    fun supportedDuplicateStillSkips() {
        assertEquals(
            ClipboardAutoDecider.Decision.DUPLICATE,
            ClipboardAutoDecider.decide(true, CANON_A, null, CANON_A, supported = true),
        )
    }

    @Test
    fun autoDisabledTakesPriorityOverSupported() {
        assertEquals(
            ClipboardAutoDecider.Decision.AUTO_DISABLED,
            ClipboardAutoDecider.decide(false, CANON_A, null, null, supported = true),
        )
    }
}
