package com.kuaixia.app.data.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.kuaixia.app.core.log.AppLogRepository
import com.kuaixia.app.core.log.AppLogger
import com.kuaixia.app.core.log.LogTags
import java.io.File

private const val TAG = "MediaStore"

/**
 * 把合并完成的视频保存到系统相册（Movies/快夏），无需任何存储权限。
 *
 * - API 29+：用 RELATIVE_PATH="Movies/快夏" + IS_PENDING 事务，相册/图库可见。
 * - API 26-28：无 RELATIVE_PATH，写入默认 Movies 目录（仍可见，只是不在"快夏"子目录）。
 */
object MediaStoreSaver {

    /**
     * 把 [sourceFile] 写入 MediaStore Video 集合，返回插入的 [Uri]（失败返回 null）。
     */
    fun saveToMovies(context: Context, sourceFile: File, fileName: String): Uri? {
        val resolver = context.contentResolver
        val relativePath = "Movies/快夏"
        val isQPlus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        AppLogRepository.i(
            LogTags.MEDIASTORE,
            "准备写入 displayName=$fileName mimeType=video/mp4 relativePath=$relativePath " +
                "sourceSize=${sourceFile.length()} api=${Build.VERSION.SDK_INT}",
        )
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (isQPlus) {
                put(MediaStore.Video.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val collection = if (isQPlus) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val uri = resolver.insert(collection, values) ?: run {
            AppLogRepository.e(LogTags.MEDIASTORE, "MediaStore insert 返回 null displayName=$fileName")
            return null
        }
        AppLogRepository.i(LogTags.MEDIASTORE, "insert 成功 uri=$uri")

        return try {
            resolver.openOutputStream(uri)?.use { out ->
                sourceFile.inputStream().use { input -> input.copyTo(out) }
            } ?: throw IllegalStateException("openOutputStream null")

            if (isQPlus) {
                val update = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                resolver.update(uri, update, null, null)
            }
            AppLogRepository.i(LogTags.MEDIASTORE, "写入完成 uri=$uri")
            uri
        } catch (e: Exception) {
            AppLogRepository.e(LogTags.MEDIASTORE, "写入失败 uri=$uri err=${e.message}", e)
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    /**
     * 把图片写入 MediaStore Image 集合（Pictures/快夏），返回 [Uri]（失败 null）。
     * MIME 由真实响应/推断决定；fileSize 用于占位日志。
     */
    fun saveToPictures(context: Context, sourceFile: File, fileName: String, mimeType: String): Uri? {
        val resolver = context.contentResolver
        val relativePath = "Pictures/快夏"
        val isQPlus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val mime = mimeType.ifBlank { "image/jpeg" }
        AppLogRepository.i(
            LogTags.MEDIASTORE,
            "准备写入图片 displayName=$fileName mimeType=$mime relativePath=$relativePath " +
                "sourceSize=${sourceFile.length()} api=${Build.VERSION.SDK_INT}",
        )
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            if (isQPlus) {
                put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val collection = if (isQPlus) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val uri = resolver.insert(collection, values) ?: run {
            AppLogRepository.e(LogTags.MEDIASTORE, "图片 insert 返回 null displayName=$fileName")
            return null
        }
        AppLogRepository.i(LogTags.MEDIASTORE, "图片 insert 成功 uri=$uri")
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                sourceFile.inputStream().use { input -> input.copyTo(out) }
            } ?: throw IllegalStateException("openOutputStream null")
            if (isQPlus) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            }
            AppLogRepository.i(LogTags.MEDIASTORE, "图片写入完成 uri=$uri")
            uri
        } catch (e: Exception) {
            AppLogRepository.e(LogTags.MEDIASTORE, "图片写入失败 uri=$uri err=${e.message}", e)
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }
}
