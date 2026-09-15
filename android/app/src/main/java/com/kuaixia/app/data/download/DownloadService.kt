package com.kuaixia.app.data.download

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.kuaixia.app.KuaixiaApp
import com.kuaixia.app.core.log.AppLogger
import com.kuaixia.app.core.log.LogTags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 下载前台服务。
 *
 * 职责（严格限定，不复制下载逻辑）：
 * - 把 App 置为前台（保活），下载期间切后台/锁屏/回桌面不中断；
 * - 观察 [DownloadRepository.tasks]，调用 [DownloadNotifier] 刷新通知；
 * - 无活跃任务时自行停止。
 *
 * 真正的下载调度完全在 DownloadRepository（独立 IO scope），本 Service 只观察状态。
 */
class DownloadService : Service() {

    // BUG-001：任务流的 O(n) 统计/通知数据构建属于 CPU 工作，放在 Default 线程池，
    // 不再占用主线程（ColorOS "MainThread worked timeout" ANR 的直接来源）。
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var collectJob: Job? = null
    private var notifier: DownloadNotifier? = null

    /** 诊断计数（低频日志）：tasks 发射次数 / 通知重建次数。 */
    private var emissionCount = 0L

    private val repository: DownloadRepository?
        get() = (application as? KuaixiaApp)?.container?.downloadRepository

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifier = DownloadNotifier(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val repo = repository
        if (repo == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val nf = notifier ?: DownloadNotifier(this).also { notifier = it }

        if (collectJob == null) {
            // 立即用当前快照展示前台通知（startForeground 必须尽快调用）
            startForegroundCompat(nf)

            collectJob = serviceScope.launch {
                val sentTerminal = HashSet<String>()
                AppLogger.i(
                    "开始观察 tasks（collect 线程=${Thread.currentThread().name}）",
                    LogTags.DOWNLOAD,
                )
                repo.tasks.collectLatest { tasks ->
                    emissionCount++
                    // 终态通知（每次从终态离开后允许再次触发）
                    val terminalNow = tasks.filter {
                        it.state == DownloadState.COMPLETED || it.state == DownloadState.FAILED
                    }.map { it.id }.toSet()
                    sentTerminal.retainAll(terminalNow)  // 仍处终态的保留，避免重复发
                    tasks.forEach { t ->
                        if ((t.state == DownloadState.COMPLETED || t.state == DownloadState.FAILED) &&
                            !sentTerminal.contains(t.id)
                        ) {
                            nf.notifyTerminal(t)
                            sentTerminal.add(t.id)
                        }
                    }

                    nf.updateProgress(tasks)

                    val activeCount = tasks.count {
                        it.state == DownloadState.QUEUED || it.state == DownloadState.DOWNLOADING
                    }
                    if (emissionCount % 100L == 0L) {
                        AppLogger.i(
                            "tasks 发射 $emissionCount 次（collect 线程=${Thread.currentThread().name}）",
                            LogTags.DOWNLOAD,
                        )
                    }
                    if (activeCount == 0) {
                        AppLogger.i(
                            "下载队列空闲，停止前台服务（累计发射 $emissionCount 次）",
                            LogTags.DOWNLOAD,
                        )
                        nf.dismissProgress()
                        stopForegroundCompat()
                        stopSelf()
                    }
                }
            }
        } else {
            // 已有观察者：只刷新一次当前进度（可能新任务入队）
            nf.updateProgress(repo.tasks.value)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        collectJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat(nf: DownloadNotifier) {
        val notif = nf.buildProgressNotification(repository?.tasks?.value.orEmpty())
        startForeground(DownloadNotifier.PROGRESS_NOTIFICATION_ID, notif)
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    companion object {
        /** 启动前台服务（Android 8+ 需 startForegroundService，进入后 5s 内 startForeground）。 */
        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, DownloadService::class.java)
                if (Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure {
                AppLogger.e("启动前台服务失败", it, LogTags.DOWNLOAD)
            }
        }
    }
}
