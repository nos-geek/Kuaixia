package com.kuaixia.app.data.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.kuaixia.app.R
import java.util.Locale

/**
 * 下载通知构建与发送。
 *
 * - 前台进行通知（[PROGRESS_NOTIFICATION_ID]，常驻）：单任务显示文件名/阶段/进度/速度，
 *   多任务聚合为「正在下载 N 个任务」；
 * - 终态通知：每个任务完成/失败发一条（点击打开 App）。
 *
 * 仅在任务状态经 Repository 变化时调用，不做额外下载逻辑。
 */
class DownloadNotifier(private val context: Context) {

    private val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.download_progress),
            NotificationManager.IMPORTANCE_LOW, // 进度通知不响铃不打扰
        ).apply { description = context.getString(R.string.download_channel_description) }
        nm.createNotificationChannel(channel)
    }

    /** 构建进行通知（供 startForeground 直接使用；同时 notify 一次）。 */
    fun buildProgressNotification(tasks: List<DownloadTask>): Notification {
        val active = tasks.filter {
            it.state == DownloadState.QUEUED || it.state == DownloadState.DOWNLOADING
        }
        val notif: Notification = if (active.isEmpty()) {
            base()
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(context.getString(R.string.download_notif_preparing))
                .build()
        } else if (active.size == 1) {
            progressSingle(active.first())
        } else {
            val total = active.sumOf { it.progress.percent.toLong() } / active.size
            progressAggregate(active.size, total.toInt())
        }
        return notif
    }

    /** 更新前台进行通知；无活跃任务时传入空列表，由 Service 负责撤前台。 */
    fun updateProgress(tasks: List<DownloadTask>) {
        val active = tasks.filter {
            it.state == DownloadState.QUEUED || it.state == DownloadState.DOWNLOADING
        }
        if (active.isEmpty()) return
        val notif = buildProgressNotification(tasks)
        runCatching { nm.notify(PROGRESS_NOTIFICATION_ID, notif) }
    }

    /** 某任务进入终态时发送完成/失败通知。 */
    fun notifyTerminal(task: DownloadTask) {
        val notif = buildTerminal(task)
        runCatching { nm.notify(terminalId(task.id), notif) }
    }

    /** 移除某任务的终态通知（任务被删除时）。 */
    fun cancelTaskNotifications(taskId: String) {
        runCatching { nm.cancel(terminalId(taskId)) }
    }

    /** 清空进行通知（前台服务停止时）。 */
    fun dismissProgress() {
        runCatching { nm.cancel(PROGRESS_NOTIFICATION_ID) }
    }

    // ---- 内部 ----

    private fun progressSingle(t: DownloadTask): Notification {
        val title = t.title ?: t.fileName
        val body: String = if (t.state == DownloadState.QUEUED) {
            context.getString(R.string.download_notif_queued)
        } else {
            val stageText = when (t.stage) {
                DownloadStage.DOWNLOADING_VIDEO -> context.getString(R.string.download_notif_downloading_video)
                DownloadStage.DOWNLOADING_AUDIO -> context.getString(R.string.download_notif_downloading_audio)
                DownloadStage.MERGING -> context.getString(R.string.download_notif_merging)
                else -> context.getString(R.string.download_notif_downloading)
            }
            "$stageText  ${t.progress.percent}%  ${formatSpeed(t.progress.speed)}"
        }
        return base()
            .setContentTitle(title)
            .setContentText(body)
            .setProgress(100, t.progress.percent, t.progress.percent <= 0)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun progressAggregate(n: Int, percent: Int): Notification = base()
        .setContentTitle(context.getString(R.string.download_notif_aggregate_title, n))
        .setContentText(context.getString(R.string.download_notif_total_progress, percent))
        .setProgress(100, percent, percent <= 0)
        .setOnlyAlertOnce(true)
        .build()

    private fun buildTerminal(t: DownloadTask): Notification {
        val success = t.state == DownloadState.COMPLETED
        val bigText = if (success) {
            context.getString(R.string.download_notif_saved, t.fileName)
        } else {
            val error = t.errorMessage ?: context.getString(R.string.download_notif_unknown_error)
            "${t.fileName}\n$error"
        }
        return base(importanceHigh = !success)
            .setContentTitle(
                context.getString(
                    if (success) R.string.download_notif_complete_title
                    else R.string.download_notif_failed_title,
                ),
            )
            .setContentText(t.fileName)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun base(importanceHigh: Boolean = false): NotificationCompat.Builder {
        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP }
        val pi = launchIntent?.let {
            PendingIntent.getActivity(
                context,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val priority = if (importanceHigh) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_LOW
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setPriority(priority)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        if (pi != null) builder.setContentIntent(pi)
        return builder
    }

    private fun terminalId(taskId: String): Int =
        (taskId.hashCode() and 0x7fffffff).coerceAtLeast(1000)

    private fun formatSpeed(speed: Long): String {
        if (speed <= 0) return ""
        return if (speed >= 1024 * 1024) {
            String.format(Locale.ROOT, "%.1f MB/s", speed / (1024.0 * 1024.0))
        } else {
            String.format(Locale.ROOT, "%.0f KB/s", speed / 1024.0)
        }
    }

    companion object {
        const val CHANNEL_ID = "kuaixia_download"
        const val PROGRESS_NOTIFICATION_ID = 1000
    }
}
