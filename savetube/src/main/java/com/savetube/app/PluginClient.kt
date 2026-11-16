package com.savetube.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import androidx.core.content.FileProvider
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The yt-dlp plugin answered with an error (for example a video it cannot open either). */
class PluginException(message: String) : Exception(message)

/**
 * Talks to the optional yt-dlp plugin app. It is used when Savetube's own parser fails, and it
 * works anonymously: no Google account is ever involved.
 */
object PluginClient {
    const val PACKAGE = "com.savetube.plugin"

    fun isInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess

    suspend fun info(context: Context, url: String): Track {
        val reply = request(context, Protocol.INFO, null, Bundle().apply { putString(Protocol.URL, url) })
        val json = JSONObject(reply.getString(Protocol.JSON)!!)
        if (json.optInt("version") != Protocol.VERSION) {
            throw PluginException(tr("Плагин устарел, обновите его", "The plugin is out of date, please update it"))
        }
        val duration = json.optLong("duration")

        val audioArr = json.getJSONArray("audio")
        val audio = (0 until audioArr.length()).map { audioArr.getJSONObject(it) }.take(3).map {
            val abr = it.getInt("abr")
            DownloadOption(
                kind = Kind.AUDIO, label = "M4A", badge = "${abr}k", audioUrl = "",
                sizeBytes = it.optLong("size").takeIf { s -> s > 0 },
                format = "bestaudio[ext=m4a][abr<=${abr + 1}]/bestaudio[abr<=${abr + 1}]/bestaudio/best",
            )
        }.ifEmpty { listOf(DownloadOption(Kind.AUDIO, "M4A", "AAC", "", format = "bestaudio[ext=m4a]/bestaudio/best")) }
        val audioSize = audioArr.optJSONObject(0)?.optLong("size") ?: 0L

        val videoArr = json.getJSONArray("video")
        val video = (0 until videoArr.length()).map { videoArr.getJSONObject(it) }.take(3).map {
            val h = it.getInt("height")
            DownloadOption(
                kind = Kind.VIDEO, label = "${h}P", badge = if (h >= 720) "HD" else "SD", audioUrl = "",
                sizeBytes = it.optLong("size").takeIf { s -> s > 0 }?.plus(audioSize),
                height = h,
                format = "bv*[height<=$h][ext=mp4]+ba[ext=m4a]/b[height<=$h][ext=mp4]/bv*[height<=$h]+ba/b[height<=$h]",
            )
        }

        return Track(
            id = json.optString("id").ifEmpty { url },
            url = url,
            title = json.optString("title").ifEmpty { tr("Без названия", "Untitled") },
            uploader = json.optString("uploader").removeSuffix(" - Topic"),
            thumbnail = json.optString("thumbnail").ifEmpty { null },
            durationSec = duration,
            audioOptions = audio,
            videoOptions = video,
            viaPlugin = true,
        )
    }

    /**
     * Has the plugin download [track] in the given option into [target] (M4A for audio, MP4 for
     * video); [startSec]..[endSec] limits it to a clip. The plugin writes through a content uri
     * that is shared with it only for this call.
     */
    suspend fun download(
        context: Context, track: Track, option: DownloadOption, target: File,
        startSec: Long, endSec: Long, onProgress: (Float) -> Unit,
    ) {
        target.parentFile?.mkdirs()
        target.createNewFile()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", target)
        context.grantUriPermission(
            PACKAGE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        try {
            val data = Bundle().apply {
                putString(Protocol.URL, track.url)
                putString(Protocol.ID, track.id)
                putString(Protocol.FORMAT, option.format)
                putString(Protocol.KIND, if (option.kind == Kind.AUDIO) "audio" else "video")
                putString(Protocol.URI, uri.toString())
                putLong(Protocol.START, startSec)
                putLong(Protocol.END, endSec)
            }
            request(context, Protocol.DOWNLOAD, track.id, data, onProgress)
        } finally {
            context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }

    /** Binds to the plugin, sends one request and waits for its final reply; progress goes to [onProgress]. */
    private suspend fun request(
        context: Context, what: Int, id: String?, payload: Bundle, onProgress: ((Float) -> Unit)? = null,
    ): Bundle = suspendCancellableCoroutine { cont ->
        val app = context.applicationContext
        var service: Messenger? = null
        var bound = false
        lateinit var connection: ServiceConnection

        fun unbind() {
            if (bound) {
                bound = false
                runCatching { app.unbindService(connection) }
            }
        }

        val replies = Messenger(Handler(Looper.getMainLooper()) { msg ->
            when (msg.what) {
                Protocol.PROGRESS -> onProgress?.invoke(msg.data.getFloat(Protocol.FRACTION))
                Protocol.OK -> { unbind(); if (cont.isActive) cont.resume(msg.data) }
                Protocol.ERROR -> {
                    unbind()
                    if (cont.isActive) cont.resumeWithException(PluginException("yt-dlp: " + msg.data.getString(Protocol.MESSAGE)))
                }
            }
            true
        })

        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = Messenger(binder)
                val msg = Message.obtain(null, what).apply { data = payload; replyTo = replies }
                runCatching { service!!.send(msg) }.onFailure {
                    unbind()
                    if (cont.isActive) cont.resumeWithException(IOException("Could not reach the plugin"))
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                // The plugin process died mid-request (for example out of memory).
                unbind()
                if (cont.isActive) cont.resumeWithException(IOException("The plugin stopped unexpectedly"))
            }
        }

        val intent = Intent().setComponent(ComponentName(PACKAGE, "$PACKAGE.PluginService"))
        bound = runCatching { app.bindService(intent, connection, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
        if (!bound) {
            cont.resumeWithException(IllegalStateException(tr("Не удалось подключиться к плагину", "Could not connect to the plugin")))
            return@suspendCancellableCoroutine
        }

        cont.invokeOnCancellation {
            if (id != null) {
                runCatching {
                    service?.send(Message.obtain(null, Protocol.CANCEL).apply {
                        this.data = Bundle().apply { putString(Protocol.ID, id) }
                    })
                }
            }
            unbind()
        }
    }
}
