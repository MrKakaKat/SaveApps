package com.savex.app

enum class MediaType { VIDEO, GIF, PHOTO }

data class MediaVariant(
    val url: String,
    val height: Int,
    val sizeBytes: Long? = null,
) {
    val label: String get() = if (height > 0) "${height}P" else tr("Оригинал", "Original")
    val isHd: Boolean get() = height >= 720
}

/** An audio choice: a direct audio file, or a video whose sound is pulled out ([extract]). */
data class AudioVariant(
    val label: String,
    val badge: String,
    val url: String,
    val extract: Boolean,
    val ext: String,
    val sizeBytes: Long? = null,
)

data class MediaItem(
    val type: MediaType,
    val thumbnail: String,
    val variants: List<MediaVariant>,
    val durationMs: Long = 0,
    val audio: List<AudioVariant> = emptyList(),
)

data class Post(
    val id: String,
    val url: String,
    val authorName: String,
    val authorHandle: String,
    val avatar: String?,
    val text: String,
    val media: List<MediaItem>,
)

data class HistoryEntry(
    val id: String,
    val url: String,
    val thumbnail: String,
    val type: MediaType,
    val authorName: String,
)
