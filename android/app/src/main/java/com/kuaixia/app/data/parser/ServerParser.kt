package com.kuaixia.app.data.parser

import com.kuaixia.app.core.log.AppLogger
import com.kuaixia.app.core.model.MediaParseResult
import com.kuaixia.app.core.model.MediaStream
import com.kuaixia.app.data.ParserRepository
import com.kuaixia.app.data.model.StreamInfo
import com.kuaixia.app.data.model.VideoInfo

/**
 * 服务器解析器：调用 Phase 1 的 `POST /api/v1/parse`，
 * 把服务器返回的 [MediaParseResult] 转换成统一领域模型 [VideoInfo]。
 */
class ServerParser(private val repository: ParserRepository) : VideoParser {

    override suspend fun parse(url: String): Result<VideoInfo> {
        AppLogger.i("parse url=$url", TAG)
        return repository.parse(url).map { it.toVideoInfo() }
    }

    private fun MediaParseResult.toVideoInfo(): VideoInfo = VideoInfo(
        id = originalUrl,
        title = title,
        author = author,
        webpageUrl = originalUrl,
        duration = duration,
        thumbnail = thumbnail,
        platform = platform,
        streams = streams.map { it.toStreamInfo() },
    )

    private fun MediaStream.toStreamInfo(): StreamInfo = StreamInfo(
        formatId = id,
        url = url,
        quality = quality,
        ext = mimeType?.substringAfter('/', "")?.takeIf { it.isNotBlank() },
        resolution = if (width != null && height != null) "${width}×${height}" else null,
        width = width,
        height = height,
        fps = fps?.toFloat(),
        fileSize = fileSize,
        vcodec = codec,
        acodec = null,
        hasAudio = hasAudio,
    )

    private companion object {
        const val TAG = "ServerParser"
    }
}
