package dev.vox.audio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.vox.audio.VM
import dev.vox.audio.Row as CatRow
import dev.vox.audio.data.Book
import dev.vox.audio.data.HttpAddon
import dev.vox.audio.data.Torrents

// ------------------------------------------------------------------ HOME
@Composable
fun HomeScreen(vm: VM, onOpen: (Book) -> Unit) {
    val hero = vm.continueList.firstOrNull() ?: vm.rows.firstNotNullOfOrNull { it.books.firstOrNull() }
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    val cardW = cardWidth()
    val heroH = (cfg.screenHeightDp * 0.42f).coerceIn(240f, 460f).dp
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Hero(vm, hero, heroH, onOpen) }
        if (vm.update != null) item { UpdateBanner(vm) }
        item { Greeting(vm) }
        vm.featured?.let { f -> item { FeaturedCard(vm, f, onOpen) } }
        if (vm.continueList.isNotEmpty()) item {
            Shelf("Continue Listening") {
                items(vm.continueList, key = { "h" + it.key }) { b ->
                    Column(Modifier.width(cardW)) {
                        BookCard(b, Modifier.fillMaxWidth(), progress = vm.fractionOf(b)) { vm.resume(b) }
                        Text(vm.resumeLabel(b) ?: " ", style = MaterialTheme.typography.labelSmall, color = VoxRed, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }
        if (vm.library.isNotEmpty()) item {
            Shelf("My List") { items(vm.library, key = { "l" + it.key }) { BookCard(it, Modifier.width(cardW)) { onOpen(it) } } }
        }
        vm.becauseShelf?.let { sh -> item(key = "because") { DiscoverShelf(sh, onOpen) } }
        items(vm.shelves, key = { "sh" + it.id }) { DiscoverShelf(it, onOpen) }
        items(vm.rows.filter { it.loading || it.books.isNotEmpty() || it.error != null }, key = { it.key }) { RowSection(vm, it, onOpen) }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Hero(vm: VM, b: Book?, height: androidx.compose.ui.unit.Dp, onOpen: (Book) -> Unit) {
    val bg = MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxWidth().height(height)) {
        if (b?.cover != null) AsyncImage(b.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = .75f)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xAA000000), Color.Transparent, bg))))
        Row(Modifier.statusBarsPadding().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("VOX", color = VoxRed, fontSize = 28.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
        }
        if (b != null) Column(Modifier.align(Alignment.BottomStart).padding(16.dp, 0.dp, 16.dp, 20.dp)) {
            Text(displayTitle(b.title), fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (b.author.isNotBlank()) Text(b.author, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val label = vm.resumeLabel(b)
                Button({ if (label != null) vm.resume(b) else onOpen(b) }, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)) {
                    Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(4.dp))
                    Text(if (label != null) "Resume · $label" else "Play", fontWeight = FontWeight.Bold)
                }
                if (label != null) FilledTonalButton({ onOpen(b) }) { Text("Details") }
                FilledTonalButton({ vm.toggleLibrary(b) }) {
                    Icon(if (vm.inLibrary(b)) Icons.Rounded.Check else Icons.Rounded.Add, null)
                    Spacer(Modifier.width(4.dp)); Text("My List")
                }
            }
        }
    }
}

@Composable
private fun cardWidth() = if (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 840) 180.dp else 150.dp

