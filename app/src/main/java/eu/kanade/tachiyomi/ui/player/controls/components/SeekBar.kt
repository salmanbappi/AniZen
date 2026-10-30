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
 * Liquid-glass seekbar, the horizontal sibling of the volume / brightness tubes: a slim glass tube on a
 * faint dark backing with a light rim, a buffered strip, and "liquid" that fills it from the left (vertical
 * colour gradient plus cylinder shading and a gloss line). The front of the liquid carries a bright
 * meniscus glint instead of a dot; while dragging it swells into a glass bead and the tube thickens a bit.
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
    // Same geometry as Seeker: the track is inset by the thumb radius on both sides, so the thumbnail
    // preview (which uses the same inset) keeps sitting exactly over the front of the liquid.
    val inset = seekerThumbRadius.toPx()
    val trackW = size.width - inset * 2f
    if (trackW <= 0f) return
    val g = grow.coerceIn(0f, 1.1f)
    val cy = size.height / 2f

    // Tube: 12 dp, swelling to 15 dp while dragging.
    val tubeH = (12f + 3f * g).dp.toPx()
    val tubeR = tubeH / 2f
    val tubeTop = cy - tubeR
    val tubeCorner = CornerRadius(tubeR, tubeR)
    val tubeTopLeft = Offset(inset, tubeTop)
    val tubeSize = Size(trackW, tubeH)

    // 1) glass tube: dark backing, light gradient, rim bright on top, glossy highlight line
    drawRoundRect(
        color = Color.Black.copy(alpha = 0.28f),
        topLeft = tubeTopLeft,
        size = tubeSize,
        cornerRadius = tubeCorner,
    )
    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color.White.copy(alpha = 0.18f), Color.White.copy(alpha = 0.06f)),
            startY = tubeTop,
            endY = tubeTop + tubeH,
        ),
        topLeft = tubeTopLeft,
        size = tubeSize,
        cornerRadius = tubeCorner,
    )

    // inner area where buffer and liquid live
    val pad = 2.dp.toPx()
    val innerH = tubeH - pad * 2f
    val innerR = innerH / 2f
    val innerTop = tubeTop + pad
    val innerLeft = inset + pad
    val innerW = trackW - pad * 2f
    val innerCorner = CornerRadius(innerR, innerR)

    // 2) buffered part: a faint frosted strip inside the tube
    val bufferedW = innerW * bufferedFraction
    if (bufferedW > innerH) {
        drawRoundRect(
            color = Color.White.copy(alpha = 0.20f),
            topLeft = Offset(innerLeft, innerTop),
            size = Size(bufferedW, innerH),
            cornerRadius = innerCorner,
        )
    }

    // 3) liquid: fills from the left up to the current position
    val cx = inset + trackW * fraction
    val hasLiquid = cx - inset > 0.5f
    val liquidRight = maxOf(cx, innerLeft + innerH).coerceAtMost(innerLeft + innerW)
    val liquidW = liquidRight - innerLeft
    if (hasLiquid && liquidW > 0.5f) {
        val liquidTopLeft = Offset(innerLeft, innerTop)
        val liquidSize = Size(liquidW, innerH)
        // body: pale at the top, saturated at the bottom (same as the volume tubes)
        drawRoundRect(
            brush = Brush.verticalGradient(
                colors = listOf(accentLight, accent),
                startY = innerTop,
                endY = innerTop + innerH,
            ),
            topLeft = liquidTopLeft,
            size = liquidSize,
            cornerRadius = innerCorner,
        )
        // the tail is a little deeper than the front, so the liquid looks like it is flowing forward
        drawRoundRect(
            brush = Brush.horizontalGradient(
                colors = listOf(Color.Black.copy(alpha = 0.26f), Color.Transparent),
                startX = innerLeft,
                endX = innerLeft + liquidW * 0.75f,
            ),
            topLeft = liquidTopLeft,
            size = liquidSize,
            cornerRadius = innerCorner,
        )
        // cylinder shading: light along the top, a little shade along the bottom
        drawRoundRect(
            brush = Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.32f),
                0.5f to Color.Transparent,
                1f to Color.Black.copy(alpha = 0.16f),
                startY = innerTop,
                endY = innerTop + innerH,
            ),
            topLeft = liquidTopLeft,
            size = liquidSize,
            cornerRadius = innerCorner,
        )
        // glass edge on the liquid
        drawRoundRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0f)),
                startY = innerTop,
                endY = innerTop + innerH,
            ),
            topLeft = liquidTopLeft,
            size = liquidSize,
            cornerRadius = innerCorner,
            style = Stroke(width = 0.75.dp.toPx()),
        )
        // gloss line along the surface
        if (liquidW > innerH * 1.6f) {
            drawRoundRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.50f), Color.White.copy(alpha = 0.18f)),
                    startX = innerLeft,
                    endX = liquidRight,
                ),
                topLeft = Offset(innerLeft + innerR, innerTop + innerH * 0.16f),
                size = Size(liquidW - innerH, innerH * 0.18f),
                cornerRadius = CornerRadius(innerH * 0.09f, innerH * 0.09f),
            )
        }
    }

    // 4) tube rim and highlight, over the liquid like the glass of the volume tubes
    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0.10f)),
            startY = tubeTop,
            endY = tubeTop + tubeH,
        ),
        topLeft = tubeTopLeft,
        size = tubeSize,
        cornerRadius = tubeCorner,
        style = Stroke(width = 1.dp.toPx()),
    )
    drawRoundRect(
        brush = Brush.horizontalGradient(
            colors = listOf(Color.White.copy(alpha = 0.34f), Color.White.copy(alpha = 0f)),
            startX = inset + tubeR,
            endX = inset + tubeR + minOf(trackW * 0.22f, 72.dp.toPx()),
        ),
        topLeft = Offset(inset + tubeR, tubeTop + 1.6.dp.toPx()),
        size = Size(minOf(trackW * 0.22f, 72.dp.toPx()), 1.2.dp.toPx()),
        cornerRadius = CornerRadius(0.6.dp.toPx(), 0.6.dp.toPx()),
    )

    // 5) chapter gaps (cleared out of everything drawn so far)
    val gapW = 2.5.dp.toPx()
    for (f in chapterFractions) {
        val x = inset + trackW * f
        drawRect(
            color = Color.Black,
            topLeft = Offset(x - gapW / 2f, cy - tubeH * 0.95f),
            size = Size(gapW, tubeH * 1.9f),
            blendMode = BlendMode.Clear,
        )
    }

    // 6) front of the liquid: a bright glint that swells into a glass bead while dragging
    if (hasLiquid || g > 0.02f) {
        val edgeX = if (hasLiquid) liquidRight else cx
        val glowR = tubeH * (1.2f + 0.7f * g)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(accentLight.copy(alpha = 0.34f + 0.18f * g), Color.Transparent),
                center = Offset(edgeX, cy),
                radius = glowR,
            ),
            radius = glowR,
            center = Offset(edgeX, cy),
        )
        val beadW = (2.6f + 8.4f * g).dp.toPx()
        val idleH = innerH * 0.62f
        val beadH = idleH + (innerH + tubeH * 0.95f - idleH) * g
        val beadCx = edgeX - 2.4.dp.toPx() * (1f - g).coerceAtLeast(0f)
        val beadTop = cy - beadH / 2f
        val beadLeft = beadCx - beadW / 2f
        val beadCorner = CornerRadius(beadW / 2f, beadW / 2f)
        drawRoundRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.White.copy(alpha = 0.96f), lerp(accentLight, Color.White, 0.35f)),
                startY = beadTop,
                endY = beadTop + beadH,
            ),
            topLeft = Offset(beadLeft, beadTop),
            size = Size(beadW, beadH),
            cornerRadius = beadCorner,
        )
        val glass = g.coerceIn(0f, 1f)
        if (glass > 0.02f) {
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.White.copy(alpha = 0.95f * glass), accent.copy(alpha = 0.75f * glass)),
                    startY = beadTop,
                    endY = beadTop + beadH,
                ),
                topLeft = Offset(beadLeft, beadTop),
                size = Size(beadW, beadH),
                cornerRadius = beadCorner,
                style = Stroke(width = 1.dp.toPx()),
            )
            drawOval(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.White.copy(alpha = 0.85f * glass), Color.White.copy(alpha = 0.04f * glass)),
                    startY = beadTop + beadH * 0.06f,
                    endY = beadTop + beadH * 0.36f,
                ),
                topLeft = Offset(beadCx - beadW * 0.32f, beadTop + beadH * 0.06f),
                size = Size(beadW * 0.64f, beadH * 0.3f),
            )
        }
    }
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
