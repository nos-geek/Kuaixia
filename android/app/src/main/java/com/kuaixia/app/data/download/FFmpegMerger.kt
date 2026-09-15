package com.kuaixia.app.data.download

import com.arthenica.ffmpegkit.FFmpegKit
import com.kuaixia.app.core.error.AppException
import com.kuaixia.app.core.error.ErrorCode
import com.kuaixia.app.core.log.AppLogRepository
import com.kuaixia.app.core.log.AppLogger
import com.kuaixia.app.core.log.LogSanitizer
import com.kuaixia.app.core.log.LogTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "FFmpeg"

/**
 * FFmpeg 封装：负责 DASH 分离流的音视频合并。
 *
 * 使用 stream copy（`-c copy`），不重新编码，速度快、无质量损失。
 * 依赖 dev.ffmpegkit-maintained:ffmpeg-kit-full（LGPL，含 mp4 muxer）。
 */
object FFmpegMerger {

    /**
     * 把 [videoFile] 与 [audioFile] 合并到 [outputFile]（mp4）。
     * 在 IO 线程同步执行 FFmpeg，成功后返回 [outputFile]。
     */
    suspend fun merge(videoFile: File, audioFile: File, outputFile: File): Result<File> =
        withContext(Dispatchers.IO) {
            val args = arrayOf(
                "-y",
                "-i", videoFile.absolutePath,
                "-i", audioFile.absolutePath,
                "-c", "copy",
                "-movflags", "+faststart",
                outputFile.absolutePath,
            )
            AppLogRepository.i(
                LogTags.FFMPEG,
                "开始合并 input1=${videoFile.absolutePath} input2=${audioFile.absolutePath} output=${outputFile.absolutePath}",
            )
            AppLogRepository.d(LogTags.FFMPEG, "arguments=${args.joinToString(" ")}")
            val started = System.currentTimeMillis()
            return@withContext try {
                val session = FFmpegKit.executeWithArguments(args)
                val rc = session.returnCode
                val logs = session.allLogsAsString.orEmpty()
                if (rc.isValueSuccess) {
                    AppLogRepository.i(
                        LogTags.FFMPEG,
                        "合并成功 exitCode=${rc.value} elapsed_ms=${System.currentTimeMillis() - started} " +
                            "output=${outputFile.absolutePath} size=${outputFile.length()}",
                    )
                    Result.success(outputFile)
                } else {
                    val safeLogs = LogSanitizer.sanitize(logs).take(2000)
                    AppLogRepository.e(
                        LogTags.FFMPEG,
                        "合并失败 exitCode=${rc.value} elapsed_ms=${System.currentTimeMillis() - started} logs=$safeLogs",
                    )
                    Result.failure(
                        AppException(ErrorCode.PARSER_ERROR, "音视频合并失败", safeLogs),
                    )
                }
            } catch (e: Exception) {
                AppLogRepository.e(LogTags.FFMPEG, "FFmpeg 执行异常", e)
                Result.failure(AppException(ErrorCode.PARSER_ERROR, "FFmpeg 执行异常：${e.message}"))
            }
        }

    /**
     * Phase 4：按 playlist 顺序合并任意数量分片（M3U8/HLS）。
     *
     * 用 FFmpeg concat demuxer：写 `file '<abs>'` 清单文件后 `-f concat -safe 0 -i list -c copy`。
     * [initFile]（EXT-X-MAP fMP4 init）如有则置于清单首位。
     * 不做任何转码；顺序严格由清单（= 分片索引顺序）决定，不依赖文件名排序。
     *
     * @param logTag 调用方日志 tag（M3U8Downloader 传 "M3U8"，让日志归属正确模块）。
     */
    suspend fun concatSegments(
        segmentFiles: List<File>,
        initFile: File?,
        outputFile: File,
        logTag: String = LogTags.FFMPEG,
    ): Result<File> = withContext(Dispatchers.IO) {
        val listFile = File(outputFile.parentFile, "${outputFile.name}.concat.txt")
        listFile.parentFile?.mkdirs()
        val listContent = buildString {
            initFile?.let { appendLine("file '${it.absolutePath.replace("'", "'\\''")}'") }
            segmentFiles.forEach { appendLine("file '${it.absolutePath.replace("'", "'\\''")}'") }
        }
        runCatching { listFile.writeText(listContent, Charsets.UTF_8) }
            .onFailure { return@withContext Result.failure(AppException(ErrorCode.PARSER_ERROR, "写合并清单失败")) }

        val args = arrayOf(
            "-y",
            "-f", "concat",
            "-safe", "0",
            "-i", listFile.absolutePath,
            "-c", "copy",
            "-movflags", "+faststart",
            outputFile.absolutePath,
        )
        AppLogRepository.i(
            logTag,
            "concat 合并 inputs=${segmentFiles.size}" +
                (if (initFile != null) " (含 init)" else "") +
                " list=${listFile.absolutePath} output=${outputFile.absolutePath}",
        )
        AppLogRepository.d(logTag, "arguments=${args.joinToString(" ")}")
        val started = System.currentTimeMillis()
        return@withContext try {
            val session = FFmpegKit.executeWithArguments(args)
            val rc = session.returnCode
            if (rc.isValueSuccess) {
                AppLogRepository.i(
                    logTag,
                    "concat 成功 exitCode=${rc.value} elapsed_ms=${System.currentTimeMillis() - started} " +
                        "output=${outputFile.absolutePath} size=${outputFile.length()}",
                )
                Result.success(outputFile)
            } else {
                val logs = LogSanitizer.sanitize(session.allLogsAsString.orEmpty()).take(2000)
                AppLogRepository.e(
                    logTag,
                    "concat 失败 exitCode=${rc.value} elapsed_ms=${System.currentTimeMillis() - started} logs=$logs",
                )
                Result.failure(
                    AppException(
                        ErrorCode.PARSER_ERROR,
                        "分片合并失败（该 HLS 可能含不支持的编码结构）",
                        logs,
                    ),
                )
            }
        } catch (e: Exception) {
            AppLogRepository.e(logTag, "concat 执行异常", e)
            Result.failure(AppException(ErrorCode.PARSER_ERROR, "FFmpeg 合并异常：${e.message}"))
        }
    }
}
