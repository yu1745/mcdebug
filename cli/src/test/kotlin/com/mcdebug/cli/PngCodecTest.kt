package com.mcdebug.cli

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlin.math.abs

/** Independent filtered RGB/RGBA fixture construction, without image libraries or MC classes. */
internal object PngTestFixtures {
    val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

    fun header(width: Int = 1, height: Int = 1, color: Int = 6, depth: Int = 8,
               compression: Int = 0, filter: Int = 0, interlace: Int = 0): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use {
            it.writeInt(width); it.writeInt(height)
            it.write(byteArrayOf(depth.toByte(), color.toByte(), compression.toByte(), filter.toByte(), interlace.toByte()))
        }
        return out.toByteArray()
    }

    fun chunk(type: String, data: ByteArray = byteArrayOf()): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use {
            val name = type.toByteArray(Charsets.US_ASCII)
            it.writeInt(data.size); it.write(name); it.write(data)
            val crc = CRC32().apply { update(name); update(data) }
            it.writeInt(crc.value.toInt())
        }
        return out.toByteArray()
    }

    fun deflate(raw: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        DeflaterOutputStream(out).use { it.write(raw) }
        return out.toByteArray()
    }

    fun chunks(vararg chunks: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(signature)
        chunks.forEach { out.write(it) }
        return out.toByteArray()
    }

    fun png(header: ByteArray = header(), raw: ByteArray = byteArrayOf(0, 1, 2, 3, -1),
            compressed: ByteArray = deflate(raw)): ByteArray =
        chunks(chunk("IHDR", header), chunk("IDAT", compressed), chunk("IEND"))

    fun rgba(vararg channels: Int): ByteArray = channels.map { it.toByte() }.toByteArray()

    fun encoded(width: Int = 1, height: Int = 1, rgba: ByteArray = rgba(1, 2, 3, 255)): ByteArray =
        PngCodec.encode(PngCodec.Image(width, height, rgba))

    fun filtered(width: Int, height: Int, channels: Int, pixels: ByteArray, filters: List<Int>): ByteArray {
        val raw = ByteArrayOutputStream()
        val stride = width * channels
        repeat(height) { y ->
            val filter = filters[y % filters.size]
            raw.write(filter)
            repeat(stride) { x ->
                fun sample(row: Int, column: Int): Int =
                    if (row < 0 || column < 0) 0 else pixels[row * stride + column].toInt() and 255
                val a = sample(y, x - channels)
                val b = sample(y - 1, x)
                val c = sample(y - 1, x - channels)
                val predictor = when (filter) {
                    0 -> 0
                    1 -> a
                    2 -> b
                    3 -> (a + b) / 2
                    else -> {
                        val distances = listOf(abs(b - c), abs(a - c), abs(a + b - 2 * c))
                        listOf(a, b, c)[distances.indexOf(distances.min())]
                    }
                }
                raw.write((pixels[y * stride + x].toInt() and 255) - predictor)
            }
        }
        return png(header(width, height, if (channels == 4) 6 else 2), raw.toByteArray())
    }
}

class PngCodecTest {
    @TempDir lateinit var temp: Path

    @Test
    fun `RGBA writer preserves all channels including transparent hidden RGB`() {
        val pixels = PngTestFixtures.rgba(11, 222, 33, 0, 255, 0, 128, 255, 1, 2, 3, 17, 0, 0, 0, 0)
        val png = PngTestFixtures.encoded(2, 2, pixels)
        val image = PngCodec.decode(png)
        assertEquals(2, image.width)
        assertEquals(2, image.height)
        assertArrayEquals(pixels, image.rgba)
        assertEquals(6, png[25].toInt(), "writer is RGBA8")
    }

