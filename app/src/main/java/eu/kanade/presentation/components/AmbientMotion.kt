package eu.kanade.presentation.components

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlin.math.sin

// ANZ -->
/*
 * Shared "always moving" motion for the colourful page backgrounds.
 *
 * One clock ([rememberAuroraPhase]) drives everything: a number that runs from 0 to 1 every 2 minutes.
 * Every movement uses a whole number of cycles per loop (see [auroraCycle]), so it wraps seamlessly,
 * and every consumer reads it in the DRAW phase only, so nothing recomposes per frame.
 */

private const val AuroraLoopSeconds = 120f
private const val TwoPi = 6.2831855f

/** The clock of the screen that is currently on show. The default never moves. */
val LocalAuroraPhase = staticCompositionLocalOf<State<Float>> { mutableFloatStateOf(0f) }

/**
 * Looping clock in 0..1. It only runs while the app is on screen (lifecycle STARTED): in the
 * background there is no loop at all, and it resumes from where it stopped. It also stays still when
 * the system animations are turned off.
 */
@Composable
fun rememberAuroraPhase(): State<Float> {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val phase = remember { mutableFloatStateOf(0f) }
    var started by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                started = true
            } else if (event == Lifecycle.Event.ON_STOP) {
                started = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val motionAllowed = remember(context) { animationsEnabled(context) }
    LaunchedEffect(started, motionAllowed) {
        if (!started || !motionAllowed) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            // a long stall (e.g. a heavy frame) must not make the lights jump
            val delta = (now - last).coerceIn(0L, 100_000_000L)
            last = now
            var next = phase.floatValue + delta / (AuroraLoopSeconds * 1_000_000_000f)
            if (next >= 1f) next -= 1f
            phase.floatValue = next
        }
    }
    return phase
}

private fun animationsEnabled(context: Context): Boolean = try {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
} catch (_: Exception) {
    true
}

/** Smooth wave of [cycles] whole turns per loop, shifted by [shift] turns. Result in -1..1. */
fun auroraCycle(phase: Float, cycles: Int, shift: Float = 0f): Float =
    sin(TwoPi * (cycles * phase + shift))

private class Blob(
    val kx: Int,
    val ky: Int,
    val shift: Float,
    val baseX: Float,
    val baseY: Float,
    val rangeX: Float,
    val rangeY: Float,
    val radius: Float,
    val alpha: Float,
)

// Cycles per 120 s loop: 8 -> 15 s, 7 -> 17 s, 6 -> 20 s, 5 -> 24 s, 4 -> 30 s. Different horizontal and
// vertical counts make each light trace a slow loop instead of a plain circle.
private val Blobs = listOf(
    Blob(kx = 8, ky = 5, shift = 0.00f, baseX = 0.20f, baseY = 0.14f, rangeX = 0.34f, rangeY = 0.10f, radius = 0.62f, alpha = 0.46f),
    Blob(kx = 6, ky = 7, shift = 0.31f, baseX = 0.85f, baseY = 0.50f, rangeX = 0.32f, rangeY = 0.14f, radius = 0.62f, alpha = 0.38f),
    Blob(kx = 5, ky = 8, shift = 0.67f, baseX = 0.20f, baseY = 0.84f, rangeX = 0.34f, rangeY = 0.12f, radius = 0.62f, alpha = 0.34f),
    Blob(kx = 7, ky = 4, shift = 0.48f, baseX = 0.78f, baseY = 0.18f, rangeX = 0.28f, rangeY = 0.10f, radius = 0.50f, alpha = 0.26f),
)

/**
 * Page backdrop: a dark coloured vertical base ([top] -> [mid] -> [deep]) with four soft lights in
 * neighbouring hues of [seed] drifting slowly over it, so the colours keep flowing.
 *
 * Built to be cheap: it lives in its own layer (the per-frame redraw never touches the content above
 * it), the gradients are built once per size / colour change and only translated per frame, the lights
 * are small circles instead of full-screen rectangles, and there is no blur.
 *
 * @param glow 0.65..1, how strongly the lights show (lower while no cover colour is known yet).
 * @param intensity extra scale for screens that need calmer lights behind lots of text.
 */
@Composable
fun AuroraBackdrop(
    top: Color,
    mid: Color,
    deep: Color,
    seed: Color,
    glow: Float,
    phase: State<Float>,
    modifier: Modifier = Modifier,
    intensity: Float = 1f,
) {
    Box(
        modifier = modifier
            .graphicsLayer { }
            .drawWithCache {
                val w = size.width
                val h = size.height
                val strength = glow * intensity
                val base = Brush.verticalGradient(0f to top, 0.45f to mid, 1f to deep)
                val lights = listOf(
                    lerp(seed, Color.White, 0.12f),
                    auroraHueShift(seed, 55f),
                    auroraHueShift(seed, -45f),
                    auroraHueShift(seed, 150f),
                )
                val radii = FloatArray(Blobs.size) { w * Blobs[it].radius }
                val brushes = List(Blobs.size) { i ->
                    val c = lights[i]
                    Brush.radialGradient(
                        colors = listOf(
                            c.copy(alpha = (Blobs[i].alpha * strength).coerceIn(0f, 1f)),
                            c.copy(alpha = 0f),
                        ),
                        center = Offset.Zero,
                        radius = radii[i],
                    )
                }
                onDrawBehind {
                    drawRect(brush = base)
                    val t = phase.value
                    for (i in Blobs.indices) {
                        val b = Blobs[i]
                        val x = w * (b.baseX + b.rangeX * auroraCycle(t, b.kx, b.shift))
                        val y = h * (b.baseY + b.rangeY * auroraCycle(t, b.ky, b.shift + 0.25f))
                        translate(left = x, top = y) {
                            drawCircle(brush = brushes[i], radius = radii[i], center = Offset.Zero)
                        }
                    }
                }
            },
    )
}

private fun auroraHueShift(color: Color, degrees: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgb(), hsv)
    hsv[0] = ((hsv[0] + degrees) % 360f + 360f) % 360f
    hsv[1] = (hsv[1] * 1.1f).coerceIn(0f, 1f)
    hsv[2] = hsv[2].coerceAtLeast(0.55f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/**
 * Slide-up + fade-in the first time an item shows up. [seen] remembers the keys that already played,
 * so scrolling an item away and back does not replay it. [delayMillis] staggers rows that appear together.
 */
@Composable
fun Modifier.revealOnce(seen: MutableSet<Any>, key: Any, delayMillis: Int = 0): Modifier {
    val firstTime = remember(key) { seen.add(key) }
    val progress = remember(key) { Animatable(if (firstTime) 0f else 1f) }
    LaunchedEffect(key) {
        if (firstTime) {
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 520, delayMillis = delayMillis, easing = FastOutSlowInEasing),
            )
        }
    }
    return this.graphicsLayer {
        val p = progress.value
        alpha = p
        translationY = (1f - p) * 36.dp.toPx()
    }
}
// ANZ <--
