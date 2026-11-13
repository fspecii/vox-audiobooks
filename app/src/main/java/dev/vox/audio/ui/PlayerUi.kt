package dev.vox.audio.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.togetherWith
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.ui.input.pointer.pointerInput
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
                if (b.addonId == "local" && vm.hasBook(b.id)) IconButton({ vm.openBook(b) }) { Icon(Icons.Rounded.MenuBook, "Read along") }
                IconButton({ vm.skip(-15_000) }) { Icon(Icons.Rounded.Replay10, "Back 15s") }
                IconButton({ vm.togglePlay() }) { Icon(if (vm.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play/Pause") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullPlayer(vm: VM, onClose: () -> Unit) {
    val b = vm.nowBook ?: run { onClose(); return }
    var drag by remember { mutableStateOf<Float?>(null) }
    var speedMenu by remember { mutableStateOf(false) }
    var sleepMenu by remember { mutableStateOf(false) }
    var marks by remember { mutableStateOf(false) }
    var chapters by remember { mutableStateOf(false) }
    val sp = vm.speed.coerceAtLeast(0.5f)

    // Everything that matters (title, seek bar, transport, chips) lives in one block that is never clipped:
    // the cover takes whatever height is left, so nothing needs scrolling on any screen shape.
    val controls: @Composable (Modifier) -> Unit = { m ->
        Column(m, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(b.title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            androidx.compose.animation.AnimatedContent(b.chapters.getOrNull(vm.chapter)?.title.orEmpty(), transitionSpec = {
                (androidx.compose.animation.slideInVertically { it / 2 } + androidx.compose.animation.fadeIn()) togetherWith
                    (androidx.compose.animation.slideOutVertically { -it / 2 } + androidx.compose.animation.fadeOut())
            }, label = "chapterTitle") { t ->
                Text(t, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            }
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
                val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
                val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                val pressed by src.collectIsPressedAsState()
                val press by androidx.compose.animation.core.animateFloatAsState(if (pressed) .9f else 1f, androidx.compose.animation.core.spring(stiffness = 600f), label = "press")
                FilledIconButton({ haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); vm.togglePlay() },
                    Modifier.size(68.dp).graphicsLayer { scaleX = press; scaleY = press }, interactionSource = src) {
                    androidx.compose.animation.AnimatedContent(vm.isPlaying, transitionSpec = {
                        (androidx.compose.animation.scaleIn(androidx.compose.animation.core.spring(stiffness = 500f)) + androidx.compose.animation.fadeIn()) togetherWith
                            (androidx.compose.animation.scaleOut() + androidx.compose.animation.fadeOut())
                    }, label = "pp") { playing -> Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play/Pause", Modifier.size(38.dp)) }
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
                AssistChip({ chapters = true }, { Text("Chapters") }, leadingIcon = { Icon(Icons.Rounded.FormatListBulleted, null) })
                AssistChip({ vm.addBookmark() }, { Text("Bookmark") }, leadingIcon = { Icon(Icons.Rounded.BookmarkAdd, null) })
            }
        }
    }

    val dragY = remember { androidx.compose.animation.core.Animatable(0f) }
    val scopeP = rememberCoroutineScope()
    val dismissState = androidx.compose.foundation.gestures.rememberDraggableState { d -> scopeP.launch { dragY.snapTo((dragY.value + d).coerceAtLeast(0f)) } }
    Surface(Modifier.fillMaxSize()
        .graphicsLayer { translationY = dragY.value; val f = (dragY.value / 1400f).coerceIn(0f, 1f); alpha = 1f - f * .6f; scaleX = 1f - f * .06f; scaleY = 1f - f * .06f }
        .draggable(dismissState, androidx.compose.foundation.gestures.Orientation.Vertical, onDragStopped = { v ->
            if (dragY.value > 380f || v > 1800f) { dragY.animateTo(2400f, androidx.compose.animation.core.tween(180)); onClose(); dragY.snapTo(0f) }
            else dragY.animateTo(0f, androidx.compose.animation.core.spring(dampingRatio = .75f, stiffness = 400f))
        })) {
        // the cover, blurred and darkened, tints the whole screen
        Box(Modifier.fillMaxSize()) {
            if (b.cover != null) coil.compose.AsyncImage(b.cover, null, Modifier.fillMaxSize().blur(60.dp), contentScale = androidx.compose.ui.layout.ContentScale.Crop, alpha = .35f)
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(androidx.compose.ui.graphics.Color(0x66000000), MaterialTheme.colorScheme.background))))
        }
        BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding()) {
            val sideBySide = maxWidth >= 600.dp && maxWidth > maxHeight * 0.85f
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClose) { Icon(Icons.Rounded.KeyboardArrowDown, "Close") }
                    Spacer(Modifier.weight(1f))
                    if (b.addonId == "local" && vm.hasBook(b.id)) ModeSwitch(audio = true, onAudio = {}, onBook = { vm.openBook(b) })
                    if (vm.bookmarks.isNotEmpty()) TextButton({ marks = true }) { Icon(Icons.Rounded.Bookmarks, null); Spacer(Modifier.width(6.dp)); Text("${vm.bookmarks.size}") }
                }
                if (sideBySide) Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).fillMaxHeight().padding(16.dp), Alignment.Center) {
                        BoxWithConstraints(Modifier.fillMaxSize(), Alignment.Center) {
                            val side = minOf(maxWidth, maxHeight, 520.dp)
                            GestureCover(vm, b.cover, side)
                        }
                    }
                    controls(Modifier.weight(1f).widthIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(16.dp))
                } else Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.weight(1f).fillMaxWidth().padding(8.dp), Alignment.Center) {
                        BoxWithConstraints(Modifier.fillMaxSize(), Alignment.Center) {
                            val side = minOf(maxWidth, maxHeight, 520.dp)
                            GestureCover(vm, b.cover, side)
                        }
                    }
                    controls(Modifier.fillMaxWidth().widthIn(max = 560.dp).padding(bottom = 8.dp))
                }
            }
        }
    }

    if (chapters) ModalBottomSheet({ chapters = false }, containerColor = MaterialTheme.colorScheme.surface) {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val local = b.addonId == "local"
        LaunchedEffect(b.id) { if (local) dev.vox.audio.reader.Converter.prepareView(ctx, b.id) }
        val plan = if (local && dev.vox.audio.reader.Converter.plannedBook == b.id) dev.vox.audio.reader.Converter.planned else emptyList()
        val done = dev.vox.audio.reader.Converter.doneParts.toSet()
        val active = dev.vox.audio.reader.Converter.activePart
        val readyCount = done.size
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text(b.title, style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (plan.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text("$readyCount of ${plan.size} parts ready" + if (readyCount < plan.size) " · generating in the background" else "", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                if (plan.size <= 60) PartsBar(plan, done, active, height = 8.dp)
                else LinearProgressIndicator({ readyCount / plan.size.toFloat() }, Modifier.fillMaxWidth().height(8.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp)), color = VoxRed)
            }
        }
        val state = androidx.compose.foundation.lazy.rememberLazyListState()
        if (plan.isEmpty()) LazyColumn(state = state, contentPadding = PaddingValues(bottom = 24.dp)) {
            items(b.chapters.size) { i ->
                val c = b.chapters[i]; val cur = i == vm.chapter
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    leadingContent = { if (cur) Icon(Icons.Rounded.GraphicEq, null, tint = VoxRed) else Text("${i + 1}", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    headlineContent = { Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (cur) VoxRed else MaterialTheme.colorScheme.onSurface) },
                    supportingContent = if (c.durationSec > 0) ({ Text(fmtTime((c.durationSec * 1000f / sp).toLong())) }) else null,
                    modifier = Modifier.clickable { vm.gotoChapter(i); chapters = false })
            }
        } else {
            // chapters with their parts: ready parts play, the others are generated next when tapped
            val byChapter = plan.groupBy { it.chapter }
            val titles = remember(b.id) { dev.vox.audio.reader.Ebooks.list.firstOrNull { it.id == b.id } }
            val curPartId = b.chapters.getOrNull(vm.chapter)?.id ?: -1
            LazyColumn(state = state, contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
                byChapter.entries.forEachIndexed { idx, (ch, parts) ->
                    item(key = "c$ch") {
                        val ready = parts.count { it.id in done }
                        val isCur = parts.any { it.id == curPartId }
                        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(parts.first().title.substringBefore(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                                    color = if (isCur) VoxRed else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleSmall)
                                Text("$ready/${parts.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.height(6.dp))
                            PartsBar(parts, done, active)
                        }
                    }
                    if (parts.size > 1 || parts.first().id !in done) items(parts.size, key = { "p${parts[it].id}" }) { k ->
                        val p = parts[k]; val isDone = p.id in done; val cur = p.id == curPartId
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                            leadingContent = { when {
                                cur -> Icon(Icons.Rounded.GraphicEq, null, tint = VoxRed)
                                isDone -> Icon(Icons.Rounded.CheckCircle, null, tint = VoxRed.copy(alpha = .8f))
                                p.id == active -> CircularProgressIndicator(Modifier.size(20.dp), color = VoxRed, strokeWidth = 2.dp)
                                else -> Icon(Icons.Rounded.Schedule, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            } },
                            headlineContent = { Text("Part ${k + 1}", color = if (cur) VoxRed else MaterialTheme.colorScheme.onSurface) },
                            supportingContent = { Text(if (isDone) { val d = b.chapters.firstOrNull { it.id == p.id }?.durationSec ?: 0; if (d > 0) fmtTime((d * 1000f / sp).toLong()) else "Ready" } else if (p.id == active) "Generating now…" else "Not ready · tap to make this next") },
                            modifier = Modifier.clickable {
                                if (isDone) { vm.gotoChapter(vm.partIndex(p.id)); chapters = false }
                                else { vm.generateNext(b.id, p.id) }
                            })
                    }
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

/** Audio | Book: one tap moves between listening and reading the same book at the same sentence. */
@Composable
fun ModeSwitch(audio: Boolean, onAudio: () -> Unit, onBook: () -> Unit, dark: Boolean = true) {
    val on = VoxRed; val off = androidx.compose.ui.graphics.Color.Transparent
    val ink = if (dark) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color(0xFF2B2622)
    Row(Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(50)).background(ink.copy(alpha = .12f)).padding(3.dp)) {
        @Composable fun seg(sel: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, click: () -> Unit) {
            Row(Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(50)).background(if (sel) on else off).clickable(onClick = click)
                .padding(horizontal = 14.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(18.dp), tint = if (sel) androidx.compose.ui.graphics.Color.White else ink)
                Spacer(Modifier.width(6.dp))
                Text(label, color = if (sel) androidx.compose.ui.graphics.Color.White else ink, style = MaterialTheme.typography.labelLarge)
            }
        }
        seg(audio, Icons.Rounded.Headphones, "Audio", onAudio)
        seg(!audio, Icons.Rounded.MenuBook, "Book", onBook)
    }
}

/**
 * The cover is the remote: tap = play/pause, double-tap left/right = back 15 s / forward 30 s (with a visible pulse),
 * swipe left/right = next/previous part. It breathes (scales) with the playback state.
 */
@Composable
fun GestureCover(vm: VM, cover: String?, side: androidx.compose.ui.unit.Dp) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val scale by androidx.compose.animation.core.animateFloatAsState(if (vm.isPlaying) 1f else .9f, androidx.compose.animation.core.spring(.6f, 300f), label = "coverScale")
    val slide = remember { androidx.compose.animation.core.Animatable(0f) }
    val scope = rememberCoroutineScope()
    var flash by remember { mutableStateOf<Int?>(null) }        // -1 back, +1 forward
    LaunchedEffect(flash) { if (flash != null) { kotlinx.coroutines.delay(550); flash = null } }
    val density = androidx.compose.ui.platform.LocalDensity.current
    Box(Modifier.size(side).graphicsLayer { scaleX = scale; scaleY = scale; translationX = slide.value; rotationZ = slide.value / 60f }
        .pointerInput(Unit) {
            detectTapGestures(
                onTap = { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove); vm.togglePlay() },
                onDoubleTap = { o -> if (o.x < size.width / 2f) { vm.skip(-15_000); flash = -1 } else { vm.skip(30_000); flash = 1 }
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress) })
        }
        .draggable(androidx.compose.foundation.gestures.rememberDraggableState { d -> scope.launch { slide.snapTo(slide.value + d) } },
            androidx.compose.foundation.gestures.Orientation.Horizontal, onDragStopped = { v ->
                val th = with(density) { 90.dp.toPx() }
                when {
                    slide.value < -th || v < -1500f -> { slide.animateTo(-size(density, side) , androidx.compose.animation.core.tween(140)); vm.next(); slide.snapTo(size(density, side)); slide.animateTo(0f, androidx.compose.animation.core.spring(.8f, 350f)) }
                    slide.value > th || v > 1500f -> { slide.animateTo(size(density, side), androidx.compose.animation.core.tween(140)); vm.prev(); slide.snapTo(-size(density, side)); slide.animateTo(0f, androidx.compose.animation.core.spring(.8f, 350f)) }
                    else -> slide.animateTo(0f, androidx.compose.animation.core.spring(.7f, 400f))
                }
            })) {
        Cover(cover, Modifier.fillMaxSize().graphicsLayer { shadowElevation = 24f }, 20.dp)
        androidx.compose.animation.AnimatedVisibility(flash != null, Modifier.align(if (flash == -1) Alignment.CenterStart else Alignment.CenterEnd),
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(80)) + androidx.compose.animation.scaleIn(initialScale = .6f),
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(300))) {
            Box(Modifier.padding(20.dp).size(84.dp).clip(androidx.compose.foundation.shape.CircleShape).background(androidx.compose.ui.graphics.Color(0xAA000000)), Alignment.Center) {
                Icon(if (flash == -1) Icons.Rounded.Replay10 else Icons.Rounded.Forward30, null, Modifier.size(44.dp), tint = androidx.compose.ui.graphics.Color.White)
            }
        }
    }
}

private fun size(d: androidx.compose.ui.unit.Density, side: androidx.compose.ui.unit.Dp) = with(d) { side.toPx() }
