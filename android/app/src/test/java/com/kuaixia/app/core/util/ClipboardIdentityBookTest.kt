package com.kuaixia.app.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * BUG-002：剪贴板「作品身份」去重的端到端纯逻辑验证（12 个场景）。
 *
 * 复用线上真实样本：
 * - 短链 `https://v.douyin.com/6kAjxhW68PA`
 * - awemeId `7683858155142507429`
 * - 长链 `https://www.iesdouyin.com/share/slides/7683858155142507429?...`
 *
 * 状态机语义完全不变（AUTO_DISABLED / NO_URL / IN_FLIGHT / DUPLICATE / UNSUPPORTED / TRIGGER），
 * 只是比较的 key 从「canonical URL」换成「作品身份」。
 */
class ClipboardIdentityBookTest {

    private val TRIGGER = ClipboardAutoDecider.Decision.TRIGGER
    private val DUPLICATE = ClipboardAutoDecider.Decision.DUPLICATE
    private val IN_FLIGHT = ClipboardAutoDecider.Decision.IN_FLIGHT
    private val AUTO_DISABLED = ClipboardAutoDecider.Decision.AUTO_DISABLED
    private val UNSUPPORTED = ClipboardAutoDecider.Decision.UNSUPPORTED

    private val AWEME = "7683858155142507429"
    private val SHORT = "https://v.douyin.com/6kAjxhW68PA"
    private val LONG = "https://www.iesdouyin.com/share/slides/$AWEME"
    private val LONG_WITH_PARAMS =
        "$LONG?region=CN&activity_info=a&share_sign=SIG&ug_share_id=U&with_sec_did=1"

    private val OTHER_AWEME = "7451234567890123456"
    private val OTHER_SHORT = "https://v.douyin.com/JXhI_o7V3D0"
    private val OTHER_LONG = "https://www.iesdouyin.com/share/slides/$OTHER_AWEME"

    /** 模拟 HomeViewModel 的一次剪贴板检查：决策为 TRIGGER 时占位。 */
    private fun ClipboardIdentityBook.check(
        url: String,
        autoEnabled: Boolean = true,
        supported: Boolean = true,
    ): ClipboardAutoDecider.Decision {
        val d = decide(url, autoEnabled, supported)
        if (d == TRIGGER) markTriggered(url)
        return d
    }

    /** 模拟解析成功：用最终网页 URL 登记身份别名。 */
    private fun ClipboardIdentityBook.parsed(requestedUrl: String, webpageUrl: String) {
        registerResolved(requestedUrl, webpageUrl)
        markFinished()
    }

    /** 模拟 App 重启：只保留持久化部分。 */
    private fun ClipboardIdentityBook.restart(): ClipboardIdentityBook {
        val last = snapshotLastIdentity()
        val aliases = snapshotAliases()
        val revived = ClipboardIdentityBook()
        revived.restore(last, aliases)
        return revived
    }

    // ---------------- 场景 1~3：同形态 URL ----------------

    @Test
    fun scenario1_sameShortLinkIsDuplicate() {
        val book = ClipboardIdentityBook()
        assertEquals(TRIGGER, book.check(SHORT))
        book.parsed(SHORT, LONG)
        assertEquals("解析成功后应升级为作品身份", "douyin:aweme:$AWEME", book.lastIdentity)
        assertEquals(DUPLICATE, book.check(SHORT))
    }

    @Test
    fun scenario2_sameLongLinkIsDuplicate() {
        val book = ClipboardIdentityBook()
        assertEquals(TRIGGER, book.check(LONG))
        book.parsed(LONG, LONG)
        assertEquals(DUPLICATE, book.check(LONG))
    }

    @Test
    fun scenario3_longLinkWithDifferentQueryIsDuplicate() {
        val book = ClipboardIdentityBook()
        assertEquals(TRIGGER, book.check(LONG))
        book.parsed(LONG, LONG)
        assertEquals(DUPLICATE, book.check(LONG_WITH_PARAMS))
        assertEquals(DUPLICATE, book.check("$LONG?foo=bar&share_sign=OTHER"))
    }

