package com.kuaixia.app.data.image

import com.kuaixia.app.data.model.ImageResource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * p96 坏 Host 刷新策略单测（纯 JVM）。
 *
 * 覆盖：只替换 p96、二次刷新、三次仍 p96、刷新数量不足作废、刷新出现重复 key 的确定性。
 */
class P96RefreshPolicyTest {

    private fun img(host: String, key: String): ImageResource = ImageResource(
        url = "https://$host/tos-cn-i-0813c000-ce/$key~tplv-dy-water-v2:abc=:2160:3840.webp?lk3s=1&x-signature=S",
        mimeType = "image/webp",
        extension = "webp",
    )

    private val P96 = "p96-sign.douyinpic.com"
    private val P5EX = "p5-ex-gddgtc-sign.douyinpic.com"
    private val P11 = "p11-sign.douyinpic.com"
    private val P26 = "p26-sign.douyinpic.com"

    private val KEY_A = "oAAA1AAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    private val KEY_B = "oBBB2BBBBBBBBBBBBBBBBBBBBBBBBB"

    // ---------- Test 1：只替换 p96，非 p96 原样保留 ----------
    @Test
    fun replacesOnlyP96AndKeepsHealthyHosts() {
        val original = listOf(img(P96, KEY_A), img(P5EX, KEY_B))
        val refresh = listOf(img(P26, KEY_A), img(P11, KEY_B))

        val r = P96RefreshPolicy.merge(original, refresh, expectedCount = 2)

        assertTrue(r.accepted)
        assertEquals(2, r.images.size)
        assertEquals(P26, P96RefreshPolicy.hostOf(r.images[0].url))
        // B 原本是 p5-ex（可用），即使刷新给了 p11 也不替换
        assertEquals(P5EX, P96RefreshPolicy.hostOf(r.images[1].url))
        assertSame(original[1], r.images[1])
        assertEquals(1, r.replaced)
        assertEquals(0, r.finalP96)
        assertEquals(1, r.replacements.size)
        assertEquals(KEY_A, r.replacements[0].key)
        assertEquals(P96, r.replacements[0].oldHost)
        assertEquals(P26, r.replacements[0].newHost)
    }

    // ---------- Test 2：第一次刷新仍 p96，第二次刷新给到可用 Host ----------
    @Test
    fun secondAttemptAppliesWhenFirstStillP96() {
        val original = listOf(img(P96, KEY_A))

        val r1 = P96RefreshPolicy.merge(original, listOf(img(P96, KEY_A)), expectedCount = 1)
        assertTrue(r1.accepted)
        assertEquals(0, r1.replaced)
        assertEquals(1, r1.finalP96)
        assertEquals(P96, P96RefreshPolicy.hostOf(r1.images[0].url))

        val r2 = P96RefreshPolicy.merge(r1.images, listOf(img(P11, KEY_A)), expectedCount = 1)
        assertTrue(r2.accepted)
        assertEquals(1, r2.replaced)
        assertEquals(0, r2.finalP96)
        assertEquals(P11, P96RefreshPolicy.hostOf(r2.images[0].url))
    }

    // ---------- Test 3：多次刷新仍是 p96 → 保留 p96（不丢图） ----------
    @Test
    fun keepsP96WhenEveryAttemptStillP96() {
        val original = listOf(img(P96, KEY_A))
        val r1 = P96RefreshPolicy.merge(original, listOf(img(P96, KEY_A)), expectedCount = 1)
        val r2 = P96RefreshPolicy.merge(r1.images, listOf(img(P96, KEY_A)), expectedCount = 1)

        assertTrue(r2.accepted)
        assertEquals(0, r2.replaced)
        assertEquals(1, r2.finalP96)
        assertEquals(1, r2.images.size)
        assertEquals(P96, P96RefreshPolicy.hostOf(r2.images[0].url))
    }

    // ---------- Test 4：刷新数量不足 → 整次作废，保留原始列表 ----------
    @Test
    fun refreshWithCountMismatchIsRejected() {
        val original = (1..20).map { if (it <= 11) img(P96, "$KEY_A$it") else img(P5EX, "$KEY_B$it") }
        val refresh = (1..18).map { img(P26, "$KEY_A$it") }

        val r = P96RefreshPolicy.merge(original, refresh, expectedCount = 20)

        assertFalse(r.accepted)
        assertEquals(0, r.replaced)
        assertEquals(11, r.finalP96)
        assertSame(original, r.images)
        assertEquals(20, r.images.size)
    }

