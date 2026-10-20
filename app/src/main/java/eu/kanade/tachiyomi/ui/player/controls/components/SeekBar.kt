/*
 * Copyright 2024 Abdallah Mehiz
 * https://github.com/abdallahmehiz/mpvKt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.kanade.tachiyomi.ui.player.controls.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.vivvvek.seeker.Seeker
import dev.vivvvek.seeker.SeekerDefaults
import dev.vivvvek.seeker.Segment
import eu.kanade.tachiyomi.animesource.model.ChapterType
import eu.kanade.tachiyomi.ui.player.controls.LocalPlayerButtonsClickEvent
import `is`.xyz.mpv.Utils
import tachiyomi.presentation.core.components.material.padding

/**
 * Width of the video timer slots flanking the seekbar.
 * Reused by the player prompt so it can align with the duration timer's text.
 */
internal val videoTimerWidth = 64.dp

// ANZ -->
/** Radius of the seekbar thumb; the thumbnail preview uses it to sit exactly over the thumb. */
internal val seekerThumbRadius = 12.dp
// ANZ <--

@Immutable
data class IndexedSegment(
    val name: String,
    val start: Float,
    val color: Color = Color.Unspecified,
    val index: Int = 0,
    val chapterType: ChapterType = ChapterType.Other,
) {
    companion object {
        val Unspecified = IndexedSegment(name = "", start = 0f)
    }

    fun toSegment(): Segment = Segment(name, start, color)
}

@Composable
fun SeekbarWithTimers(
    position: Float,
    duration: Float,
    readAheadValue: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    timersInverted: Pair<Boolean, Boolean>,
    positionTimerOnClick: () -> Unit,
    durationTimerOnCLick: () -> Unit,
    onDurationTextLayout: (TextLayoutResult) -> Unit = {},
    chapters: List<Segment>,
    modifier: Modifier = Modifier,
) {
    val clickEvent = LocalPlayerButtonsClickEvent.current
    // ANZ -->
    val segments = remember(chapters, duration) {
        chapters
            .filter { it.start in 0f..duration }
            .let {
                // add an extra segment at 0 if it doesn't exist.
                if (it.isNotEmpty() && it[0].start != 0f) {
                    listOf(Segment("", 0f)) + it
                } else {
                    it
                }
            }
    }
    // ANZ <--
    Row(
        modifier = modifier.height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
    ) {
        VideoTimer(
            value = position,
            timersInverted.first,
            onClick = {
                clickEvent()
                positionTimerOnClick()
            },
            modifier = Modifier.width(videoTimerWidth),
        )
        // ANZ -->
        GlassSeekbar(
            position = position,
            duration = duration,
            readAheadValue = readAheadValue,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            segments = segments,
            modifier = Modifier.weight(1f),
        )
        // ANZ <--
        VideoTimer(
            value = if (timersInverted.second) position - duration else duration,
            isInverted = timersInverted.second,
            onClick = {
                clickEvent()
                durationTimerOnCLick()
            },
            onTextLayout = onDurationTextLayout,
            modifier = Modifier.width(videoTimerWidth),
        )
    }
}

// ANZ -->
/**
 * Liquid-glass seekbar: a dark translucent track with a thin gradient rim, a buffered strip, an accent
 * gradient progress with a soft glow and gloss line, and a glossy orb that swells while dragging.
 *
 * The look is drawn by a [Canvas]; the real [Seeker] sits on top, fully transparent, and keeps handling
 * touch, dragging, chapter segments and accessibility exactly as before.
 */
