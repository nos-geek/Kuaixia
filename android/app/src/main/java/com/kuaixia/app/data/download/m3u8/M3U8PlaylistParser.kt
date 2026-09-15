package com.kuaixia.app.data.download.m3u8

import java.net.URI

/**
 * M3U8/HLS playlist 解析器（纯解析，无网络 IO）。
 *
 * - **Master Playlist**：识别 `#EXT-X-STREAM-INF` → [Variant]（BANDWIDTH/RESOLUTION/CODECS/FRAME-RATE/URI）
 * - **Media Playlist**：识别 `#EXTINF` → [Segment]（按原文顺序保序，序号即顺序）
 * - **相对 URI**：一律基于最终 playlist URL 用 [URI.resolve] 解析成绝对地址
 * - **EXT-X-KEY**：记录加密方式（NONE/无 = 允许；AES-128 / SAMPLE-AES / DRM keyformat = 明确不支持，抛 [M3U8UnsupportedException]）
 * - **EXT-X-MAP**：记录 init URI（fMP4），由下载器先写 init 再写分片
 *
 * 不下载任何内容；不猜测字节；解析失败抛 [M3U8ParseException]。
 */
object M3U8PlaylistParser {

    private const val TAG_M3U = "#EXTM3U"
    private const val TAG_VERSION = "#EXT-X-VERSION"
    private const val TAG_STREAM_INF = "#EXT-X-STREAM-INF"
    private const val TAG_INF = "#EXTINF"
    private const val TAG_KEY = "#EXT-X-KEY"
    private const val TAG_MAP = "#EXT-X-MAP"
    private const val TAG_ENDLIST = "#EXT-X-ENDLIST"

    // ---- 模型 ----

    /** Master 变体（一个清晰度）。 */
    data class Variant(
        val uri: String,
        val bandwidth: Long?,
        val resolution: String?,
        val codecs: String?,
        val frameRate: Double?,
    ) {
        /** 如 1920x1080 → 1080P。 */
        val height: Int?
            get() = resolution
                ?.substringAfter('x', "")
                ?.substringBefore('x', "")
                ?.trim()
                ?.toIntOrNull()
    }

    /** 单个媒体分片。 */
    data class Segment(
        val index: Int,
        val duration: Double?,
        val uri: String,
    )

    /** 加密方式。 */
    enum class Encryption { NONE, AES_128, SAMPLE_AES, DRM }

    /** 解析结果。 */
    sealed class Playlist {
        data class Master(val variants: List<Variant>) : Playlist()
        data class Media(
            val segments: List<Segment>,
            val encryption: Encryption,
            val encryptionMethodDetail: String?,
            /** fMP4 初始化段绝对 URI（EXT-X-MAP）；null = 普通 TS/无 init。 */
            val initUri: String?,
        ) : Playlist()
    }

    // ---- 异常 ----

    class M3U8ParseException(message: String) : Exception(message)

    /** 加密/DRM 等明确不支持的特性。UI 据 [label] 提示。 */
    class M3U8UnsupportedException(val label: String, message: String) : Exception(message)

    // ---- 解析入口 ----

    /**
     * @param raw      playlist 文本
     * @param baseUri  playlist 的最终绝对 URL（重定向后），用于 resolve 相对分片/变体
     */
    fun parse(raw: String, baseUri: String): Playlist {
        val lines = raw.replace("\r\n", "\n").split('\n')
        // 去除 UTF-8 BOM
        val head = lines.firstOrNull()?.removePrefix("\uFEFF")
        val normalized = if (lines.isNotEmpty()) listOf(head!!) + lines.drop(1) else lines

        if (!normalized.any { it.startsWith(TAG_M3U) }) {
            throw M3U8ParseException("不是有效的 M3U8 playlist（缺少 #EXTM3U）")
        }

        // 含 STREAM-INF → Master
        if (normalized.any { it.startsWith(TAG_STREAM_INF) }) {
            return parseMaster(normalized, baseUri)
        }
        return parseMedia(normalized, baseUri)
    }

    // ---- Master ----

