package com.kuaixia.app.core

import android.app.Activity
import android.app.Application
import java.lang.ref.WeakReference

/**
 * P6 diagnostic only：记录当前 RESUMED Activity 的弱引用，供 WebView 宿主生命周期实验使用。
 * - 只持有 [WeakReference]（不永久持有 Activity，无泄漏）；
 * - paused/destroyed 即清空；
 * - 正式业务不使用本 provider（仅 WebViewParser 的 A_B_ACTIVITY_HOST_TEST 实验路径引用）。
 */
object ActivityProvider {

    @Volatile
    private var resumed: WeakReference<Activity>? = null

    @Volatile
    private var registered = false

    fun register(app: Application) {
        if (registered) return
        synchronized(this) {
            if (registered) return
            registered = true
        }
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                resumed = WeakReference(activity)
            }

            override fun onActivityPaused(activity: Activity) {
                if (resumed?.get() === activity) resumed = null
            }

            override fun onActivityDestroyed(activity: Activity) {
                if (resumed?.get() === activity) resumed = null
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) {}
        })
    }

    /** 当前 RESUMED Activity（可能为 null：无前台 Activity）。 */
    fun current(): Activity? = resumed?.get()
}
