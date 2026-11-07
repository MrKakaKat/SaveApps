package com.savetube.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

enum class Lang { SYSTEM, RU, EN }

/** Current UI language; Compose re-reads it, so switching applies instantly. */
object L {
    var lang by mutableStateOf(Lang.EN)
    val en: Boolean
        get() = when (lang) {
            Lang.SYSTEM -> Locale.getDefault().language != "ru"
            Lang.RU -> false
            Lang.EN -> true
        }
}

fun tr(ru: String, en: String) = if (L.en) en else ru

/**
 * Quality preferences. For video, [videoQuality] is a height in pixels, [BEST] or [ASK];
 * for audio, [audioQuality] is [BEST], [SMALLEST] or [ASK].
 */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var lang by mutableStateOf(Lang.valueOf(prefs.getString("lang", Lang.EN.name)!!))
        private set
    var defaultKind by mutableStateOf(Kind.valueOf(prefs.getString("kind", Kind.VIDEO.name)!!))
        private set
    var videoQuality by mutableStateOf(prefs.getInt("video", ASK))
        private set
    var audioQuality by mutableStateOf(prefs.getInt("audio", ASK))
        private set

    init {
        L.lang = lang
    }

    fun updateLang(value: Lang) {
        lang = value
        L.lang = value
        prefs.edit().putString("lang", value.name).apply()
    }

    fun updateDefaultKind(value: Kind) {
        defaultKind = value
        prefs.edit().putString("kind", value.name).apply()
    }

    fun updateVideoQuality(value: Int) {
        videoQuality = value
        prefs.edit().putInt("video", value).apply()
    }

    fun updateAudioQuality(value: Int) {
        audioQuality = value
        prefs.edit().putInt("audio", value).apply()
    }

    /** True when the user chose a default quality for the default kind, so no sheet is needed. */
    val autoDownload: Boolean
        get() = (if (defaultKind == Kind.VIDEO) videoQuality else audioQuality) != ASK

    companion object {
        const val ASK = 0
        const val BEST = -1
        const val SMALLEST = -2
        val VIDEO_CHOICES = listOf(ASK, BEST, 1080, 720, 480, 360)
        val AUDIO_CHOICES = listOf(ASK, BEST, SMALLEST)

        /**
         * Index of the option to take from [heights] (sorted best first): the best one not above
         * [pref]; when everything is above it, the lowest one available.
         */
        fun pickVideo(heights: List<Int>, pref: Int): Int {
            if (heights.isEmpty()) return 0
            if (pref == BEST || pref == ASK) return 0
            return heights.indexOfFirst { it <= pref }.takeIf { it >= 0 } ?: heights.lastIndex
        }

        fun pickAudio(count: Int, pref: Int): Int = if (pref == SMALLEST) (count - 1).coerceAtLeast(0) else 0
    }
}
