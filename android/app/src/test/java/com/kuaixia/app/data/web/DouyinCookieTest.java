package com.kuaixia.app.data.web;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * DouyinWebSession 纯逻辑测试：Cookie 串 key-value 对数统计。
 * 不含 Android 依赖，可在 JVM 直接运行。
 */
public class DouyinCookieTest {

    @Test
    public void countsNormalPairs() {
        assertEquals(3, DouyinWebSessionKt.countCookiePairs("sessionid=abc; ttwid=def; passport_csrf_token=xyz"));
    }

    @Test
    public void nullAndBlankAreZero() {
        assertEquals(0, DouyinWebSessionKt.countCookiePairs(null));
        assertEquals(0, DouyinWebSessionKt.countCookiePairs(""));
        assertEquals(0, DouyinWebSessionKt.countCookiePairs("   "));
    }

    @Test
    public void ignoresPairsWithoutEquals() {
        // 无 '=' 的 token（如 HttpOnly 标记残留）不算键值对
        assertEquals(2, DouyinWebSessionKt.countCookiePairs("a=1; b; c=3; d"));
    }

    @Test
    public void handlesSinglePair() {
        assertEquals(1, DouyinWebSessionKt.countCookiePairs("only=one"));
    }

    @Test
    public void trimsSpacesAroundPairs() {
        assertEquals(2, DouyinWebSessionKt.countCookiePairs("  a=1 ;  b=2  "));
    }
}
