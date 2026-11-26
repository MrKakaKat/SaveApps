package com.savex.app

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import kotlinx.coroutines.ensureActive
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/**
 * Turns a short silent video (how X stores GIFs) into a real animated GIF: one shared
 * 256-colour palette picked by median cut, LZW-compressed frames, endless loop.
 */
object GifEncoder {
    private const val MAX_SIDE = 480
    private const val MAX_FPS = 25f
    private const val MAX_FRAMES = 400

    suspend fun fromVideo(video: File, gif: File) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(video.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: throw IllegalStateException("No duration")
            val frameCount = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull()
            } else null
            val sourceFps = frameCount?.let { it * 1000f / durationMs.coerceAtLeast(1) } ?: 15f
            val fps = sourceFps.coerceIn(1f, MAX_FPS)
            val count = (durationMs * fps / 1000f).roundToInt().coerceIn(1, MAX_FRAMES)

            fun frame(i: Int): Bitmap? {
                val raw = if (frameCount != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    retriever.getFrameAtIndex((i * sourceFps / fps).roundToInt().coerceAtMost(frameCount - 1))
                } else {
                    retriever.getFrameAtTime((i * 1_000_000L / fps).toLong(), MediaMetadataRetriever.OPTION_CLOSEST)
                } ?: return null
                val scale = MAX_SIDE.toFloat() / maxOf(raw.width, raw.height)
                return if (scale >= 1f) raw
                else Bitmap.createScaledBitmap(raw, (raw.width * scale).roundToInt(), (raw.height * scale).roundToInt(), true)
            }

            // The palette comes from about ten frames spread over the clip.
            val samples = (0 until count step maxOf(1, count / 10)).mapNotNull { frame(it) }
            val first = samples.firstOrNull() ?: throw IllegalStateException("No frames")
            val palette = palette(samples)
            val lut = lookup(palette)
            val delay = (100f / fps).roundToInt().coerceAtLeast(2)