@Composable
private fun UpdateBanner(vm: VM) {
    val r = vm.update ?: return
    Surface(color = VoxRed, shape = RoundedCornerShape(10.dp), modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 0.dp).fillMaxWidth()) {
        Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Vox ${r.version} is available", fontWeight = FontWeight.Bold, color = Color.White)
                val p = vm.updateProgress
                if (p != null) LinearProgressIndicator({ p }, Modifier.fillMaxWidth().padding(top = 4.dp), color = Color.White, trackColor = Color(0x55FFFFFF))
                else vm.updateMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color.White) }
            }
            if (vm.updateProgress == null) TextButton({ if (vm.updateReady != null) vm.installUpdate() else vm.downloadUpdate() },
                colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) { Text(if (vm.updateReady != null) "Install" else "Update", fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun Shelf(title: String, content: LazyListScope.() -> Unit) {
    Column(Modifier.padding(top = 16.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(8.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun RowSection(vm: VM, r: CatRow, onOpen: (Book) -> Unit) {
    val cardW = cardWidth()
    Column(Modifier.padding(top = 16.dp)) {
        Text(r.cat.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(8.dp))
        when {
            r.books.isEmpty() && r.loading -> Box(Modifier.fillMaxWidth().height(150.dp), Alignment.Center) { CircularProgressIndicator(color = VoxRed) }
            r.books.isEmpty() && r.error != null -> Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(r.error, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                TextButton({ vm.retry(r) }) { Text("Retry") }
            }
            else -> LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(r.books, key = { it.key }) { BookCard(it, Modifier.width(cardW)) { onOpen(it) } }
                if (!r.end) item {
                    Box(Modifier.width(100.dp).height(150.dp), Alignment.Center) {
                        if (r.loading) CircularProgressIndicator(color = VoxRed) else TextButton({ vm.loadMore(r) }) { Text("More") }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ SEARCH
@Composable
fun SearchScreen(vm: VM, onOpen: (Book) -> Unit) {
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importBook(uri) }
    // chip -> filter id (VM keeps 0 all, 1 torrents, 2 youtube, 3 books)
    val chips = listOf("All" to 0, "Books" to 3, "Torrents" to 1, "YouTube" to 2)
    val f = vm.searchFilter
    val showBooksHome = vm.query.isBlank() && (f == 0 || f == 3)
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = vm.query, onValueChange = vm::search, singleLine = true, modifier = Modifier.weight(1f),
                placeholder = { Text("Search books, audio, YouTube…", maxLines = 1) },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (vm.query.isNotEmpty()) IconButton({ vm.search("") }) { Icon(Icons.Rounded.Close, null) } },
            )
            FilledTonalIconButton({ picker.launch(arrayOf("application/epub+zip", "text/plain", "application/octet-stream")) }) { Icon(Icons.Rounded.UploadFile, "Import an EPUB or TXT") }
        }
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            chips.forEach { (l, id) -> FilterChip(f == id, { vm.setFilter(id) }, { Text(l) }) }
        }
        if (vm.query.isBlank() && vm.recent.isNotEmpty()) {
            Row(Modifier.padding(16.dp, 10.dp, 16.dp, 0.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Recent", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                TextButton({ vm.clearRecent() }) { Text("Clear") }
            }
            androidx.compose.foundation.lazy.LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(vm.recent.size) { i -> AssistChip({ vm.search(vm.recent[i]) }, { Text(vm.recent[i], maxLines = 1) }, leadingIcon = { Icon(Icons.Rounded.History, null, Modifier.size(18.dp)) }) }
            }
        }
        if (vm.bookBusy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp, 8.dp), color = VoxRed)
        vm.bookMsg?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp, 4.dp)) }
        val nothing = vm.results.isEmpty() && vm.gutResults.isEmpty()
        when {
            showBooksHome && dev.vox.audio.reader.Ebooks.list.isEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
                Text("Search every source at once: audiobooks, free books, YouTube and torrents.\nPaste a YouTube link or magnet to open it directly, or import your own EPUB.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            vm.query.isBlank() && !showBooksHome -> Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
                Text(if (f == 1) "Search torrents or paste a magnet link." else "Search YouTube or paste a link or playlist.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            vm.searching && nothing -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = VoxRed) }
            vm.query.isNotBlank() && !vm.searching && nothing -> Box(Modifier.fillMaxSize(), Alignment.Center) { Text("No results", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else -> LazyVerticalGrid(GridCells.Adaptive(130.dp), contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (showBooksHome) {
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { Text("Your books", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(4.dp, 4.dp, 4.dp, 0.dp)) }
                    items(dev.vox.audio.reader.Ebooks.list, key = { "eb" + it.id }) { EbookCard(vm, it) }
                }
                if (vm.gutResults.isNotEmpty()) {
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { Text("Free books", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(4.dp, 4.dp, 4.dp, 0.dp)) }
                    items(vm.gutResults, key = { "g" + it.id }) { g -> BookCard(Book("gut:${g.id}", "gutenberg", g.title, g.author, g.cover)) { vm.openGutenberg(g) } }
                }
                if (vm.results.isNotEmpty()) {
                    if (vm.gutResults.isNotEmpty()) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { Text("Audiobooks, YouTube and torrents", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(4.dp, 8.dp, 4.dp, 0.dp)) }
                    items(vm.results, key = { it.key }) { BookCard(it) { onOpen(it) } }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ MY LIST (favorites + history)
@Composable
fun MyListScreen(vm: VM, onOpen: (Book) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val items = when (tab) { 0 -> vm.library; 1 -> vm.history.filter { !vm.isFinished(it) }; else -> vm.history.filter { vm.isFinished(it) } }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Text("My Vox", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 8.dp))
        StatsCard(vm)
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(tab == 0, { tab = 0 }, { Text("Favorites (${vm.library.size})") })
            FilterChip(tab == 1, { tab = 1 }, { Text("History (${vm.history.count { !vm.isFinished(it) }})") })
            FilterChip(tab == 2, { tab = 2 }, { Text("Finished (${vm.history.count { vm.isFinished(it) }})") })
        }
        if (items.isEmpty()) Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
            Text(when (tab) { 0 -> "Add books with the + button."; 1 -> "Books you listen to show up here."; else -> "Books you finish show up here." },
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else LazyVerticalGrid(GridCells.Adaptive(130.dp), contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.key }) { b ->
                Box {
                    BookCard(b, progress = vm.fractionOf(b)) { onOpen(b) }
                    if (tab != 0) IconButton({ vm.removeFromHistory(b) }, Modifier.align(Alignment.TopEnd).size(32.dp)) {
                        Icon(Icons.Rounded.Close, "Remove", tint = Color.White)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ ADDONS
@Composable
fun AddonsScreen(vm: VM) {
    var url by remember { mutableStateOf("") }
    var cache by remember { mutableLongStateOf(Torrents.cacheBytes()) }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(16.dp)) {
        item {
            Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("Addons", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Addons add catalogs of audiobooks. Paste a manifest URL (https:// or stremio://) from a source you trust.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(url, { url = it }, singleLine = true, label = { Text("Addon manifest URL") },
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    if (vm.installBusy) CircularProgressIndicator(Modifier.size(24.dp), color = VoxRed)
                    else TextButton({ vm.installAddon(url); url = "" }, enabled = url.isNotBlank()) { Text("Install") }
                })
            vm.installError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(16.dp))
        }
        items(vm.addons, key = { it.id }) { a ->
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = { Text(a.name) },
                supportingContent = { Text(if (a is HttpAddon) a.manifestUrl else "Built-in · ${a.catalogs.size} catalog(s)", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingContent = { if (a is HttpAddon) IconButton({ vm.removeAddon(a) }) { Icon(Icons.Rounded.Delete, "Remove") } },
            )
        }
        item {
            Spacer(Modifier.height(24.dp))
            Text("App", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = { Text("Vox ${vm.appVersion}") },
                supportingContent = { Text(vm.updateMsg ?: vm.update?.let { "Version ${it.version} available" } ?: "Updates come from GitHub Releases") },
                trailingContent = {
                    when {
                        vm.updateProgress != null -> CircularProgressIndicator(Modifier.size(24.dp), color = VoxRed)
                        vm.updateReady != null -> TextButton({ vm.installUpdate() }) { Text("Install") }
                        vm.update != null -> TextButton({ vm.downloadUpdate() }) { Text("Update") }
                        vm.checkingUpdate -> CircularProgressIndicator(Modifier.size(24.dp), color = VoxRed)
                        else -> TextButton({ vm.checkUpdate() }) { Text("Check") }
                    }
                },
            )
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = { Text("Update automatically") },
                supportingContent = { Text("Check on launch, download and install when nothing is playing") },
                trailingContent = { Switch(vm.autoUpdate, { vm.toggleAutoUpdate() }) },
            )
            var voiceMenu by remember { mutableStateOf(false) }
            val auto = vm.voiceId.isBlank()
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = { Text("Reading voice") },
                supportingContent = {
                    val inst = dev.vox.audio.reader.Voices.installing
                    Text(when {
                        inst != null -> "Downloading voice… ${(dev.vox.audio.reader.Voices.progress * 100).toInt()}%"
                        auto -> "Automatic: matches the language of each book"
                        else -> dev.vox.audio.reader.Voices.all.firstOrNull { it.id == vm.voiceId }?.label ?: vm.voiceId
                    })
                },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val pv = dev.vox.audio.reader.Voices.previewing
                        val sc = rememberCoroutineScope()
                        IconButton({ sc.launch {
                            val id = vm.voiceId.ifBlank { dev.vox.audio.reader.Voices.all[0].id }
                            if (!dev.vox.audio.reader.Voices.installed(id)) dev.vox.audio.reader.Voices.install(id)
                            dev.vox.audio.reader.Voices.preview(id, "en")
                        } }, enabled = !pv) { if (pv) CircularProgressIndicator(Modifier.size(20.dp), color = VoxRed, strokeWidth = 2.dp) else Icon(Icons.Rounded.RecordVoiceOver, "Preview this voice") }
                    Box {
                        TextButton({ voiceMenu = true }) { Text("Change") }
                        DropdownMenu(voiceMenu, { voiceMenu = false }) {
                            DropdownMenuItem({ Text("Automatic") }, { vm.setVoice(""); voiceMenu = false }, leadingIcon = { if (auto) Icon(Icons.Rounded.Check, null) })
                            dev.vox.audio.reader.Voices.all.forEach { v ->
                                DropdownMenuItem({ Text(v.label + if (dev.vox.audio.reader.Voices.installed(v.id)) "" else "  ·  ${v.sizeMb} MB download") },
                                    { vm.setVoice(v.id); voiceMenu = false }, leadingIcon = { if (v.id == vm.voiceId) Icon(Icons.Rounded.Check, null) })
                            }
                        }
                    }
                    }
                },
            )
            dev.vox.audio.reader.Voices.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            val tuneKind = dev.vox.audio.reader.Voices.byId(vm.voiceId)?.kind ?: "piper"
            val tuned = dev.vox.audio.reader.Tuner.best(tuneKind)
            val testing = dev.vox.audio.reader.Tuner.running
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = { Text("Conversion speed") },
                supportingContent = {
                    Text(when {
                        testing -> dev.vox.audio.reader.Tuner.status ?: "Testing…"
                        tuned != null -> "%.1fx real-time (%d parallel x %d threads). A 10 hour book converts in about %d min."
                            .format(tuned.xrt, tuned.workers, tuned.threads, (600 / tuned.xrt).toInt())
                        else -> "Not measured yet. The test finds the fastest setup for this phone (about a minute)."
                    })
                },
                trailingContent = {
                    if (testing) CircularProgressIndicator(Modifier.size(24.dp), color = VoxRed)
                    else TextButton({ vm.testSpeed() }) { Text(if (tuned == null) "Test" else "Re-test") }
                },
            )
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = { Text("Skip silence") },
                supportingContent = { Text("Trim pauses in the narration") },
                trailingContent = { Switch(vm.skipSilence, { vm.toggleSkipSilence() }) },
            )
            Spacer(Modifier.height(8.dp))
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = { Text("Torrent cache") },
                supportingContent = { Text("${cache / 1_048_576} MB on disk") },
                trailingContent = { TextButton({ Torrents.clearCache(); cache = Torrents.cacheBytes() }) { Text("Clear") } },
            )
        }
    }
}

// ------------------------------------------------------------------ DETAIL
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(vm: VM, b: Book, onBack: (() -> Unit)?) {
    val playingThis = vm.nowBook?.key == b.key
    val progress = vm.progressOf(b)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Box(Modifier.fillMaxWidth().height(300.dp)) {
                if (b.cover != null) AsyncImage(b.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = .7f)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent, MaterialTheme.colorScheme.background))))
                Row(Modifier.statusBarsPadding().padding(8.dp)) {
                    if (onBack != null) IconButton(onBack) { Icon(Icons.Rounded.ArrowBack, "Back") }
                }
                Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                    Text(b.title, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (b.author.isNotBlank()) Text(b.author, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (b.totalSec > 0) Text(fmtDur((b.totalSec / vm.speed.coerceAtLeast(0.5f)).toLong()) + if (vm.speed != 1f) " at ${vm.speed}x" else "", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        sourceBadge(b.addonId)?.let { Text(it, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(VoxRed).padding(horizontal = 5.dp, vertical = 2.dp)) }
                    }
                }
            }
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Button({ vm.resume(b) }, enabled = b.chapters.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)) {
                    Icon(if (playingThis && vm.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null)
                    Spacer(Modifier.width(6.dp))
                    val rl = vm.resumeLabel(b)
                    Text(if (playingThis && vm.isPlaying) "Pause" else if (playingThis) "Continue" else if (rl != null) "Continue · $rl" else "Play", fontWeight = FontWeight.Bold)
                }
                FilledTonalButton({ vm.toggleLibrary(b) }) {
                    Icon(if (vm.inLibrary(b)) Icons.Rounded.Check else Icons.Rounded.Add, null)
                    Spacer(Modifier.width(4.dp)); Text("My List")
                }
                if (b.addonId == "local" && vm.hasBook(b.id)) FilledTonalButton({ vm.openBook(b) }) {
                    Icon(Icons.Rounded.MenuBook, null); Spacer(Modifier.width(4.dp)); Text("Read along")
                }
                IconButton({ vm.toggleFinished(b) }) {
                    Icon(Icons.Rounded.TaskAlt, "Mark finished", tint = if (vm.isFinished(b)) VoxRed else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (playingThis) vm.torrentStatus?.let {
                Text("⬇ $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp, 8.dp, 16.dp, 0.dp))
            }
            if (b.description.isNotBlank()) Text(b.description, style = MaterialTheme.typography.bodyMedium, maxLines = 6,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(16.dp))
            if (b.pending > 0) Text(if (dev.vox.audio.reader.Converter.bookId == b.id) "Generating audio: ${dev.vox.audio.reader.Converter.ready} of ${dev.vox.audio.reader.Converter.total} chapters ready. You can listen now, the rest is added as it finishes."
                else "${b.pending} parts are still to be made.",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp, 8.dp, 16.dp, 0.dp))
            if (b.pending > 0 && dev.vox.audio.reader.Converter.bookId != b.id) TextButton({ vm.continueConversion(b.id) }, Modifier.padding(horizontal = 8.dp)) { Text("Generate the rest") }
            if (vm.detailLoading) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp), color = VoxRed)
                if (b.addonId == "torrent" || b.addonId == "youtube") Text(
                    if (b.addonId == "torrent") "Fetching torrent metadata from peers…" else "Asking yt-dlp…",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp))
            }
            vm.detailError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        }
        itemsIndexed(b.chapters) { i, ch ->
            val current = playingThis && vm.chapter == i
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = { Text(ch.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (current) VoxRed else MaterialTheme.colorScheme.onSurface) },
                supportingContent = if (ch.durationSec > 0) ({ Text(fmtTime(ch.durationSec * 1000L)) }) else null,
                leadingContent = { if (current) Icon(Icons.Rounded.GraphicEq, null, tint = VoxRed) else Text("${i + 1}") },
                modifier = Modifier.clickable { if (playingThis) vm.gotoChapter(i) else vm.play(b, i) },
            )
        }
    }
}

