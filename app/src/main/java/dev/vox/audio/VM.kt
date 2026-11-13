package dev.vox.audio

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dev.vox.audio.data.*
import dev.vox.audio.play.PlaybackService
import kotlinx.coroutines.*

data class Row(val addon: Addon, val cat: CatalogDef, val books: List<Book> = emptyList(),
               val loading: Boolean = true, val error: String? = null, val end: Boolean = false) {
    val key get() = "${addon.id}|${cat.id}"
}

class VM(app: Application) : AndroidViewModel(app) {
    val store = Store(app)
    private val builtin: List<Addon> = listOf(LibriVoxAddon(), ArchiveAddon(), TorrentAddon(), YouTubeAddon())

    var addons by mutableStateOf(builtin)
    var installBusy by mutableStateOf(false)
    var installError by mutableStateOf<String?>(null)

    var rows by mutableStateOf<List<Row>>(emptyList())
    var query by mutableStateOf("")
    var results by mutableStateOf<List<Book>>(emptyList())
    var searching by mutableStateOf(false)

    var detail by mutableStateOf<Book?>(null)
    var detailLoading by mutableStateOf(false)
    var detailError by mutableStateOf<String?>(null)
    var library by mutableStateOf(store.library())
    var history by mutableStateOf(store.history())
    var searchFilter by mutableIntStateOf(0)      // 0 = all, 1 = torrents, 2 = youtube
    var torrentStatus by mutableStateOf<String?>(null)

