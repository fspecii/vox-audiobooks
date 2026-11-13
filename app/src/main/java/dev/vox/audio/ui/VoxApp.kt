package dev.vox.audio.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import dev.vox.audio.VM

private data class Tab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)
private val tabs = listOf(Tab("Home", Icons.Rounded.Home), Tab("Search", Icons.Rounded.Search), Tab("Books", Icons.Rounded.MenuBook), Tab("My Vox", Icons.Rounded.Bookmarks), Tab("Settings", Icons.Rounded.Settings))

@Composable
fun VoxApp(vm: VM) {
    val w = LocalConfiguration.current.screenWidthDp
    val rail = w >= 600
    val twoPane = w >= 640          // Fold unfolded: list + detail side by side
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val player = vm.playerOpen
    val reading = vm.reading

    BackHandler(player) { vm.playerOpen = false }
    BackHandler(!player && vm.detail != null && !twoPane) { vm.closeDetail() }

    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxSize()) {
                if (rail) NavigationRail(Modifier.statusBarsPadding()) {
                    Spacer(Modifier.weight(1f))
                    tabs.forEachIndexed { i, t ->
                        NavigationRailItem(tab == i, { tab = i }, { Icon(t.icon, t.label) }, label = { Text(t.label) })
                    }
                    Spacer(Modifier.weight(1f))
                }
                Column(Modifier.weight(1f)) {
                    Box(Modifier.weight(1f), Alignment.TopCenter) { Box(Modifier.widthIn(max = 1500.dp)) { Content(vm, tab, twoPane) { vm.reading = it } } }
                    MiniPlayer(vm) { vm.playerOpen = true }
                    if (!rail) NavigationBar {
                        tabs.forEachIndexed { i, t ->
                            NavigationBarItem(tab == i, { tab = i }, { Icon(t.icon, t.label) }, label = { Text(t.label) })
                        }
                    }
                }
            }
            reading?.let { id -> ReaderScreen(vm, id) { vm.reading = null } }
            AnimatedVisibility(player, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
                FullPlayer(vm) { vm.playerOpen = false }
            }
        }
    }
}

@Composable
private fun Content(vm: VM, tab: Int, twoPane: Boolean, onRead: (String) -> Unit) {
    val detail = vm.detail
    if (tab == 4) { AddonsScreen(vm); return }
    if (tab == 2) { BooksScreen(vm, onRead); return }
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
    } else if (detail != null) DetailScreen(vm, detail) { vm.closeDetail() } else list()
}
