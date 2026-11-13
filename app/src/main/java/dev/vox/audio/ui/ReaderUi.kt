package dev.vox.audio.ui

import android.graphics.Paint
import android.graphics.Path
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import dev.vox.audio.VM
import dev.vox.audio.reader.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class Theme(val paper: Color, val ink: Color, val sub: Color)
private val themes = listOf(
    Theme(Color(0xFFF6F1E7), Color(0xFF2B2622), Color(0xFF8A8176)),     // paper
    Theme(Color(0xFFE9DCC0), Color(0xFF3B2F1F), Color(0xFF8C7A5B)),     // sepia
    Theme(Color(0xFF121212), Color(0xFFCFCAC0), Color(0xFF7D7870)),     // night
)

// ------------------------------------------------------------------ BOOKS TAB
@Composable
fun BooksScreen(vm: VM, onRead: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var q by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<GutBook>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var menuFor by remember { mutableStateOf<EbookMeta?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true; msg = null
            runCatching { Ebooks.importUri(uri, "Book") }.fold({ onRead(it) }, { msg = it.message ?: "Could not import" })
            busy = false
        }
    }
    LaunchedEffect(q) {
        if (q.isBlank()) { results = emptyList(); return@LaunchedEffect }
        delay(450); busy = true
        results = runCatching { Ebooks.gutenberg(q) }.getOrDefault(emptyList()); busy = false
    }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Text("Books", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp))
        Text("Read with a page-curl, or let a Piper voice read to you, on-device. Import an EPUB/TXT or grab a free classic.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
        Row(Modifier.padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(q, { q = it }, singleLine = true, placeholder = { Text("Find free books (Project Gutenberg)") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) }, modifier = Modifier.weight(1f))
            FilledTonalIconButton({ picker.launch(arrayOf("application/epub+zip", "text/plain", "application/octet-stream")) }) { Icon(Icons.Rounded.UploadFile, "Import") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp), color = VoxRed)
        msg?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp, 0.dp)) }
        if (q.isNotBlank()) {
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(results.size) { i ->
                    val g = results[i]
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        leadingContent = { Cover(g.cover, Modifier.size(48.dp, 64.dp), 4.dp) },
                        headlineContent = { Text(g.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(g.author, maxLines = 1) },
                        trailingContent = { TextButton({ scope.launch { busy = true; msg = null
                            runCatching { Ebooks.downloadGutenberg(g) }.fold({ q = ""; onRead(it) }, { msg = "Download failed: ${it.message}" }); busy = false } }) { Text("Read") } },
                    )
                }
            }
        } else if (Ebooks.list.isEmpty()) Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
            Text("Your library is empty.\nSearch above or import an .epub.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else LazyVerticalGrid(GridCells.Adaptive(130.dp), contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(Ebooks.list, key = { it.id }) { b ->
                Box {
                    BookCard(dev.vox.audio.data.Book(b.id, "ebook", b.title, b.author, b.cover)) { onRead(b.id) }
                    IconButton({ menuFor = b }, Modifier.align(Alignment.TopEnd).size(32.dp)) { Icon(Icons.Rounded.MoreVert, "More", tint = Color.White) }
                    if (Converter.bookId == b.id) LinearProgressIndicator({ Converter.progress }, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp), color = VoxRed)
                    DropdownMenu(menuFor?.id == b.id, { menuFor = null }) {
                        DropdownMenuItem({ Text("Remove") }, { Ebooks.remove(b.id); menuFor = null; vm.refreshLocal() })
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ READER
@Composable
fun ReaderScreen(vm: VM, id: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var book by remember { mutableStateOf<EBook?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var textSp by remember { mutableFloatStateOf(vm.store.readerSize) }
    var themeIdx by remember { mutableIntStateOf(vm.store.readerTheme) }
    var serif by remember { mutableStateOf(vm.store.readerSerif) }
    val th = themes[themeIdx]
    var chrome by remember { mutableStateOf(true) }
    var toc by remember { mutableStateOf(false) }
    var fontMenu by remember { mutableStateOf(false) }
    var voiceMenu by remember { mutableStateOf(false) }
    var convertAsk by remember { mutableStateOf(false) }
    var paged by remember { mutableStateOf<Paged?>(null) }
    var anchor by remember { mutableStateOf(vm.store.readPos(id) ?: (0 to 0)) }
    val curl = rememberCurlState(0)
    var followTarget by remember { mutableIntStateOf(-1) }

    BackHandler { if (Narrator.state != Narrator.S.Idle && Narrator.bookId == id) Narrator.stop(); onClose() }
    LaunchedEffect(id) { runCatching { Ebooks.load(id) }.fold({ book = it }, { error = it.message }) }
    LaunchedEffect(Unit) { delay(2500); chrome = false }
    DisposableEffect(themeIdx) {
        val w = (view.context as android.app.Activity).window
        val c = WindowCompat.getInsetsController(w, view); val old = c.isAppearanceLightStatusBars
        c.isAppearanceLightStatusBars = themeIdx != 2
        onDispose { c.isAppearanceLightStatusBars = old }
    }

    Box(Modifier.fillMaxSize().background(th.paper)) {
        val b = book
        if (b == null) { Box(Modifier.fillMaxSize(), Alignment.Center) { if (error != null) Text(error!!, color = th.ink) else CircularProgressIndicator(color = VoxRed) }; return@Box }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val padH = 26.dp; val padTop = 56.dp; val padBottom = 44.dp
            val twoUp = maxWidth >= 640.dp; val per = if (twoUp) 2 else 1       // unfolded Fold / tablet: two-page spread
            val gutter = 40.dp
            val usable = maxWidth.coerceAtMost(1400.dp)
            val pageWdp = if (twoUp) (usable - padH * 2 - gutter) / 2 else maxWidth - padH * 2
            val pageHdp = maxHeight - padTop - padBottom
            val wPx = with(density) { pageWdp.roundToPx() }; val hPx = with(density) { pageHdp.roundToPx() }
            val textPx = with(density) { textSp.sp.toPx() }
            LaunchedEffect(b, wPx, hPx, textSp, serif, themeIdx) {
                val p = withContext(Dispatchers.Default) { Paginator.build(b, wPx, hPx, textPx, serif, th.ink.toArgb()) }
                paged = p
                curl.jump(p.pageOf(anchor.first, anchor.second) / per)
            }
            val pg = paged
            if (pg == null) { CircularProgressIndicator(Modifier.align(Alignment.Center), color = VoxRed); return@BoxWithConstraints }

            // remember where we are; a manual page turn while narrating restarts the voice from that page
            LaunchedEffect(pg) {
                snapshotFlow { curl.settled }.collect { i ->
                    val s = pg.pages.getOrNull(i * per) ?: return@collect
                    anchor = s.chapter to s.charStart
                    vm.store.setReadPos(id, s.chapter, s.charStart)
                    if (followTarget == i) followTarget = -1
                    else if (Narrator.bookId == id && Narrator.state != Narrator.S.Idle) startListen(ctx, vm, b, pg, s, scope)
                }
            }
            // follow the voice with page turns
            val cur = Narrator.cursor
            LaunchedEffect(cur, Narrator.chapter) {
                if (cur == null || Narrator.bookId != id || Narrator.state == Narrator.S.Idle) return@LaunchedEffect
                val target = pg.pageOf(Narrator.chapter, cur.first) / per
                if (target != curl.settled && !curl.progress.isRunning) { followTarget = target; curl.curlY = 0.84f; curl.goTo(target) }
            }

            CurlPager(count = (pg.pages.size + per - 1) / per, state = curl, paper = th.paper, contentKey = Triple(textSp, themeIdx, serif to per), modifier = Modifier.fillMaxSize(),
                onTap = { x, w ->
                    when {
                        x < w * 0.30f -> scope.launch { curl.curlY = 0.84f; curl.goTo((curl.progress.value.toInt() - if (curl.progress.value % 1f == 0f) 1 else 0).coerceAtLeast(0)) }
                        x > w * 0.70f -> scope.launch { curl.curlY = 0.84f; curl.goTo(curl.progress.value.toInt() + 1) }
                        else -> chrome = !chrome
                    }
                }) { i ->
                Box(Modifier.fillMaxSize().background(th.paper)) {
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center) {
                        for (k in 0 until per) {
                            if (k == 1) Spacer(Modifier.width(gutter))
                            PageView(b, pg, i * per + k, id, th, pageWdp, pageHdp, padTop, pg.pages.size)
                        }
                    }
                    if (twoUp) Box(Modifier.align(Alignment.Center).width(1.dp).fillMaxHeight(0.7f).background(th.sub.copy(alpha = .3f)))
                }
            }

            // ---- top chrome
            if (chrome) Row(Modifier.fillMaxWidth().align(Alignment.TopCenter).background(th.paper.copy(alpha = .96f)).statusBarsPadding().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton({ if (Narrator.bookId == id) Narrator.stop(); onClose() }) { Icon(Icons.Rounded.ArrowBack, "Back", tint = th.ink) }
                Text(b.title, color = th.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                IconButton({ toc = true }) { Icon(Icons.Rounded.FormatListBulleted, "Contents", tint = th.ink) }
                Box {
                    IconButton({ fontMenu = true }) { Icon(Icons.Rounded.TextFields, "Text", tint = th.ink) }
                    DropdownMenu(fontMenu, { fontMenu = false }) {
                        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton({ textSp = (textSp - 1).coerceAtLeast(13f); vm.store.readerSize = textSp }) { Icon(Icons.Rounded.Remove, "Smaller") }
                            Text("${textSp.toInt()}")
                            IconButton({ textSp = (textSp + 1).coerceAtMost(32f); vm.store.readerSize = textSp }) { Icon(Icons.Rounded.Add, "Larger") }
                        }
                        DropdownMenuItem({ Text(if (serif) "Sans-serif font" else "Serif font") }, { serif = !serif; vm.store.readerSerif = serif })
                        DropdownMenuItem({ Text("Theme: ${listOf("Paper", "Sepia", "Night")[themeIdx]}") }, { themeIdx = (themeIdx + 1) % 3; vm.store.readerTheme = themeIdx })
                    }
                }
                IconButton({ convertAsk = true }, enabled = !Converter.running) { Icon(Icons.Rounded.AudioFile, "Convert to audiobook", tint = th.ink) }
            }

            // ---- bottom: voice controls
            val active = Narrator.bookId == id && Narrator.state != Narrator.S.Idle
            val voiceId = vm.voiceId.ifBlank { Voices.forLang(b.lang).id }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                (Voices.installing ?: Converter.bookId?.takeIf { it == id })?.let {
                    val p = if (Voices.installing != null) Voices.progress else Converter.progress
                    Surface(color = th.ink.copy(alpha = .92f)) { Column(Modifier.padding(12.dp).fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (Voices.installing != null) "Downloading voice… ${(p * 100).toInt()}%" else "Converting · ${Converter.status ?: ""} · ${(p * 100).toInt()}%",
                                color = th.paper, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            if (Voices.installing == null && Converter.readyId == id && Converter.ready > 0)
                                TextButton({ if (Narrator.bookId == id) Narrator.stop(); onClose(); vm.playLocal(id) }) { Text("Listen now", color = th.paper, fontWeight = FontWeight.Bold) }
                            if (Voices.installing == null) TextButton({ Converter.cancel() }) { Text("Pause", color = th.paper.copy(alpha = .7f)) }
                        }
                        LinearProgressIndicator({ p }, Modifier.fillMaxWidth().padding(top = 6.dp), color = VoxRed)
                    } }
                }
                Voices.error?.let { Surface(color = MaterialTheme.colorScheme.errorContainer) { Text(it, Modifier.padding(12.dp).fillMaxWidth(), fontSize = 13.sp) } }
                Narrator.message?.let { Surface(color = MaterialTheme.colorScheme.errorContainer) { Text(it, Modifier.padding(12.dp).fillMaxWidth(), fontSize = 13.sp) } }
                if (chrome || active) Row(Modifier.fillMaxWidth().background(th.ink.copy(alpha = .94f)).navigationBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    val spec = pg.pages.getOrNull(curl.settled * per)
                    FilledIconButton({
                        if (active) Narrator.toggle()
                        else if (spec != null) startListen(ctx, vm, b, pg, spec, scope)
                    }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = VoxRed)) {
                        if (Narrator.state == Narrator.S.Loading || (Voices.installing != null)) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                        else Icon(if (Narrator.state == Narrator.S.Playing && active) Icons.Rounded.Pause else Icons.Rounded.Headphones, "Listen", tint = Color.White)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(if (active) "Reading aloud" else "Listen to this book", color = th.paper, modifier = Modifier.weight(1f), fontSize = 14.sp)
                    if (active) IconButton({ Narrator.stop() }) { Icon(Icons.Rounded.Stop, "Stop", tint = th.paper) }
                    var speedMenu by remember { mutableStateOf(false) }
                    Box {
                        TextButton({ speedMenu = true }) { Text("${Narrator.speed}x", color = th.paper) }
                        DropdownMenu(speedMenu, { speedMenu = false }) {
                            listOf(0.8f, 1f, 1.15f, 1.3f, 1.5f).forEach { s -> DropdownMenuItem({ Text("${s}x") }, { Narrator.speed = s; speedMenu = false }) }
                        }
                    }
                    Box {
                        TextButton({ voiceMenu = true }) { Text(Voices.all.firstOrNull { it.id == voiceId }?.label?.substringAfter("· ", "")?.ifBlank { null } ?: "Voice", color = th.paper) }
                        DropdownMenu(voiceMenu, { voiceMenu = false }) {
                            Voices.all.forEach { v ->
                                DropdownMenuItem({ Text(v.label + if (Voices.installed(v.id)) "" else "  ·  65 MB") },
                                    { vm.setVoice(v.id); voiceMenu = false },
                                    leadingIcon = { if (v.id == voiceId) Icon(Icons.Rounded.Check, null) })
                            }
                        }
                    }
                }
            }

            if (toc) AlertDialog({ toc = false }, confirmButton = { TextButton({ toc = false }) { Text("Close") } }, title = { Text("Contents") },
                text = { LazyColumn { itemsIndexed(b.chapters) { ci, c ->
                    Text(c.title, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().clickable {
                        toc = false; scope.launch { curl.jump(pg.firstPage[ci]) }
                    }.padding(vertical = 10.dp))
                } } })
            if (convertAsk) AlertDialog({ convertAsk = false },
                title = { Text("Convert to audiobook?") },
                text = { Text("Generates the audiobook chapter by chapter with the ${Voices.all.firstOrNull { it.id == voiceId }?.label ?: "selected"} voice, fully on this phone. You can start listening as soon as the first chapter is ready (Home > Made on this phone); the rest keeps converting in the background.") },
                dismissButton = { TextButton({ convertAsk = false }) { Text("Cancel") } },
                confirmButton = { TextButton({
                    convertAsk = false
                    scope.launch {
                        if (!Voices.installed(voiceId)) Voices.install(voiceId)
                        if (Voices.installed(voiceId)) Converter.start(ctx, b, voiceId) { vm.refreshLocal() }
                    }
                }) { Text("Convert") } })
        }
    }
}

