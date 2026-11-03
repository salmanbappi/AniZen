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
import eu.kanade.presentation.library.components.CoverTonesCache

// ANZ -->
/**
 * One backdrop for the whole details page (behind the cover and behind the info, genres, cast and episodes): the same
 * blurred copy of the cover and drifting lights as the Home screen, a little calmer because this page has a lot of
 * text. Until the colours of the cover are read it shows the old single-colour tint, then fades to the real colours.
 */
@Composable
fun AnimeBodyBackdrop(
    animeId: Long,
    coverVersion: Long,
    ambient: AnimeAmbient,
    modifier: Modifier = Modifier,
) {
    val tones = CoverTonesCache.get(animeId, coverVersion)?.takeIf { it.lights.isNotEmpty() }
    val lights = tones?.lights ?: auroraLightsFor(ambient.seed)
    val fade = tween<Color>(durationMillis = 700)
    val top by animateColorAsState(tones?.top ?: ambient.top, fade, label = "coverTop")
    val mid by animateColorAsState(tones?.mid ?: ambient.mid, fade, label = "coverMid")
    val deep by animateColorAsState(tones?.deep ?: ambient.deep, fade, label = "coverDeep")
    val lightA by animateColorAsState(lights[0], fade, label = "coverLightA")
    val lightB by animateColorAsState(lights[1], fade, label = "coverLightB")
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
private const val MotionSpeed = 2
// ANZ <--
