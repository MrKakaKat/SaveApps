package com.savetube.plugin

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs yt-dlp for Savetube. No Google account is involved: everything is requested anonymously.
 * Savetube sends a link, gets the video's details, then asks for a download and receives the
 * finished file through a content uri it has granted this plugin write access to.
 */
class PluginService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { handle(it); true })

    override fun onBind(intent: Intent): IBinder = messenger.binder

    override fun onCreate() {
        super.onCreate()
        scope.launch { prepare(this@PluginService) }
    }

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    private fun handle(msg: Message) {
        val reply = msg.replyTo ?: return
        val data = msg.data
        when (msg.what) {
            Protocol.INFO -> launch(reply, null) { info(data.getString(Protocol.URL)!!) }
            Protocol.DOWNLOAD -> {
                val id = data.getString(Protocol.ID)!!
                launch(reply, id) { download(data, reply) }
            }
            Protocol.CANCEL -> {
                val id = data.getString(Protocol.ID) ?: return
                YoutubeDL.getInstance().destroyProcessById(id)
                jobs.remove(id)?.cancel()
            }
        }
    }

    private fun launch(reply: Messenger, id: String?, block: suspend () -> String) {
        val job = scope.launch {
            val out = runCatching {
                ready.await()
                block()
            }
            out.onSuccess { json -> send(reply, Protocol.OK, Protocol.JSON to json) }
                .onFailure { if (it !is CancellationException) send(reply, Protocol.ERROR, Protocol.MESSAGE to errorText(it)) }
            id?.let { jobs.remove(it) }
        }
        id?.let { jobs[it] = job }
    }

    private fun send(to: Messenger, what: Int, vararg pairs: Pair<String, String>) {
        val m = Message.obtain(null, what)
        m.data = Bundle().apply { pairs.forEach { (k, v) -> putString(k, v) } }
        runCatching { to.send(m) }
    }

    private fun progress(to: Messenger, fraction: Float) {
        val m = Message.obtain(null, Protocol.PROGRESS)
        m.data = Bundle().apply { putFloat(Protocol.FRACTION, fraction) }
        runCatching { to.send(m) }
    }

    /** yt-dlp prints "ERROR: ..." lines; show the last one, not the whole log. */
    private fun errorText(e: Throwable): String {
        val text = e.message.orEmpty()
        return text.lines().lastOrNull { it.contains("ERROR", true) }?.substringAfter("ERROR:")?.trim()
            ?: text.lines().lastOrNull { it.isNotBlank() }?.trim()
            ?: e.javaClass.simpleName
    }

    private fun info(url: String): String {
        val info = try {
            YoutubeDL.getInstance().getInfo(request(url, embedded = false))
        } catch (e: Exception) {
            // Age-gated videos sometimes play through the embedded-player clients without signing in.
            if (!e.message.orEmpty().contains("confirm your age", true)) throw e
            val retry = runCatching { YoutubeDL.getInstance().getInfo(request(url, embedded = true)) }
            if (retry.isFailure) throw e // report the original, clearer error
            embeddedUrls += url
            retry.getOrThrow()
        }
        val formats = info.formats.orEmpty()

        val video = JSONArray()
        formats.filter { it.vcodec != null && it.vcodec != "none" && it.height > 0 }
            .groupBy { it.height }
            .toSortedMap(compareByDescending { it })
            .forEach { (h, list) ->
                val best = list.maxByOrNull { it.tbr }
                video.put(JSONObject().put("height", h).put("size", size(best)))
            }

        val audio = JSONArray()
        formats.filter { it.vcodec == "none" && it.acodec != null && it.acodec != "none" && it.abr > 0 }
            .groupBy { it.abr }
            .toSortedMap(compareByDescending { it })
            .forEach { (abr, list) -> audio.put(JSONObject().put("abr", abr).put("size", size(list.firstOrNull()))) }

        return JSONObject()
            .put("version", Protocol.VERSION)
            .put("id", info.id)
            .put("title", info.title ?: "")
            .put("uploader", info.uploader ?: "")
            .put("thumbnail", info.thumbnail ?: "")
            .put("duration", info.duration)
            .put("video", video)
            .put("audio", audio)
            .toString()
    }

    /** Links that only opened through the embedded-player clients; downloads must use them too. */
    private val embeddedUrls = ConcurrentHashMap.newKeySet<String>()

    private fun request(url: String, embedded: Boolean): YoutubeDLRequest {
        val req = YoutubeDLRequest(url).addOption("--no-playlist")
        if (embedded) req.addOption("--extractor-args", "youtube:player_client=tv_embedded,web_embedded")
        return req
    }

    private fun size(f: VideoFormat?): Long = f?.let { if (it.fileSize > 0) it.fileSize else it.fileSizeApproximate } ?: 0L

    private fun download(data: Bundle, reply: Messenger): String {
        val id = data.getString(Protocol.ID)!!
        val kind = data.getString(Protocol.KIND)!!
        val start = data.getLong(Protocol.START, -1)
        val end = data.getLong(Protocol.END, -1)
        val target = Uri.parse(data.getString(Protocol.URI)!!)
        val dir = File(cacheDir, "dl/${System.nanoTime()}").apply { mkdirs() }
        try {
            val url = data.getString(Protocol.URL)!!
            val req = request(url, embedded = url in embeddedUrls)
                .addOption("--no-mtime")
                .addOption("-f", data.getString(Protocol.FORMAT)!!)
                .addOption("-o", "${dir.absolutePath}/out.%(ext)s")
            if (kind == "audio") req.addOption("-x").addOption("--audio-format", "m4a")
            else req.addOption("--merge-output-format", "mp4")
            if (start >= 0 && end > start) req.addOption("--download-sections", "*$start-$end")

            YoutubeDL.getInstance().execute(req, id) { percent, _, _ -> progress(reply, percent.coerceIn(0f, 100f) / 100f) }

            val file = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }?.maxByOrNull { it.length() }
                ?: throw IllegalStateException("yt-dlp produced no file")
            contentResolver.openOutputStream(target, "wt")!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            return JSONObject().put("ext", file.extension).toString()
        } finally {
            dir.deleteRecursively()
        }
    }

    companion object {
        /** Completes once yt-dlp and ffmpeg are unpacked and usable. */
        val ready = CompletableDeferred<Unit>()
        private var started = false

        @Synchronized
        fun prepare(context: Context) {
            if (started) return
            started = true
            runCatching {
                YoutubeDL.getInstance().init(context)
                FFmpeg.getInstance().init(context)
                ready.complete(Unit)
            }.onFailure { ready.completeExceptionally(it) }
            update(context, force = false)
        }

        /** YouTube changes often; a fresh yt-dlp keeps downloads working without reinstalling. */
        fun update(context: Context, force: Boolean): String? {
            val prefs = context.getSharedPreferences("ytdlp", Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            if (!force && now - prefs.getLong("updated", 0) < 24 * 3600_000L) return null
            return runCatching {
                val status = YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel._STABLE)
                prefs.edit().putLong("updated", now).apply()
                status?.toString()
            }.getOrNull()
        }
    }
}
