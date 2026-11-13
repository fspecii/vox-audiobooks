package dev.vox.audio.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.VertexMode
import androidx.compose.ui.graphics.Vertices
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Page-curl pager modelled on the renderer inside the Kindle app (a cylinder "curl mesh"):
 * the page is peeled from its right edge, wrapped around a rolling cylinder, and its BACK
 * face is shown (mirrored, paper coloured, with a faint ghost of the text) while the next
 * page waits underneath. Progress is one float, so swiping back un-rolls the same curl.
 */
@Stable
class CurlState(initial: Int) {
    val progress = Animatable(initial.toFloat())
    var settled by mutableIntStateOf(initial)
        internal set
    internal var maxIdx = 0
    /** vertical position (0..1) of the finger / corner that peels the page: tilts the fold line */
    var curlY by mutableFloatStateOf(0.62f)

    suspend fun goTo(page: Int) {
        val t = page.coerceIn(0, maxIdx).toFloat()
        val d = abs(t - progress.value).coerceAtMost(1f)
        progress.animateTo(t, tween((300 + 260 * d).toInt(), easing = FastOutSlowInEasing))
        settled = t.roundToInt()
    }

    suspend fun jump(page: Int) {
        val t = page.coerceIn(0, maxIdx)
        progress.snapTo(t.toFloat())
        settled = t
    }
}

@Composable
fun rememberCurlState(initial: Int = 0) = remember { CurlState(initial) }

@Composable
fun CurlPager(
    count: Int,
    state: CurlState,
    paper: Color,
    modifier: Modifier = Modifier,
    onTap: (x: Float, width: Float) -> Unit,
    contentKey: Any? = null,
    page: @Composable (Int) -> Unit,
) {
    val maxIdx = (count - 1).coerceAtLeast(0)
    state.maxIdx = maxIdx
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val p = state.progress
    var widthPx by remember { mutableFloatStateOf(1f) }
    var heightPx by remember { mutableFloatStateOf(1f) }
    val layers = remember { HashMap<Int, GraphicsLayer>() }
    var version by remember { mutableIntStateOf(0) }

    fun radius(w: Float) = min(0.115f * w, with(density) { 84.dp.toPx() })
    fun travel(w: Float) = w + (PI.toFloat() + 1f) * radius(w)

    fun atRest() = abs(p.value - p.value.roundToInt()) < 0.002f

    val base by remember { derivedStateOf { floor(p.value + 1e-4f).toInt().coerceIn(0, maxIdx) } }
    val resting by remember { derivedStateOf { p.value.roundToInt().coerceIn(0, maxIdx) } }

    LaunchedEffect(count) { if (p.value > maxIdx) state.jump(maxIdx) }

    // textures of the pages that can be peeled (the resting page, and the one before it for a back-swipe)
    val textures = remember { HashMap<Int, Pair<ImageBitmap, Paint>>() }
    var texVersion by remember { mutableIntStateOf(0) }
    suspend fun capture(i: Int) {
        val l = layers[i] ?: return
        runCatching {
            val bmp = l.toImageBitmap()
            val paint = Paint().apply { shader = ImageShader(bmp, TileMode.Clamp, TileMode.Clamp); isAntiAlias = true; color = Color.White }
            textures[i] = bmp to paint
            texVersion++
        }
    }
    LaunchedEffect(resting, count, paper, contentKey) {
        delay(140); capture(resting); capture(resting - 1)
        delay(900); capture(resting)            // pick up images that finished loading
        textures.keys.removeAll { abs(it - resting) > 1 }
    }
    val mesh = remember { CurlMesh(64, 14) }

    Box(
        modifier
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f); heightPx = it.height.toFloat().coerceAtLeast(1f) }
            .pointerInput(count) {
                // Runs in the INITIAL pass, i.e. before the text under the finger sees anything, so a swipe or
                // tap anywhere on the page always turns it. Only a clean tap that lands on a link reaches the link.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    state.curlY = (down.position.y / heightPx).coerceIn(0.25f, 0.9f)
                    val vt = VelocityTracker(); vt.addPosition(down.uptimeMillis, down.position)
                    val slop = viewConfiguration.touchSlop
                    var dragging = false
                    var last = down.position
                    var sign = 0f
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                        if (!dragging) {
                            if (!ch.pressed) {
                                // finger lifted without moving: a tap. Let children (links) react first, then check.
                                val main = awaitPointerEvent(PointerEventPass.Main)
                                val c2 = main.changes.firstOrNull { it.id == down.id }
                                if (c2 != null && !c2.isConsumed && !p.isRunning) onTap(ch.position.x, widthPx)
                                return@awaitEachGesture
                            }
                            val moved = ch.position - down.position
                            if (abs(moved.x) > slop || abs(moved.y) > slop) { dragging = true; last = ch.position } else continue
                        }
                        if (!ch.pressed) {
                            val v = vt.calculateVelocity().x
                            ch.consume()
                            scope.launch {
                                val cur = p.value
                                val f = cur - floor(cur)
                                val target = when {
                                    v < -700f -> ceil(cur)
                                    v > 700f -> floor(cur)
                                    sign < 0 -> if (f > 0.28f) ceil(cur) else floor(cur)   // peeling forward
                                    else -> if (f < 0.72f) floor(cur) else ceil(cur)       // peeling back
                                }.toInt()
                                state.goTo(target)
                            }
                            return@awaitEachGesture
                        }
                        val dx = ch.position.x - last.x
                        last = ch.position
                        vt.addPosition(ch.uptimeMillis, ch.position)
                        if (dx != 0f) sign = if (dx < 0) -1f else 1f
                        state.curlY = (0.8f * state.curlY + 0.2f * (ch.position.y / heightPx)).coerceIn(0.25f, 0.9f)
                        scope.launch { p.snapTo((p.value - dx / (0.85f * travel(widthPx))).coerceIn(0f, maxIdx.toFloat())) }
                        ch.consume()   // children (text, links) now see a cancelled touch
                    }
                }
            }
    ) {
        // Pages around the current one stay composed (so images are warm) but are only ever
        // drawn by the curl renderer below. The resting page is composed last so it owns touches (links).
        val lo = (base - 1).coerceAtLeast(0)
        val hi = (base + 2).coerceAtMost(maxIdx)
        val order = (lo..hi).filter { it != resting } + resting
        if (count > 0) for (i in order) key(i) { PageHost(i, layers, paper, { version++ }, { atRest() && i == p.value.roundToInt().coerceIn(0, maxIdx) }) { page(i) } }

        Canvas(Modifier.fillMaxSize()) {
            @Suppress("UNUSED_EXPRESSION") version
            @Suppress("UNUSED_EXPRESSION") texVersion
            if (count == 0) return@Canvas
            val k = base
            val f = (p.value - k).coerceIn(0f, 1f)
            if (f < 0.002f || k >= maxIdx) return@Canvas      // at rest the page host draws itself
            drawRect(paper)
            run {
                val tex = textures[k]
                if (tex != null) drawCurlMesh(mesh, tex.second, layers[k + 1], f, paper, radius(size.width), state.curlY, density.density)
                else drawCurl(layers[k], layers[k + 1], f, paper, radius(size.width), density.density)
            }
        }
    }
}

