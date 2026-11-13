package dev.vox.audio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.vox.audio.data.Book

@Composable
fun Cover(url: String?, modifier: Modifier = Modifier, radius: Dp = 8.dp) {
    Box(modifier.clip(RoundedCornerShape(radius)).background(MaterialTheme.colorScheme.surfaceVariant), Alignment.Center) {
        Icon(Icons.Rounded.Headphones, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .4f))
        if (url != null) AsyncImage(url, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
    }
}

fun sourceBadge(addonId: String): String? = when {
    addonId == "torrent" -> "TORRENT"
    addonId == "youtube" -> "YOUTUBE"
    addonId == "local" -> "AI VOICE"
    addonId == "ebook" -> "BOOK"
    addonId == "ebook+audio" -> "BOOK + AUDIO"
    else -> null
}

/** Netflix-style tile: cover, optional watched-progress bar, source badge. */
@Composable
fun BookCard(b: Book, modifier: Modifier = Modifier, progress: Float? = null, showText: Boolean = true, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)) {
        Box {
            Cover(b.cover, Modifier.fillMaxWidth().aspectRatio(1f))
            sourceBadge(b.addonId)?.let {
                Text(it, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color_White,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(4.dp))
                        .background(VoxRed).padding(horizontal = 5.dp, vertical = 2.dp))
            }
            if (progress != null) LinearProgressIndicator(progress = { progress },
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                color = VoxRed, trackColor = androidx.compose.ui.graphics.Color(0x66000000))
        }
        if (showText) {
            Spacer(Modifier.height(6.dp))
            // fixed text block (2 title lines + 1 author line) so every tile in every row has the same height
            Text(displayTitle(b.title), style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.heightIn(min = 40.dp), lineHeight = 20.sp)
            Text(b.author.ifBlank { " " }, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private val Color_White = androidx.compose.ui.graphics.Color.White

fun fmtTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600; val m = s % 3600 / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** "2h 05m" / "45m" for a number of seconds. */
fun fmtDur(sec: Long): String {
    val m = (sec / 60).coerceAtLeast(0); return if (m >= 60) "${m / 60}h ${"%02d".format(m % 60)}m" else "${m}m"
}

/** Segmented progress of an on-device audiobook: one block per part, grouped by chapter. Red = ready, pulsing = generating now. */
@Composable
fun PartsBar(chapterParts: List<dev.vox.audio.reader.Part>, done: Set<Int>, active: Int, modifier: Modifier = Modifier, height: Dp = 6.dp) {
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "gen")
    val a by pulse.animateFloat(.35f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(700), androidx.compose.animation.core.RepeatMode.Reverse), label = "a")
    Row(modifier.fillMaxWidth().height(height), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        chapterParts.forEach { p ->
            val c = when {
                p.id in done -> VoxRed
                p.id == active -> VoxRed.copy(alpha = a)
                else -> MaterialTheme.colorScheme.onSurface.copy(alpha = .16f)
            }
            Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(c))
        }
    }
}

/** Subtitles and long catalogue titles are cut: "Moby Dick / or, The Whale" -> "Moby Dick", at most 44 characters. */
fun displayTitle(t: String): String {
    val c = t.substringBefore(" / ").substringBefore(" : ").substringBefore(" — ").substringBefore(" (").trim().ifBlank { t }
    return if (c.length > 44) c.take(42).trimEnd(' ', ',', ':', ';') + "…" else c
}
