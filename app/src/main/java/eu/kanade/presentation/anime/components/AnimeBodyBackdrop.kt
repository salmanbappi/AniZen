package eu.kanade.presentation.anime.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import eu.kanade.presentation.components.CoverBackdrop
import eu.kanade.presentation.components.auroraLightsFor
import eu.kanade.presentation.components.rememberAuroraPhase
import eu.kanade.presentation.library.components.BackdropOverrides
import eu.kanade.presentation.library.components.CoverTonesCache
import kotlin.math.pow

// ANZ -->
/**
 * One backdrop for the whole details page (behind the cover and behind the info, genres, cast and episodes): the same
 * blurred copy of the cover and drifting lights as the Home screen, a little calmer because this page has a lot of
 * text. Until the colours of the cover are read it shows the old single-colour tint, then fades to the real colours.
 *
 * It uses the same colours as the Home carousel for this anime: the ones edited by hand ("Editar fondo") when there
 * is an edit, otherwise the automatic ones. Edited colours are kept readable (see [readable]) because this page is
 * full of text; the automatic colours are used exactly as before.
 */
@Composable
fun AnimeBodyBackdrop(
    animeId: Long,
    coverVersion: Long,
    ambient: AnimeAmbient,
    modifier: Modifier = Modifier,
) {
    val auto = CoverTonesCache.get(animeId, coverVersion)
    val tones = BackdropOverrides.apply(animeId, auto)?.takeIf { it.lights.isNotEmpty() }
    val edited = tones != null && BackdropOverrides.editOf(animeId) != null
    val lights = tones?.lights ?: auroraLightsFor(ambient.seed)
    val fade = tween<Color>(durationMillis = 700)
    val top by animateColorAsState(
        tones?.top?.let { if (edited) readable(it, MaxTopLuma) else it } ?: ambient.top,
        fade,
        label = "coverTop",
    )
    val mid by animateColorAsState(
        tones?.mid?.let { if (edited) readable(it, MaxMidLuma) else it } ?: ambient.mid,
        fade,
        label = "coverMid",
    )
    val deep by animateColorAsState(
        tones?.deep?.let { if (edited) readable(it, MaxDeepLuma) else it } ?: ambient.deep,
        fade,
        label = "coverDeep",
    )
    val lightA by animateColorAsState(
        if (edited) readable(lights[0], MaxLightLuma) else lights[0],
        fade,
        label = "coverLightA",
    )
    val lightB by animateColorAsState(
        if (edited) readable(lights[1], MaxLightLuma) else lights[1],
        fade,
        label = "coverLightB",
    )
    val blur = tones?.mosaic
    val reveal by animateFloatAsState(
        targetValue = if (blur != null) 1f else 0f,
        animationSpec = tween(durationMillis = 700),
        label = "coverBlur",
    )
    val glowTarget = tones?.let { 0.60f + 0.40f * minOf(1f, it.presence / 0.30f) } ?: ambient.glow
    val glow by animateFloatAsState(
        targetValue = glowTarget * 0.85f,
        animationSpec = tween(durationMillis = 700),
        label = "coverGlow",
    )
    // ANZ: same lights and colours, just moving twice as fast (whole turns, so it never jumps). The clock already
    // stops when the app is in the background, and it is only read while drawing (no recomposition per frame).
    val basePhase = rememberAuroraPhase()
    val phase = remember(basePhase) {
        derivedStateOf { (basePhase.value * MotionSpeed) % 1f }
    }
    CoverBackdrop(
        top = top,
        mid = mid,
        deep = deep,
        lightA = lightA,
        lightB = lightB,
        glow = glow,
        phase = phase,
        modifier = modifier,
        blurs = listOf(blur),
        blurReveal = reveal,
    )
}

/**
 * The colour, no brighter than [maxLuma] (relative luminance, 0 to 1): a colour that is too bright is darkened in
 * linear light, so its hue stays. Light text over it keeps a comfortable contrast; a darker colour is left alone.
 */
private fun readable(color: Color, maxLuma: Float): Color {
    fun linear(v: Float): Float = if (v <= 0.03928f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
    fun encode(v: Float): Float = if (v <= 0.0031308f) v * 12.92f else 1.055f * v.pow(1f / 2.4f) - 0.055f
    val r = linear(color.red)
    val g = linear(color.green)
    val b = linear(color.blue)
    val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
    if (luma <= maxLuma) return color
    val k = maxLuma / luma
    return Color(
        red = encode(r * k).coerceIn(0f, 1f),
        green = encode(g * k).coerceIn(0f, 1f),
        blue = encode(b * k).coerceIn(0f, 1f),
        alpha = color.alpha,
    )
}

private const val MotionSpeed = 2

// brightest allowed for an edited colour: the top shows behind the cover, the middle behind the title and info, the
// bottom behind the episodes (where most of the reading happens), the lights are only soft accents
private const val MaxTopLuma = 0.30f
private const val MaxMidLuma = 0.22f
private const val MaxDeepLuma = 0.14f
private const val MaxLightLuma = 0.34f
// ANZ <--
