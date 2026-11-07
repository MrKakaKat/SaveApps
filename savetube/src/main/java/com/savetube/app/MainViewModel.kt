package com.savetube.app

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val history = History(app)
    private val library = Library(app)
    val storage = Storage(app)
    val settings = Settings(app)

    var input by mutableStateOf("")
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)
    var track by mutableStateOf<Track?>(null)
        private set
    var showSheet by mutableStateOf(false)
    var historyItems by mutableStateOf(history.load())
        private set
    var libraryItems by mutableStateOf(library.load())
        private set
    var folderLabel by mutableStateOf(storage.folderLabel())
        private set

    var kind by mutableStateOf(Kind.AUDIO)
    var optionIndex by mutableStateOf(0)

    /** Part of the video to keep, in seconds; offered for videos longer than [RANGE_FROM_SEC]. */
    var rangeStart by mutableStateOf(0L)
    var rangeEnd by mutableStateOf(0L)

    val isPartial: Boolean
        get() = track?.let { rangeStart > 0 || rangeEnd < it.durationSec } == true

    /** Progress of the running download, 0..1, or null when idle. */
    var progress by mutableStateOf<Float?>(null)
        private set

    private var lastClipboard: String? = null
    private var fetchJob: Job? = null
    private var downloadJob: Job? = null

    /** Called when the app gains focus; pastes a fresh YouTube link from the clipboard once. */
    fun onClipboard(text: String?) {
        val url = YtApi.extractUrl(text) ?: return
        if (url == lastClipboard) return
        lastClipboard = url
        if (url == input && track != null) return
        input = url
        fetch()
    }

    fun onShared(text: String?) {
        val url = YtApi.extractUrl(text)
        if (url == null) {
            error = tr("В поделённом тексте нет ссылки на YouTube", "The shared text has no YouTube link")
            return
        }
        lastClipboard = url
        input = url
        fetch()
    }

    fun fetch() {
        val url = YtApi.extractUrl(input)
        if (url == null) {
            error = tr("Вставьте ссылку на YouTube", "Paste a YouTube link")
            return
        }
        fetchJob?.cancel()
        fetchJob = viewModelScope.launch {
            loading = true
            error = null
            message = null
            runCatching { YtApi.fetch(url) }
                .onSuccess { t ->
                    track = t
                    historyItems = history.add(HistoryEntry(t.id, t.url, t.thumbnail.orEmpty(), t.title, t.uploader))
                    prepare(t)
                }
                .onFailure { if (it !is CancellationException) error = it.message ?: tr("Не удалось открыть видео", "Could not open the video") }
            loading = false
        }
    }

    /**
     * Pre-selects the quality from the settings. Downloads right away when there is only one
     * option or the settings already name a default quality.
     */
    private fun prepare(t: Track) {
        rangeStart = 0
        rangeEnd = t.durationSec
        kind = if (settings.defaultKind == Kind.VIDEO && t.videoOptions.isNotEmpty()) Kind.VIDEO else Kind.AUDIO
        optionIndex = defaultIndex(kind)
        val single = t.audioOptions.size + t.videoOptions.size <= 1
        if (single || (settings.autoDownload && kind == settings.defaultKind)) download() else showSheet = true
    }

    private fun defaultIndex(k: Kind): Int {
        val t = track ?: return 0
        return if (k == Kind.VIDEO) Settings.pickVideo(t.videoOptions.map { it.height }, settings.videoQuality)
        else Settings.pickAudio(t.audioOptions.size, settings.audioQuality)
    }

    fun switchKind(k: Kind) {
        kind = k
        optionIndex = defaultIndex(k)
    }

    fun options(): List<DownloadOption> =
        track?.let { if (kind == Kind.AUDIO) it.audioOptions else it.videoOptions }.orEmpty()

    fun download() {
        val t = track ?: return
        val option = options().getOrNull(optionIndex) ?: return
        showSheet = false
        error = null
        message = null
        progress = 0f
        downloadJob = viewModelScope.launch {
            val dir = File(getApplication<Application>().cacheDir, "dl/${System.nanoTime()}").apply { mkdirs() }
            runCatching {
                withContext(Dispatchers.IO) { if (option.kind == Kind.AUDIO) saveAudio(t, option, dir) else saveVideo(t, option, dir) }
            }
                .onSuccess {
                    message = if (option.kind == Kind.AUDIO) tr("Добавлено в библиотеку", "Added to the library") else tr("Видео сохранено", "Video saved")
                }
                .onFailure { if (it !is CancellationException) error = it.message ?: tr("Ошибка загрузки", "Download failed") }
            withContext(Dispatchers.IO) { dir.deleteRecursively() }
            progress = null
        }
    }

    private suspend fun saveAudio(t: Track, option: DownloadOption, dir: File) {
        val raw = File(dir, "a.mp4")
        val total = option.sizeBytes ?: 0
        Media.download(option.audioUrl, raw) { if (total > 0) progress = (it.toFloat() / total).coerceAtMost(1f) * 0.95f }
        val out = File(dir, "track.m4a")
        Media.mux(listOf(raw), out, startUs(), endUs())
        val cover = t.thumbnail?.let { Media.squareCover(it) }
        Media.writeTags(out, t.title, t.uploader, cover)
        val uri = storage.save(out, Storage.fileName(name(t.uploader, t.title) + rangeSuffix(), "m4a"), Kind.AUDIO)
        libraryItems.find { it.id == t.id }?.cover?.let { File(it).delete() }
        val coverPath = cover?.let { bytes -> library.newCoverFile(t.id).apply { writeBytes(bytes) }.absolutePath }
        val id = if (isPartial) "${t.id}_${rangeStart}_$rangeEnd" else t.id
        val item = LibraryItem(id, uri.toString(), t.title, t.uploader, coverPath, rangeEnd - rangeStart)
        withContext(Dispatchers.Main) { libraryItems = library.put(item) }
        progress = 1f
    }

    private suspend fun saveVideo(t: Track, option: DownloadOption, dir: File) {
        val video = File(dir, "v.mp4")
        val audio = File(dir, "a.mp4")
        val total = option.sizeBytes ?: 0
        var videoBytes = 0L
        Media.download(option.videoUrl!!, video) {
            videoBytes = it
            if (total > 0) progress = (it.toFloat() / total).coerceAtMost(1f) * 0.95f
        }
        Media.download(option.audioUrl, audio) {
            if (total > 0) progress = ((videoBytes + it).toFloat() / total).coerceAtMost(1f) * 0.95f
        }
        val out = File(dir, "video.mp4")
        Media.mux(listOf(video, audio), out, startUs(), endUs())
        storage.save(out, Storage.fileName(t.title + rangeSuffix(), "mp4"), Kind.VIDEO)
        progress = 1f
    }

    fun cancelDownload() {
        downloadJob?.cancel()
    }

    fun openHistory(entry: HistoryEntry) {
        input = entry.url
        fetch()
    }

    fun clearHistory() {
        history.clear()
        historyItems = emptyList()
    }

    fun setFolder(uri: Uri?) {
        runCatching { storage.folder = uri }.onFailure { error = tr("Нет доступа к папке", "No access to the folder") }
        folderLabel = storage.folderLabel()
    }

    /** Rewrites tags inside the file itself, then renames it to "Artist - Title". */
    fun editTrack(item: LibraryItem, title: String, artist: String, newCover: Uri?, onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val app = getApplication<Application>()
                    val cover = newCover
                        ?.let { app.contentResolver.openInputStream(it)?.use { s -> s.readBytes() } }
                        ?.let { Media.squareJpeg(it) }
                    val uri = Uri.parse(item.uri)
                    val tmp = storage.copyToCache(uri, "edit_${item.id}.m4a")
                    try {
                        Media.writeTags(tmp, title, artist, cover)
                        storage.write(uri, tmp)
                    } finally {
                        tmp.delete()
                    }
                    val newUri = storage.rename(uri, Storage.fileName(name(artist, title), "m4a"))
                    val coverPath = cover?.let { bytes ->
                        item.cover?.let { File(it).delete() }
                        library.newCoverFile(item.id).apply { writeBytes(bytes) }.absolutePath
                    } ?: item.cover
                    item.copy(uri = newUri.toString(), title = title, artist = artist, cover = coverPath)
                }
            }
                .onSuccess { libraryItems = library.put(it); onDone() }
                .onFailure { error = tr("Не удалось изменить файл", "Could not change the file") + ": ${it.message}" }
        }
    }

    fun deleteTrack(item: LibraryItem) {
        storage.delete(Uri.parse(item.uri))
        libraryItems = library.remove(item.id)
    }

    private fun startUs() = if (isPartial) rangeStart * 1_000_000 else 0L
    private fun endUs() = if (isPartial) rangeEnd * 1_000_000 else Long.MAX_VALUE

    private fun rangeSuffix() = if (isPartial) " (${clock(rangeStart)}-${clock(rangeEnd)})".replace(':', '.') else ""

    private fun clock(sec: Long) = "%d:%02d".format(sec / 60, sec % 60)

    private fun name(artist: String, title: String) = if (artist.isBlank()) title else "$artist - $title"

    companion object {
        const val RANGE_FROM_SEC = 600L
    }
}
