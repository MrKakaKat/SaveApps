package com.savetok.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** TikTok posts through the public tikwm.com API: watermark-free video, photos and the sound. */
object TikTokApi {
    private const val BASE = "https://www.tikwm.com"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val linkRegex = Regex("""https?://(?:www\.|m\.|vm\.|vt\.)?tiktok\.com/[^\s]+""")

    /** Finds the first TikTok link in arbitrary text (shared text often contains extra words). */
    fun extractUrl(text: String?): String? = text?.let { linkRegex.find(it)?.value }

    suspend fun fetchPost(url: String): Post = withContext(Dispatchers.IO) {
        val api = "$BASE/api/".toHttpUrl().newBuilder()
            .addQueryParameter("url", url)
            .addQueryParameter("hd", "1")
            .build()
        val req = Request.Builder().url(api).header("User-Agent", "Mozilla/5.0 (Linux; Android 14)").build()
        val json = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            JSONObject(resp.body!!.string())
        }
        if (json.optInt("code", -1) != 0) {
            throw IllegalStateException(tr("TikTok не отдал пост", "TikTok did not return the post") + ": " + json.optString("msg"))
        }
        val d = json.getJSONObject("data")
        val author = d.optJSONObject("author")
        val cover = abs(d.optString("cover").ifEmpty { d.optString("origin_cover") })
        val music = abs(d.optString("music").ifEmpty { d.optJSONObject("music_info")?.optString("play").orEmpty() })
        val soundOption = music.takeIf { it.isNotEmpty() }?.let {
            AudioVariant("MP3", "♫", it, extract = false, ext = "mp3")
        }

        val images = d.optJSONArray("images")
        val media = if (images != null && images.length() > 0) {
            (0 until images.length()).map { i ->
                val img = abs(images.getString(i))
                MediaItem(
                    type = MediaType.PHOTO,
                    thumbnail = img,
                    variants = listOf(MediaVariant(img, 0)),
                    // Photo posts have one soundtrack; it hangs on the first photo.
                    audio = if (i == 0) listOfNotNull(soundOption) else emptyList(),
                )
            }
        } else {
            val hd = abs(d.optString("hdplay"))
            val sd = abs(d.optString("play"))
            val variants = buildList {
                if (hd.isNotEmpty()) add(MediaVariant(hd, 1080, d.optLong("hd_size").takeIf { it > 0 }, tr("Лучшее", "Best")))
                if (sd.isNotEmpty() && sd != hd) add(MediaVariant(sd, 720, d.optLong("size").takeIf { it > 0 }, tr("Обычное", "Normal")))
            }
            if (variants.isEmpty()) throw IllegalStateException(tr("В этом посте нет видео", "This post has no video"))
            val audio = listOfNotNull(
                soundOption,
                AudioVariant("M4A", "AAC", variants.last().url, extract = true, ext = "m4a"),
            )
            listOf(MediaItem(MediaType.VIDEO, cover, variants, d.optLong("duration") * 1000, audio))
        }

        Post(
            id = d.optString("id").ifEmpty { url },
            url = url,
            authorName = author?.optString("nickname").orEmpty(),
            authorHandle = author?.optString("unique_id").orEmpty(),
            avatar = author?.optString("avatar")?.takeIf { it.isNotEmpty() }?.let(::abs),
            text = d.optString("title"),
            media = media,
        )
    }

    /** tikwm sometimes returns paths relative to its own host. */
    private fun abs(url: String): String = if (url.startsWith("/")) BASE + url else url
}
