package com.savetube.app

enum class Kind { AUDIO, VIDEO }

/** One choice in the download sheet. Video options carry a separate audio stream to merge in. */
data class DownloadOption(
    val kind: Kind,
    val label: String,
    val badge: String,
    val audioUrl: String,
    val videoUrl: String? = null,
    val sizeBytes: Long? = null,
    val height: Int = 0,
)

data class Track(
    val id: String,
    val url: String,
    val title: String,
    val uploader: String,
    val thumbnail: String?,
    val durationSec: Long,
    val audioOptions: List<DownloadOption>,
    val videoOptions: List<DownloadOption>,
    /** Set for a stream that is still live; [durationSec] is then its rewind window. */
    val live: LiveInfo? = null,
)

data class HistoryEntry(
    val id: String,
    val url: String,
    val thumbnail: String,
    val title: String,
    val uploader: String,
)

/** A downloaded audio file. [uri] is a MediaStore or SAF document uri; [cover] a local jpg. */
data class LibraryItem(
    val id: String,
    val uri: String,
    val title: String,
    val artist: String,
    val cover: String?,
    val durationSec: Long,
)
