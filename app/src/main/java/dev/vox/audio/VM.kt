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
    private val builtin: List<Addon> = listOf(LocalAddon(app), LibriVoxAddon(), ArchiveAddon(), TorrentAddon(), YouTubeAddon())

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
    var finished by mutableStateOf(store.finished())
    var bookmarks by mutableStateOf<List<Bookmark>>(emptyList())     // bookmarks of the book now playing
    var skipSilence by mutableStateOf(store.skipSilence)
    var autoUpdate by mutableStateOf(store.autoUpdate)
    var sleepAtChapterEnd by mutableStateOf(false)

    // self-update
    var update by mutableStateOf<Updater.Release?>(null)
    var updateProgress by mutableStateOf<Float?>(null)
    var updateReady by mutableStateOf<java.io.File?>(null)
    var updateMsg by mutableStateOf<String?>(null)
    var checkingUpdate by mutableStateOf(false)
    val appVersion: String = Updater.currentVersion(app)

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
    private var pausedAt = 0L
    private var sleepStartChapter = -1

    init {
        Torrents.init(app)
        dev.vox.audio.reader.Ebooks.init(app)
        dev.vox.audio.reader.Voices.init(app)
        YT.attach(app)
        viewModelScope.launch { YT.updateIfStale() }
        connect()
        if (store.autoUpdate && System.currentTimeMillis() - store.lastUpdateCheck > 6 * 3600_000L) checkUpdate(auto = true)
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
                chapter = c.currentMediaItemIndex
                duration = c.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: ((nowBook?.chapters?.getOrNull(chapter)?.durationSec ?: 0) * 1000L)
                if (c.isPlaying && ++tick % 10 == 0) saveProgress()
                if (tick % 4 == 0) torrentStatus = Torrents.statusFor(c.currentMediaItem?.localConfiguration?.uri?.toString())
                if (sleepEndsAt > 0 && System.currentTimeMillis() >= sleepEndsAt) { c.pause(); sleepEndsAt = 0 }
                if (sleepAtChapterEnd && sleepStartChapter >= 0 && chapter != sleepStartChapter) { c.pause(); sleepAtChapterEnd = false }
                if (updateReady != null && !c.isPlaying && store.autoUpdate) installUpdate()
                if (tick % 6 == 0) extendLocal(c)
            }
        }
    }

    // ---------- addons / catalogs ----------
    fun addonFor(b: Book) = addons.firstOrNull { it.id == b.addonId }

    fun refreshLocal() {
        val r = rows.firstOrNull { it.addon.id == "local" } ?: return
        rows = rows.map { if (it.key == r.key) it.copy(loading = true) else it }
        loadRow(r, 0)
    }
    // navigation shared between player and reader (audio first: the two always know about each other)
    var reading by mutableStateOf<String?>(null)
    var playerOpen by mutableStateOf(false)
    var readerJump by mutableStateOf<Pair<Int, Int>?>(null)     // (chapter, char offset) the open reader should show

    fun hasBook(id: String) = dev.vox.audio.reader.Ebooks.list.any { it.id == id }

    /** From an on-device audiobook straight to the same sentence in its ebook. */
    fun openBook(b: Book) {
        if (b.addonId != "local") return
        val live = nowBook?.key == b.key
        val p = store.progress(b.key)
        val chap = if (live) b.chapters.getOrNull(chapter) ?: nowBook?.chapters?.getOrNull(chapter) else b.chapters.firstOrNull { it.id == (p?.chapter ?: 0) }
        val pid = chap?.id ?: (p?.chapter ?: 0)
        val ebCh = chap?.ch?.takeIf { it >= 0 } ?: pid
        val ms = if (live) position else p?.positionMs ?: 0L
        val off = dev.vox.audio.reader.SyncMap.charAt(getApplication(), b.id, pid, ms)?.first ?: 0
        store.setReadPos(b.id, ebCh, off)
        readerJump = ebCh to off
        playerOpen = false
        reading = b.id
    }

    // part ids <-> playlist positions (a growing on-device audiobook has gaps until every part is generated)
    private fun storedOf(b: Book, idx: Int) = if (b.addonId == "local") b.chapters.getOrNull(idx)?.id ?: idx else idx
    private fun idxOf(b: Book, stored: Int): Int {
        val last = (b.chapters.size - 1).coerceAtLeast(0)
        if (b.addonId != "local") return stored.coerceIn(0, last)
        return b.chapters.indexOfFirst { it.id == stored }.takeIf { it >= 0 } ?: b.chapters.indexOfFirst { it.id > stored }.takeIf { it >= 0 } ?: last
    }

    fun seekChapterMs(ci: Int, ms: Long) { controller?.let { if (ci in 0 until it.mediaItemCount) it.seekTo(ci, ms) } }

    var preparing by mutableStateOf(false)

    /**
     * The one "Listen" action for ebooks: make sure the part holding this spot exists (generating from here first, then the rest of the
     * book), then play it in the same player as every other audiobook. If it is already playing, just move to the sentence.
     */
    fun listenFrom(b: dev.vox.audio.reader.EBook, chapter: Int, offset: Int) = viewModelScope.launch {
        val app = getApplication<Application>()
        val R = dev.vox.audio.reader.Voices
        val voice = voiceId.ifBlank { R.forLang(b.lang).id }
        if (!R.installed(voice)) R.install(voice)
        if (!R.installed(voice)) return@launch
        val part = dev.vox.audio.reader.Plan.partFor(dev.vox.audio.reader.Plan.parts(b), chapter, offset) ?: return@launch
        preparing = true
        try {
            dev.vox.audio.reader.Converter.start(app, b, voice, part.id) { refreshLocal() }
            var waited = 0
            while (!dev.vox.audio.reader.Converter.isReady(app, b.id, part.id)) {
                delay(250)
                if (dev.vox.audio.reader.Converter.status?.startsWith("Conversion failed") == true || ++waited > 2400) return@launch
            }
            val m = runCatching { addons.first { it.id == "local" }.meta(Book(b.id, "local", "")) }.getOrNull() ?: return@launch
            val ms = dev.vox.audio.reader.SyncMap.msAt(app, b.id, part.id, offset)
            val c = controller
            if (c != null && nowBook?.key == m.key && c.mediaItemCount > 0) {
                syncPlaylist(c, m)
                val idx = (0 until c.mediaItemCount).firstOrNull { c.getMediaItemAt(it).mediaId.substringAfter('#').toIntOrNull() == part.id } ?: 0
                c.seekTo(idx, ms); if (!c.isPlaying) togglePlay()
            } else play(m, idxOf(m, part.id), ms)
        } finally { preparing = false }
    }

    /** Reading progress of an ebook shows up in Continue Listening next to audiobooks. */
    fun touchEbook(m: dev.vox.audio.reader.EbookMeta, chapter: Int, offset: Int, fraction: Float) {
        val key = "ebook|${m.id}"
        store.saveProgress(Progress(key, chapter, offset.toLong(), System.currentTimeMillis(), fraction.coerceIn(0f, 1f)))
        if (history.firstOrNull()?.key != key) {
            history = (listOf(Book(m.id, "ebook", m.title, m.author, m.cover)) + history.filter { it.key != key }).take(40)
            store.setHistory(history)
        }
    }

    fun testSpeed() = viewModelScope.launch {
        val id = voiceId.ifBlank { dev.vox.audio.reader.Voices.all[0].id }
        if (!dev.vox.audio.reader.Voices.installed(id)) dev.vox.audio.reader.Voices.install(id)
        if (dev.vox.audio.reader.Voices.installed(id)) dev.vox.audio.reader.Tuner.run(id)
    }
    var voiceId by mutableStateOf(store.voice)            // "" = automatic (match the book's language)
    fun setVoice(id: String) { voiceId = id; store.voice = id
        if (id.isNotBlank() && !dev.vox.audio.reader.Voices.installed(id)) viewModelScope.launch { dev.vox.audio.reader.Voices.install(id) } }
    fun pauseBook() { controller?.let { if (it.isPlaying) it.pause() } }

    private fun refreshRows() {
        rows = addons.flatMap { a -> a.catalogs.map { Row(a, it) } }
        rows.forEach { loadRow(it, 0) }
    }

    private fun loadRow(r: Row, skip: Int) = viewModelScope.launch {
        val res = runCatching { r.addon.catalog(r.cat.id, null, skip) }
        rows = rows.map {
            if (it.key != r.key) it else res.fold(
                { b -> it.copy(books = if (skip == 0) b else it.books + b, loading = false, error = null, end = b.isEmpty() || r.addon.id == "local") },
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
        if (b.addonId == "ebook") { reading = b.id; return }
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

    /** An ebook and its converted audiobook are one book: show it once, and prefer the audiobook (audio first). */
    val continueList: List<Book> get() {
        val groups = LinkedHashMap<String, Book>()
        for (b in history) {
            if (b.key in finished) continue
            val g = if (b.addonId == "ebook" || b.addonId == "local") "book:${b.id}" else b.key
            val cur = groups[g]
            if (cur == null || (b.addonId == "local" && cur.addonId == "ebook")) groups[g] = b
        }
        return groups.values.toList()
    }
    fun removeFromHistory(b: Book) { history = history.filter { it.key != b.key }; store.setHistory(history) }
    fun fractionOf(b: Book) = if (b.key in finished) 1f else store.progress(b.key)?.fraction?.takeIf { it > 0.005f }
    fun isFinished(b: Book) = b.key in finished
    fun toggleFinished(b: Book) {
        finished = if (b.key in finished) finished - b.key else finished + b.key
        store.setFinished(finished)
        if (b.key !in finished) return
        store.saveProgress(Progress(b.key, 0, 0, System.currentTimeMillis(), 1f))
    }
    /** "Ch 5 · 12:34" for the Resume button. */
    fun resumeLabel(b: Book): String? {
        if (b.key in finished) return null
        if (b.addonId == "ebook") { val p = store.progress(b.key) ?: return null; return "Ch ${p.chapter + 1} · ${(p.fraction * 100).toInt()}%" }
        val p = store.progress(b.key) ?: return null
        if (p.positionMs < 1000 && p.chapter == 0) return null
        return "Ch ${p.chapter + 1} · ${dev.vox.audio.ui.fmtTime(p.positionMs)}"
    }

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
                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                    if (!playing) { pausedAt = System.currentTimeMillis(); saveProgress() }
                }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { chapter = c.currentMediaItemIndex; saveProgress() }
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) nowBook?.takeIf { dev.vox.audio.reader.Converter.bookId != it.id }?.let {
                        finished = finished + it.key; store.setFinished(finished)
                        store.saveProgress(Progress(it.key, 0, 0, System.currentTimeMillis(), 1f))
                    }
                }
            })
            isPlaying = c.isPlaying
            if (c.mediaItemCount > 0) { if (nowBook == null) restoreNowBook(c) } else restoreLast(c)
            nowBook?.let { bookmarks = store.bookmarks(it.key) }
        }, ContextCompat.getMainExecutor(getApplication()))
    }

    private fun restoreNowBook(c: MediaController) {
        val items = (0 until c.mediaItemCount).map { c.getMediaItemAt(it) }
        val key = items.first().mediaId.substringBefore('#')
        nowBook = Book(key.substringAfter('|'), key.substringBefore('|'),
            items.first().mediaMetadata.albumTitle?.toString().orEmpty(), items.first().mediaMetadata.artist?.toString().orEmpty(),
            items.first().mediaMetadata.artworkUri?.toString(),
            chapters = items.map { Chapter(it.mediaMetadata.title?.toString().orEmpty(), it.localConfiguration?.uri?.toString().orEmpty(), 0, it.mediaId.substringAfter('#').toIntOrNull() ?: -1) })
    }

    /** Reopen the last book paused at the exact saved spot, so the mini player is there on launch. */
    private fun restoreLast(c: MediaController) {
        val b = store.lastBook() ?: return
        if (b.chapters.isEmpty() || isStale(b)) return
        val p = store.progress(b.key)
        val idx = idxOf(b, p?.chapter ?: 0)
        val pos = ((p?.positionMs ?: 0) - 5000).coerceAtLeast(0)
        nowBook = b; chapter = idx; position = pos
        duration = b.chapters[idx].durationSec * 1000L
        c.setMediaItems(mediaItems(b), idx, pos)
        c.setPlaybackSpeed(store.speedFor(b.key) ?: speed)
        speed = store.speedFor(b.key) ?: speed
    }

    private fun mediaItems(book: Book) = book.chapters.mapIndexed { i, ch ->
        MediaItem.Builder().setUri(ch.url).setMediaId("${book.key}#${if (book.addonId == "local" && ch.id >= 0) ch.id else i}")
            .setMediaMetadata(MediaMetadata.Builder().setTitle(ch.title).setArtist(book.author).setAlbumTitle(book.title)
                .apply {
                    val u = book.cover
                    // private-file covers cannot be opened by SystemUI (lock screen / notification), so embed the bytes
                    if (u != null && u.startsWith("file://")) runCatching { java.io.File(u.removePrefix("file://")).takeIf { it.length() < 1_500_000 }?.readBytes() }.getOrNull()
                        ?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) } ?: setArtworkUri(Uri.parse(u))
                    else setArtworkUri(u?.let { Uri.parse(it) })
                }.build()).build()
    }

    fun play(book: Book, chapterIdx: Int = 0, posMs: Long = 0) {
        val c = controller ?: return
        if (book.chapters.isEmpty()) return
        val items = mediaItems(book)
        nowBook = book
        store.setLastBook(book)
        bookmarks = store.bookmarks(book.key)
        if (book.key in finished) { finished = finished - book.key; store.setFinished(finished) }
        speed = store.speedFor(book.key) ?: speed
        sleepStartChapter = -1
        history = (listOf(book.copy(chapters = emptyList())) + history.filter { it.key != book.key }).take(40)
        store.setHistory(history)
        c.setMediaItems(items, chapterIdx.coerceIn(0, items.lastIndex), posMs)
        c.setPlaybackSpeed(speed)
        c.prepare(); c.play()
    }

    /** Continue from the saved chapter + position (5 s of rewind). Loads the chapter list first if the card came from history. */
    /** An on-device audiobook that is still being generated gains parts: insert them into the running playlist, in book order. */
    private var extending = false
    private fun syncPlaylist(c: MediaController, m: Book) {
        val ids = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId.substringAfter('#').toIntOrNull() ?: -1 }.toMutableList()
        val items = mediaItems(m)
        m.chapters.forEachIndexed { i, ch ->
            if (ch.id !in ids) { val at = i.coerceAtMost(ids.size); c.addMediaItem(at, items[i]); ids.add(at, ch.id) }
        }
        nowBook = m.copy(cover = nowBook?.cover ?: m.cover); store.setLastBook(nowBook)
        if (detail?.key == m.key) detail = nowBook
    }

    private fun extendLocal(c: MediaController) {
        val b = nowBook ?: return
        if (extending || b.addonId != "local" || dev.vox.audio.reader.Converter.readyId != b.id || dev.vox.audio.reader.Converter.ready <= c.mediaItemCount) return
        extending = true
        viewModelScope.launch {
            runCatching { addons.first { it.id == "local" }.meta(b) }.onSuccess { m -> if (m.chapters.size > c.mediaItemCount) syncPlaylist(c, m) }
            extending = false
        }
    }

    /** Seconds of audio left in the whole book (null when some chapter lengths are unknown). */
    fun bookLeftSec(): Long? {
        val b = nowBook ?: return null
        if (b.chapters.isEmpty() || b.chapters.any { it.durationSec <= 0 }) return null
        val cur = (b.chapters[chapter.coerceIn(0, b.chapters.lastIndex)].durationSec - position / 1000).coerceAtLeast(0)
        return cur + b.chapters.drop(chapter + 1).sumOf { it.durationSec }
    }

    /** Start (or continue) an on-device audiobook, even while it is still being generated. */
    fun playLocal(id: String) = viewModelScope.launch {
        val m = runCatching { addons.first { it.id == "local" }.meta(Book(id, "local", "")) }.getOrNull() ?: return@launch
        if (m.chapters.isEmpty()) return@launch
        detail = m; resume(m)
    }

    private fun isStale(b: Book): Boolean {
        val u = b.chapters.firstOrNull()?.url ?: return false
        if (u.startsWith("file://")) return b.chapters.any { !java.io.File(u.removePrefix("file://").substringBeforeLast('/') + "/" + it.url.substringAfterLast('/')).exists() }
        return !Torrents.knows(u)
    }

    fun resume(book: Book) {
        if (book.addonId == "ebook") { reading = book.id; return }
        val cur = nowBook?.takeIf { it.key == book.key }
        if (cur != null && !isStale(cur)) { togglePlay(); return }
        val p = store.progress(book.key)
        val fresh = p == null || book.key in finished
        val stored = if (fresh) 0 else p!!.chapter
        val pos = if (fresh) 0L else (p!!.positionMs - 5000).coerceAtLeast(0)
        if (book.chapters.isNotEmpty() && !isStale(book)) { play(book, idxOf(book, stored), pos); return }
        store.lastBook()?.takeIf { it.key == book.key && it.chapters.isNotEmpty() && !isStale(it) }?.let { play(it, idxOf(it, stored), pos); return }
        viewModelScope.launch {    // torrent URLs from an earlier launch: re-resolve through the addon
            val a = addonFor(book) ?: return@launch
            runCatching { a.meta(book) }.onSuccess { m -> play(m.copy(cover = m.cover ?: book.cover, author = m.author.ifBlank { book.author }), idxOf(m, stored), pos) }
        }
    }

    fun progressOf(b: Book) = store.progress(b.key)
    fun togglePlay() {
        val c = controller ?: return
        if (c.isPlaying) { c.pause(); return }
        nowBook?.takeIf { isStale(it) }?.let { resume(it); return }
        // smart rewind: the longer you were away, the more it backs up
        val away = if (pausedAt > 0) System.currentTimeMillis() - pausedAt else 0L
        val back = when { away > 30 * 60_000L -> 15_000L; away > 5 * 60_000L -> 8_000L; away > 60_000L -> 4_000L; else -> 0L }
        if (back > 0 && c.currentPosition > back) c.seekTo(c.currentPosition - back)
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        c.play()
    }
    fun seekTo(ms: Long) { controller?.seekTo(ms); position = ms }
    fun skip(ms: Long) { controller?.let { it.seekTo((it.currentPosition + ms).coerceAtLeast(0)) } }
    fun next() { controller?.seekToNextMediaItem() }
    fun prev() { controller?.let { if (it.currentPosition > 3000 || !it.hasPreviousMediaItem()) it.seekTo(0) else it.seekToPreviousMediaItem() } }
    fun gotoChapter(i: Int) { controller?.seekTo(i, 0) }
    fun changeSpeed(s: Float) {
        speed = s; store.speed = s; controller?.setPlaybackSpeed(s)
        nowBook?.let { store.setSpeedFor(it.key, s) }
    }

    fun toggleSkipSilence() {
        skipSilence = !skipSilence; store.skipSilence = skipSilence
        PlaybackService.exo?.skipSilenceEnabled = skipSilence
    }

    // ---------- bookmarks ----------
    fun addBookmark() {
        val b = nowBook ?: return; val c = controller ?: return
        val note = "${b.chapters.getOrNull(c.currentMediaItemIndex)?.title.orEmpty()} · ${dev.vox.audio.ui.fmtTime(c.currentPosition)}"
        bookmarks = (bookmarks + Bookmark(b.key, storedOf(b, c.currentMediaItemIndex), c.currentPosition, note, System.currentTimeMillis()))
            .sortedWith(compareBy({ it.chapter }, { it.positionMs }))
        store.setBookmarks(b.key, bookmarks)
    }
    fun removeBookmark(m: Bookmark) { bookmarks = bookmarks - m; store.setBookmarks(m.bookKey, bookmarks) }
    fun jumpTo(m: Bookmark) { val b = nowBook ?: return; controller?.let { it.seekTo(idxOf(b, m.chapter), m.positionMs); if (!it.isPlaying) togglePlay() } }

    // ---------- self-update (GitHub releases) ----------
    fun checkUpdate(auto: Boolean = false) = viewModelScope.launch {
        checkingUpdate = true; if (!auto) updateMsg = null
        runCatching { Updater.latest() }.fold({ r ->
            store.lastUpdateCheck = System.currentTimeMillis()
            if (r != null && Updater.isNewer(r.version, appVersion)) {
                update = r
                if (auto && store.autoUpdate) downloadUpdate()
            } else { update = null; if (!auto) updateMsg = "You're on the latest version ($appVersion)" }
        }, { if (!auto) updateMsg = "Update check failed: ${it.message}" })
        checkingUpdate = false
    }

    fun downloadUpdate() = viewModelScope.launch {
        val r = update ?: return@launch
        if (updateProgress != null) return@launch
        updateProgress = 0f; updateMsg = null
        runCatching { Updater.download(getApplication(), r) { updateProgress = it } }
            .onSuccess { updateReady = it }
            .onFailure { updateMsg = "Download failed: ${it.message}" }
        updateProgress = null
    }

    fun installUpdate() {
        val f = updateReady ?: return
        val app = getApplication<Application>()
        if (!Updater.canInstall(app)) {
            updateMsg = "Allow Vox to install updates, then tap Install again"
            app.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${app.packageName}")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        updateReady = null          // avoid re-triggering from the ticker
        saveProgress()
        viewModelScope.launch { runCatching { Updater.install(app, f) }.onFailure { updateMsg = "Install failed: ${it.message}"; updateReady = f } }
    }

    fun toggleAutoUpdate() { autoUpdate = !autoUpdate; store.autoUpdate = autoUpdate }

    fun setSleep(minutes: Int) {
        sleepJob?.cancel()
        sleepAtChapterEnd = minutes < 0
        sleepStartChapter = if (minutes < 0) chapter else -1
        sleepEndsAt = if (minutes <= 0) 0 else System.currentTimeMillis() + minutes * 60_000L
    }

    fun saveProgress() {
        val b = nowBook ?: return
        val c = controller ?: return
        if (c.playbackState == Player.STATE_IDLE || c.playbackState == Player.STATE_ENDED) return
        val n = c.mediaItemCount.coerceAtLeast(1)
        val within = if (c.duration > 0) c.currentPosition.toFloat() / c.duration else 0f
        val stored = storedOf(b, c.currentMediaItemIndex)
        val totalParts = if (b.addonId == "local") (b.chapters.size + b.pending).coerceAtLeast(1) else n
        store.saveProgress(Progress(b.key, stored, c.currentPosition, System.currentTimeMillis(),
            ((stored + within) / totalParts).coerceIn(0f, 1f)))
    }

    override fun onCleared() { saveProgress(); controller?.release(); super.onCleared() }
}
