package com.savex.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val history = History(app)
    val settings = Settings(app)

    var input by mutableStateOf("")
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)
    var post by mutableStateOf<Post?>(null)
        private set
    var showSheet by mutableStateOf(false)
    var historyItems by mutableStateOf(history.load())
        private set

    var kind by mutableStateOf(Kind.VIDEO)

    /** Indices of media chosen for download, and chosen quality per media and kind. */
    var selected by mutableStateOf(setOf<Int>())
    var quality by mutableStateOf(mapOf<Int, Int>())
    var audioQuality by mutableStateOf(mapOf<Int, Int>())

    /** Progress of audio extraction, 0..1, or null when idle. */
    var progress by mutableStateOf<Float?>(null)
        private set

    private var lastClipboard: String? = null
    private var job: Job? = null
    private var audioJob: Job? = null

    /** Called when the app gains focus; pastes a fresh post link from the clipboard once. */
    fun onClipboard(text: String?) {
        val url = TwitterApi.extractUrl(text) ?: return
        if (url == lastClipboard) return
        lastClipboard = url
        if (url == input && post != null) return
        input = url
        fetch()
    }

    fun onShared(text: String?) {
        val url = TwitterApi.extractUrl(text)
        if (url == null) {
            error = tr("В поделённом тексте нет ссылки на пост X", "The shared text has no X post link")
            return
        }
        lastClipboard = url
        input = url
        fetch()
    }

    fun fetch() {
        val url = TwitterApi.extractUrl(input)
        if (url == null) {
            error = tr("Вставьте ссылку на пост X", "Paste an X post link")
            return
        }
        job?.cancel()
        job = viewModelScope.launch {
            loading = true
            error = null
            message = null
            runCatching { TwitterApi.fetchPost(url) }
                .onSuccess { p ->
                    post = p
                    val first = p.media.first()
                    historyItems = history.add(HistoryEntry(p.id, p.url, first.thumbnail, first.type, p.authorName))
                    prepare(p)
                }
                .onFailure { if (it !is CancellationException) error = it.message ?: tr("Не удалось загрузить пост", "Could not load the post") }
            loading = false
        }
    }

    /**
     * Pre-selects qualities from the settings. Downloads right away when there is nothing to
     * choose (a single photo or GIF) or when the settings already name a default quality.
     */
    private fun prepare(p: Post) {
        val hasAudio = p.media.any { it.audio.isNotEmpty() }
        kind = if (settings.defaultKind == Kind.AUDIO && hasAudio) Kind.AUDIO else Kind.VIDEO
        quality = p.media.indices.associateWith { i ->
            Settings.pickVideo(p.media[i].variants.map { it.height }, settings.videoQuality)
        }
        audioQuality = p.media.indices.associateWith { i ->
            Settings.pickAudio(p.media[i].audio.size, settings.audioQuality)
        }
        selected = selectable().toSet()

        val single = p.media.size == 1 && p.media[0].variants.size <= 1 && !hasAudio
        val preset = settings.autoDownload && (kind == settings.defaultKind)
        if (single || preset) download() else showSheet = true
    }

    /** Media that can be downloaded in the current [kind]. */
    fun selectable(): List<Int> {
        val p = post ?: return emptyList()
        return p.media.indices.filter { kind == Kind.VIDEO || p.media[it].audio.isNotEmpty() }
    }

    fun switchKind(k: Kind) {
        kind = k
        selected = selectable().toSet()
    }

    fun toggle(index: Int) {
        if (index !in selectable()) return
        selected = if (index in selected) selected - index else selected + index
    }

    fun download() {
        val p = post ?: return
        showSheet = false
        val chosen = selected.sorted()
        if (kind == Kind.VIDEO) {
            chosen.forEach { i ->
                val item = p.media[i]
                val variant = item.variants.getOrNull(quality[i] ?: 0) ?: return@forEach
                Downloader.enqueue(getApplication(), p, i, item, variant)
            }
            message = tr("Загрузка началась", "Download started")
            return
        }
        audioJob?.cancel()
        audioJob = viewModelScope.launch {
            error = null
            message = null
            progress = 0f
            runCatching {
                chosen.forEachIndexed { n, i ->
                    val item = p.media[i]
                    val variant = item.audio.getOrNull(audioQuality[i] ?: 0) ?: return@forEachIndexed
                    val name = "${APP_FOLDER.lowercase()}_${p.authorHandle}_${p.id}_${i + 1}"
                    AudioSaver.save(getApplication(), variant, name) { f -> progress = (n + f) / chosen.size }
                }
            }
                .onSuccess { message = tr("Аудио сохранено в Музыка/$APP_FOLDER", "Audio saved to Music/$APP_FOLDER") }
                .onFailure { if (it !is CancellationException) error = it.message ?: tr("Ошибка загрузки", "Download failed") }
            progress = null
        }
    }

    fun cancelAudio() {
        audioJob?.cancel()
    }

    fun openHistory(entry: HistoryEntry) {
        input = entry.url
        fetch()
    }

    fun clearHistory() {
        history.clear()
        historyItems = emptyList()
    }
}