            BufferedOutputStream(gif.outputStream()).use { out ->
                writeHeader(out, first.width, first.height, palette)
                for (i in 0 until count) {
                    coroutineContext.ensureActive()
                    val bmp = frame(i) ?: continue
                    val scaled = if (bmp.width != first.width || bmp.height != first.height) {
                        Bitmap.createScaledBitmap(bmp, first.width, first.height, true)
                    } else bmp
                    writeFrame(out, scaled, lut, delay)
                }
                out.write(0x3B)
            }
        } finally {
            retriever.release()
        }
    }

    private fun key(argb: Int) =
        (((argb shr 19) and 31) shl 10) or (((argb shr 11) and 31) shl 5) or ((argb shr 3) and 31)

    private fun comp(c: Int, ch: Int) = (c shr (10 - 5 * ch)) and 31

    /** Median cut over a 15-bit colour histogram. */
    private fun palette(frames: List<Bitmap>): IntArray {
        val hist = IntArray(32768)
        for (bmp in frames) {
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            var i = 0
            while (i < px.size) {
                hist[key(px[i])]++
                i += 4
            }
        }
        val boxes = mutableListOf((0 until 32768).filter { hist[it] > 0 }.toMutableList())
        fun range(box: List<Int>, ch: Int) = box.maxOf { comp(it, ch) } - box.minOf { comp(it, ch) }
        while (boxes.size < 256) {
            var best = 0L
            var bi = -1
            boxes.forEachIndexed { i, box ->
                if (box.size < 2) return@forEachIndexed
                val score = (0..2).maxOf { range(box, it) }.toLong() * box.sumOf { hist[it].toLong() }
                if (score > best) { best = score; bi = i }
            }
            if (bi < 0) break
            val box = boxes.removeAt(bi)
            val ch = (0..2).maxBy { range(box, it) }
            box.sortBy { comp(it, ch) }
            val total = box.sumOf { hist[it].toLong() }
            var acc = 0L
            var cut = box.size / 2
            for (j in box.indices) {
                acc += hist[box[j]]
                if (acc * 2 >= total) { cut = (j + 1).coerceIn(1, box.size - 1); break }
            }
            boxes.add(box.subList(0, cut).toMutableList())
            boxes.add(box.subList(cut, box.size).toMutableList())
        }
        val palette = IntArray(256)
        boxes.forEachIndexed { i, box ->
            val total = box.sumOf { hist[it].toLong() }.coerceAtLeast(1)
            val rgb = (0..2).map { ch -> (box.sumOf { comp(it, ch).toLong() * hist[it] } * 255 / (31 * total)).toInt() }
            palette[i] = (rgb[0] shl 16) or (rgb[1] shl 8) or rgb[2]
        }
        return palette
    }

    /** Nearest palette entry for every 15-bit colour. */
    private fun lookup(palette: IntArray): ByteArray {
        val lut = ByteArray(32768)
        for (c in 0 until 32768) {
            val r = comp(c, 0) * 255 / 31
            val g = comp(c, 1) * 255 / 31
            val b = comp(c, 2) * 255 / 31
            var best = 0
            var bestDist = Int.MAX_VALUE
            for (i in palette.indices) {
                val p = palette[i]
                val dr = r - ((p shr 16) and 255)
                val dg = g - ((p shr 8) and 255)
                val db = b - (p and 255)
                val d = dr * dr + dg * dg + db * db
                if (d < bestDist) { bestDist = d; best = i }
            }
            lut[c] = best.toByte()
        }
        return lut
    }

    private fun OutputStream.short(v: Int) {
        write(v and 255)
        write((v shr 8) and 255)
    }

    private fun writeHeader(out: OutputStream, w: Int, h: Int, palette: IntArray) {
        out.write("GIF89a".toByteArray())
        out.short(w)
        out.short(h)
        out.write(0xF7) // global colour table, 256 entries
        out.write(0)
        out.write(0)
        for (p in palette) {
            out.write((p shr 16) and 255)
            out.write((p shr 8) and 255)
            out.write(p and 255)
        }
        // NETSCAPE2.0 extension: loop forever.
        out.write(byteArrayOf(0x21, 0xFF.toByte(), 0x0B))
        out.write("NETSCAPE2.0".toByteArray())
        out.write(byteArrayOf(0x03, 0x01, 0x00, 0x00, 0x00))
    }

    private fun writeFrame(out: OutputStream, bmp: Bitmap, lut: ByteArray, delay: Int) {
        val w = bmp.width
        val h = bmp.height
        val argb = IntArray(w * h)
        bmp.getPixels(argb, 0, w, 0, 0, w, h)
        val px = ByteArray(argb.size) { lut[key(argb[it])] }
        out.write(byteArrayOf(0x21, 0xF9.toByte(), 0x04, 0x04))
        out.short(delay)
        out.write(0)
        out.write(0)
        out.write(0x2C)
        out.short(0)
        out.short(0)
        out.short(w)
        out.short(h)
        out.write(0)
        lzw(out, px)
    }

    /** GIF LZW with 8-bit pixels; codes grow from 9 to 12 bits and the table resets when full. */
    private fun lzw(out: OutputStream, px: ByteArray) {
        val clear = 256
        val eoi = 257
        out.write(8)
        val block = ByteArray(255)
        var blockLen = 0
        var bits = 0L
        var nbits = 0
        fun emit(code: Int, size: Int) {
            bits = bits or (code.toLong() shl nbits)
            nbits += size
            while (nbits >= 8) {
                block[blockLen++] = (bits and 255).toByte()
                bits = bits shr 8
                nbits -= 8
                if (blockLen == 255) {
                    out.write(255)
                    out.write(block, 0, 255)
                    blockLen = 0
                }
            }
        }
        var size = 9
        var next = 258
        val table = HashMap<Int, Int>(8192)
        emit(clear, size)
        var prefix = px[0].toInt() and 255
        for (i in 1 until px.size) {
            val k = px[i].toInt() and 255
            val key = (prefix shl 8) or k
            val found = table[key]
            if (found != null) {
                prefix = found
                continue
            }
            emit(prefix, size)
            if (next == 4096) {
                emit(clear, size)
                table.clear()
                next = 258
                size = 9
            } else {
                if (next >= (1 shl size)) size++
                table[key] = next++
            }
            prefix = k
        }
        emit(prefix, size)
        emit(eoi, size)
        if (nbits > 0) block[blockLen++] = (bits and 255).toByte()
        if (blockLen > 0) {
            out.write(blockLen)
            out.write(block, 0, blockLen)
        }
        out.write(0)
    }
}