    @Test
    fun `RGB and RGBA decode every filter including Paeth wraparound and edge rows`() {
        for (channels in listOf(3, 4)) {
            val width = 7
            val height = 6
            val pixels = ByteArray(width * height * channels) { (it * 73 + it / 7 * 19).toByte() }
            val expected = if (channels == 4) pixels else ByteArray(width * height * 4).also { rgba ->
                repeat(width * height) { p ->
                    repeat(3) { c -> rgba[p * 4 + c] = pixels[p * 3 + c] }
                    rgba[p * 4 + 3] = 255.toByte()
                }
            }
            for (filters in (0..4).map { listOf(it) } + listOf(listOf(0, 1, 2, 3, 4))) {
                val image = PngCodec.decode(PngTestFixtures.filtered(width, height, channels, pixels, filters))
                assertArrayEquals(expected, image.rgba, "channels=$channels filters=$filters")
            }
        }
    }

    @Test
    fun `all truncated prefixes bad signatures and trailing PNG data are rejected`() {
        val png = PngTestFixtures.png()
        for (length in png.indices) rejects(png.copyOf(length))
        rejects(png.copyOf().apply { this[0] = 0 })
        rejects(png + byteArrayOf(0))
        rejects(png + PngTestFixtures.chunk("IEND"))
    }

    @Test
    fun `CRC is verified on IHDR IDAT IEND and ignored ancillary chunks`() {
        val header = PngTestFixtures.chunk("IHDR", PngTestFixtures.header())
        val data = PngTestFixtures.chunk("IDAT", PngTestFixtures.deflate(byteArrayOf(0, 1, 2, 3, -1)))
        val text = PngTestFixtures.chunk("tEXt", "note\u0000value".toByteArray())
        val end = PngTestFixtures.chunk("IEND")
        val chunks = listOf(header, text, data, end)
        for (index in chunks.indices) {
            val broken = chunks.mapIndexed { i, chunk ->
                if (i != index) chunk else chunk.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
            }
            rejects(PngTestFixtures.chunks(*broken.toTypedArray()))
        }
        assertEquals(1, PngCodec.decode(PngTestFixtures.chunks(*chunks.toTypedArray())).width)
    }

    @Test
    fun `dimensions and pixel products are bounded before decompression or allocation`() {
        for ((width, height) in listOf(0 to 1, 1 to 0, -1 to 1, 1 to -1, 16385 to 1, 1 to 16385,
            16384 to 1025, 4097 to 4096, Int.MAX_VALUE to Int.MAX_VALUE)) {
            rejects(PngTestFixtures.png(PngTestFixtures.header(width, height)))
        }
        assertThrows(IllegalArgumentException::class.java) { PngCodec.encode(PngCodec.Image(0, 1, byteArrayOf())) }
        assertThrows(IllegalArgumentException::class.java) { PngCodec.encode(PngCodec.Image(1, 1, byteArrayOf())) }
    }

    @Test
    fun `unsupported depth palette grayscale interlace and PNG methods fail explicitly`() {
        for (depth in listOf(1, 2, 4, 16)) rejects(PngTestFixtures.png(PngTestFixtures.header(depth = depth)))
        for (color in listOf(0, 3, 4, 5, 7)) rejects(PngTestFixtures.png(PngTestFixtures.header(color = color)))
        rejects(PngTestFixtures.png(PngTestFixtures.header(interlace = 1)))
        rejects(PngTestFixtures.png(PngTestFixtures.header(compression = 1)))
        rejects(PngTestFixtures.png(PngTestFixtures.header(filter = 1)))
        rejects(PngTestFixtures.chunks(
            PngTestFixtures.chunk("IHDR", PngTestFixtures.header(color = 2)),
            PngTestFixtures.chunk("tRNS", byteArrayOf(0, 1, 0, 2, 0, 3)),
            PngTestFixtures.chunk("IDAT", PngTestFixtures.deflate(byteArrayOf(0, 1, 2, 3))),
            PngTestFixtures.chunk("IEND"),
        ))
    }