// ------------------------------------------------------------------ STATS
@Composable
private fun StatsCard(vm: VM) {
    @Suppress("UNUSED_EXPRESSION") vm.statsTick
    val st = vm.store
    val today = st.listenedOn(0); val week = (0..6).sumOf { st.listenedOn(it) }; val streak = st.streak()
    if (st.totalListened < 60_000) return
    fun fmt(ms: Long): String { val m = ms / 60_000; return if (m >= 60) "${m / 60}h ${"%02d".format(m % 60)}m" else "${m}m" }
    val days = (6 downTo 0).map { st.listenedOn(it) }; val max = (days.maxOrNull() ?: 1L).coerceAtLeast(1L)
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f), modifier = Modifier.padding(16.dp, 4.dp, 16.dp, 12.dp).fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column { Text(fmt(today), fontWeight = FontWeight.Bold, fontSize = 20.sp); Text("today", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Column { Text(fmt(week), fontWeight = FontWeight.Bold, fontSize = 20.sp); Text("this week", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Column { Text(if (streak > 0) "$streak 🔥" else "0", fontWeight = FontWeight.Bold, fontSize = 20.sp); Text("day streak", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Spacer(Modifier.height(4.dp))
                Text("${fmt(st.totalListened)} listened in total", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.height(44.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
                days.forEachIndexed { i, d ->
                    Box(Modifier.width(8.dp).fillMaxHeight((d / max.toFloat()).coerceIn(.08f, 1f)).clip(RoundedCornerShape(3.dp)).background(if (i == 6) VoxRed else VoxRed.copy(alpha = .45f)))
                }
            }
        }
    }
}

// ------------------------------------------------------------------ FIRST RUN
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun Onboarding(vm: VM) {
    val pages = listOf(
        Triple(Icons.Rounded.Headphones, "Audiobooks from everywhere", "LibriVox, the Internet Archive, YouTube and torrents in one place. Add Stremio-style addons for more."),
        Triple(Icons.Rounded.MenuBook, "Read or listen to any book", "Open a free classic or your own EPUB, then choose: page-curl reading or an audiobook made on your phone."),
        Triple(Icons.Rounded.AutoAwesome, "Audio made on your phone", "A natural voice reads the book in short parts, starting where you are. Play after the first minute while the rest is made. Switch between listening and reading at the same sentence."),
    )
    val pager = androidx.compose.foundation.pager.rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth()) { Spacer(Modifier.weight(1f)); TextButton({ vm.finishOnboarding() }) { Text("Skip") } }
            androidx.compose.foundation.pager.HorizontalPager(pager, Modifier.weight(1f)) { i ->
                val (icon, title, body) = pages[i]
                Column(Modifier.fillMaxSize().widthIn(max = 480.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Box(Modifier.size(120.dp).clip(androidx.compose.foundation.shape.CircleShape).background(VoxRed.copy(alpha = .16f)), Alignment.Center) { Icon(icon, null, Modifier.size(60.dp), tint = VoxRed) }
                    Spacer(Modifier.height(32.dp))
                    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(16.dp)) {
                repeat(pages.size) { i -> Box(Modifier.size(if (pager.currentPage == i) 22.dp else 8.dp, 8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(if (pager.currentPage == i) VoxRed else MaterialTheme.colorScheme.onSurface.copy(alpha = .25f))) }
            }
            Button({ if (pager.currentPage == pages.lastIndex) vm.finishOnboarding() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                Modifier.fillMaxWidth().widthIn(max = 480.dp), colors = ButtonDefaults.buttonColors(containerColor = VoxRed, contentColor = Color.White)) {
                Text(if (pager.currentPage == pages.lastIndex) "Start listening" else "Next", fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ------------------------------------------------------------------ DISCOVER
@Composable
private fun Greeting(vm: VM) {
    val h = java.time.LocalTime.now().hour
    val hello = when { h < 5 -> "Still up?"; h < 12 -> "Good morning"; h < 18 -> "Good afternoon"; else -> "Good evening" }
    val st = vm.store.listenedOn(0)
    Row(Modifier.padding(16.dp, 14.dp, 16.dp, 0.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(hello, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(if (st > 60_000) "${st / 60_000} min listened today" else "What are we listening to?", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FilledTonalButton({ vm.surprise() }, enabled = vm.shelves.any { it.books.isNotEmpty() }) {
            Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Surprise me")
        }
    }
}

@Composable
private fun FeaturedCard(vm: VM, f: dev.vox.audio.data.Featured, onOpen: (Book) -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f),
        modifier = Modifier.padding(16.dp, 14.dp, 16.dp, 0.dp).fillMaxWidth().clickable { onOpen(f.book) }) {
        Row(Modifier.padding(12.dp)) {
            Cover(f.book.cover, Modifier.size(112.dp), 12.dp)
            Column(Modifier.padding(start = 14.dp).weight(1f).heightIn(min = 112.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(f.label.uppercase(), color = VoxRed, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                    Text(displayTitle(f.book.title), fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(f.book.author, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Text(f.blurb, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button({ onOpen(f.book) }, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp), colors = ButtonDefaults.buttonColors(containerColor = VoxRed, contentColor = Color.White)) {
                        Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Listen")
                    }
                    if (f.book.totalSec > 0) Text("  ${fmtDur(f.book.totalSec.toLong())}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun DiscoverShelf(sh: dev.vox.audio.data.Shelf, onOpen: (Book) -> Unit) {
    if (!sh.loading && sh.books.isEmpty()) return
    val cardW = cardWidth()
    Column(Modifier.padding(top = 18.dp)) {
        Text(sh.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp))
        if (sh.subtitle.isNotBlank()) Text(sh.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(8.dp))
        if (sh.books.isEmpty()) LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(4) { Box(Modifier.width(cardW).aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .4f))) }
        } else LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sh.books, key = { sh.id + it.key }) { BookCard(it, Modifier.width(cardW)) { onOpen(it) } }
        }
    }
}