    // ---------- Test 5：刷新出现重复 object key → deterministic 且不产出重复图片 ----------
    @Test
    fun duplicateKeysInRefreshPickFirstAndNeverDuplicate() {
        val keyC = "oCCC3CCCCCCCCCCCCCCCCCCCCCCCCC"
        // 原始 3 张；刷新也是 3 条（条数一致 → 采纳），但其中 A 出现两次
        val original = listOf(img(P96, KEY_A), img(P96, KEY_B), img(P96, keyC))
        val refresh = listOf(img(P26, KEY_A), img(P11, KEY_A), img(P5EX, KEY_B))

        val r1 = P96RefreshPolicy.merge(original, refresh, expectedCount = 3)
        val r2 = P96RefreshPolicy.merge(original, refresh, expectedCount = 3)

        assertTrue(r1.accepted)
        assertEquals(3, r1.images.size)
        // 同 key 取首次出现（p26），不因顺序抖动改变
        assertEquals(P26, P96RefreshPolicy.hostOf(r1.images[0].url))
        assertEquals(P5EX, P96RefreshPolicy.hostOf(r1.images[1].url))
        // 刷新中缺失的 C 保留原 p96，绝不丢图
        assertEquals(P96, P96RefreshPolicy.hostOf(r1.images[2].url))
        // 两次调用结果完全一致（deterministic）
        assertEquals(
            r1.images.map { it.url },
            r2.images.map { it.url },
        )
        // 不产生重复图片：输出条数与 object key 唯一数一致（不多也不丢）
        assertEquals(3, r1.images.size)
        assertEquals(3, r1.images.map { PageJsonMerge.extractObjKey(it.url) }.toSet().size)
    }

    // ---------- 辅助覆盖：host 判定与计数 ----------
    @Test
    fun p96DetectionUsesExactHost() {
        assertTrue(P96RefreshPolicy.isP96(img(P96, KEY_A).url))
        assertFalse(P96RefreshPolicy.isP96(img(P5EX, KEY_A).url))
        assertFalse(P96RefreshPolicy.isP96(img(P11, KEY_A).url))
        assertFalse(P96RefreshPolicy.isP96(img(P26, KEY_A).url))
        // 前缀相似但不同 host 不得误判
        assertFalse(P96RefreshPolicy.isP96("https://p96-sign.douyinpic.com.evil.com/x~tplv-a.webp"))
        assertFalse(P96RefreshPolicy.isP96(null))
    }

    @Test
    fun countP96CountsOnlyBadHost() {
        val list = listOf(img(P96, KEY_A), img(P5EX, KEY_A), img(P96, KEY_B), img(P26, KEY_B))
        assertEquals(2, P96RefreshPolicy.countP96(list))
        assertEquals(0, P96RefreshPolicy.countP96(listOf(img(P5EX, KEY_A))))
    }

    @Test
    fun mergeWithoutAnyP96KeepsEverythingAsIs() {
        val original = listOf(img(P5EX, KEY_A), img(P11, KEY_B))
        val r = P96RefreshPolicy.merge(original, listOf(img(P26, KEY_A), img(P26, KEY_B)), expectedCount = 2)
        assertTrue(r.accepted)
        assertEquals(0, r.replaced)
        assertSame(original, r.images)
    }

    @Test
    fun missingKeyInRefreshKeepsOriginal() {
        val original = listOf(img(P96, KEY_A), img(P96, KEY_B))
        // 刷新只给了 A，缺 B → B 保留原 p96，绝不丢图
        val r = P96RefreshPolicy.merge(original, listOf(img(P26, KEY_A), img(P26, "oCCC3CCCCCCCCCCCCCCCCCCCCCCCCC")), expectedCount = 2)
        assertTrue(r.accepted)
        assertEquals(1, r.replaced)
        assertEquals(1, r.finalP96)
        assertEquals(P26, P96RefreshPolicy.hostOf(r.images[0].url))
        assertEquals(P96, P96RefreshPolicy.hostOf(r.images[1].url))
    }
}
