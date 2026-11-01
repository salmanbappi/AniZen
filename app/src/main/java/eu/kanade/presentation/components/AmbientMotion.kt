package eu.kanade.presentation.components

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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlin.math.hypot
import kotlin.math.roundToInt
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

// A slow drift does not need more than ~45-60 updates per second: on 90/120 Hz screens every other frame is skipped.
private const val MinStepNanos = 12_000_000L

// How much of the page the cover mosaic covers (the rest is the dark coloured base showing through).
private const val MosaicAlpha = 0.80f

/** The backdrop is painted at 1/3 of the screen resolution and stretched back: 9x fewer pixels to shade. */
private const val LowRes = 3

/** The clock of the screen that is currently on show. The default never moves. */
val LocalAuroraPhase = staticCompositionLocalOf<State<Float>> { mutableFloatStateOf(0f) }

/**
 * Looping clock in 0..1. It only runs while the app is on screen (lifecycle STARTED): in the background
 * there is no loop at all, and it resumes from where it stopped. It does NOT depend on the system
 * animation-scale setting, so the colours keep moving even if the phone has animations turned down.
 */
@Composable
fun rememberAuroraPhase(): State<Float> {
    val lifecycleOwner = LocalLifecycleOwner.current
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
    LaunchedEffect(started) {
        if (!started) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val elapsed = now - last
            if (elapsed < MinStepNanos) continue
            last = now
            // a long stall (e.g. a heavy frame) must not make the lights jump
            val delta = elapsed.coerceAtMost(100_000_000L)
            var next = phase.floatValue + delta / (AuroraLoopSeconds * 1_000_000_000f)
            if (next >= 1f) next -= 1f
            phase.floatValue = next
        }
    }
    return phase
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

// Cycles per 120 s loop: 11 -> 11 s, 10 -> 12 s, 9 -> 13 s, 8 -> 15 s, 7 -> 17 s, 6 -> 20 s. Different horizontal
// and vertical counts make each light trace a slow loop instead of a plain circle.
private val Blobs = listOf(
    Blob(kx = 10, ky = 7, shift = 0.00f, baseX = 0.22f, baseY = 0.16f, rangeX = 0.36f, rangeY = 0.12f, radius = 0.80f, alpha = 0.58f),
    Blob(kx = 8, ky = 9, shift = 0.31f, baseX = 0.84f, baseY = 0.46f, rangeX = 0.34f, rangeY = 0.16f, radius = 0.80f, alpha = 0.50f),
    Blob(kx = 7, ky = 11, shift = 0.67f, baseX = 0.20f, baseY = 0.80f, rangeX = 0.36f, rangeY = 0.14f, radius = 0.80f, alpha = 0.46f),
    Blob(kx = 9, ky = 6, shift = 0.48f, baseX = 0.78f, baseY = 0.20f, rangeX = 0.30f, rangeY = 0.12f, radius = 0.62f, alpha = 0.36f),
)

/**
 * A "flow": soft diagonal ribbons of two lights that glide across the whole screen, so the colour of every
 * part of the page keeps changing. [cycles] whole periods are travelled per loop (negative = other way).
 */
private class Flow(
    val angle: Float,
    val period: Float,
    val cycles: Int,
    val alphaA: Float,
    val alphaB: Float,
    val colorA: Int,
    val colorB: Int,
)

// Period 1.25 screens every 15 s and 1.8 screens every 24 s, crossing each other: they never repeat the same picture.
private val Flows = listOf(
    Flow(angle = 26f, period = 1.25f, cycles = 8, alphaA = 0.27f, alphaB = 0.23f, colorA = 0, colorB = 1),
    Flow(angle = -34f, period = 1.80f, cycles = -5, alphaA = 0.21f, alphaB = 0.18f, colorA = 2, colorB = 3),
)

// The ribbons are sampled every 1/16 of the period. Their opacity swells and fades (sin^2) but never drops
// below RibbonFloor, so there are no dark gaps between them, and their colour blends from [a] to [b] and back.
private const val RibbonSteps = 16
private const val RibbonFloor = 0.30f

private fun ribbonStops(a: Color, alphaA: Float, b: Color, alphaB: Float): Array<Pair<Float, Color>> =
    Array(RibbonSteps + 1) { i ->
        val u = i / RibbonSteps.toFloat()
        val wave = sin(TwoPi * u)
        val toB = 0.5f - 0.5f * wave
        val swell = RibbonFloor + (1f - RibbonFloor) * wave * wave
        val alpha = ((alphaA * (1f - toB) + alphaB * toB) * swell).coerceIn(0f, 1f)
        u to lerp(a, b, toB).copy(alpha = alpha)
    }

