package com.kuaixia.app.core.util

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import com.kuaixia.app.core.model.AppLanguage
import java.util.Locale

/** 应用语言运行时切换：内存缓存 + Context locale 包装。 */
object LocaleHelper {

    @Volatile
    var current: AppLanguage = AppLanguage.ZH
        private set

    fun set(lang: AppLanguage) {
        current = lang
    }

    /** 返回带指定语言的 Context（用于 Activity.attachBaseContext）。 */
    fun wrap(context: Context): Context {
        val locale = Locale(current.code)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocales(LocaleList(locale))
        return context.createConfigurationContext(config)
    }
}