@Composable
private fun PageHost(
    index: Int, layers: MutableMap<Int, GraphicsLayer>, paper: Color, onReady: () -> Unit,
    direct: () -> Boolean,
    content: @Composable () -> Unit,
) {
    val layer = rememberGraphicsLayer()
    DisposableEffect(layer) {
        layers[index] = layer
        onReady()
        onDispose { if (layers[index] === layer) layers.remove(index) }
    }
    Box(
        Modifier.fillMaxSize()
            .drawWithContent {
                layer.record { this@drawWithContent.drawContent() }
                // the resting page paints itself, so any change to it (theme, text size, images) shows immediately;
                // the curl overlay only takes over while a page is being turned
                if (direct()) drawLayer(layer)
            }
            .background(paper)
    ) { content() }
}

private fun DrawScope.drawCurl(
    cur: GraphicsLayer?, under: GraphicsLayer?, f: Float, paper: Color, R: Float, dens: Float,
) {
    val W = size.width
    val H = size.height
    val pi = PI.toFloat()
    val D = W + (pi + 1f) * R
    val xc = W - f * D                       // where the page starts to roll
    val sMax = W - xc                        // arc length of the part that is lifted

    // 1. the page that is being uncovered, slightly dimmed until the curl passes
    if (under != null) drawLayer(under)
    drawRect(Color.Black.copy(alpha = 0.18f * (1f - f)))

    // rightmost point reached by the rolling cylinder, for the shadow it throws on the next page
    val xr = xc + R * sin(min(sMax, pi * R / 2f) / R)
    val sh = 34f * dens
    if (xr < W) {
        drawRect(
            Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.34f), Color.Transparent), startX = xr, endX = xr + sh),
            topLeft = Offset(xr, 0f), size = Size(min(sh, W - xr), H),
        )
    }

    // 2. the flat, untouched part of the current page
    val flatR = xc.coerceAtMost(W)
    if (flatR > 0f && cur != null) {
        clipRect(0f, 0f, flatR, H) {
            drawRect(paper)
            drawLayer(cur)
        }
        // shadow the folded-over back face drops on the page it lies on
        if (sMax > pi * R) {
            val xe = xc - (sMax - pi * R)
            val w = 26f * dens
            clipRect(0f, 0f, flatR, H) {
                drawRect(
                    Brush.horizontalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.26f)), startX = xe - w, endX = xe),
                    topLeft = Offset(xe - w, 0f), size = Size(w, H),
                )
            }
        }
    }

    if (cur == null) return

    // 3. the curled part, strip by strip in order of increasing height above the page
    val sStart = max(0f, -xc)
    val cylEnd = min(sMax, pi * R)
    if (cylEnd > sStart) {
        val n = max(2, ceil((cylEnd - sStart) / (R * 0.11f)).toInt())
        val step = (cylEnd - sStart) / n
        for (i in 0 until n) strip(cur, xc, sStart + i * step, sStart + (i + 1) * step, R, paper, H)
    }
    if (sMax > pi * R) strip(cur, xc, max(sStart, pi * R), sMax, R, paper, H)
}

