package com.kuaixia.app.core.log

import android.content.Context
import java.io.File

/**
 * 崩溃报告存储：把最近一次崩溃写入 App 内部存储，
 * 下次启动可读取「上次崩溃」。
 */
object CrashLogStore {

    private const val FILE_NAME = "last_crash.txt"

    @Volatile
    private var dir: File? = null

    fun init(context: Context) {
        dir = File(context.filesDir, "logs")
    }

    fun write(report: String) {
        runCatching {
            val d = dir ?: return
            d.mkdirs()
            File(d, FILE_NAME).writeText(report, Charsets.UTF_8)
        }
    }

    fun read(): String? = runCatching {
        val d = dir ?: return null
        val file = File(d, FILE_NAME)
        if (file.exists()) file.readText(Charsets.UTF_8) else null
    }.getOrNull()

    fun clear() {
        runCatching { dir?.let { File(it, FILE_NAME).delete() } }
    }
}
