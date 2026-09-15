package com.kuaixia.app.data.download

/**
 * Image magic-number sniffing (pure Kotlin, JVM-testable).
 *
 * Used only to confirm the real file format after download (fallback when the
 * HTTP Content-Type is missing or generic). Never used to guess business
 * types and never overrides an explicit image response header.
 *
 * Covers: JPEG / PNG / APNG / GIF / WebP / AVIF / HEIC(HEIF) / ICO / BMP.
 */
object ImageTypeSniffer {

    data class Type(val mime: String, val ext: String)

    /** Sniff the file header (first 32 bytes are enough). Null = unknown. */
    fun sniff(head: ByteArray): Type? {
        val len = head.size
        if (len < 12) return null

        // GIF87a / GIF89a
        if (head[0] == 'G'.code.toByte() && head[1] == 'I'.code.toByte() && head[2] == 'F'.code.toByte() &&
            head[3] == '8'.code.toByte() && (head[4] == '7'.code.toByte() || head[4] == '9'.code.toByte()) &&
            head[5] == 'a'.code.toByte()
        ) {
            return Type("image/gif", "gif")
        }
        // PNG: 89 50 4E 47 0D 0A 1A 0A ; APNG when first chunk type is acTL
        if (head[0] == 0x89.toByte() && head[1] == 0x50.toByte() && head[2] == 0x4E.toByte() &&
            head[3] == 0x47.toByte()
        ) {
            val isApng = len >= 16 &&
                head[8] == 'a'.code.toByte() && head[9] == 'c'.code.toByte() &&
                head[10] == 'T'.code.toByte() && head[11] == 'L'.code.toByte()
            return if (isApng) Type("image/apng", "apng") else Type("image/png", "png")
        }
        // JPEG: FF D8 FF
        if (head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte()) {
            return Type("image/jpeg", "jpg")
        }
        // RIFF....WEBP
        if (len >= 12 && head[0] == 'R'.code.toByte() && head[1] == 'I'.code.toByte() &&
            head[2] == 'F'.code.toByte() && head[3] == 'F'.code.toByte() &&
            head[8] == 'W'.code.toByte() && head[9] == 'E'.code.toByte() &&
            head[10] == 'B'.code.toByte() && head[11] == 'P'.code.toByte()
        ) {
            return Type("image/webp", "webp")
        }
        // ISO BMFF: ....ftyp(brand)
        if (len >= 12 && head[4] == 'f'.code.toByte() && head[5] == 't'.code.toByte() &&
            head[6] == 'y'.code.toByte() && head[7] == 'p'.code.toByte()
        ) {
            val brand = String(head, 8, 4, Charsets.US_ASCII)
            if (brand == "avif" || brand == "avis") return Type("image/avif", "avif")
            if (brand == "heic" || brand == "heix" || brand == "hevc" ||
                brand == "heim" || brand == "heis"
            ) {
                return Type("image/heic", "heic")
            }
            if (brand == "mif1" || brand == "msf1") return Type("image/avif", "avif")
            return null
        }
        // ICO: 00 00 01 00
        if (head[0] == 0x00.toByte() && head[1] == 0x00.toByte() &&
            head[2] == 0x01.toByte() && head[3] == 0x00.toByte()
        ) {
            return Type("image/vnd.microsoft.icon", "ico")
        }
        // BMP: 'BM'
        if (head[0] == 'B'.code.toByte() && head[1] == 'M'.code.toByte()) {
            return Type("image/bmp", "bmp")
        }
        return null
    }

    /** MIME to extension (no dot). Unknown returns null. */
    fun extOf(mime: String?): String? = when (mime?.lowercase()?.trim()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/avif" -> "avif"
        "image/apng" -> "apng"
        "image/heic" -> "heic"
        "image/heif" -> "heif"
        "image/vnd.microsoft.icon", "image/x-icon" -> "ico"
        "image/bmp" -> "bmp"
        "image/svg+xml" -> "svg"
        else -> null
    }

    /** True when the Content-Type is an image mime. */
    fun isImageMime(mime: String?): Boolean =
        !mime.isNullOrBlank() && mime.lowercase().trim().startsWith("image/")

    /** Generic/binary content types that carry no real type info. */
    fun isGenericMime(mime: String?): Boolean {
        if (mime.isNullOrBlank()) return true
        val m = mime.lowercase().trim()
        return m == "application/octet-stream" || m == "binary/octet-stream" ||
            m == "application/binary" || m == "application/x-binary"
    }
}