/** The four lights used for a single [seed] colour: the colour itself and neighbouring hues of it. */
fun auroraLightsFor(seed: Color): List<Color> = listOf(
    lerp(seed, Color.White, 0.10f),
    auroraHueShift(seed, 38f),
    auroraHueShift(seed, -34f),
    auroraHueShift(seed, 140f),
)

/**
 * Page backdrop: a dark coloured vertical base ([top] -> [mid] -> [deep]) with two crossing flows of
 * coloured ribbons and four big soft lights drifting over it, all in neighbouring hues of [seed].
 *
 * Built to be cheap: it lives in its own layer that is painted at 1/3 of the resolution (the soft colours
 * do not need more) and stretched back up, so the per-frame redraw never touches the content above it and
 * shades 9x fewer pixels; the gradients are built once per size / colour change and only moved per frame;
 * there is no blur.
 *
 * @param palette the four lights to use (e.g. read from the cover); defaults to hues around [seed].
 * @param mosaics one tiny blurred picture of the cover per carousel page (null while it is not read yet) and
 * [mosaicPosition] the carousel position in pages (page + drag offset, read while drawing): while the user
 * swipes, the two neighbouring pictures are cross-faded following the finger. [mosaicReveal] 0..1 fades the
 * whole mosaic in or out.
 * @param glow 0.65..1, how strongly the colours show (lower while no cover colour is known yet).
 * @param intensity extra scale for screens that need calmer colours behind lots of text.
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
    palette: List<Color>? = null,
    mosaics: List<ImageBitmap?> = emptyList(),
    mosaicPosition: () -> Float = { 0f },
    mosaicReveal: Float = 1f,
) {
    Box(
        modifier = modifier
            .clipToBounds()
            .layout { measurable, constraints ->
                val w = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
                val h = if (constraints.hasBoundedHeight) constraints.maxHeight else constraints.minHeight
                val placeable = measurable.measure(
                    Constraints.fixed(
                        width = maxOf(1, (w + LowRes - 1) / LowRes),
                        height = maxOf(1, (h + LowRes - 1) / LowRes),
                    ),
                )
                layout(w, h) { placeable.place(0, 0) }
            }
            .graphicsLayer {
                scaleX = LowRes.toFloat()
                scaleY = LowRes.toFloat()
                transformOrigin = TransformOrigin(0f, 0f)
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawWithCache {
                val w = size.width
                val h = size.height
                val strength = glow * intensity
                val base = Brush.verticalGradient(0f to top, 0.45f to mid, 1f to deep)
                val lights = palette?.takeIf { it.size >= 4 } ?: auroraLightsFor(seed)
                val flowBrushes = List(Flows.size) { i ->
                    val f = Flows[i]
                    Brush.linearGradient(
                        *ribbonStops(lights[f.colorA], f.alphaA * strength, lights[f.colorB], f.alphaB * strength),
                        start = Offset.Zero,
                        end = Offset(w * f.period, 0f),
                        tileMode = TileMode.Repeated,
                    )
                }
                val radii = FloatArray(Blobs.size) { w * Blobs[it].radius }
                val blobBrushes = List(Blobs.size) { i ->
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
                // keeps the lower half calm so the text over it stays easy to read; tinted, never black
                val veilTop = h * 0.30f
                val veil = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, deep.copy(alpha = 0.16f)),
                    startY = veilTop,
                    endY = h,
                )
                // and the very top, under the status bar and the title, a little darker
                val scrimHeight = h * 0.20f
                val scrim = Brush.verticalGradient(
                    colors = listOf(Color.Black.copy(alpha = 0.30f), Color.Transparent),
                    startY = 0f,
                    endY = scrimHeight,
                )
                val diagonal = hypot(w, h)
                val pivot = Offset(w / 2f, h / 2f)
                onDrawBehind {
                    drawRect(brush = base)
                    if (mosaics.isNotEmpty() && mosaicReveal > 0.01f) {
                        val pos = mosaicPosition().coerceIn(0f, (mosaics.size - 1).toFloat())
                        val first = pos.toInt()
                        val frac = pos - first
                        val a = mosaics[first]
                        val b = mosaics[minOf(first + 1, mosaics.size - 1)]
                        // a page whose picture is not ready simply does not take part in the cross-fade
                        var wa = if (a != null) 1f - frac else 0f
                        var wb = if (b != null) frac else 0f
                        val total = wa + wb
                        if (total > 0.001f) {
                            wa /= total
                            wb /= total
                            val k = MosaicAlpha * mosaicReveal
                            if (a != null && wa > 0.001f) drawMosaic(a, k * wa)
                            if (b != null && wb > 0.001f) drawMosaic(b, k * wb)
                        }
                    }
                    val t = phase.value
                    for (i in Flows.indices) {
                        val f = Flows[i]
                        val period = w * f.period
                        // whole periods per loop, so the picture at t = 1 is exactly the one at t = 0
                        val offset = ((f.cycles * t * period) % period + period) % period
                        rotate(degrees = f.angle, pivot = pivot) {
                            translate(left = offset) {
                                drawRect(
                                    brush = flowBrushes[i],
                                    topLeft = Offset(pivot.x - diagonal / 2f - period, pivot.y - diagonal / 2f),
                                    size = Size(diagonal + period, diagonal),
                                )
                            }
                        }
                    }
                    for (i in Blobs.indices) {
                        val b = Blobs[i]
                        val x = w * (b.baseX + b.rangeX * auroraCycle(t, b.kx, b.shift))
                        val y = h * (b.baseY + b.rangeY * auroraCycle(t, b.ky, b.shift + 0.25f))
                        // each light also swells and fades a little, so the mix of colours keeps changing
                        val breathe = 0.80f + 0.20f * auroraCycle(t, 4 + i, b.shift)
                        translate(left = x, top = y) {
                            drawCircle(brush = blobBrushes[i], radius = radii[i], center = Offset.Zero, alpha = breathe)
                        }
                    }
                    drawRect(brush = veil, topLeft = Offset(0f, veilTop), size = Size(w, h - veilTop))
                    drawRect(brush = scrim, size = Size(w, scrimHeight))
                }
            },
    )
}

/** Stretches the tiny picture over the whole backdrop; the smooth up-scaling is what blurs it. */
private fun DrawScope.drawMosaic(image: ImageBitmap, alpha: Float) {
    drawImage(
        image = image,
        srcSize = IntSize(image.width, image.height),
        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
        alpha = alpha,
        filterQuality = FilterQuality.Low,
    )
}

