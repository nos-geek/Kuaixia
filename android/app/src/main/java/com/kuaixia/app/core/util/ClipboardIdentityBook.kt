package com.kuaixia.app.core.util

/**
 * 剪贴板自动解析的「作品身份账本」（纯 Kotlin，可 JVM 单测，无 Android 依赖）。
 *
 * 职责（BUG-002）：
 * 1. 把「这次剪贴板 URL 对应哪个作品身份」算出来（[resolve]），优先走已登记的短链别名；
 * 2. 记住「正在解析的身份」与「已解析过的身份」，供 [ClipboardAutoDecider] 判 IN_FLIGHT / DUPLICATE；
 * 3. 解析成功后用**最终网页 URL** 登记短链别名（[registerResolved]）——短链本身不含作品 ID，
 *    只有解析完成后才知道它对应哪个 awemeId；
 * 4. 支持持久化快照与恢复（[snapshotAliases] / [restore]），使 App 重启后仍能命中同一作品。
 *
 * **不改动** [ClipboardAutoDecider] 的任何语义：IN_FLIGHT / DUPLICATE / TRIGGER /
 * AUTO_DISABLED / UNSUPPORTED 的含义与优先级完全不变，只是比较的 key 从
 * 「canonical URL 字符串」换成「作品身份」。
 */
class ClipboardIdentityBook {

    /** 已自动解析过的作品身份（同一进程内有效；重启后由 [restore] 恢复）。 */
    @Volatile
    var lastIdentity: String? = null
        private set

    /** 正在自动解析中的作品身份。 */
    @Volatile
    var inFlightIdentity: String? = null
        private set

    /** canonical(URL) → identity：短链等「自身不含作品 ID」的入口靠它归并到同一个作品。 */
    private val aliases = LinkedHashMap<String, String>()

    /** 计算某 URL 对应的作品身份（先查别名表，再按 URL 提取）。 */
    fun resolve(url: String): String = MediaIdentity.resolve(url, aliases)

    /**
     * 完整决策（等价于原先 HomeViewModel 内的 canonical 比较，只是 key 换成 identity）。
     *
     * @param autoEnabled 自动解析开关
     * @param supported   平台是否受支持（UNKNOWN/CDN 不自动触发）
     */
    fun decide(
        url: String,
        autoEnabled: Boolean,
        supported: Boolean,
    ): ClipboardAutoDecider.Decision = ClipboardAutoDecider.decide(
        autoEnabled = autoEnabled,
        canonicalUrl = resolve(url),
        inFlightCanonical = inFlightIdentity,
        lastAutoParsedCanonical = lastIdentity,
        supported = supported,
    )

    /**
     * 决策为 TRIGGER 后调用：占位，防止 INITIAL/RESUME/Listener 三重触发同一任务。
     * 未识别身份的短链也会占位（用其 canonical），解析成功后由 [registerResolved] 升级为作品身份。
     */
    fun markTriggered(url: String) {
        val identity = resolve(url)
        inFlightIdentity = identity
        lastIdentity = identity
    }

    /** 解析结束（成功或失败）后调用：释放 in-flight 占位。 */
    fun markFinished() {
        inFlightIdentity = null
    }

    /**
     * 解析成功后调用：用最终网页 URL 提取作品 ID，并把「本次请求的 URL」登记为该身份的别名。
     *
     * @param requestedUrl 本次真正解析的 URL（可能是短链）
     * @param resolvedUrl  解析得到的最终网页 URL（含 awemeId）
     * @return 账本是否发生变化（调用方可据此决定是否落盘）
     */
    fun registerResolved(requestedUrl: String, resolvedUrl: String?): Boolean {
        val target = resolvedUrl?.trim().orEmpty()
        if (target.isBlank()) return false
        if (MediaIdentity.contentIdOf(target) == null) return false // 拿不到作品 ID → 保持既有 fallback
        val identity = MediaIdentity.identityOf(target)
        var changed = false
        val key = MediaUrlCanonicalizer.canonical(requestedUrl)
        if (aliases[key] != identity) {
            aliases[key] = identity
            changed = true
        }
        if (lastIdentity != identity) {
            lastIdentity = identity
            changed = true
        }
        trimAliases()
        return changed
    }

    /** 取消解析（用户手动取消）后调用。 */
    fun resetInFlight() {
        inFlightIdentity = null
    }

    /**
     * 用持久化状态恢复账本（App 重启后首次检查时调用）。
     *
     * 只在内存状态为空时采纳持久化值，避免用「尚未写盘的旧值」覆盖进程内更新的状态。
     */
    fun restore(persistedLastIdentity: String?, persistedAliases: Set<String>?) {
        if (lastIdentity.isNullOrBlank() && !persistedLastIdentity.isNullOrBlank()) {
            lastIdentity = persistedLastIdentity
        }
        if (aliases.isEmpty()) {
            aliases.putAll(MediaIdentity.decodeAliases(persistedAliases))
        }
    }

    /** 供持久化：别名表的 DataStore 形态。 */
    fun snapshotAliases(): Set<String> = MediaIdentity.encodeAliases(aliases)

    /** 供持久化：当前已解析身份（可能为空）。 */
    fun snapshotLastIdentity(): String = lastIdentity.orEmpty()

    /** 别名条目数（测试用）。 */
    fun aliasCount(): Int = aliases.size

    private fun trimAliases() {
        while (aliases.size > MediaIdentity.MAX_ALIASES) {
            val first = aliases.keys.firstOrNull() ?: break
            aliases.remove(first)
        }
    }
}