    // playback
    private var controller: MediaController? = null
    var nowBook by mutableStateOf<Book?>(null)
    var chapter by mutableIntStateOf(0)
    var isPlaying by mutableStateOf(false)
    var position by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)
    var speed by mutableFloatStateOf(store.speed)
    var sleepEndsAt by mutableLongStateOf(0L)
    private var sleepJob: Job? = null
    private var searchJob: Job? = null

    init {
        Torrents.init(app)
        YT.attach(app)
        viewModelScope.launch { YT.updateIfStale() }
        connect()
        viewModelScope.launch {
            val remote = store.addonUrls().map { u -> async { runCatching { HttpAddon.install(u) }.getOrNull() } }.awaitAll().filterNotNull()
            addons = builtin + remote
            refreshRows()
        }
        viewModelScope.launch {
            var tick = 0
            while (true) {
                delay(500)
                val c = controller ?: continue
                position = c.currentPosition
                duration = c.duration.takeIf { it != C.TIME_UNSET } ?: 0L
                chapter = c.currentMediaItemIndex
                if (c.isPlaying && ++tick % 10 == 0) saveProgress()
                if (tick % 4 == 0) torrentStatus = Torrents.statusFor(c.currentMediaItem?.localConfiguration?.uri?.toString())
                if (sleepEndsAt > 0 && System.currentTimeMillis() >= sleepEndsAt) { c.pause(); sleepEndsAt = 0 }
            }
        }
    }

    // ---------- addons / catalogs ----------
    fun addonFor(b: Book) = addons.firstOrNull { it.id == b.addonId }

    private fun refreshRows() {
        rows = addons.flatMap { a -> a.catalogs.map { Row(a, it) } }
        rows.forEach { loadRow(it, 0) }
    }

    private fun loadRow(r: Row, skip: Int) = viewModelScope.launch {
        val res = runCatching { r.addon.catalog(r.cat.id, null, skip) }
        rows = rows.map {
            if (it.key != r.key) it else res.fold(
                { b -> it.copy(books = if (skip == 0) b else it.books + b, loading = false, error = null, end = b.isEmpty()) },
                { e -> it.copy(loading = false, error = e.message ?: "Failed") })
        }
    }

    fun loadMore(r: Row) {
        val cur = rows.firstOrNull { it.key == r.key } ?: return
        if (cur.loading || cur.end) return
        rows = rows.map { if (it.key == r.key) it.copy(loading = true) else it }
        loadRow(cur, cur.books.size)
    }

    fun retry(r: Row) { rows = rows.map { if (it.key == r.key) it.copy(loading = true, error = null) else it }; loadRow(r, r.books.size) }

    fun installAddon(url: String) = viewModelScope.launch {
        installBusy = true; installError = null
        runCatching { HttpAddon.install(url) }.fold({ a ->
            if (addons.any { it.id == a.id }) installError = "Already installed"
            else { addons = addons + a; store.setAddonUrls(store.addonUrls() + a.manifestUrl); refreshRows() }
        }, { installError = "Could not load manifest: ${it.message}" })
        installBusy = false
    }

    fun removeAddon(a: Addon) {
        if (a !is HttpAddon) return
        addons = addons - a
        store.setAddonUrls(store.addonUrls() - a.manifestUrl)
        refreshRows()
    }

    fun search(q: String) {
        query = q
        searchJob?.cancel()
        if (q.isBlank()) { results = emptyList(); searching = false; return }
        searching = true
        searchJob = viewModelScope.launch {
            delay(400)
            val isRef = q.trim().startsWith("magnet:") || Regex("^[0-9a-fA-F]{40}$").matches(q.trim())
            val isYt = Regex("^(https?://)?(www\\.|m\\.|music\\.)?(youtube\\.com|youtu\\.be)/.+").matches(q.trim())
            val pool = addons.filter {
                when {
                    isRef -> it.id == "torrent"
                    isYt -> it.id == "youtube"
                    searchFilter == 1 -> it.id == "torrent"
                    searchFilter == 2 -> it.id == "youtube"
                    else -> true
                }
            }
            val all = pool.flatMap { a -> a.catalogs.filter { it.searchable }.take(1).map { c -> a to c } }
                .map { (a, c) -> async { runCatching { a.catalog(c.id, q, 0) }.getOrDefault(emptyList()) } }.awaitAll()
            results = all.flatten()
            searching = false
        }
    }

    fun setFilter(f: Int) { searchFilter = f; search(query) }

    // ---------- detail / library ----------
    fun open(b: Book) {
        detail = b; detailError = null; detailLoading = b.chapters.isEmpty()
        if (b.chapters.isNotEmpty()) return
        viewModelScope.launch {
            val a = addonFor(b)
            if (a == null) { detailError = "Addon for this book is not installed"; detailLoading = false; return@launch }
            runCatching { a.meta(b) }.fold(
                { m -> if (detail?.key == b.key) detail = m.copy(cover = m.cover ?: b.cover, author = m.author.ifBlank { b.author }) },
                { e -> detailError = e.message ?: "Failed to load" })
            detailLoading = false
        }
    }
    fun closeDetail() { detail = null }

    fun removeFromHistory(b: Book) { history = history.filter { it.key != b.key }; store.setHistory(history) }
    fun fractionOf(b: Book) = store.progress(b.key)?.fraction?.takeIf { it > 0.005f }

    fun inLibrary(b: Book) = library.any { it.key == b.key }
    fun toggleLibrary(b: Book) {
        library = if (inLibrary(b)) library.filter { it.key != b.key } else listOf(b) + library
        store.setLibrary(library)
    }

    // ---------- playback ----------
    private fun connect() {
        val token = SessionToken(getApplication(), ComponentName(getApplication(), PlaybackService::class.java))
        val f = MediaController.Builder(getApplication(), token).buildAsync()
        f.addListener({
            val c = runCatching { f.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing; if (!playing) saveProgress() }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { chapter = c.currentMediaItemIndex }
            })
            isPlaying = c.isPlaying
            if (nowBook == null && c.mediaItemCount > 0) restoreNowBook(c)
        }, ContextCompat.getMainExecutor(getApplication()))
    }

    private fun restoreNowBook(c: MediaController) {
        val items = (0 until c.mediaItemCount).map { c.getMediaItemAt(it) }
        val key = items.first().mediaId.substringBefore('#')
        nowBook = Book(key.substringAfter('|'), key.substringBefore('|'),
            items.first().mediaMetadata.albumTitle?.toString().orEmpty(), items.first().mediaMetadata.artist?.toString().orEmpty(),
            items.first().mediaMetadata.artworkUri?.toString(),
            chapters = items.map { Chapter(it.mediaMetadata.title?.toString().orEmpty(), it.localConfiguration?.uri?.toString().orEmpty()) })
    }

    fun play(book: Book, chapterIdx: Int = 0, posMs: Long = 0) {
        val c = controller ?: return
        if (book.chapters.isEmpty()) return
        val items = book.chapters.mapIndexed { i, ch ->
            MediaItem.Builder().setUri(ch.url).setMediaId("${book.key}#$i")
                .setMediaMetadata(MediaMetadata.Builder().setTitle(ch.title).setArtist(book.author).setAlbumTitle(book.title)
                    .setArtworkUri(book.cover?.let { Uri.parse(it) }).build()).build()
        }
        nowBook = book
        history = (listOf(book.copy(chapters = emptyList())) + history.filter { it.key != book.key }).take(40)
        store.setHistory(history)
        c.setMediaItems(items, chapterIdx.coerceIn(0, items.lastIndex), posMs)
        c.setPlaybackSpeed(speed)
        c.prepare(); c.play()
    }

    fun resume(book: Book) {
        if (nowBook?.key == book.key) { togglePlay(); return }
        val p = store.progress(book.key)
        play(book, p?.chapter ?: 0, p?.positionMs ?: 0)
    }

    fun progressOf(b: Book) = store.progress(b.key)
    fun togglePlay() { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
    fun seekTo(ms: Long) { controller?.seekTo(ms); position = ms }
    fun skip(ms: Long) { controller?.let { it.seekTo((it.currentPosition + ms).coerceAtLeast(0)) } }
    fun next() { controller?.seekToNextMediaItem() }
    fun prev() { controller?.let { if (it.currentPosition > 3000 || !it.hasPreviousMediaItem()) it.seekTo(0) else it.seekToPreviousMediaItem() } }
    fun gotoChapter(i: Int) { controller?.seekTo(i, 0) }
    fun changeSpeed(s: Float) { speed = s; store.speed = s; controller?.setPlaybackSpeed(s) }

    fun setSleep(minutes: Int) {
        sleepJob?.cancel()
        sleepEndsAt = if (minutes <= 0) 0 else System.currentTimeMillis() + minutes * 60_000L
    }

    private fun saveProgress() {
        val b = nowBook ?: return
        val c = controller ?: return
        val n = c.mediaItemCount.coerceAtLeast(1)
        val within = if (c.duration > 0) c.currentPosition.toFloat() / c.duration else 0f
        store.saveProgress(Progress(b.key, c.currentMediaItemIndex, c.currentPosition, System.currentTimeMillis(),
            ((c.currentMediaItemIndex + within) / n).coerceIn(0f, 1f)))
    }

    override fun onCleared() { saveProgress(); controller?.release(); super.onCleared() }
}
