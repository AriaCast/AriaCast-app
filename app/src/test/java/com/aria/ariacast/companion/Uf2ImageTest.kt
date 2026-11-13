package com.aria.ariacast.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

class Uf2ImageTest {
    private fun asset(name: String) = listOf("src/main/assets/companion/$name", "app/src/main/assets/companion/$name").map(::File).first { it.exists() }

    @Test fun bundledFirmwareParsesAndMatchesItsChecksum() {
        val bytes = asset("aria_companion.uf2").readBytes()
        val expected = asset("aria_companion.uf2.sha256").readText().trim().substringBefore(' ')
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(expected, actual)
        val image = Uf2Image.parse(bytes)
        assertEquals(bytes.size / 512, image.pageCount)
        val ranges = image.contiguousRanges()
        assertEquals(0x10000000, ranges.first().first)  // starts at XIP flash base
        assertEquals(bytes.size / 2, ranges.sumOf { it.second.size })
    }

    @Test fun mergesAdjacentPagesAndSplitsGaps() {
        fun block(addr: Int, fill: Byte) = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x0A324655); putInt(0x9E5D5157.toInt()); putInt(0x2000); putInt(addr); putInt(256)
            putInt(0); putInt(3); putInt(Uf2Image.RP2040_FAMILY)
            put(ByteArray(256) { fill }); position(508); putInt(0x0AB16F30)
        }.array()
        val img = Uf2Image.parse(block(0x10000000, 1) + block(0x10000100, 2) + block(0x10001000, 3))
        val r = img.contiguousRanges()
        assertEquals(2, r.size)
        assertEquals(512, r[0].second.size)
        assertEquals(0x10001000, r[1].first)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOtherChipFamilies() {
        val b = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x0A324655); putInt(0x9E5D5157.toInt()); putInt(0x2000); putInt(0x10000000); putInt(256)
            putInt(0); putInt(1); putInt(0x12345678)
        }.array()
        Uf2Image.parse(b)
    }

    @Test fun firmwareImageIsSmall() {
        assertTrue(asset("aria_companion.uf2").length() < 512 * 1024)
    }
}