private fun startListen(ctx: android.content.Context, vm: VM, b: EBook, pg: Paged, spec: PageSpec, scope: kotlinx.coroutines.CoroutineScope) {
    val voice = vm.voiceId.ifBlank { Voices.forLang(b.lang).id }
    vm.pauseBook()
    scope.launch {
        if (!Voices.installed(voice)) Voices.install(voice)
        if (Voices.installed(voice)) Narrator.start(ctx, b, pg.plain, spec.chapter, spec.charStart, voice)
    }
}

@Composable
private fun PageView(b: EBook, pg: Paged, idx: Int, id: String, th: Theme, w: androidx.compose.ui.unit.Dp, h: androidx.compose.ui.unit.Dp,
                     padTop: androidx.compose.ui.unit.Dp, total: Int) {
    Box(Modifier.width(w).fillMaxHeight()) {
        val spec = pg.pages.getOrNull(idx) ?: return@Box
        val hl = remember { Paint().apply { color = 0x55E50914; style = Paint.Style.FILL } }
        Canvas(Modifier.padding(top = padTop).size(w, h)) {
            drawIntoCanvas { c ->
                val nc = c.nativeCanvas
                nc.save(); nc.clipRect(0f, 0f, size.width, spec.bottom - spec.top); nc.translate(0f, -spec.top)
                val cr = Narrator.cursor
                var path: Path? = null
                if (cr != null && Narrator.chapter == spec.chapter && Narrator.bookId == id && Narrator.state != Narrator.S.Idle) {
                    val s = maxOf(cr.first, spec.charStart); val e = minOf(cr.last + 1, spec.charEnd)
                    if (s < e) path = Path().also { spec.layout.getSelectionPath(s, e, it) }
                }
                path?.let { nc.drawPath(it, hl) }
                spec.layout.draw(nc)
                nc.restore()
            }
        }
        Text("${b.chapters.getOrNull(spec.chapter)?.title.orEmpty()}  ·  ${idx + 1} / $total", color = th.sub, fontSize = 11.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.BottomCenter).padding(vertical = 14.dp))
    }
}