@Composable
private fun GlassSeekbar(
    position: Float,
    duration: Float,
    readAheadValue: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    segments: List<Segment>,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    val accentLight = remember(accent) { lerp(accent, Color.White, 0.72f) }
    val interaction = remember { MutableInteractionSource() }
    val dragging by interaction.collectIsDraggedAsState()
    val grow by animateFloatAsState(
        targetValue = if (dragging) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "glass_seekbar_grow",
    )
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(dragging) {
        if (dragging) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
    val chapterFractions = remember(segments, duration) {
        if (duration > 0f) {
            segments.map { it.start / duration }.filter { it > 0f && it < 1f }
        } else {
            emptyList()
        }
    }
    val fraction = if (duration > 0f) (position / duration).coerceIn(0f, 1f) else 0f
    val buffered = if (duration > 0f) (readAheadValue / duration).coerceIn(0f, 1f) else 0f

    Box(modifier = modifier.fillMaxHeight(), contentAlignment = Alignment.Center) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
        ) {
            drawGlassSeekbar(
                fraction = fraction,
                bufferedFraction = buffered,
                chapterFractions = chapterFractions,
                accent = accent,
                accentLight = accentLight,
                grow = grow,
            )
        }
        Seeker(
            modifier = Modifier.fillMaxSize(),
            value = position.coerceIn(0f, duration),
            range = 0f..duration,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            readAheadValue = readAheadValue,
            segments = segments,
            // invisible on purpose: the Canvas above draws everything
            colors = SeekerDefaults.seekerColors(
                progressColor = Color.Transparent,
                trackColor = Color.Transparent,
                disabledProgressColor = Color.Transparent,
                disabledTrackColor = Color.Transparent,
                thumbColor = Color.Transparent,
                disabledThumbColor = Color.Transparent,
                readAheadColor = Color.Transparent,
            ),
            dimensions = SeekerDefaults.seekerDimensions(
                trackHeight = 6.dp,
                progressHeight = 6.dp,
                thumbRadius = seekerThumbRadius,
                gap = 3.dp,
            ),
            interactionSource = interaction,
        )
    }
}

