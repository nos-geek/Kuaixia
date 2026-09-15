package com.kuaixia.app.data.parser

import com.kuaixia.app.core.error.AppException
import com.kuaixia.app.core.log.AppLogger
import com.kuaixia.app.data.model.VideoInfo
import com.kuaixia.app.data.ytdlp.YtDlpEngine
import com.kuaixia.app.data.ytdlp.YtDlpMapper
import com.kuaixia.app.data.ytdlp.YtDlpResult

/**
 * 本地 yt-dlp 解析器。产出统一领域模型 [VideoInfo]。
 */
class YtDlpParser(private val engine: YtDlpEngine) : VideoParser {

    override suspend fun parse(url: String): Result<VideoInfo> {
        val started = System.currentTimeMillis()
        AppLogger.i("parse url=$url", TAG)
        val result = engine.extractInfo(url)
        return result.fold(
            onSuccess = { raw ->
                AppLogger.i(
                    "parse ok id=${raw.id} title=${raw.title?.take(50)} formats=${raw.formats.size} " +
                        "elapsed_ms=${System.currentTimeMillis() - started}",
                    TAG,
                )
                val mapped = mapToVideoInfo(raw, url)
                if (mapped.streams.isEmpty()) {
                    AppLogger.w("mapped streams empty (无可直接下载格式)", TAG)
                }
                Result.success(mapped)
            },
            onFailure = { e ->
                val ae = e as? AppException
                AppLogger.w(
                    "parse failed code=${ae?.code} msg=${e.message} " +
                        "elapsed_ms=${System.currentTimeMillis() - started}",
                    TAG,
                )
                // 完整 stderr 仅进日志，不暴露给 UI
                ae?.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                    AppLogger.w("yt-dlp stderr detail: $detail", TAG)
                }
                Result.failure(e)
            },
        )
    }

    private fun mapToVideoInfo(raw: YtDlpResult, url: String): VideoInfo =
        YtDlpMapper.map(raw, url)

    private companion object {
        const val TAG = "YtDlp"
    }
}
