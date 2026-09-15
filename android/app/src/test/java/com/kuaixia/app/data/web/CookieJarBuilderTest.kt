package com.kuaixia.app.data.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1：Cookie jar 合成纯逻辑测试（[buildNetscapeCookieJar]）。
 *
 * 纪律：测试内只使用**明显的占位值**（dummy-*），不包含任何真实 Cookie 值；
 * 断言采用**结构化比对**（按行 / 按 tab 拆字段），不看子串 —— 失败时可直接看到字段差异。
 */
class CookieJarBuilderTest {

    private val douyin = ".douyin.com"
    private val iesdouyin = ".iesdouyin.com"

    /** jar 数据行（跳过注释/空行）→ 按 tab 拆成字段。 */
    private fun jarFields(out: CookieJarResult): List<List<String>> =
        out.text.lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('\t') }

    /** 期望的单条 jar 行：domain  TRUE  /  TRUE  0  name  value。 */
    private fun line(domain: String, name: String, value: String) =
        listOf(domain, "TRUE", "/", "TRUE", "0", name, value)

    @Test
    fun svWebIdPresentIsExportedUnderDouyinDomain() {
        // 1. 存在 s_v_web_id（来自 douyin 锚点）→ 必须导出、归属 .douyin.com、hasSvWebId=true
        val out = buildNetscapeCookieJar(
            listOf(AnchorCookie(douyin, "s_v_web_id=dummy-a; ttwid=dummy-b")),
        )
        assertEquals(2, out.count)
        assertTrue("存在 s_v_web_id 时必须置位", out.hasSvWebId)
        assertEquals(listOf(douyin), out.domains)
        assertEquals(
            listOf(
                line(douyin, "s_v_web_id", "dummy-a"),
                line(douyin, "ttwid", "dummy-b"),
            ),
            jarFields(out),
        )
        assertTrue(out.text.startsWith("# Netscape HTTP Cookie File"))
    }

    @Test
    fun missingSvWebIdKeepsOriginalBehaviour() {
        // 2. 不含 s_v_web_id → hasSvWebId=false，其余条目照旧导出（保持原行为）
        val out = buildNetscapeCookieJar(listOf(AnchorCookie(douyin, "ttwid=dummy-b")))
        assertEquals(1, out.count)
        assertFalse(out.hasSvWebId)
        assertEquals(listOf(douyin), out.domains)
        assertEquals(listOf(line(douyin, "ttwid", "dummy-b")), jarFields(out))
    }

    @Test
    fun iesdouyinAnchorDoesNotThrowAndKeepsItsOwnDomain() {
        // 3. 只有 iesdouyin 锚点有 Cookie（douyin 锚点为 null）→ 不异常，且归属 .iesdouyin.com
        val out = buildNetscapeCookieJar(
            listOf(
                AnchorCookie(douyin, null),
                AnchorCookie(iesdouyin, "ttwid=dummy-c"),
            ),
        )
        assertEquals(1, out.count)
        assertEquals(listOf(iesdouyin), out.domains)
        assertEquals(listOf(line(iesdouyin, "ttwid", "dummy-c")), jarFields(out))
    }

    @Test
    fun duplicateNamePrefersFirstAnchorAndSetsSvWebId() {
        // 4. 同名 Cookie 由「首个锚点」胜出（douyin 在前 → s_v_web_id 归属 .douyin.com）；
        //    第二个锚点的非重复条目仍按自己的域导出
        val out = buildNetscapeCookieJar(
            listOf(
                AnchorCookie(douyin, "s_v_web_id=dummy-a"),
                AnchorCookie(iesdouyin, "s_v_web_id=dummy-b; ttwid=dummy-c"),
            ),
        )
        assertEquals("同名去重后只写 2 条（s_v_web_id + ttwid）", 2, out.count)
        assertTrue(out.hasSvWebId)
        assertEquals(listOf(douyin, iesdouyin), out.domains)
        assertEquals(
            listOf(
                line(douyin, "s_v_web_id", "dummy-a"),
                line(iesdouyin, "ttwid", "dummy-c"),
            ),
            jarFields(out),
        )
    }

    @Test
    fun emptyOrAllNullAnchorsYieldZeroCount() {
        // 5. 无可用 Cookie → count=0（调用方据此返回 null，不产出空 jar 文件）
        assertEquals(0, buildNetscapeCookieJar(emptyList()).count)
        assertFalse(buildNetscapeCookieJar(emptyList()).hasSvWebId)
        val allNull = buildNetscapeCookieJar(
            listOf(AnchorCookie(douyin, null), AnchorCookie(iesdouyin, "")),
        )
        assertEquals(0, allNull.count)
        assertEquals(emptyList<String>(), allNull.domains)
        assertFalse(allNull.hasSvWebId)
        assertEquals(emptyList<List<String>>(), jarFields(allNull))
    }

    @Test
    fun malformedPairsAreSkipped() {
        // 6. 空段 / 无 '=' / 空 name / 空 value 一律跳过；合法条目保留
        val out = buildNetscapeCookieJar(
            listOf(AnchorCookie(douyin, "; ; a=; =b; s_v_web_id=dummy-a")),
        )
        assertEquals(1, out.count)
        assertTrue(out.hasSvWebId)
        assertEquals(listOf(line(douyin, "s_v_web_id", "dummy-a")), jarFields(out))
    }

    @Test
    fun namesAndValuesAreSanitizedToSingleLineFields() {
        // 7. name 中空格 → '_'；value 中制表/换行 → 空格（沿用既有清洗，不破坏行结构）
        val out = buildNetscapeCookieJar(listOf(AnchorCookie(douyin, "s v_web_id=dummy\tvalue")))
        assertEquals(1, out.count)
        assertEquals(listOf(line(douyin, "s_v_web_id", "dummy value")), jarFields(out))
    }
}