private fun DrawScope.drawGlassSeekbar(
    fraction: Float,
    bufferedFraction: Float,
    chapterFractions: List<Float>,
    accent: Color,
    accentLight: Color,
    grow: Float,
) {
    // Same geometry as Seeker: the track is inset by the thumb radius on both sides.
    val inset = seekerThumbRadius.toPx()
    val trackW = size.width - inset * 2f
    if (trackW <= 0f) return
    val cy = size.height / 2f
    val trackH = 6.dp.toPx()
    val r = trackH / 2f
    val top = cy - r
    val corner = CornerRadius(r, r)
    val trackTopLeft = Offset(inset, top)
    val trackSize = Size(trackW, trackH)

    // 1) glass track: dark base + light gradient + thin rim
    drawRoundRect(
        color = Color.Black.copy(alpha = 0.30f),
        topLeft = trackTopLeft,
        size = trackSize,
        cornerRadius = corner,
    )
    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.07f)),
            startY = top,
            endY = top + trackH,
        ),
        topLeft = trackTopLeft,
        size = trackSize,
        cornerRadius = corner,
    )
    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color.White.copy(alpha = 0.50f), Color.White.copy(alpha = 0.08f)),
            startY = top,
            endY = top + trackH,
        ),
        topLeft = trackTopLeft,
        size = trackSize,
        cornerRadius = corner,
        style = Stroke(width = 1.dp.toPx()),
    )

    // 2) buffered part
    val bufferedW = trackW * bufferedFraction
    if (bufferedW > trackH) {
        drawRoundRect(
            color = Color.White.copy(alpha = 0.26f),
            topLeft = trackTopLeft,
            size = Size(bufferedW, trackH),
            cornerRadius = corner,
        )
    }

    // 3) progress: soft glow, accent gradient, gloss line
    val cx = inset + trackW * fraction
    val progressW = cx - inset
    if (progressW > 0.5f) {
        val w = maxOf(progressW, trackH)
        val glowH = trackH * 3f
        drawRoundRect(
            brush = Brush.verticalGradient(
                colors = listOf(accent.copy(alpha = 0f), accent.copy(alpha = 0.30f), accent.copy(alpha = 0f)),
                startY = cy - glowH / 2f,
                endY = cy + glowH / 2f,
            ),
            topLeft = Offset(inset, cy - glowH / 2f),
            size = Size(w, glowH),
            cornerRadius = CornerRadius(glowH / 2f, glowH / 2f),
        )
        drawRoundRect(
            brush = Brush.horizontalGradient(
                colors = listOf(accent, accentLight),
                startX = inset,
                endX = inset + w,
            ),
            topLeft = trackTopLeft,
            size = Size(w, trackH),
            cornerRadius = corner,
        )
        if (w > trackH * 1.5f) {
            drawRoundRect(
                color = Color.White.copy(alpha = 0.42f),
                topLeft = Offset(inset + r, top + trackH * 0.16f),
                size = Size(w - trackH, trackH * 0.26f),
                cornerRadius = CornerRadius(trackH * 0.13f, trackH * 0.13f),
            )
        }
    }

    // 4) chapter gaps (cleared out of everything drawn so far)
    val gapW = 3.dp.toPx()
    for (f in chapterFractions) {
        val x = inset + trackW * f
        drawRect(
            color = Color.Black,
            topLeft = Offset(x - gapW / 2f, cy - trackH * 1.6f),
            size = Size(gapW, trackH * 3.2f),
            blendMode = BlendMode.Clear,
        )
    }

    // 5) glossy orb, swells while dragging
    val orb = (7.5f + 4f * grow.coerceIn(0f, 1.1f)).dp.toPx()
    val orbCenter = Offset(cx, cy)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(accent.copy(alpha = 0.55f), accent.copy(alpha = 0f)),
            center = orbCenter,
            radius = orb * 2.3f,
        ),
        radius = orb * 2.3f,
        center = orbCenter,
    )
    drawCircle(
        brush = Brush.verticalGradient(
            colors = listOf(accentLight, accent),
            startY = cy - orb,
            endY = cy + orb,
        ),
        radius = orb,
        center = orbCenter,
    )
    drawCircle(
        brush = Brush.verticalGradient(
            colors = listOf(Color.White.copy(alpha = 0.90f), Color.White.copy(alpha = 0.25f)),
            startY = cy - orb,
            endY = cy + orb,
        ),
        radius = orb - 0.6.dp.toPx(),
        center = orbCenter,
        style = Stroke(width = 1.2.dp.toPx()),
    )
    drawOval(
        brush = Brush.verticalGradient(
            colors = listOf(Color.White.copy(alpha = 0.85f), Color.White.copy(alpha = 0.05f)),
            startY = cy - orb * 0.8f,
            endY = cy - orb * 0.25f,
        ),
        topLeft = Offset(cx - orb * 0.5f, cy - orb * 0.8f),
        size = Size(orb, orb * 0.55f),
    )
}
// ANZ <--

@Composable
fun VideoTimer(
    value: Float,
    isInverted: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    onTextLayout: (TextLayoutResult) -> Unit = {},
) {
    val interactionSource = remember { MutableInteractionSource() }
    Text(
        modifier = modifier
            .fillMaxHeight()
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(),
                onClick = onClick,
            )
            .wrapContentHeight(Alignment.CenterVertically),
        text = Utils.prettyTime(value.toInt(), isInverted),
        color = Color.White.copy(alpha = 0.92f),
        textAlign = TextAlign.Center,
        onTextLayout = onTextLayout,
        // ANZ --> smaller, medium weight timers
        style = MaterialTheme.typography.labelMedium.copy(
            fontFamily = androidx.compose.ui.text.font.FontFamily.SansSerif,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            fontFeatureSettings = "tnum",
        )
        // ANZ <--
    )
}

@Preview
@Composable
private fun PreviewSeekBar() {
    SeekbarWithTimers(
        5f,
        20f,
        4f,
        {},
        {},
        Pair(false, true),
        {},
        {},
        chapters = emptyList(),
    )
}
