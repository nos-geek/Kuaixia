package com.kuaixia.app.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageTypeSnifferTest {

    private fun bytes(vararg b: Int): ByteArray = ByteArray(b.size) { b[it].toByte() }

    @Test
    fun jpeg() {
        val t = ImageTypeSniffer.sniff(bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 0, 0, 0, 0, 0, 0, 0))
        assertEquals("image/jpeg", t?.mime)
        assertEquals("jpg", t?.ext)
    }

    @Test
    fun png() {
        val sig = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0)
        val t = ImageTypeSniffer.sniff(sig)
        assertEquals("image/png", t?.mime)
        assertEquals("png", t?.ext)
    }

    @Test
    fun apng() {
        // png signature + acTL chunk type (+ 长度/数据占位到 >=16B)
        val b = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
            "acTL".toByteArray() + ByteArray(4)
        val t = ImageTypeSniffer.sniff(b)
        assertEquals("image/apng", t?.mime)
        assertEquals("apng", t?.ext)
    }

    @Test
    fun gif() {
        val b = "GIF89a".toByteArray() + ByteArray(6)
        val t = ImageTypeSniffer.sniff(b)
        assertEquals("image/gif", t?.mime)
        assertEquals("gif", t?.ext)
    }

    @Test
    fun webp() {
        val b = "RIFF".toByteArray() + ByteArray(4) + "WEBP".toByteArray()
        val t = ImageTypeSniffer.sniff(b)
        assertEquals("image/webp", t?.mime)
        assertEquals("webp", t?.ext)
    }

    @Test
    fun avifFtyp() {
        val b = ByteArray(4) + "ftyp".toByteArray() + "avif".toByteArray() + ByteArray(4)
        val t = ImageTypeSniffer.sniff(b)
        assertEquals("image/avif", t?.mime)
        assertEquals("avif", t?.ext)
    }

    @Test
    fun heicFtyp() {
        val b = ByteArray(4) + "ftyp".toByteArray() + "heic".toByteArray() + ByteArray(4)
        val t = ImageTypeSniffer.sniff(b)
        assertEquals("image/heic", t?.mime)
        assertEquals("heic", t?.ext)
    }

    @Test
    fun icoAndBmp() {
        val ico = ImageTypeSniffer.sniff(bytes(0x00, 0x00, 0x01, 0x00, 0, 0, 0, 0, 0, 0, 0, 0))
        assertEquals("ico", ico?.ext)
        val bmp = ImageTypeSniffer.sniff("BM".toByteArray() + ByteArray(10))
        assertEquals("bmp", bmp?.ext)
    }

    @Test
    fun unknownReturnsNull() {
        assertNull(ImageTypeSniffer.sniff("hello world!!".toByteArray()))
        assertNull(ImageTypeSniffer.sniff(ByteArray(8)))
    }

    @Test
    fun extOfMapping() {
        assertEquals("jpg", ImageTypeSniffer.extOf("image/jpeg"))
        assertEquals("png", ImageTypeSniffer.extOf("image/png"))
        assertEquals("webp", ImageTypeSniffer.extOf("image/webp"))
        assertEquals("gif", ImageTypeSniffer.extOf("image/gif"))
        assertEquals("avif", ImageTypeSniffer.extOf("image/avif"))
        assertEquals("ico", ImageTypeSniffer.extOf("image/vnd.microsoft.icon"))
        assertNull(ImageTypeSniffer.extOf("text/html"))
    }

    @Test
    fun mimeClassifiers() {
        assertEquals(true, ImageTypeSniffer.isImageMime("image/webp"))
        assertEquals(false, ImageTypeSniffer.isImageMime("text/html"))
        assertEquals(false, ImageTypeSniffer.isImageMime(null))
        assertEquals(true, ImageTypeSniffer.isGenericMime("application/octet-stream"))
        assertEquals(true, ImageTypeSniffer.isGenericMime(""))
        assertEquals(false, ImageTypeSniffer.isGenericMime("image/jpeg"))
    }
}
