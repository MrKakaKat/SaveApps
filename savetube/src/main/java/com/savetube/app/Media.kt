package com.savetube.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.net.Uri
import kotlinx.coroutines.ensureActive
import okhttp3.Request
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.AndroidArtwork
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext

object Media {
    private const val CHUNK = 8L * 1024 * 1024

    /**
     * Downloads [url] into [dest] in ranged chunks (YouTube throttles single huge requests).
     * [onBytes] receives the number of bytes written so far.
     */
    suspend fun download(url: String, dest: File, onBytes: (Long) -> Unit) {
        val ua = userAgentFor(url)
        var pos = 0L
        var total = Long.MAX_VALUE
        dest.outputStream().use { out ->
            while (pos < total) {
                coroutineContext.ensureActive()
                val req = Request.Builder().url(url)
                    .header("User-Agent", ua)
                    .header("Range", "bytes=$pos-${pos + CHUNK - 1}")
                    .build()
                NpDownloader.client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                    total = if (resp.code == 206) {
                        resp.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: Long.MAX_VALUE
                    } else {
                        pos = 0 // server ignored Range and sends everything
                        Long.MAX_VALUE
                    }
                    val body = resp.body ?: throw IllegalStateException(tr("Пустой ответ", "Empty response"))
                    val buf = ByteArray(64 * 1024)
                    val input = body.byteStream()
                    var read = 0
                    while (input.read(buf).also { n -> read = n } != -1) {
                        out.write(buf, 0, read)
                        pos += read
                        onBytes(pos)
                    }
                    if (resp.code != 206) total = pos
                    if (body.contentLength() == 0L) total = pos
                }
            }
        }
    }

    /** Stream URLs are bound to the YouTube client that produced them (the `c=` parameter). */
    private fun userAgentFor(url: String): String {
        val client = Uri.parse(url).getQueryParameter("c").orEmpty().uppercase()
        return when {
            client.startsWith("IOS") -> YoutubeParsingHelper.getIosUserAgent(null)
            client.startsWith("VISION") -> YoutubeParsingHelper.getVisionOsUserAgent(null)
            client.startsWith("ANDROID") -> YoutubeParsingHelper.getAndroidUserAgent(null)
            else -> NpDownloader.UA
        }
    }

    /**
     * Copies the tracks of [inputs] into one regular MP4 with Android's MediaMuxer. Used both to
     * merge DASH video+audio and to turn fragmented DASH audio into a normal .m4a.
     */
    fun mux(inputs: List<File>, output: File, startUs: Long = 0, endUs: Long = Long.MAX_VALUE) {
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val extractors = inputs.map { f -> MediaExtractor().apply { setDataSource(f.absolutePath) } }
        try {
            val tracks = extractors.map { ex ->
                ex.selectTrack(0)
                muxer.addTrack(ex.getTrackFormat(0))
            }
            // A cut can only begin on a keyframe, so the clip starts at the one just before [startUs]
            // and every track is shifted so the clip begins at zero.
            if (startUs > 0) extractors.forEach { it.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC) }
            val offset = extractors.map { it.sampleTime }.filter { it >= 0 }.minOrNull() ?: 0
            muxer.start()
            val buffer = ByteBuffer.allocate(4 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            extractors.forEachIndexed { i, ex ->
                while (true) {
                    val size = ex.readSampleData(buffer, 0)
                    if (size < 0 || ex.sampleTime > endUs) break
                    info.set(0, size, (ex.sampleTime - offset).coerceAtLeast(0), ex.sampleFlags)
                    muxer.writeSampleData(tracks[i], buffer, info)
                    ex.advance()
                }
            }
            muxer.stop()
        } finally {
            muxer.release()
            extractors.forEach { it.release() }
        }
    }

    /** Fetches a thumbnail and crops it to a square JPEG for use as album art. */
    fun squareCover(url: String): ByteArray? = runCatching {
        val req = Request.Builder().url(url).header("User-Agent", NpDownloader.UA).build()
        val bytes = NpDownloader.client.newCall(req).execute().use { it.body!!.bytes() }
        squareJpeg(bytes)
    }.getOrNull()

    fun squareJpeg(bytes: ByteArray): ByteArray? {
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val side = minOf(src.width, src.height)
        val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val scaled = if (side > 1000) Bitmap.createScaledBitmap(square, 1000, 1000, true) else square
        return ByteArrayOutputStream().use {
            scaled.compress(Bitmap.CompressFormat.JPEG, 92, it)
            it.toByteArray()
        }
    }

    /** Writes title/artist and cover into the file's own tags, so they travel with the file. */
    fun writeTags(file: File, title: String, artist: String, cover: ByteArray?) {
        val audio = AudioFileIO.read(file)
        val tag = audio.tagOrCreateAndSetDefault
        tag.setField(FieldKey.TITLE, title)
        tag.setField(FieldKey.ARTIST, artist)
        tag.setField(FieldKey.ALBUM_ARTIST, artist)
        tag.setField(FieldKey.COMPOSER, artist)
        if (cover != null) {
            tag.deleteArtworkField()
            tag.setField(AndroidArtwork().apply {
                binaryData = cover
                mimeType = "image/jpeg"
                pictureType = 3 // front cover
            })
        }
        AudioFileIO.write(audio)
    }
}
