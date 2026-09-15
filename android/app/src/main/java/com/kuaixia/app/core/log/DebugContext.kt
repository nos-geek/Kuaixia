package com.kuaixia.app.core.log

/**
 * 全局调试上下文：记录当前所处阶段 / 任务 / URL，供崩溃报告定位。
 * 崩溃发生在任何阶段时，都能从 [snapshot] 拿到当时在做什么。
 */
object DebugContext {

    @Volatile
    var currentStage: String = Stages.IDLE
        private set

    @Volatile
    var currentTaskId: String? = null
        private set

    @Volatile
    var currentUrl: String? = null
        private set

    /** 当前解析器状态（版本/可用性），由 App 启动后持续采集写入，崩溃时一并记录。 */
    @Volatile
    var parserInfo: String = "未采集"
        private set

    fun setParserInfo(info: String) {
        parserInfo = info
    }

    /** 进入某阶段（可选更新任务与 URL）。 */
    fun enter(stage: String, taskId: String? = null, url: String? = null) {
        currentStage = stage
        if (taskId != null) currentTaskId = taskId
        if (url != null) currentUrl = url
    }

    fun setStage(stage: String) {
        currentStage = stage
    }

    fun clear() {
        currentStage = Stages.IDLE
        currentTaskId = null
        currentUrl = null
    }

    fun snapshot(): String = buildString {
        appendLine("当前阶段：").append(currentStage)
        appendLine("当前任务：").append(currentTaskId ?: "(无)")
        appendLine("当前URL：").append(LogSanitizer.sanitizeUrl(currentUrl))
        appendLine("解析器状态：").append(parserInfo)
    }
}