private fun DrawScope.strip(cur: GraphicsLayer, xc: Float, s0: Float, s1: Float, R: Float, paper: Color, H: Float) {
    val pi = PI.toFloat()
    fun mapX(s: Float) = if (s <= pi * R) xc + R * sin(s / R) else xc - (s - pi * R)
    val xs0 = xc + s0
    val xs1 = xc + s1
    val d0 = mapX(s0)
    val d1 = mapX(s1)
    val w = xs1 - xs0
    if (w <= 0f) return
    val sx = (d1 - d0) / w
    val theta = (((s0 + s1) / 2f) / R).coerceIn(0f, pi)
    val lift = (1f - cos(theta)) / 2f                    // 0 on the page, 1 at the top of the roll
    val sy = 1f + 0.045f * lift
    val l = min(d0, d1) - 0.6f
    val r = max(d0, d1) + 0.6f
    if (r < 0f || l > size.width) return

    clipRect(l, 0f, r, H) {
        withTransform({
            translate(left = d0 - xs0 * sx)
            scale(scaleX = sx, scaleY = sy, pivot = Offset(0f, H / 2f))
        }) { drawLayer(cur) }

        if (theta < pi / 2f) {
            // front of the roll: darker as it turns away from the light
            drawRect(Color.Black.copy(alpha = 0.46f * sin(theta)))
        } else {
            // back of the sheet: blank paper with only a faint ghost of the mirrored text
            drawRect(paper.copy(alpha = 0.86f))
            val hl = exp(-(((theta - 2.35f) / 0.45f) * ((theta - 2.35f) / 0.45f)))
            drawRect(Color.White.copy(alpha = 0.34f * hl))
            drawRect(Color.Black.copy(alpha = 0.07f + 0.10f * (1f - hl) * (pi - theta) / (pi / 2f)))
        }
    }
}


/** A grid that is bent around a rolling cylinder; see drawCurlMesh. */
private class CurlMesh(val nx: Int, val ny: Int) {
    val vc = (nx + 1) * (ny + 1)
    val px = FloatArray(vc); val py = FloatArray(vc)
    val ox = FloatArray(vc); val oy = FloatArray(vc)
    val th = FloatArray(vc); val sv = FloatArray(vc)
    var w = 0f; var h = 0f
    var tex: List<Offset> = emptyList()

    fun layout(W: Float, H: Float) {
        if (W == w && H == h) return
        w = W; h = H
        for (iy in 0..ny) for (ix in 0..nx) { val i = iy * (nx + 1) + ix; px[i] = W * ix / nx; py[i] = H * iy / ny }
        tex = List(vc) { Offset(px[it], py[it]) }
    }

    /** Wrap the page around a cylinder of radius R whose axis is perpendicular to n=(cos phi, sin phi), through (xc, H/2). */
    fun fold(xc: Float, phi: Float, R: Float) {
        val pi = PI.toFloat(); val cn = cos(phi); val sn = sin(phi)
        val cx = w / 2f; val cy = h / 2f
        for (i in 0 until vc) {
            val s = (px[i] - xc) * cn + (py[i] - cy) * sn
            sv[i] = s
            var x = px[i]; var y = py[i]; var t = 0f
            if (s > 0f) {
                if (s < pi * R) { t = s / R; val d = R * sin(t) - s; x += cn * d; y += sn * d }
                else { t = pi; val d = -(s - pi * R) - s; x += cn * d; y += sn * d }
                val lift = (1f - cos(t)) / 2f
                val sc = 1f + 0.05f * lift
                x = cx + (x - cx) * sc; y = cy + (y - cy) * sc
            }
            ox[i] = x; oy[i] = y; th[i] = t
        }
    }
}