// Home backdrop built from the cover -->
private const val CoverBlurAlpha = 0.92f
private const val CoverOverscan = 1.10f
private const val CoverOrbAlphaA = 0.52f
private const val CoverOrbAlphaB = 0.42f

/**
 * Backdrop of the Home screen: a dark base, the blurred copy of the cover (see CoverTones) that sways very slowly, and
 * just two big lights drifting over it. Painted at 1/3 of the resolution like [AuroraBackdrop] (cheap), the animation
 * is only read while drawing (no recomposition), and it stops when the app leaves the screen.
 *
 * @param blurs one blurred picture per carousel page (null while it is not read yet); [blurPosition] is the carousel
 * position in pages (page + drag offset): while swiping, the two neighbouring pictures are cross-faded following the
 * finger. [blurReveal] 0..1 fades the picture in or out. [glow] 0.6..1 scales the lights.
 */
@Composable
fun CoverBackdrop(
    top: Color,
    mid: Color,
    deep: Color,
    lightA: Color,
    lightB: Color,
    glow: Float,
    phase: State<Float>,
    modifier: Modifier = Modifier,
    blurs: List<ImageBitmap?> = emptyList(),
    blurPosition: () -> Float = { 0f },
    blurReveal: Float = 1f,
) {
    Box(
        modifier = modifier
            .clipToBounds()
            .layout { measurable, constraints ->
                val w = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
                val h = if (constraints.hasBoundedHeight) constraints.maxHeight else constraints.minHeight
                val placeable = measurable.measure(
                    Constraints.fixed(
                        width = maxOf(1, (w + LowRes - 1) / LowRes),
                        height = maxOf(1, (h + LowRes - 1) / LowRes),
                    ),
                )
                layout(w, h) { placeable.place(0, 0) }
            }
            .graphicsLayer {
                scaleX = LowRes.toFloat()
                scaleY = LowRes.toFloat()
                transformOrigin = TransformOrigin(0f, 0f)
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawWithCache {
                val w = size.width
                val h = size.height
                val base = Brush.verticalGradient(0f to top, 0.45f to mid, 1f to deep)
                val radiusA = w * 0.75f
                val radiusB = w * 0.60f
                val brushA = Brush.radialGradient(
                    colors = listOf(
                        lightA.copy(alpha = (CoverOrbAlphaA * glow).coerceIn(0f, 1f)),
                        lightA.copy(alpha = 0f),
                    ),
                    center = Offset.Zero,
                    radius = radiusA,
                )
                val brushB = Brush.radialGradient(
                    colors = listOf(
                        lightB.copy(alpha = (CoverOrbAlphaB * glow).coerceIn(0f, 1f)),
                        lightB.copy(alpha = 0f),
                    ),
                    center = Offset.Zero,
                    radius = radiusB,
                )
                // lower part calm for the shelves, upper part a little darker under the status bar and the greeting
                val veilTop = h * 0.30f
                val veil = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, deep.copy(alpha = 0.08f)),
                    startY = veilTop,
                    endY = h,
                )
                val scrimHeight = h * 0.28f
                val scrim = Brush.verticalGradient(
                    colors = listOf(Color.Black.copy(alpha = 0.22f), Color.Transparent),
                    startY = 0f,
                    endY = scrimHeight,
                )
                onDrawBehind {
                    val t = phase.value
                    drawRect(brush = base)
                    if (blurs.isNotEmpty() && blurReveal > 0.01f) {
                        val pos = blurPosition().coerceIn(0f, (blurs.size - 1).toFloat())
                        val first = pos.toInt()
                        val frac = pos - first
                        val a = blurs[first]
                        val b = blurs[minOf(first + 1, blurs.size - 1)]
                        var wa = if (a != null) 1f - frac else 0f
                        var wb = if (b != null) frac else 0f
                        val total = wa + wb
                        if (total > 0.001f) {
                            wa /= total
                            wb /= total
                            val k = CoverBlurAlpha * blurReveal
                            // the whole picture sways a little: whole turns per loop, so it never jumps
                            val sx = w * 0.035f * auroraCycle(t, 5, 0f)
                            val sy = h * 0.020f * auroraCycle(t, 7, 0.30f)
                            if (a != null && wa > 0.001f) drawCoverBlur(a, k * wa, sx, sy)
                            if (b != null && wb > 0.001f) drawCoverBlur(b, k * wb, sx, sy)
                        }
                    }
                    val ax = w * (0.25f + 0.30f * auroraCycle(t, 7, 0f))
                    val ay = h * (0.22f + 0.12f * auroraCycle(t, 5, 0.25f))
                    translate(left = ax, top = ay) {
                        drawCircle(
                            brush = brushA,
                            radius = radiusA,
                            center = Offset.Zero,
                            alpha = 0.85f + 0.15f * auroraCycle(t, 9, 0.10f),
                        )
                    }
                    val bx = w * (0.78f + 0.25f * auroraCycle(t, 6, 0.31f))
                    val by = h * (0.52f + 0.14f * auroraCycle(t, 4, 0.56f))
                    translate(left = bx, top = by) {
                        drawCircle(
                            brush = brushB,
                            radius = radiusB,
                            center = Offset.Zero,
                            alpha = 0.85f + 0.15f * auroraCycle(t, 8, 0.70f),
                        )
                    }
                    drawRect(brush = veil, topLeft = Offset(0f, veilTop), size = Size(w, h - veilTop))
                    drawRect(brush = scrim, size = Size(w, scrimHeight))
                }
            },
    )
}

/** The picture is drawn a little bigger than the screen, so swaying it never shows an edge. */
private fun DrawScope.drawCoverBlur(image: ImageBitmap, alpha: Float, swayX: Float, swayY: Float) {
    val dw = size.width * CoverOverscan
    val dh = size.height * CoverOverscan
    translate(left = (size.width - dw) / 2f + swayX, top = (size.height - dh) / 2f + swayY) {
        drawImage(
            image = image,
            srcSize = IntSize(image.width, image.height),
            dstSize = IntSize(dw.roundToInt(), dh.roundToInt()),
            alpha = alpha,
            filterQuality = FilterQuality.Low,
        )
    }
}
// Home backdrop built from the cover <--

private fun auroraHueShift(color: Color, degrees: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgb(), hsv)
    hsv[0] = ((hsv[0] + degrees) % 360f + 360f) % 360f
    hsv[1] = (hsv[1] * 1.1f).coerceIn(0.45f, 1f)
    hsv[2] = hsv[2].coerceAtLeast(0.70f)
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
