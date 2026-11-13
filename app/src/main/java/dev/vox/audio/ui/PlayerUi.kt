package dev.vox.audio.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.vox.audio.VM

@Composable
fun MiniPlayer(vm: VM, onExpand: () -> Unit) {
    val b = vm.nowBook ?: return
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth().clickable(onClick = onExpand)) {
        Column {
            LinearProgressIndicator(progress = { if (vm.duration > 0) vm.position.toFloat() / vm.duration else 0f }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(b.cover, Modifier.size(44.dp), 8.dp)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(b.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                    Text(b.chapters.getOrNull(vm.chapter)?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton({ vm.skip(-15_000) }) { Icon(Icons.Rounded.Replay10, "Back 15s") }
                IconButton({ vm.togglePlay() }) { Icon(if (vm.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play/Pause") }
            }
        }
    }
}

@Composable
fun FullPlayer(vm: VM, onClose: () -> Unit) {
    val b = vm.nowBook ?: run { onClose(); return }
    var drag by remember { mutableStateOf<Float?>(null) }
    var speedMenu by remember { mutableStateOf(false) }
    var sleepMenu by remember { mutableStateOf(false) }
    var marks by remember { mutableStateOf(false) }
    val sp = vm.speed.coerceAtLeast(0.5f)

    // Everything that matters (title, seek bar, transport, chips) lives in one block that is never clipped:
    // the cover takes whatever height is left, so nothing needs scrolling on any screen shape.
    val controls: @Composable (Modifier) -> Unit = { m ->
        Column(m, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(b.title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(b.chapters.getOrNull(vm.chapter)?.title.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            val dur = vm.duration.coerceAtLeast(1L).toFloat()
            Slider(value = drag ?: vm.position.toFloat().coerceIn(0f, dur), onValueChange = { drag = it },
                onValueChangeFinished = { drag?.let { vm.seekTo(it.toLong()) }; drag = null }, valueRange = 0f..dur)
            // times are real listening time: they shrink when the speed goes up
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(fmtTime(((drag ?: vm.position.toFloat()) / sp).toLong()), style = MaterialTheme.typography.labelMedium)
                vm.bookLeftSec()?.let { Text("${fmtDur((it / sp).toLong())} left" + if (sp != 1f) " at ${sp}x" else "",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text(fmtTime((vm.duration / sp).toLong()), style = MaterialTheme.typography.labelMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                IconButton({ vm.prev() }) { Icon(Icons.Rounded.SkipPrevious, "Previous") }
                IconButton({ vm.skip(-15_000) }) { Icon(Icons.Rounded.Replay10, "Back 15s") }
                FilledIconButton({ vm.togglePlay() }, Modifier.size(68.dp)) {
                    Icon(if (vm.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play/Pause", Modifier.size(38.dp))
                }
                IconButton({ vm.skip(30_000) }) { Icon(Icons.Rounded.Forward30, "Forward 30s") }
                IconButton({ vm.next() }) { Icon(Icons.Rounded.SkipNext, "Next") }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box {
                    AssistChip({ speedMenu = true }, { Text("${vm.speed}x") }, leadingIcon = { Icon(Icons.Rounded.Speed, null) })
                    DropdownMenu(speedMenu, { speedMenu = false }) {
                        listOf(0.75f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f).forEach { s -> DropdownMenuItem({ Text("${s}x") }, { vm.changeSpeed(s); speedMenu = false }) }
                    }
                }
                Box {
                    val left = if (vm.sleepEndsAt > 0) ((vm.sleepEndsAt - System.currentTimeMillis()) / 60000 + 1).toInt() else 0
                    AssistChip({ sleepMenu = true }, { Text(if (left > 0) "$left min" else if (vm.sleepAtChapterEnd) "Chapter end" else "Sleep") }, leadingIcon = { Icon(Icons.Rounded.Bedtime, null) })
                    DropdownMenu(sleepMenu, { sleepMenu = false }) {
                        listOf(0 to "Off", -1 to "End of chapter", 15 to "15 min", 30 to "30 min", 45 to "45 min", 60 to "1 hour", 90 to "90 min")
                            .forEach { (m, l) -> DropdownMenuItem({ Text(l) }, { vm.setSleep(m); sleepMenu = false }) }
                    }
                }
                AssistChip({ vm.addBookmark() }, { Text("Bookmark") }, leadingIcon = { Icon(Icons.Rounded.BookmarkAdd, null) })
            }
        }
    }

    Surface(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding()) {
            val sideBySide = maxWidth >= 600.dp && maxWidth > maxHeight * 0.85f
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClose) { Icon(Icons.Rounded.KeyboardArrowDown, "Close") }
                    Spacer(Modifier.weight(1f))
                    if (vm.bookmarks.isNotEmpty()) TextButton({ marks = true }) { Icon(Icons.Rounded.Bookmarks, null); Spacer(Modifier.width(6.dp)); Text("${vm.bookmarks.size}") }
                }
                if (sideBySide) Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).fillMaxHeight().padding(16.dp), Alignment.Center) {
                        Cover(b.cover, Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true).sizeIn(maxWidth = 520.dp, maxHeight = 520.dp), 20.dp)
                    }
                    controls(Modifier.weight(1f).widthIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(16.dp))
                } else Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.weight(1f).fillMaxWidth().padding(8.dp), Alignment.Center) {
                        Cover(b.cover, Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true).sizeIn(maxWidth = 480.dp, maxHeight = 480.dp), 20.dp)
                    }
                    controls(Modifier.fillMaxWidth().widthIn(max = 560.dp).padding(bottom = 8.dp))
                }
            }
        }
    }

    if (marks) AlertDialog({ marks = false }, confirmButton = { TextButton({ marks = false }) { Text("Close") } }, title = { Text("Bookmarks") },
        text = { LazyColumn { items(vm.bookmarks.size) { i ->
            val m = vm.bookmarks[i]
            ListItem(colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                headlineContent = { Text(m.note, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                trailingContent = { IconButton({ vm.removeBookmark(m) }) { Icon(Icons.Rounded.Close, "Remove") } },
                modifier = Modifier.clickable { vm.jumpTo(m); marks = false })
        } } })
}
