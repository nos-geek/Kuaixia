package com.kuaixia.app.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * BUG-002：作品身份（identity）解析。
 *
 * 核心原则：**awemeId 才是同一抖音作品的稳定身份，URL 只是入口**。
 * 短链与长链必须归一到同一个 identity；取不到作品 ID 时退化为 canonical（行为不变）。
 */
class MediaIdentityTest {

    private val AWEME_ID = "7683858155142507429"
    private val LONG_BASE = "https://www.iesdouyin.com/share/slides/$AWEME_ID"
    private val SHORT = "https://v.douyin.com/6kAjxhW68PA"

    @Test
    fun slidesLongLinkYieldsAwemeIdentity() {
        assertEquals("douyin:aweme:$AWEME_ID", MediaIdentity.identityOf(LONG_BASE))
    }

    @Test
    fun shareParamsDoNotChangeIdentity() {
        // 取证确认过的真实分享参数：activity_info / share_sign / ug_share_id / with_sec_did
        val withParams = "$LONG_BASE?region=CN&activity_info=a&share_sign=SIG&ug_share_id=U&with_sec_did=1"
        val withMore = "$LONG_BASE?region=US&activity_info=b&share_sign=SIG2&ug_share_id=U2&with_sec_did=1&foo=bar"
        assertEquals("douyin:aweme:$AWEME_ID", MediaIdentity.identityOf(withParams))
        assertEquals(MediaIdentity.identityOf(withParams), MediaIdentity.identityOf(withMore))
    }

    @Test
    fun videoAndNoteLongLinksSupported() {
        assertEquals("douyin:aweme:$AWEME_ID", MediaIdentity.identityOf("https://www.douyin.com/video/$AWEME_ID"))
        assertEquals("douyin:aweme:$AWEME_ID", MediaIdentity.identityOf("https://www.douyin.com/note/$AWEME_ID"))
        assertEquals("douyin:aweme:$AWEME_ID", MediaIdentity.identityOf("https://www.iesdouyin.com/share/video/$AWEME_ID"))
    }

    @Test
    fun shortLinkHasNoIdAndFallsBackToCanonical() {
        // 短链自身不含作品 ID：不能靠猜测短码，只能退化为 canonical（由别名在解析成功后补齐）
        assertNull(MediaIdentity.contentIdOf(SHORT))
        assertEquals("https://v.douyin.com/6kAjxhW68PA", MediaIdentity.identityOf(SHORT))
    }

    @Test
    fun differentAwemeIdsYieldDifferentIdentities() {
        val other = "https://www.iesdouyin.com/share/slides/7451234567890123456"
        assertEquals("douyin:aweme:7451234567890123456", MediaIdentity.identityOf(other))
        assert(MediaIdentity.identityOf(other) != MediaIdentity.identityOf(LONG_BASE))
    }

    @Test
    fun nonDouyinUrlKeepsCanonicalFallback() {
        val url = "https://www.bilibili.com/video/BV1xx411c7mD?spm_id_from=333.999"
        assertEquals(MediaUrlCanonicalizer.canonical(url), MediaIdentity.identityOf(url))
        assertNull(MediaIdentity.contentIdOf(url))
    }

    @Test
    fun modalIdQueryIsUsedAsFallback() {
        val url = "https://www.douyin.com/discover?modal_id=$AWEME_ID&from=web"
        assertEquals("douyin:aweme:$AWEME_ID", MediaIdentity.identityOf(url))
    }

    @Test
    fun resolveUsesAliasTableWhenPresent() {
        val aliases = mapOf(
            MediaUrlCanonicalizer.canonical(SHORT) to "douyin:aweme:$AWEME_ID",
        )
        // 短链命中别名 → 与长链同一身份
        assertEquals("douyin:aweme:$AWEME_ID", MediaIdentity.resolve(SHORT, aliases))
        assertEquals("douyin:aweme:$AWEME_ID", MediaIdentity.resolve(LONG_BASE, aliases))
    }

    @Test
    fun aliasEncodeDecodeRoundTrip() {
        val aliases = linkedMapOf(
            "https://v.douyin.com/6kAjxhW68PA" to "douyin:aweme:$AWEME_ID",
            "https://v.douyin.com/JXhI_o7V3D0" to "douyin:aweme:7451234567890123456",
        )
        val decoded = MediaIdentity.decodeAliases(MediaIdentity.encodeAliases(aliases))
        assertEquals(aliases, decoded)
    }

    @Test
    fun aliasDecodeSkipsMalformedEntries() {
        val decoded = MediaIdentity.decodeAliases(setOf("", "novalue", "abc"))
        assertEquals(emptyMap<String, String>(), decoded)
        assertEquals(emptyMap<String, String>(), MediaIdentity.decodeAliases(null))
    }

    @Test
    fun identityTypeAndAwemeIdHelpers() {
        val identity = "douyin:aweme:$AWEME_ID"
        assertEquals("aweme", MediaIdentity.identityTypeOf(identity))
        assertEquals(AWEME_ID, MediaIdentity.awemeIdFromIdentity(identity))
        assertEquals("url", MediaIdentity.identityTypeOf("https://v.douyin.com/6kAjxhW68PA"))
        assertNull(MediaIdentity.awemeIdFromIdentity("https://v.douyin.com/6kAjxhW68PA"))
        assertNull(MediaIdentity.awemeIdFromIdentity(null))
    }
}