    @Test
    fun `zlib must be complete checksummed exact length and have no trailing compressed bytes`() {
        val raw = byteArrayOf(0, 1, 2, 3, -1)
        val compressed = PngTestFixtures.deflate(raw)
        for (length in compressed.indices) rejects(PngTestFixtures.png(compressed = compressed.copyOf(length)))
        rejects(PngTestFixtures.png(compressed = compressed.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }))
        rejects(PngTestFixtures.png(compressed = compressed + byteArrayOf(0)))
        rejects(PngTestFixtures.png(compressed = compressed + compressed))
        rejects(PngTestFixtures.png(raw = raw.copyOf(4)))
        rejects(PngTestFixtures.png(raw = raw + byteArrayOf(0)))
        rejects(PngTestFixtures.png(raw = byteArrayOf(5, 1, 2, 3, -1)))
        // A highly compressible bomb is stopped after the declared 5-byte scanline plus one extra byte.
        rejects(PngTestFixtures.png(raw = ByteArray(1024 * 1024)))
    }

    @Test
    fun `chunk order types lengths and required chunks are enforced`() {
        val header = PngTestFixtures.chunk("IHDR", PngTestFixtures.header())
        val compressed = PngTestFixtures.deflate(byteArrayOf(0, 1, 2, 3, -1))
        val data = PngTestFixtures.chunk("IDAT", compressed)
        val end = PngTestFixtures.chunk("IEND")
        rejects(PngTestFixtures.chunks(data, header, end))
        rejects(PngTestFixtures.chunks(header, header, data, end))
        rejects(PngTestFixtures.chunks(header, end))
        rejects(PngTestFixtures.chunks(header, data))
        rejects(PngTestFixtures.chunks(header, data, PngTestFixtures.chunk("IEND", byteArrayOf(0))))
        rejects(PngTestFixtures.chunks(PngTestFixtures.chunk("IHDR", ByteArray(12)), data, end))
        rejects(PngTestFixtures.chunks(header, PngTestFixtures.chunk("ABCD"), data, end))
        rejects(PngTestFixtures.chunks(header, PngTestFixtures.chunk("abca"), data, end))
        rejects(PngTestFixtures.chunks(header, PngTestFixtures.chunk("t1Xt"), data, end))
        rejects(PngTestFixtures.chunks(header, data, PngTestFixtures.chunk("tEXt"), data, end))
        val oversizedLength = data.copyOf().apply { this[0] = 127 }
        rejects(PngTestFixtures.chunks(header, oversizedLength, end))
        val split = compressed.size / 2
        val image = PngCodec.decode(PngTestFixtures.chunks(header,
            PngTestFixtures.chunk("IDAT", compressed.copyOfRange(0, split)),
            PngTestFixtures.chunk("IDAT", compressed.copyOfRange(split, compressed.size)), end))
        assertArrayEquals(byteArrayOf(1, 2, 3, -1), image.rgba)
        val palette = PngTestFixtures.chunk("PLTE", byteArrayOf(0, 0, 0))
        assertEquals(1, PngCodec.decode(PngTestFixtures.chunks(header, palette, data, end)).width)
        rejects(PngTestFixtures.chunks(header, data, palette, end))
        rejects(PngTestFixtures.chunks(header, palette, palette, data, end))
        rejects(PngTestFixtures.chunks(header, PngTestFixtures.chunk("PLTE", byteArrayOf(0)), data, end))
    }

    @Test
    fun `file size cap rejects sparse oversized input without reading or allocating it`() {
        val path = temp.resolve("oversized.png")
        Files.newByteChannel(path, CREATE_NEW, WRITE).use {
            it.position(PngCodec.MAX_INPUT_BYTES.toLong())
            it.write(ByteBuffer.wrap(byteArrayOf(0)))
        }
        val error = assertThrows(IllegalArgumentException::class.java) { PngCodec.read(path) }
        assertTrue(error.message!!.contains("128 MiB"))
    }

    private fun rejects(png: ByteArray) {
        assertThrows(IllegalArgumentException::class.java) { PngCodec.decode(png) }
    }
}
