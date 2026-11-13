package dev.vox.audio.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import dev.vox.audio.VM

private data class Tab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)
private val tabs = listOf(Tab("Home", Icons.Rounded.Home), Tab("Search", Icons.Rounded.Search), Tab("My Vox", Icons.Rounded.Bookmarks), Tab("Settings", Icons.Rounded.Settings))

@Composable
fun VoxApp(vm: VM) {
    val w = LocalConfiguration.current.screenWidthDp
    val rail = w >= 600
    val twoPane = w >= 640          // Fold unfolded: list + detail side by side
    val reading = vm.reading
    val player = vm.playerOpen
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // bottom bar: hides while you scroll down, comes back after a short scroll up (or at the top)
    var barVisible by remember { mutableStateOf(true) }
    var acc by remember { mutableFloatStateOf(0f) }
    val hideOnScroll = remember {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                val dy = available.y
                if (dy == 0f) return androidx.compose.ui.geometry.Offset.Zero
                if ((dy < 0) != (acc < 0)) acc = 0f                       // direction changed: restart the count
                acc += dy
                if (acc < -60f) barVisible = false                         // scrolling down (content moving up)
                else if (acc > 40f) barVisible = true                      // a little scroll up
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }
    LaunchedEffect(tab, vm.detail, reading, player) { barVisible = true; acc = 0f }

    BackHandler(!player && vm.detail != null && !twoPane) { vm.closeDetail() }

    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxSize()) {
                if (rail) NavigationRail(Modifier.statusBarsPadding()) {
                    Spacer(Modifier.weight(1f))
                    tabs.forEachIndexed { i, t ->
                        NavigationRailItem(tab == i, { vm.playerOpen = false; if (!twoPane) vm.closeDetail(); tab = i }, { Icon(t.icon, t.label) }, label = { Text(t.label) })
                    }
                    Spacer(Modifier.weight(1f))
                }
                Column(Modifier.weight(1f)) {
                    // the player lives INSIDE this column, above the navigation: Home / Search / My Vox / Settings stay tappable and close the player
                    if (player) Box(Modifier.weight(1f)) { FullPlayer(vm, bottomInset = rail) { vm.playerOpen = false } }
                    else Box(Modifier.weight(1f).nestedScroll(hideOnScroll), Alignment.TopCenter) { Box(Modifier.widthIn(max = 1500.dp)) { androidx.compose.animation.Crossfade(tab, animationSpec = androidx.compose.animation.core.tween(180), label = "tab") { t -> Content(vm, t, twoPane) { vm.openEbook(it) } } } }
                    if (!player) MiniPlayer(vm) { vm.playerOpen = true }
                    if (!rail) androidx.compose.animation.AnimatedVisibility(barVisible,
                        enter = androidx.compose.animation.expandVertically(androidx.compose.animation.core.tween(220)) + fadeIn(androidx.compose.animation.core.tween(200)),
                        exit = androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(220)) + fadeOut(androidx.compose.animation.core.tween(160))) {
                        NavigationBar {
                            tabs.forEachIndexed { i, t ->
                                NavigationBarItem(tab == i, { vm.playerOpen = false; if (!twoPane) vm.closeDetail(); tab = i }, { Icon(t.icon, t.label) }, label = { Text(t.label) })
                            }
                        }
                    }
                }
            }
            var lastRead by remember { mutableStateOf<String?>(null) }
            if (reading != null) lastRead = reading
            AnimatedVisibility(reading != null, enter = fadeIn(androidx.compose.animation.core.tween(220)) + androidx.compose.animation.scaleIn(initialScale = .94f, animationSpec = androidx.compose.animation.core.tween(260)),
                exit = fadeOut(androidx.compose.animation.core.tween(160)) + androidx.compose.animation.scaleOut(targetScale = .96f)) {
                lastRead?.let { id -> ReaderScreen(vm, id) { vm.reading = null } }
            }
            vm.askBook?.let { id ->
                val meta = dev.vox.audio.reader.Ebooks.list.firstOrNull { it.id == id }
                AlertDialog({ vm.askBook = null },
                    icon = { Icon(Icons.Rounded.MenuBook, null) },
                    title = { Text(meta?.title ?: "Open book", maxLines = 2) },
                    text = { Text("Read it page by page, or listen to it as an audiobook? The audio is made on your phone, starts after the first minute and keeps generating while you listen. You can switch between the two at any time.") },
                    confirmButton = { Button({ vm.listenBook(id) }) { Icon(Icons.Rounded.Headphones, null); Spacer(Modifier.width(8.dp)); Text("Listen") } },
                    dismissButton = { Row { TextButton({ vm.askBook = null }) { Text("Cancel") }; TextButton({ vm.askBook = null; vm.reading = id }) { Icon(Icons.Rounded.MenuBook, null); Spacer(Modifier.width(8.dp)); Text("Read") } } })
            }
            AnimatedVisibility(vm.preparing && !player && reading == null, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp)) {
                Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 6.dp) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = VoxRed, strokeWidth = 2.dp); Spacer(Modifier.width(12.dp))
                        Text("Making the first minutes of audio…")
                    }
                }
            }
            if (!vm.onboarded) Onboarding(vm)
        }
    }
}

@Composable
private fun Content(vm: VM, tab: Int, twoPane: Boolean, onRead: (String) -> Unit) {
    val detail = vm.detail
    if (tab == 3) { AddonsScreen(vm); return }
    val list: @Composable () -> Unit = {
        when (tab) { 0 -> HomeScreen(vm) { vm.open(it) }; 1 -> SearchScreen(vm) { vm.open(it) }; else -> MyListScreen(vm) { vm.open(it) } }
    }
    if (twoPane) Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1.1f)) { list() }
        VerticalDivider()
        Box(Modifier.weight(1f)) {
            val fallback = vm.nowBook ?: vm.continueList.firstOrNull { it.addonId != "ebook" }
            if (detail != null) DetailScreen(vm, detail, null)
            else if (fallback != null) DetailScreen(vm, fallback, null)
            else Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("Pick an audiobook", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    } else androidx.compose.animation.AnimatedContent(detail, transitionSpec = {
        if (targetState != null) (androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(260)) { it / 4 } + fadeIn(androidx.compose.animation.core.tween(220))) togetherWith
            (androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(260)) { -it / 6 } + fadeOut(androidx.compose.animation.core.tween(160)))
        else (androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(260)) { -it / 6 } + fadeIn(androidx.compose.animation.core.tween(220))) togetherWith
            (androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(260)) { it / 4 } + fadeOut(androidx.compose.animation.core.tween(160)))
    }, label = "detail") { d -> if (d != null) DetailScreen(vm, d) { vm.closeDetail() } else list() }
}