    private fun parseMaster(lines: List<String>, baseUri: String): Playlist.Master {
        val variants = mutableListOf<Variant>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith(TAG_STREAM_INF)) {
                val attrs = parseAttributes(line.removePrefix(TAG_STREAM_INF))
                // URI 在下一行
                var uriLine: String? = null
                for (j in i + 1 until lines.size) {
                    val next = lines[j].trim()
                    if (next.isEmpty() || next.startsWith("#")) continue
                    uriLine = next
                    i = j
                    break
                }
                if (uriLine == null) {
                    throw M3U8ParseException("STREAM-INF 后缺少变体 URI")
                }
                variants += Variant(
                    uri = resolve(baseUri, uriLine),
                    bandwidth = attrs["BANDWIDTH"]?.toLongOrNull(),
                    resolution = attrs["RESOLUTION"],
                    codecs = attrs["CODECS"],
                    frameRate = attrs["FRAME-RATE"]?.toDoubleOrNull(),
                )
            }
            i++
        }
        if (variants.isEmpty()) throw M3U8ParseException("Master playlist 无可用变体")
        return Playlist.Master(variants)
    }

    // ---- Media ----

    private fun parseMedia(lines: List<String>, baseUri: String): Playlist.Media {
        val segments = mutableListOf<Segment>()
        var encryption = Encryption.NONE
        var encryptionDetail: String? = null
        var initUri: String? = null
        var pendingDuration: Double? = null
        var lastInfIndex = -1

        for (line0 in lines) {
            val line = line0.trim()
            when {
                line.isEmpty() || line.startsWith("#EXT-X-VERSION") || line == TAG_M3U ||
                    line.startsWith("#EXT-X-MEDIA-SEQUENCE") || line.startsWith("#EXT-X-TARGETDURATION") ||
                    line.startsWith("#EXT-X-DISCONTINUITY") || line == TAG_ENDLIST ||
                    line.startsWith("#EXT-X-PLAYLIST-TYPE") || line.startsWith("#EXT-X-ALLOW-CACHE") ||
                    line.startsWith("#EXT-X-INDEPENDENT-SEGMENTS") -> {
                    // 忽略（本阶段不处理 discontinuity 语义，顺序拼接不受影响）
                }

                line.startsWith("#EXT-X-BYTERANGE") ->
                    throw M3U8UnsupportedException(
                        "EXT-X-BYTERANGE",
                        "当前 HLS 使用字节范围分片（EXT-X-BYTERANGE），暂不支持",
                    )

                line.startsWith("#EXT-X-I-FRAMES-ONLY") ->
                    throw M3U8UnsupportedException(
                        "I-FRAMES-ONLY",
                        "当前 HLS 为 I 帧索引流（EXT-X-I-FRAMES-ONLY），不能作为正片下载",
                    )

                line.startsWith(TAG_INF) -> {
                    val rest = line.removePrefix(TAG_INF).trim().removePrefix(":")
                    pendingDuration = rest.substringBefore(',').toDoubleOrNull()
                }

                line.startsWith(TAG_KEY) -> {
                    val attrs = parseAttributes(line.removePrefix(TAG_KEY))
                    val method = attrs["METHOD"]?.uppercase() ?: "NONE"
                    val keyFormat = attrs["KEYFORMAT"] ?: ""
                    encryptionDetail = "method=$method" + if (keyFormat.isNotBlank()) " keyformat=$keyFormat" else ""
                    encryption = when {
                        method == "NONE" -> Encryption.NONE
                        method == "AES-128" || method == "AES128" -> Encryption.AES_128
                        method == "SAMPLE-AES" || method == "SAMPLE-AES-CTR" || method == "SAMPLE-AES-CENC" ->
                            Encryption.SAMPLE_AES
                        else -> Encryption.DRM
                    }
                }

                line.startsWith(TAG_MAP) -> {
                    // 仅取 URI（BYTERANGE init 本阶段不支持，出现即说明 fMP4 复杂流）
                    val attrs = parseAttributes(line.removePrefix(TAG_MAP))
                    initUri = attrs["URI"]?.let { resolve(baseUri, unquote(it)) }
                        ?: throw M3U8ParseException("EXT-X-MAP 无 URI")
                }

                line.startsWith("#") -> {
                    // 其它标签忽略（含注释与未知 tag）
                }

                else -> {
                    // 非注释行 = 分片 URI；若上一条是 #EXTINF 则配对，否则无时长（宽容处理仍保序收录）
                    val segUri = resolve(baseUri, line)
                    lastInfIndex++
                    segments += Segment(index = lastInfIndex, duration = pendingDuration, uri = segUri)
                    pendingDuration = null
                }
            }
        }

        if (segments.isEmpty()) {
            throw M3U8ParseException("Media playlist 无分片")
        }
        if (encryption != Encryption.NONE) {
            val label = when (encryption) {
                Encryption.AES_128 -> "AES-128"
                Encryption.SAMPLE_AES -> "SAMPLE-AES"
                Encryption.DRM -> "DRM"
                else -> "unknown"
            }
            throw M3U8UnsupportedException(
                label,
                "当前 HLS 流使用 $label 加密（$encryptionDetail），暂不支持",
            )
        }
        return Playlist.Media(
            segments = segments,
            encryption = encryption,
            encryptionMethodDetail = encryptionDetail,
            initUri = initUri,
        )
    }

    // ---- 工具 ----

    private fun resolve(base: String, uri: String): String =
        runCatching { URI(base).resolve(uri).toString() }
            .getOrElse { throw M3U8ParseException("URI 解析失败 base=$base uri=$uri") }

    /** 解析 `A="b",C=d` 形式的属性。 */
    private fun parseAttributes(rest: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val regex = Regex("([A-Za-z0-9-]+)=(\"[^\"]*\"|[^,]*)")
        regex.findAll(rest).forEach { m ->
            map[m.groupValues[1]] = m.groupValues[2]
        }
        return map
    }

    private fun unquote(s: String): String =
        if (s.startsWith("\"") && s.endsWith("\"") && s.length >= 2) s.substring(1, s.length - 1) else s
}
