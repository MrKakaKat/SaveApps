package com.savetube.app

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.net.Uri
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext

/**
 * A running YouTube live stream is served as numbered segments (`&sq=N`), each a few seconds
 * long. While the stream allows rewinding, older segments stay available, so a time range maps
 * to a range of segment numbers.
 */
data class LiveInfo(val firstSeq: Long, val headSeq: Long, val segSec: Int) {
    val durationSec: Long get() = (headSeq - firstSeq + 1) * segSec
}

object Live {
    private const val PARALLEL = 4

    /** Finds the newest segment and the oldest one still available. */
    fun probe(url: String, segSec: Int): LiveInfo {
        val head = listOf<Long?>(0, null).firstNotNullOfOrNull { sq ->
            runCatching { segment(url, sq, probeOnly = true).use { it.header("X-Head-Seqnum")?.toLongOrNull() } }.getOrNull()
        } ?: throw IllegalStateException(tr("Стрим не отдаёт номер последнего фрагмента", "The stream did not report its latest segment"))
        var first = 0L
        if (!available(url, 0)) {
            // The rewind window has moved past the start: binary search for its edge.
            var lo = 0L
            var hi = head
            while (hi - lo > 1) {
                val mid = (lo + hi) / 2
                if (available(url, mid)) hi = mid else lo = mid
            }
            first = hi
        }
        return LiveInfo(first, head, segSec.coerceAtLeast(1))
    }

    private fun available(url: String, sq: Long): Boolean =
        runCatching { segment(url, sq, probeOnly = true).use { it.isSuccessful } }.getOrDefault(false)

    /** Stream URLs of app clients expect a POST, like NewPipe does; the web ones a GET. */
    private fun segment(url: String, sq: Long?, probeOnly: Boolean = false): Response {
        val full = if (sq == null) url else "$url&sq=$sq&rn=$sq"
        val client = Uri.parse(url).getQueryParameter("c").orEmpty().uppercase()
        val builder = Request.Builder().url(full).header("User-Agent", Media.userAgentFor(url))
        if (probeOnly) builder.header("Range", "bytes=0-0")
        if (client.startsWith("IOS") || client.startsWith("ANDROID") || client.startsWith("VISION")) {
            builder.post(ByteArray(0).toRequestBody())
        }
        return NpDownloader.client.newCall(builder.build()).execute()
    }

    /** Downloads segments [from]..[to] into [dir]; [onDone] gets the count finished so far. */
    suspend fun download(url: String, from: Long, to: Long, dir: File, prefix: String, onDone: () -> Unit): List<File> {
        val seqs = (from..to).toList()
        val files = seqs.map { File(dir, "$prefix$it.mp4") }
        for (chunk in seqs.indices.chunked(PARALLEL)) {
            coroutineContext.ensureActive()
            coroutineScope {
                chunk.map { i ->
                    async {
                        fetch(url, seqs[i], files[i])
                        onDone()
                    }
                }.awaitAll()
            }
        }
        return files
    }

    private fun fetch(url: String, sq: Long, dest: File) {
        var last: Exception? = null
        repeat(3) {
            try {
                segment(url, sq).use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code} (sq=$sq)")
                    dest.outputStream().use { out -> resp.body!!.byteStream().copyTo(out) }
                }
                return
            } catch (e: Exception) {
                last = e
            }
        }
        throw last!!
    }

    /**
     * Joins segments into one regular MP4. Each group is one track (video, audio). A segment
     * without its own header gets the header of the group's first segment put in front.
     */
    fun mux(groups: List<List<File>>, output: File) {
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val tmp = File(output.parentFile, "seg.tmp")
        try {
            val inits = groups.map { init(it.first()) }
            val tracks = groups.mapIndexed { g, segs ->
                withExtractor(segs.first(), inits[g], tmp) { muxer.addTrack(it.getTrackFormat(0)) }
            }
            val offset = groups.mapIndexed { g, segs ->
                withExtractor(segs.first(), inits[g], tmp) { it.sampleTime }
            }.filter { it >= 0 }.minOrNull() ?: 0
            muxer.start()
            val buffer = ByteBuffer.allocate(4 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            groups.forEachIndexed { g, segs ->
                var last = -1L
                var shift = 0L
                segs.forEach { seg ->
                    withExtractor(seg, inits[g], tmp) { ex ->
                        var firstInSegment = true
                        while (true) {
                            val size = ex.readSampleData(buffer, 0)
                            if (size < 0) break
                            // If a segment restarts its clock, continue the timeline after the previous one.
                            if (firstInSegment && ex.sampleTime + shift <= last) shift = last + 20_000 - ex.sampleTime
                            firstInSegment = false
                            val time = (ex.sampleTime + shift - offset).coerceAtLeast(0)
                            if (time > last || last < 0) {
                                info.set(0, size, time, ex.sampleFlags)
                                muxer.writeSampleData(tracks[g], buffer, info)
                                last = time
                            }
                            ex.advance()
                        }
                    }
                }
            }
            muxer.stop()
        } finally {
            muxer.release()
            tmp.delete()
        }
    }

    private fun <T> withExtractor(seg: File, init: ByteArray?, tmp: File, block: (MediaExtractor) -> T): T {
        val source = if (init != null && !hasBox(seg.readBytes(), "moov")) {
            tmp.outputStream().use { it.write(init); it.write(seg.readBytes()) }
            tmp
        } else seg
        val ex = MediaExtractor()
        try {
            ex.setDataSource(source.absolutePath)
            if (ex.trackCount == 0) throw IllegalStateException(tr("Не удалось прочитать фрагмент стрима", "Could not read a stream segment"))
            ex.selectTrack(0)
            return block(ex)
        } finally {
            ex.release()
        }
    }

    /** The ftyp+moov header boxes of a segment, if it has them. */
    private fun init(seg: File): ByteArray? {
        val bytes = seg.readBytes()
        val parts = boxes(bytes).filter { it.first == "ftyp" || it.first == "moov" }
        if (parts.none { it.first == "moov" }) return null
        return parts.fold(ByteArray(0)) { acc, (_, start, size) -> acc + bytes.copyOfRange(start, start + size) }
    }

    private fun hasBox(bytes: ByteArray, type: String) = boxes(bytes).any { it.first == type }

    /** Top-level MP4 boxes as (type, offset, size). */
    private fun boxes(b: ByteArray): List<Triple<String, Int, Int>> {
        val out = mutableListOf<Triple<String, Int, Int>>()
        var pos = 0
        while (pos + 8 <= b.size) {
            var size = ((b[pos].toLong() and 0xFF) shl 24 or ((b[pos + 1].toLong() and 0xFF) shl 16) or
                ((b[pos + 2].toLong() and 0xFF) shl 8) or (b[pos + 3].toLong() and 0xFF))
            val type = String(b, pos + 4, 4, Charsets.US_ASCII)
            if (size == 1L && pos + 16 <= b.size) {
                size = (0 until 8).fold(0L) { acc, i -> (acc shl 8) or (b[pos + 8 + i].toLong() and 0xFF) }
            } else if (size == 0L) {
                size = (b.size - pos).toLong()
            }
            if (size < 8 || pos + size > b.size) break
            out += Triple(type, pos, size.toInt())
            pos += size.toInt()
        }
        return out
    }
}