    // ---------------- 场景 4~5：短链 ↔ 长链 ----------------

    @Test
    fun scenario4_unknownShortLinkTriggersThenRegistersIdentity() {
        val book = ClipboardIdentityBook()
        // 首次：短链尚无身份 → 必须 TRIGGER（不能因未拿到 awemeId 就误判 DUPLICATE）
        assertEquals(TRIGGER, book.check(SHORT))
        assertNull(MediaIdentity.contentIdOf(SHORT))
        // 解析成功 → 用最终网页 URL 登记别名
        book.parsed(SHORT, LONG_WITH_PARAMS)
        assertEquals("douyin:aweme:$AWEME", book.resolve(SHORT))
        assertEquals(1, book.aliasCount())
    }

    @Test
    fun scenario5_shortThenLongOfSameWorkIsDuplicate() {
        val book = ClipboardIdentityBook()
        assertEquals(TRIGGER, book.check(SHORT)) // Case A
        book.parsed(SHORT, LONG_WITH_PARAMS)
        assertEquals(DUPLICATE, book.check(LONG)) // Case B：同作品长链
        assertEquals(DUPLICATE, book.check(SHORT)) // Case C：同一短链
        assertEquals(DUPLICATE, book.check(LONG_WITH_PARAMS)) // Case B 变体：带分享参数
    }

    // ---------------- 场景 6：不同作品 ----------------

    @Test
    fun scenario6_differentAwemeIdTriggers() {
        val book = ClipboardIdentityBook()
        assertEquals(TRIGGER, book.check(SHORT))
        book.parsed(SHORT, LONG)
        assertEquals(TRIGGER, book.check(OTHER_LONG)) // 不同 awemeId
        book.parsed(OTHER_LONG, OTHER_LONG)
        assertEquals(TRIGGER, book.check(OTHER_SHORT)) // 另一作品的短链（未登记过）
        assertEquals("两个作品各有一条别名", 2, book.aliasCount())
    }

    @Test
    fun scenarioF_twoDifferentWorksDoNotPolluteEachOther() {
        val book = ClipboardIdentityBook()
        assertEquals(TRIGGER, book.check(SHORT)) // A
        book.parsed(SHORT, LONG)
        // 关键：B 不能因为 A 已解析过就被判 DUPLICATE
        assertEquals(TRIGGER, book.check(OTHER_SHORT)) // B
        book.parsed(OTHER_SHORT, OTHER_LONG)
        assertEquals(DUPLICATE, book.check(OTHER_LONG)) // B 长链命中 B 身份
        assertEquals(DUPLICATE, book.check(OTHER_SHORT)) // B 短链命中（别名）
        // 注：去重只记住「最近一个」作品（与既有 lastAutoParsedUrl 单槽语义一致），
        // 因此再回到 A 会重新 TRIGGER —— 这是既有行为，本轮不扩大语义。
        assertEquals(TRIGGER, book.check(SHORT))
    }

    // ---------------- 场景 7~8：重启持久化 ----------------

    @Test
    fun scenario7_sameWorkAfterRestartIsDuplicate() {
        val book = ClipboardIdentityBook()
        assertEquals(TRIGGER, book.check(SHORT)) // Case A
        book.parsed(SHORT, LONG_WITH_PARAMS)

        val revived = book.restart() // Case D：关 App 再开
        assertEquals("重启后身份应恢复", "douyin:aweme:$AWEME", revived.lastIdentity)
        assertEquals(DUPLICATE, revived.check(SHORT))
        assertEquals(DUPLICATE, revived.check(LONG))
        assertEquals(DUPLICATE, revived.check(LONG_WITH_PARAMS))
    }

    @Test
    fun scenario8_differentWorkAfterRestartTriggers() {
        val book = ClipboardIdentityBook()
        assertEquals(TRIGGER, book.check(SHORT))
        book.parsed(SHORT, LONG)

        val revived = book.restart() // Case E：重启后换作品
        assertEquals(TRIGGER, revived.check(OTHER_LONG))
        assertEquals(TRIGGER, revived.check(OTHER_SHORT))
    }

