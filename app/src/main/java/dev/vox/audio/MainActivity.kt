package dev.vox.audio

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dev.vox.audio.ui.VoxApp
import dev.vox.audio.ui.VoxTheme

class MainActivity : ComponentActivity() {
    private val vm: VM by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        handle(intent)
        setContent { VoxTheme { VoxApp(vm) } }
    }

    override fun onStop() { vm.saveProgress(); super.onStop() }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handle(intent) }

    /** stremio://… or vox://… links install an addon. */
    private fun handle(i: Intent?) {
        val d = i?.data ?: return
        if (d.scheme == "stremio" || d.scheme == "vox") vm.installAddon(d.toString())
    }
}
