package com.mcdebug.cli

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater
import kotlin.math.abs

/** Deliberately small PNG subset: non-interlaced, 8-bit RGB/RGBA, without tRNS. No java.desktop. */
internal object PngCodec {
    const val MAX_INPUT_BYTES = 128 * 1024 * 1024
    const val MAX_DIMENSION = 16384
    const val MAX_PIXELS = 16L * 1024 * 1024
    private val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

    data class Image(val width: Int, val height: Int, val rgba: ByteArray)

    fun read(path: Path): Image {
        require(Files.isRegularFile(path)) { "PNG input must be a regular file: $path" }
        require(Files.size(path) <= MAX_INPUT_BYTES) { "PNG input exceeds 128 MiB: $path" }
        val bytes = Files.newInputStream(path).use { it.readNBytes(MAX_INPUT_BYTES + 1) }
        return decode(bytes)
    }

    private fun validateDimensions(width: Long, height: Long) {
        require(width in 1L..MAX_DIMENSION.toLong() && height in 1L..MAX_DIMENSION.toLong()) {
            "PNG width/height must be 1..$MAX_DIMENSION"
        }
        require(width * height <= MAX_PIXELS) { "PNG exceeds $MAX_PIXELS pixels" }
    }

    fun decode(png: ByteArray): Image {
        require(png.size <= MAX_INPUT_BYTES) { "PNG input exceeds 128 MiB" }
        require(png.size >= signature.size && signature.indices.all { png[it] == signature[it] }) {
            "Invalid PNG signature"
        }
        var offset = signature.size
        var width = 0
        var height = 0
        var channels = 0
        var headerSeen = false
        var paletteSeen = false
        var dataSeen = false
        var dataEnded = false
        var endSeen = false
        val compressed = ByteArrayOutputStream()
        while (offset < png.size) {
            require(png.size - offset >= 12) { "Truncated PNG chunk" }
            val lengthLong = uint(png, offset)
            require(lengthLong <= (png.size - offset - 12).toLong()) { "Truncated or oversized PNG chunk" }
            val length = lengthLong.toInt()
            val typeOffset = offset + 4
            val dataOffset = offset + 8
            for (i in 0..3) {
                val b = png[typeOffset + i].toInt() and 255
                require(b in 65..90 || b in 97..122) { "Invalid PNG chunk type" }
            }
            require((png[typeOffset + 2].toInt() and 32) == 0) { "Invalid PNG reserved chunk bit" }
            val type = String(png, typeOffset, 4, Charsets.US_ASCII)
            val crc = CRC32().apply { update(png, typeOffset, length + 4) }.value
            require(crc == uint(png, dataOffset + length)) { "PNG CRC mismatch in $type" }
            require(headerSeen || type == "IHDR") { "PNG IHDR must be first" }
            if (dataSeen && type != "IDAT") dataEnded = true
            when (type) {
                "IHDR" -> {
                    require(!headerSeen && offset == signature.size && length == 13) { "Invalid PNG IHDR" }
                    val w = uint(png, dataOffset)
                    val h = uint(png, dataOffset + 4)
                    validateDimensions(w, h)
                    width = w.toInt()
                    height = h.toInt()
                    val depth = png[dataOffset + 8].toInt() and 255
                    val color = png[dataOffset + 9].toInt() and 255
                    require(depth == 8 && (color == 2 || color == 6)) {
                        "Unsupported PNG format: only RGB8/RGBA8 are supported"
                    }
                    require(png[dataOffset + 10] == 0.toByte() && png[dataOffset + 11] == 0.toByte() &&
                        png[dataOffset + 12] == 0.toByte()) {
                        "Unsupported PNG compression, filter method or interlace (non-interlaced only)"
                    }
                    channels = if (color == 6) 4 else 3
                    headerSeen = true
                }
                "PLTE" -> {
                    require(!paletteSeen && !dataSeen && length in 3..768 && length % 3 == 0) {
                        "Invalid PNG PLTE"
                    }
                    paletteSeen = true // Optional suggested palette for truecolor; does not change the pixels.
                }
                "IDAT" -> {
                    require(!dataEnded) { "PNG IDAT chunks must be consecutive" }
                    require(compressed.size().toLong() + length <= MAX_INPUT_BYTES) { "PNG compressed data exceeds limit" }
                    compressed.write(png, dataOffset, length)
                    dataSeen = true
                }
                "IEND" -> {
                    require(dataSeen && length == 0) { "Invalid PNG IEND or missing IDAT" }
                    require(dataOffset + length + 4 == png.size) { "Extra data after PNG IEND" }
                    endSeen = true
                }
                "tRNS" -> throw IllegalArgumentException("Unsupported PNG tRNS transparency; use RGBA8")
                else -> require((png[typeOffset].toInt() and 32) != 0) { "Unsupported PNG critical chunk: $type" }
            }
            offset = dataOffset + length + 4
            if (endSeen) break
        }
        require(headerSeen && dataSeen && endSeen) { "PNG is missing IHDR, IDAT or IEND" }
        return inflate(width, height, channels, compressed.toByteArray())
    }