    // ---------------- 场景 9~10：状态机语义保持 ----------------

    @Test
    fun scenario9_firstUnknownUrlAlwaysTriggers() {
        val book = ClipboardIdentityBook()
        assertEquals(TRIGGER, book.check("https://v.douyin.com/AAAAAAA"))
        book.parsed("https://v.douyin.com/AAAAAAA", "https://www.douyin.com/video/1111111111111111111")
        assertEquals(TRIGGER, book.check("https://v.douyin.com/BBBBBBB"))
    }

    @Test
    fun scenario10_inFlightSameWorkDoesNotStartTwice() {
        val book = ClipboardIdentityBook()
        assertEquals(TRIGGER, book.check(LONG))
        // 解析尚未结束（未 markFinished）：同一作品再来 → IN_FLIGHT，不重复启动
        assertEquals(IN_FLIGHT, book.check(LONG))
        assertEquals(IN_FLIGHT, book.check(LONG_WITH_PARAMS))
        book.markFinished()
        assertEquals(DUPLICATE, book.check(LONG))
    }

    @Test
    fun autoDisabledAndUnsupportedSemanticsPreserved() {
        val book = ClipboardIdentityBook()
        assertEquals(TRIGGER, book.check(SHORT))
        book.parsed(SHORT, LONG)
        assertEquals(AUTO_DISABLED, book.check(SHORT, autoEnabled = false))
        assertEquals(AUTO_DISABLED, book.check(OTHER_LONG, autoEnabled = false))
        assertEquals(UNSUPPORTED, book.check("https://p3-sign.douyinpic.com/x.jpg", supported = false))
    }

    // ---------------- 场景 11~12：fallback ----------------

    @Test
    fun scenario11_urlWithoutExtractableIdFallsBackToCanonical() {
        val book = ClipboardIdentityBook()
        val plain = "https://example.com/watch?v=abc&utm_source=x"
        assertEquals(TRIGGER, book.check(plain))
        // 解析成功但网页 URL 仍拿不到作品 ID → 不登记别名，保持 URL 级去重
        book.parsed(plain, "https://example.com/watch?v=abc")
        assertEquals(0, book.aliasCount())
        assertEquals(DUPLICATE, book.check(plain))
        assertEquals(DUPLICATE, book.check("https://example.com/watch?v=abc&utm_source=y"))
        assertEquals(TRIGGER, book.check("https://example.com/watch?v=zzz"))
    }

    @Test
    fun scenario12_nonDouyinUrlsKeepCanonicalBehavior() {
        val book = ClipboardIdentityBook()
        val bili = "https://www.bilibili.com/video/BV1xx411c7mD?spm_id_from=333.999"
        val bili2 = "https://www.bilibili.com/video/BV1xx411c7mD?spm_id_from=333.999&from=share"
        assertEquals(TRIGGER, book.check(bili))
        book.parsed(bili, bili)
        // 白名单追踪参数被丢弃 → 仍 DUPLICATE（既有行为）
        assertEquals(DUPLICATE, book.check(bili2))
        // 不同 BV 号 → TRIGGER
        assertEquals(TRIGGER, book.check("https://www.bilibili.com/video/BV1yy411c7mE"))
        assertEquals("非抖音不登记别名", 0, book.aliasCount())
    }

    @Test
    fun aliasTableIsCapped() {
        val book = ClipboardIdentityBook()
        // 超过上限时淘汰最旧条目，避免 DataStore 无限膨胀
        repeat(MediaIdentity.MAX_ALIASES + 20) { i ->
            val shortUrl = "https://v.douyin.com/SHORT$i"
            val longUrl = "https://www.iesdouyin.com/share/slides/${7000000000000000000L + i}"
            book.check(shortUrl)
            book.parsed(shortUrl, longUrl)
        }
        assertEquals(MediaIdentity.MAX_ALIASES, book.aliasCount())
        assertEquals(MediaIdentity.MAX_ALIASES, book.snapshotAliases().size)
    }
}
