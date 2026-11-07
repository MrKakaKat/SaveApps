package com.savetok.app

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/** Downloads a file and, for videos, pulls the audio track out as .m4a without re-encoding. */
object AudioSaver {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun save(context: Context, variant: AudioVariant, name: String, onProgress: (Float) -> Unit) =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "audio/${System.nanoTime()}").apply { mkdirs() }
            try {
                val src = File(dir, "src")
                download(variant.url, src, onProgress)
                val out = if (variant.extract) File(dir, "out.m4a").also { extractAudio(src, it) } else src
                publish(context, out, "$name.${variant.ext}", variant.ext)
            } finally {
                dir.deleteRecursively()
            }
        }

    private suspend fun download(url: String, dest: File, onProgress: (Float) -> Unit) {
        val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Linux; Android 14)").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            val body = resp.body ?: throw IllegalStateException("HTTP ${resp.code}")
            val total = body.contentLength()
            var done = 0L
            val buf = ByteArray(64 * 1024)
            body.byteStream().use { input ->
                dest.outputStream().use { out ->
                    while (true) {
                        kotlin.coroutines.coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
        }
    }

    private fun extractAudio(video: File, output: File) {
        val extractor = MediaExtractor().apply { setDataSource(video.absolutePath) }
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val index = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IllegalStateException(tr("В видео нет звука", "The video has no sound"))
            extractor.selectTrack(index)
            val track = muxer.addTrack(extractor.getTrackFormat(index))
            muxer.start()
            val buffer = ByteBuffer.allocate(1024 * 1024)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.set(0, size, extractor.sampleTime, extractor.sampleFlags)
                muxer.writeSampleData(track, buffer, info)
                extractor.advance()
            }
            muxer.stop()
        } finally {
            muxer.release()
            extractor.release()
        }
    }

    /** Puts the file into Music/<app> so music players see it. */
    private fun publish(context: Context, file: File, name: String, ext: String) {
        val mime = if (ext == "mp3") "audio/mpeg" else "audio/mp4"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/$APP_FOLDER")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException(tr("Не удалось сохранить файл", "Could not save the file"))
            resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), APP_FOLDER).apply { mkdirs() }
            val dest = File(dir, name)
            file.copyTo(dest, overwrite = true)
            MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf(mime), null)
        }
    }
}