private fun DrawScope.drawCurlMesh(
    m: CurlMesh, texPaint: Paint, under: GraphicsLayer?, f: Float, paper: Color, R: Float, curlY: Float, dens: Float,
) {
    val W = size.width; val H = size.height
    val pi = PI.toFloat()
    m.layout(W, H)
    val phi = ((curlY - 0.5f) * 0.95f).coerceIn(-0.42f, 0.42f)
    val ramp = (0.55f + 0.45f * min(1f, f / 0.25f))          // the roll tightens in as it starts
    val r = R * ramp
    val tn = abs(sin(phi) / cos(phi))
    val xc0 = W + (H / 2f) * tn + 2f
    val xcEnd = -(pi * r + (H / 2f) * tn + r + 8f)
    val xc = xc0 - f * (xc0 - xcEnd)
    m.fold(xc, phi, r)
    val cn = cos(phi); val sn = sin(phi)

    // the page being uncovered
    if (under != null) drawLayer(under)
    drawRect(Color.Black.copy(alpha = 0.20f * (1f - f)))

    val nx = m.nx; val ny = m.ny; val stride = nx + 1
    // cells sorted by height above the page; split into front-facing and back-facing
    val cells = ArrayList<Int>(nx * ny)
    val keys = FloatArray(nx * ny)
    for (iy in 0 until ny) for (ix in 0 until nx) {
        val a = iy * stride + ix
        val c = iy * nx + ix
        keys[c] = (m.sv[a] + m.sv[a + 1] + m.sv[a + stride] + m.sv[a + stride + 1]) / 4f
        cells += c
    }
    cells.sortBy { keys[it] }
    val front = ArrayList<Int>(); val back = ArrayList<Int>(); val curled = ArrayList<Int>()
    for (c in cells) {
        val ix = c % nx; val iy = c / nx
        val a = iy * stride + ix
        val t = (m.th[a] + m.th[a + 1] + m.th[a + stride] + m.th[a + stride + 1]) / 4f
        val tri = intArrayOf(a, a + 1, a + stride, a + 1, a + stride + 1, a + stride)
        if (t > pi / 2f) { for (v in tri) back += v } else { for (v in tri) front += v }
        if (t > 0.01f) for (v in tri) curled += v
    }
    val pos = List(m.vc) { Offset(m.ox[it], m.oy[it]) }

    fun verts(idx: List<Int>, colors: List<Color>) = Vertices(VertexMode.Triangles, pos, m.tex, colors, idx)
    val white = Paint().apply { color = Color.White; isAntiAlias = true }

    drawIntoCanvas { canvas ->
        // 1. shadow the curl throws on the next page (soft: three offset passes)
        if (curled.isNotEmpty()) {
            val black = List(m.vc) { Color.Black }
            val sv = verts(curled, black)
            for ((d, a) in listOf(3f to 0.07f, 7f to 0.06f, 12f to 0.055f, 18f to 0.05f, 25f to 0.04f, 33f to 0.035f)) {
                val p = Paint().apply { color = Color.White.copy(alpha = a * (0.5f + 0.5f * (1f - f))); isAntiAlias = true }
                canvas.save(); canvas.translate(cn * d * dens, sn * d * dens)
                canvas.drawVertices(sv, BlendMode.Modulate, p)
                canvas.restore()
            }
        }
        // 2. front of the page (flat part + the near side of the roll), lit by angle
        if (front.isNotEmpty()) {
            val cols = List(m.vc) { i ->
                val t = m.th[i]; val g = (1f - 0.46f * sin(min(t, pi)).pow(1.25f)).coerceIn(0f, 1f)
                Color(g, g, g, 1f)
            }
            canvas.drawVertices(verts(front, cols), BlendMode.Modulate, texPaint)
        }
        if (back.isNotEmpty()) {
            val backV = verts(back, List(m.vc) { Color.Black })
            // 3. shadow of the folded-over back face on the page below it
            for ((d, a) in listOf(-3f to 0.10f, -6f to 0.08f, -10f to 0.06f, -15f to 0.05f, -21f to 0.04f)) {
                val p = Paint().apply { color = Color.White.copy(alpha = a); isAntiAlias = true }
                canvas.save(); canvas.translate(cn * d * dens, (sn * d + 2f) * dens)
                canvas.drawVertices(backV, BlendMode.Modulate, p)
                canvas.restore()
            }
            // 4. the back of the sheet: blank paper, brighter where the roll catches the light
            val baseCols = List(m.vc) { i ->
                val t = m.th[i]
                val hl = exp(-(((t - 2.3f) / 0.5f) * ((t - 2.3f) / 0.5f)))
                val g = (0.90f + 0.10f * hl).coerceIn(0f, 1f)
                Color(paper.red * g, paper.green * g, paper.blue * g, 1f)
            }
            canvas.drawVertices(verts(back, baseCols), BlendMode.Modulate, white)
            // 5. the mirrored ghost of the text showing through the paper
            val ghost = List(m.vc) { Color(0.45f, 0.45f, 0.45f, 0.16f) }
            canvas.drawVertices(verts(back, ghost), BlendMode.Modulate, texPaint)
        }
    }
}
