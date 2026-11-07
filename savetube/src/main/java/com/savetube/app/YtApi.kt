package com.savetube.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream

object YtApi {
    private val linkRegex = Regex(
        """https?://(?:www\.|m\.|music\.)?(?:youtube\.com/(?:watch\?[^\s]*v=|shorts/|live/)|youtu\.be/)[\w-]{11}[^\s]*"""
    )

    /** Finds the first YouTube link in arbitrary text (shared text often contains extra words). */
    fun extractUrl(text: String?): String? = text?.let { linkRegex.find(it)?.value }

    /**
     * Only MP4/M4A (H.264 + AAC) streams are offered: Android's own MediaMuxer can join and
     * repackage them, so no ffmpeg and no re-encoding is needed.
     */
    suspend fun fetch(url: String): Track = withContext(Dispatchers.IO) {
        val info = StreamInfo.getInfo(ServiceList.YouTube, url)
        if (info.streamType == StreamType.LIVE_STREAM) return@withContext fetchLive(info, url)

        val m4a = info.audioStreams
            .filter { it.format == MediaFormat.M4A && it.isDirect() && it.isOriginalTrack() }
            .sortedByDescending { it.averageBitrate }
            .distinctBy { it.averageBitrate }
        val bestAudio = m4a.firstOrNull() ?: throw IllegalStateException(tr("Нет подходящей аудиодорожки", "No suitable audio track"))

        val audio = m4a.take(3).map {
            val kbps = if (it.averageBitrate > 0) it.averageBitrate else 128
            DownloadOption(Kind.AUDIO, "M4A", "${kbps}k", it.content, sizeBytes = it.size())
        }

        val video = info.videoOnlyStreams
            .filter { it.format == MediaFormat.MPEG_4 && it.codec.orEmpty().startsWith("avc1") && it.isDirect() }
            .groupBy { it.height }
            .filterKeys { it > 0 }
            .toSortedMap(compareByDescending { it })
            .map { (h, list) ->
                val v = list.maxBy { it.bitrate }
                DownloadOption(
                    kind = Kind.VIDEO,
                    label = "${h}P",
                    badge = if (h >= 720) "HD" else "SD",
                    audioUrl = bestAudio.content,
                    videoUrl = v.content,
                    sizeBytes = v.size()?.let { s -> s + (bestAudio.size() ?: 0) },
                    height = h,
                )
            }
            .take(3)

        Track(
            id = info.id,
            url = url,
            title = info.name,
            uploader = info.uploaderName.orEmpty().removeSuffix(" - Topic"),
            thumbnail = info.thumbnails.maxByOrNull { it.height }?.url,
            durationSec = info.duration,
            audioOptions = audio,
            videoOptions = video,
        )
    }

    /** Live streams only offer segmented DASH streams; their URLs take an `&sq=` segment number. */
    private fun fetchLive(info: StreamInfo, url: String): Track {
        val m4a = info.audioStreams
            .filter { it.format == MediaFormat.M4A && it.isUrl && it.isOriginalTrack() }
            .sortedByDescending { it.averageBitrate }
            .distinctBy { it.averageBitrate }
        val bestAudio = m4a.firstOrNull()
            ?: throw IllegalStateException(tr("У этого стрима нет подходящей аудиодорожки", "This stream has no suitable audio track"))
        val live = Live.probe(bestAudio.content, bestAudio.itagItem?.targetDurationSec ?: 5)

        val audio = m4a.take(3).map {
            val kbps = if (it.averageBitrate > 0) it.averageBitrate else 128
            DownloadOption(Kind.AUDIO, "M4A", "${kbps}k", it.content)
        }
        val video = info.videoOnlyStreams
            .filter { it.format == MediaFormat.MPEG_4 && it.codec.orEmpty().startsWith("avc1") && it.isUrl }
            .groupBy { it.height }
            .filterKeys { it > 0 }
            .toSortedMap(compareByDescending { it })
            .map { (h, list) ->
                DownloadOption(
                    kind = Kind.VIDEO,
                    label = "${h}P",
                    badge = if (h >= 720) "HD" else "SD",
                    audioUrl = bestAudio.content,
                    videoUrl = list.maxBy { it.bitrate }.content,
                    height = h,
                )
            }
            .take(3)

        return Track(
            id = info.id,
            url = url,
            title = info.name,
            uploader = info.uploaderName.orEmpty(),
            thumbnail = info.thumbnails.maxByOrNull { it.height }?.url,
            durationSec = live.durationSec,
            audioOptions = audio,
            videoOptions = video,
            live = live,
        )
    }

    private fun Stream.isDirect() = isUrl && deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP

    private fun AudioStream.isOriginalTrack() =
        audioTrackType == null || audioTrackType == AudioTrackType.ORIGINAL

    private fun AudioStream.size(): Long? = itagItem?.contentLength?.takeIf { it > 0 }

    private fun VideoStream.size(): Long? = itagItem?.contentLength?.takeIf { it > 0 }
}
