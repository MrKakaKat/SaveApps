package com.savex.app

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Saves videos, GIFs (X serves them as silent MP4) and photos into the gallery.
 *
 * The file is downloaded here instead of by the system download manager so that it can be
 * rewritten as a clean MP4 (a plain copy of the tracks, no re-encoding) and then registered in
 * MediaStore properly. Messengers and gallery pickers read MediaStore, and that is also what
 * gives the file its preview thumbnail.
 */
object MediaSaver {
    suspend fun save(
        context: Context, post: Post, index: Int, item: MediaItem, variant: MediaVariant,
        onProgress: (Float) -> Unit,
    ): Long = withContext(Dispatchers.IO) {
        val base = "${APP_FOLDER.lowercase()}_${post.authorHandle}_${post.id}_${index + 1}" +
            if (item.type == MediaType.GIF) "_gif" else ""
        val dir = File(context.cacheDir, "media/${System.nanoTime()}").apply { mkdirs() }
        try {
            val src = File(dir, if (item.type == MediaType.PHOTO) "src" else "src.mp4")
            val type = AudioSaver.download(variant.url, src, onProgress)
            // Refuse to save something that is not media (an error page, an empty reply) and say
            // exactly what came back, instead of leaving a broken file in the gallery.
            val details = "${src.length()} B, ${type.ifEmpty { "?" }}, ${variant.url.substringBefore('?').takeLast(60)}"
            val notMedia = type.startsWith("text/") || "json" in type || "html" in type
            if (src.length() < 1024 || notMedia) {
                throw IllegalStateException(tr("Сервер вернул не файл", "The server did not return a media file") + ": $details")
            }
            if (item.type == MediaType.PHOTO) {
                val ext = variant.url.substringBefore('?').substringAfterLast('.').lowercase()
                    .takeIf { it in listOf("jpg", "jpeg", "png", "webp") } ?: "jpg"
                publish(context, src, "$base.$ext", "image/${if (ext == "jpg") "jpeg" else ext}", photo = true)
            } else {
                // If the rewrite fails or gives an unreadable file, the original is still saved.
                val clean = File(dir, "clean.mp4")
                val file = when {
                    runCatching { remux(src, clean) }.isSuccess && readable(clean) -> clean
                    // Android's own MP4 parser rejects some files (X GIFs) that players handle
                    // fine; Media3 reads them with ExoPlayer's parser and writes a clean MP4.
                    runCatching { rewrite(context, src, clean.apply { delete() }) }.isSuccess && readable(clean) -> clean
                    readable(src) -> src
                    else -> {
                        // Keep the original so it can be examined; it is not put into the gallery.
                        val kept = runCatching { keepRaw(context, src, "${base}_raw.mp4") }.isSuccess
                        throw IllegalStateException(
                            tr("Файл не читается как видео", "The file is not a playable video") + ": $details" +
                                if (kept) tr(". Исходник сохранён в Download/$APP_FOLDER", ". The original was saved to Download/$APP_FOLDER") else ""
                        )
                    }
                }
                publish(context, file, "$base.mp4", "video/mp4", photo = false)
                return@withContext file.length()
            }
            src.length()
        } finally {
            dir.deleteRecursively()
        }
    }

    /** Copies every audio and video track into a fresh MP4 with timestamps starting at zero. */
    private fun remux(src: File, dest: File) {
        val extractor = MediaExtractor().apply { setDataSource(src.absolutePath) }
        val muxer = MediaMuxer(dest.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val tracks = HashMap<Int, Int>()
            var rotation = 0
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                if (mime.startsWith("video/") && format.containsKey(MediaFormat.KEY_ROTATION)) {
                    rotation = format.getInteger(MediaFormat.KEY_ROTATION)
                }
                tracks[i] = muxer.addTrack(format)
                extractor.selectTrack(i)
            }
            if (tracks.isEmpty()) throw IllegalStateException("No media tracks")
            muxer.setOrientationHint(rotation)
            muxer.start()

            val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            var offset = -1L
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val track = tracks[extractor.sampleTrackIndex]
                if (track != null) {
                    // Some files start their clock late; shift it to zero, keep small offsets as they are.
                    if (offset < 0) offset = if (extractor.sampleTime > 100_000) extractor.sampleTime else 0
                    info.set(0, size, (extractor.sampleTime - offset).coerceAtLeast(0), extractor.sampleFlags)
                    muxer.writeSampleData(track, buffer, info)
                }
                extractor.advance()
            }
            muxer.stop()
        } finally {
            runCatching { muxer.release() }
            extractor.release()
        }
    }

    private suspend fun rewrite(context: Context, src: File, dest: File) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val transformer = Transformer.Builder(context)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        if (cont.isActive) cont.resumeWithException(exportException)
                    }
                })
                .build()
            transformer.start(MediaItem.fromUri(Uri.fromFile(src)), dest.absolutePath)
            cont.invokeOnCancellation { android.os.Handler(android.os.Looper.getMainLooper()).post { transformer.cancel() } }
        }
    }

    private fun readable(file: File): Boolean = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0) > 0
        } finally {
            retriever.release()
        }
    }.getOrDefault(false)

    /** Saves a file Android cannot read into Download/<app> as a plain file, so the user can pass it on. */
    private fun keepRaw(context: Context, file: File, name: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$APP_FOLDER")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
            context.contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), APP_FOLDER).apply { mkdirs() }
            file.copyTo(File(dir, name), overwrite = true)
        }
    }

    /** Puts the file into Movies/<app> or Pictures/<app> and registers it so it shows up everywhere. */
    private fun publish(context: Context, file: File, name: String, mime: String, photo: Boolean) {
        val folder = if (photo) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_MOVIES
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$folder/$APP_FOLDER")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collection = if (photo) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val resolver = context.contentResolver
            val uri = resolver.insert(collection, values)
                ?: throw IllegalStateException(tr("Не удалось сохранить файл", "Could not save the file"))
            resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(folder), APP_FOLDER).apply { mkdirs() }
            val dest = File(dir, name)
            file.copyTo(dest, overwrite = true)
            MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf(mime), null)
        }
    }
}
