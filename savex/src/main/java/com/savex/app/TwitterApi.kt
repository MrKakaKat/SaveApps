package com.savex.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object TwitterApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    private val statusRegex =
        Regex("""https?://(?:www\.|mobile\.)?(?:x|twitter|fxtwitter|vxtwitter|fixupx)\.com/[^/\s]+/status(?:es)?/(\d+)""")

    /** Finds the first post link in arbitrary text (shared text often contains extra words). */
    fun extractUrl(text: String?): String? = text?.let { statusRegex.find(it)?.value }

    fun extractId(text: String?): String? = text?.let { statusRegex.find(it)?.groupValues?.get(1) }

    suspend fun fetchPost(url: String): Post = withContext(Dispatchers.IO) {
        val id = extractId(url) ?: throw IllegalArgumentException(tr("Это не ссылка на пост X", "This is not an X post link"))
        val post = runCatching { fetchSyndication(id, url) }
            .recoverCatching { fetchVx(id, url) }
            .getOrThrow()
        if (post.media.isEmpty()) throw IllegalStateException(tr("В этом посте нет медиа", "This post has no media"))
        withSizes(post)
    }

    private fun get(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            return resp.body!!.string()
        }
    }

    private fun syndicationToken(id: String): String {
        val n = id.toDouble() / 1e15 * Math.PI
        return toBase36(n).replace(Regex("(0+|\\.)"), "")
    }

    private fun toBase36(value: Double): String {
        val intPart = value.toLong()
        val sb = StringBuilder(java.lang.Long.toString(intPart, 36)).append('.')
        var frac = value - intPart
        repeat(12) {
            frac *= 36
            val d = frac.toInt()
            sb.append(Character.forDigit(d, 36))
            frac -= d
        }
        return sb.toString()
    }

    private fun fetchSyndication(id: String, url: String): Post {
        val json = JSONObject(
            get("https://cdn.syndication.twimg.com/tweet-result?id=$id&lang=en&token=${syndicationToken(id)}")
        )
        val user = json.getJSONObject("user")
        val details = json.optJSONArray("mediaDetails")
        val media = buildList {
            for (i in 0 until (details?.length() ?: 0)) {
                val m = details!!.getJSONObject(i)
                val thumb = m.getString("media_url_https")
                when (m.getString("type")) {
                    "photo" -> add(
                        MediaItem(MediaType.PHOTO, thumb, listOf(MediaVariant("$thumb?name=orig", 0)))
                    )
                    "video", "animated_gif" -> {
                        val info = m.getJSONObject("video_info")
                        val vars = info.getJSONArray("variants")
                        val variants = (0 until vars.length())
                            .map { vars.getJSONObject(it) }
                            .filter { it.optString("content_type") == "video/mp4" }
                            .map { MediaVariant(it.getString("url"), heightFromUrl(it.getString("url"))) }
                            .distinctBy { it.height }
                            .sortedByDescending { it.height }
                        val type = if (m.getString("type") == "animated_gif") MediaType.GIF else MediaType.VIDEO
                        add(MediaItem(type, thumb, variants, info.optLong("duration_millis"), audioFor(type, variants)))
                    }
                }
            }
        }
        return Post(
            id = id,
            url = url,
            authorName = user.optString("name"),
            authorHandle = user.optString("screen_name"),
            avatar = user.optString("profile_image_url_https").ifEmpty { null },
            text = json.optString("text"),
            media = media,
        )
    }

    private fun fetchVx(id: String, url: String): Post {
        val json = JSONObject(get("https://api.vxtwitter.com/Twitter/status/$id"))
        val arr = json.optJSONArray("media_extended")
        val media = buildList {
            for (i in 0 until (arr?.length() ?: 0)) {
                val m = arr!!.getJSONObject(i)
                val type = when (m.optString("type")) {
                    "video" -> MediaType.VIDEO
                    "gif" -> MediaType.GIF
                    else -> MediaType.PHOTO
                }
                val h = m.optJSONObject("size")?.optInt("height") ?: 0
                val variants = listOf(MediaVariant(m.getString("url"), if (type == MediaType.PHOTO) 0 else h))
                add(
                    MediaItem(
                        type = type,
                        thumbnail = m.optString("thumbnail_url", m.getString("url")),
                        variants = variants,
                        durationMs = m.optLong("duration_millis"),
                        audio = audioFor(type, variants),
                    )
                )
            }
        }
        return Post(
            id = id,
            url = url,
            authorName = json.optString("user_name"),
            authorHandle = json.optString("user_screen_name"),
            avatar = json.optString("user_profile_image_url").ifEmpty { null },
            text = json.optString("text"),
            media = media,
        )
    }

    /** Only real videos have sound (X GIFs are silent). The smallest file carries the same audio. */
    private fun audioFor(type: MediaType, variants: List<MediaVariant>): List<AudioVariant> =
        if (type != MediaType.VIDEO || variants.isEmpty()) emptyList()
        else listOf(AudioVariant("M4A", "AAC", variants.last().url, extract = true, ext = "m4a"))

    /** Video URLs look like .../vid/avc1/720x1280/xxx.mp4 — the smaller side is the quality. */
    private fun heightFromUrl(url: String): Int {
        val m = Regex("""/(\d+)x(\d+)/""").find(url) ?: return 0
        return minOf(m.groupValues[1].toInt(), m.groupValues[2].toInt())
    }

    private suspend fun withSizes(post: Post): Post = coroutineScope {
        post.copy(media = post.media.map { item ->
            item.copy(variants = item.variants.map { v ->
                async { v.copy(sizeBytes = contentLength(v.url)) }
            }.awaitAll())
        })
    }

    private fun contentLength(url: String): Long? = runCatching {
        val req = Request.Builder().url(url).head().header("User-Agent", UA).build()
        client.newCall(req).execute().use { it.header("Content-Length")?.toLongOrNull() }
    }.getOrNull()
}
