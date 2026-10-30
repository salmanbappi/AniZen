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

import androidx.compose.foundation.border
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.ui.Alignment
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CatchingPokemon
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.player.controls.LocalPlayerButtonsClickEvent
import tachiyomi.presentation.core.components.material.Button
import tachiyomi.presentation.core.components.material.DISABLED_ALPHA
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.util.tvCircleFocusHighlight
import tachiyomi.presentation.core.util.tvFocusHighlight

@Composable
fun ControlsButton(
    icon: ImageVector,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    title: String? = null,
    color: Color = Color.White,
    horizontalSpacing: Dp = MaterialTheme.padding.medium,
    iconSize: Dp = 22.dp,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val clickEvent = LocalPlayerButtonsClickEvent.current
    val iconColor = if (enabled) color else color.copy(alpha = DISABLED_ALPHA)

    Box(
        modifier = modifier
            .tvCircleFocusHighlight(
                focusedScale = 1.15f,
                focusedBackgroundAlpha = 0.35f,
            )
            .combinedClickable(
                enabled = enabled,
                onClick = {
                    clickEvent()
                    onClick()
                },
                onLongClick = onLongClick,
                interactionSource = interactionSource,
                indication = null,
            )
            .clip(CircleShape)
            .indication(
                interactionSource,
                ripple(),
            )
            .padding(
                vertical = MaterialTheme.padding.medium,
                horizontal = horizontalSpacing,
            ),
    ) {
        Icon(
            icon,
            title,
            tint = iconColor,
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
fun ControlsButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: () -> Unit = {},
    color: Color = Color.White,
) {
    val interactionSource = remember { MutableInteractionSource() }

    val clickEvent = LocalPlayerButtonsClickEvent.current
    Box(
        modifier = modifier
            .tvCircleFocusHighlight(
                focusedScale = 1.1f,
                focusedBackgroundAlpha = 0.35f,
            )
            .combinedClickable(
                onClick = {
                    clickEvent()
                    onClick()
                },
                onLongClick = onLongClick,
                interactionSource = interactionSource,
                indication = null,

            )
            // ANZ --> soft translucent pill instead of bare text
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.13f))
            .indication(
                interactionSource,
                ripple(),
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text,
            color = color,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.SemiBold,
                fontFeatureSettings = "tnum",
            ),
        )
        // ANZ <--
    }
}

@Composable
fun FilledControlsButton(
    text: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // ANZ --> same size as before, new look: tinted glass pill with an accent border and icon
    val interactionSource = remember { MutableInteractionSource() }
    val clickEvent = LocalPlayerButtonsClickEvent.current
    val accent = MaterialTheme.colorScheme.primary

    Row(
        modifier = modifier
            .tvFocusHighlight(
                shape = CircleShape,
                focusedScale = 1.06f,
            )
            .clip(CircleShape)
            .background(accent.copy(alpha = 0.22f))
            .border(1.dp, accent.copy(alpha = 0.75f), CircleShape)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = ripple(),
                onClick = {
                    clickEvent()
                    onClick()
                },
                onLongClick = onLongClick,
            )
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.FastForward,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.SemiBold,
                fontFeatureSettings = "tnum",
            ),
        )
    }
    // ANZ <--
}

// ANZ -->
/**
 * Icon-only dark glass circle, same language as the centre buttons and the side sliders: dark fill
 * with an accent glow at the bottom, thin gradient border, small specular arc, springy press and a
 * light haptic tick. [active] lights it up with the accent colour, [pulse] makes the glow breathe
 * softly to draw attention (used by the skip-intro prompt). Every animated value is read in the
 * draw phase, so nothing recomposes per frame.
 */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: () -> Unit = {},
    diameter: Dp = 42.dp,
    iconSize: Dp = 22.dp,
    active: Boolean = true,
    pulse: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "glass_icon_scale",
    )
    val lit = animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(durationMillis = 240),
        label = "glass_icon_lit",
    )
    val breathe: State<Float>? = if (pulse) {
        rememberInfiniteTransition(label = "glass_icon_pulse").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "glass_icon_pulse_value",
        )
    } else {
        null
    }
    val haptic = LocalHapticFeedback.current
    val clickEvent = LocalPlayerButtonsClickEvent.current
    val accent = MaterialTheme.colorScheme.primary
    val accentLight = remember(accent) { lerp(accent, Color.White, 0.72f) }
    val neutralTop = Color.White.copy(alpha = 0.92f)
    val neutralBottom = Color.White.copy(alpha = 0.62f)

    Box(
        modifier = modifier
            .size(diameter)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.34f))
            .drawBehind {
                val l = lit.value
                val p = breathe?.value ?: 0f
                // glass fill with an accent glow pooling at the bottom
                drawRect(
                    brush = Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.15f),
                            Color.White.copy(alpha = 0.03f),
                            accent.copy(alpha = ((0.06f + 0.20f * l) * (1f + 0.5f * p)).coerceIn(0f, 1f)),
                        ),
                    ),
                )
                // small specular arc, top-left
                val inset = size.minDimension * 0.16f
                drawArc(
                    color = Color.White.copy(alpha = 0.40f),
                    startAngle = 200f,
                    sweepAngle = 56f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - inset * 2, size.height - inset * 2),
                    style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round),
                )
                // hairline edge: bright on top, accent below
                val stroke = 1.dp.toPx()
                drawCircle(
                    brush = Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.55f),
                            accent.copy(alpha = ((0.20f + 0.45f * l) * (1f + 0.35f * p)).coerceIn(0f, 1f)),
                        ),
                    ),
                    radius = (size.minDimension - stroke) / 2f,
                    style = Stroke(width = stroke),
                )
            }
            .combinedClickable(
                interactionSource = interaction,
                indication = ripple(color = accentLight),
                onClick = {
                    clickEvent()
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                },
                onLongClick = onLongClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // The glyph is painted with a vertical gradient: neutral white when off, light accent -> accent when on.
        Crossfade(
            targetState = icon,
            modifier = Modifier
                .size(iconSize)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    val l = lit.value
                    drawRect(
                        brush = Brush.verticalGradient(
                            listOf(lerp(neutralTop, accentLight, l), lerp(neutralBottom, accent, l)),
                        ),
                        blendMode = BlendMode.SrcIn,
                    )
                },
            animationSpec = tween(durationMillis = 180),
            label = "glass_icon_swap",
        ) { current ->
            Icon(
                imageVector = current,
                contentDescription = contentDescription,
                tint = Color.White,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}
// ANZ <--

@Preview
@Composable
private fun PreviewControlsButton() {
    ControlsButton(
        Icons.Default.CatchingPokemon,
        onClick = {},
    )
}
