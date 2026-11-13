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
private val tabs = listOf(Tab("Home", Icons.Rounded.Home), Tab("Search", Icons.Rounded.Search), Tab("My Vox", Icons.Rounded.Bookmarks), Tab("Addons", Icons.Rounded.Extension))

@Composable
fun VoxApp(vm: VM) {
    val w = LocalConfiguration.current.screenWidthDp
    val rail = w >= 600
    val twoPane = w >= 700          // Fold unfolded: list + detail side by side
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var player by rememberSaveable { mutableStateOf(false) }

    BackHandler(player) { player = false }
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
                    Box(Modifier.weight(1f)) { Content(vm, tab, twoPane) }
                    MiniPlayer(vm) { player = true }
                    if (!rail) NavigationBar {
                        tabs.forEachIndexed { i, t ->
                            NavigationBarItem(tab == i, { tab = i }, { Icon(t.icon, t.label) }, label = { Text(t.label) })
                        }
                    }
                }
            }
            AnimatedVisibility(player, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
                FullPlayer(vm) { player = false }
            }
        }
    }
}

@Composable
private fun Content(vm: VM, tab: Int, twoPane: Boolean) {
    val detail = vm.detail
    if (tab == 3) { AddonsScreen(vm); return }
    val list: @Composable () -> Unit = {
        when (tab) { 0 -> HomeScreen(vm) { vm.open(it) }; 1 -> SearchScreen(vm) { vm.open(it) }; else -> MyListScreen(vm) { vm.open(it) } }
    }
    if (twoPane) Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1.1f)) { list() }
        VerticalDivider()
        Box(Modifier.weight(1f)) {
            if (detail != null) DetailScreen(vm, detail, null)
            else Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("Pick an audiobook", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    } else if (detail != null) DetailScreen(vm, detail) { vm.closeDetail() } else list()
}
