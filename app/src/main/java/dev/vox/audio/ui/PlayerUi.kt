package dev.vox.audio.ui

import androidx.compose.foundation.clickable
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
    val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 700

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClose) { Icon(Icons.Rounded.KeyboardArrowDown, "Close") }
                Spacer(Modifier.weight(1f))
            }
            val art: @Composable (Modifier) -> Unit = { Cover(b.cover, it, 20.dp) }
            val controls: @Composable (Modifier) -> Unit = { m ->
                Column(m, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(b.title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(b.chapters.getOrNull(vm.chapter)?.title.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(16.dp))
                    val dur = vm.duration.coerceAtLeast(1L).toFloat()
                    Slider(value = drag ?: vm.position.toFloat().coerceIn(0f, dur), onValueChange = { drag = it },
                        onValueChangeFinished = { drag?.let { vm.seekTo(it.toLong()) }; drag = null }, valueRange = 0f..dur)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(fmtTime((drag ?: vm.position.toFloat()).toLong()), style = MaterialTheme.typography.labelMedium)
                        Text(fmtTime(vm.duration), style = MaterialTheme.typography.labelMedium)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton({ vm.prev() }) { Icon(Icons.Rounded.SkipPrevious, "Previous") }
                        IconButton({ vm.skip(-15_000) }) { Icon(Icons.Rounded.Replay10, "Back 15s") }
                        FilledIconButton({ vm.togglePlay() }, Modifier.size(72.dp)) {
                            Icon(if (vm.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play/Pause", Modifier.size(40.dp))
                        }
                        IconButton({ vm.skip(30_000) }) { Icon(Icons.Rounded.Forward30, "Forward 30s") }
                        IconButton({ vm.next() }) { Icon(Icons.Rounded.SkipNext, "Next") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box {
                            AssistChip({ speedMenu = true }, { Text("${vm.speed}x") }, leadingIcon = { Icon(Icons.Rounded.Speed, null) })
                            DropdownMenu(speedMenu, { speedMenu = false }) {
                                listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { s ->
                                    DropdownMenuItem({ Text("${s}x") }, { vm.changeSpeed(s); speedMenu = false })
                                }
                            }
                        }
                        Box {
                            val left = if (vm.sleepEndsAt > 0) ((vm.sleepEndsAt - System.currentTimeMillis()) / 60000 + 1).toInt() else 0
                            AssistChip({ sleepMenu = true }, { Text(if (left > 0) "$left min" else "Sleep") }, leadingIcon = { Icon(Icons.Rounded.Bedtime, null) })
                            DropdownMenu(sleepMenu, { sleepMenu = false }) {
                                listOf(0 to "Off", 15 to "15 min", 30 to "30 min", 45 to "45 min", 60 to "1 hour").forEach { (m, l) ->
                                    DropdownMenuItem({ Text(l) }, { vm.setSleep(m); sleepMenu = false })
                                }
                            }
                        }
                    }
                }
            }
            if (wide) Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                art(Modifier.weight(1f).aspectRatio(1f).padding(24.dp))
                controls(Modifier.weight(1f).padding(24.dp))
            } else Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                art(Modifier.fillMaxWidth(.8f).aspectRatio(1f))
                Spacer(Modifier.height(16.dp))
                controls(Modifier.fillMaxWidth())
            }
        }
    }
}
