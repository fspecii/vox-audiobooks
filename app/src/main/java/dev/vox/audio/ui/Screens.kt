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
        if (vm.continueList.isNotEmpty()) item {
            Shelf("Continue Listening") {
                items(vm.continueList, key = { "h" + it.key }) { b ->
                    Column(Modifier.width(cardW)) {
                        BookCard(b, Modifier.fillMaxWidth(), progress = vm.fractionOf(b)) { vm.resume(b) }
                        vm.resumeLabel(b)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = VoxRed, modifier = Modifier.padding(top = 2.dp)) }
                    }
                }
            }
        }
        if (vm.library.isNotEmpty()) item {
            Shelf("My List") { items(vm.library, key = { "l" + it.key }) { BookCard(it, Modifier.width(cardW)) { onOpen(it) } } }
        }
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
            Text(b.title, fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        OutlinedTextField(
            value = vm.query, onValueChange = vm::search, singleLine = true,
            placeholder = { Text("Title, author, YouTube link or magnet") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { if (vm.query.isNotEmpty()) IconButton({ vm.search("") }) { Icon(Icons.Rounded.Close, null) } },
            modifier = Modifier.fillMaxWidth().padding(16.dp, 12.dp, 16.dp, 4.dp),
        )
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("All", "Torrents", "YouTube").forEachIndexed { i, l ->
                FilterChip(vm.searchFilter == i, { vm.setFilter(i) }, { Text(l) })
            }
        }
        when {
            vm.query.isBlank() -> Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
                Text("Search every installed source at once.\nPaste a YouTube link, playlist or magnet to open it directly.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            vm.searching -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = VoxRed) }
            vm.results.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) { Text("No results", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else -> LazyVerticalGrid(GridCells.Adaptive(130.dp), contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(vm.results, key = { it.key }) { BookCard(it) { onOpen(it) } }
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
                    Box {
                        TextButton({ voiceMenu = true }) { Text("Change") }
                        DropdownMenu(voiceMenu, { voiceMenu = false }) {
                            DropdownMenuItem({ Text("Automatic") }, { vm.setVoice(""); voiceMenu = false }, leadingIcon = { if (auto) Icon(Icons.Rounded.Check, null) })
                            dev.vox.audio.reader.Voices.all.forEach { v ->
                                DropdownMenuItem({ Text(v.label + if (dev.vox.audio.reader.Voices.installed(v.id)) "" else "  ·  65 MB download") },
                                    { vm.setVoice(v.id); voiceMenu = false }, leadingIcon = { if (v.id == vm.voiceId) Icon(Icons.Rounded.Check, null) })
                            }
                        }
                    }
                },
            )
            dev.vox.audio.reader.Voices.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
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
                else "${b.pending} chapters not generated yet. Open the book in Books and tap convert to continue.",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp, 8.dp, 16.dp, 0.dp))
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