    private fun inflate(width: Int, height: Int, channels: Int, compressed: ByteArray): Image {
        // All dimensions/products were checked as Long before any pixel allocation.
        val stride = (width.toLong() * channels).toInt()
        val rgba = ByteArray((width.toLong() * height * 4).toInt())
        var previous = ByteArray(stride)
        var row = ByteArray(stride)
        val filterByte = ByteArray(1)
        val inflater = Inflater()
        try {
            inflater.setInput(compressed)
            fun readExact(target: ByteArray) {
                var offset = 0
                while (offset < target.size) {
                    val count = inflater.inflate(target, offset, target.size - offset)
                    require(count > 0) {
                        when {
                            inflater.needsDictionary() -> "PNG zlib dictionaries are unsupported"
                            inflater.finished() -> "PNG decompressed data is shorter than dimensions"
                            inflater.needsInput() -> "Truncated PNG zlib stream"
                            else -> "Malformed PNG zlib stream"
                        }
                    }
                    offset += count
                }
            }
            for (y in 0 until height) {
                readExact(filterByte)
                val filter = filterByte[0].toInt() and 255
                require(filter in 0..4) { "Invalid PNG row filter: $filter" }
                readExact(row)
                for (x in row.indices) {
                    val a = if (x >= channels) row[x - channels].toInt() and 255 else 0
                    val b = previous[x].toInt() and 255
                    val c = if (x >= channels) previous[x - channels].toInt() and 255 else 0
                    val predictor = when (filter) {
                        0 -> 0
                        1 -> a
                        2 -> b
                        3 -> (a + b) / 2
                        else -> paeth(a, b, c)
                    }
                    row[x] = ((row[x].toInt() and 255) + predictor).toByte()
                }
                var src = 0
                var dst = y * width * 4
                repeat(width) {
                    rgba[dst++] = row[src++]
                    rgba[dst++] = row[src++]
                    rgba[dst++] = row[src++]
                    rgba[dst++] = if (channels == 4) row[src++] else 255.toByte()
                }
                val swap = previous
                previous = row
                row = swap
            }
            // Inflate at most one extra byte: reject bombs, extra scanlines and missing/corrupt zlib trailers.
            val extra = inflater.inflate(filterByte)
            require(extra == 0) { "PNG decompressed data exceeds dimensions" }
            require(inflater.finished()) { "Truncated or malformed PNG zlib trailer" }
            require(inflater.remaining == 0) { "Extra data after PNG zlib stream" }
        } catch (e: DataFormatException) {
            throw IllegalArgumentException("Malformed PNG zlib stream: ${e.message}", e)
        } finally {
            inflater.end()
        }
        return Image(width, height, rgba)
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = abs(p - a)
        val pb = abs(p - b)
        val pc = abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }

    /** Filter-0 RGBA8 writer, also useful for dependency-free test fixtures. */
    fun encode(image: Image): ByteArray {
        validateDimensions(image.width.toLong(), image.height.toLong())
        require(image.rgba.size.toLong() == image.width.toLong() * image.height * 4) { "Invalid RGBA buffer size" }
        val compressed = ByteArrayOutputStream()
        val deflater = Deflater()
        try {
            DeflaterOutputStream(compressed, deflater).use { out ->
                val stride = image.width * 4
                repeat(image.height) { y ->
                    out.write(0)
                    out.write(image.rgba, y * stride, stride)
                }
            }
        } finally {
            deflater.end()
        }
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.write(signature)
            val header = ByteArrayOutputStream()
            DataOutputStream(header).use { h ->
                h.writeInt(image.width)
                h.writeInt(image.height)
                h.write(byteArrayOf(8, 6, 0, 0, 0))
            }
            writeChunk(out, "IHDR", header.toByteArray())
            writeChunk(out, "IDAT", compressed.toByteArray())
            writeChunk(out, "IEND", byteArrayOf())
        }
        return bytes.toByteArray()
    }

    private fun writeChunk(out: DataOutputStream, type: String, data: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.writeInt(data.size)
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32().apply { update(typeBytes); update(data) }
        out.writeInt(crc.value.toInt())
    }

    private fun uint(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 255) shl 24) or ((bytes[offset + 1].toLong() and 255) shl 16) or
            ((bytes[offset + 2].toLong() and 255) shl 8) or (bytes[offset + 3].toLong() and 255)
}
