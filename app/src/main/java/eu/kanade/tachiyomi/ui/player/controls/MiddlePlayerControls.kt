package eu.kanade.tachiyomi.ui.player.controls

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R
import `is`.xyz.mpv.Utils
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.math.abs

@Composable
fun MiddlePlayerControls(
    // previous
    hasPrevious: Boolean,
    onSkipPrevious: () -> Unit,

    // middle
    isStopped: Boolean,
    isLoading: Boolean,
    isLoadingEpisode: Boolean,
    controlsShown: Boolean,
    areControlsLocked: Boolean,
    showLoadingCircle: Boolean,
    paused: Boolean,
    gestureSeekAmount: Pair<Int, Int>?,
    onPlayPauseClick: () -> Unit,

    // next
    hasNext: Boolean,
    onSkipNext: () -> Unit,

    enter: EnterTransition,
    exit: ExitTransition,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(26.dp),
    ) {
        AnimatedVisibility(
            visible = controlsShown && !areControlsLocked,
            enter = enter,
            exit = exit,
        ) {
            if (gestureSeekAmount == null) {
                // ANZ -->
                SkipCircleButton(
                    icon = Icons.Rounded.SkipPrevious,
                    onClick = onSkipPrevious,
                    enabled = hasPrevious,
                    form = PopForm.TriangleLeft,
                )
                // ANZ <--
            }
        }

        // ANZ -->
        when {
            (isLoading || isLoadingEpisode) && showLoadingCircle -> {
                Box(Modifier.size(PLAY_SIZE), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(54.dp),
                        color = rememberGlassAccent().first,
                        strokeWidth = 3.dp,
                        trackColor = Color.White.copy(alpha = 0.12f),
                    )
                }
            }
            isStopped -> {
                Spacer(Modifier.size(PLAY_SIZE))
            }
            gestureSeekAmount != null -> {
                Text(
                    stringResource(
                        MR.strings.player_gesture_seek_indicator,
                        if (gestureSeekAmount.second >= 0) '+' else '-',
                        Utils.prettyTime(abs(gestureSeekAmount.second)),
                        Utils.prettyTime(gestureSeekAmount.first + gestureSeekAmount.second),
                    ),
                    style = MaterialTheme.typography.headlineMedium.copy(
                        shadow = Shadow(Color.Black, blurRadius = 5f),
                    ),
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            }

            else -> {
                val icon = AnimatedImageVector.animatedVectorResource(R.drawable.anim_play_to_pause)
                AnimatedVisibility(
                    visible = controlsShown && !areControlsLocked,
                    enter = enter,
                    exit = exit,
                ) {
                    val (accentTop, accentBase) = rememberGlassAccent()
                    GlassCircleButton(
                        onClick = onPlayPauseClick,
                        diameter = PLAY_SIZE,
                        shape = PopShape(PopForm.Squircle),
                        hero = true,
                    ) {
                        Image(
                            painter = rememberAnimatedVectorPainter(icon, !paused),
                            modifier = Modifier
                                .size(44.dp)
                                .accentGradientFill(accentTop, accentBase),
                            colorFilter = ColorFilter.tint(Color.White),
                            contentDescription = null,
                        )
                    }
                }
            }
        }
        // ANZ <--

        AnimatedVisibility(
            visible = controlsShown && !areControlsLocked,
            enter = enter,
            exit = exit,
        ) {
            if (gestureSeekAmount == null) {
                // ANZ -->
                SkipCircleButton(
                    icon = Icons.Rounded.SkipNext,
                    onClick = onSkipNext,
                    enabled = hasNext,
                    form = PopForm.TriangleRight,
                )
                // ANZ <--
            }
        }
    }
}

// ANZ -->
private val PLAY_SIZE = 80.dp
private val SKIP_SIZE = 54.dp

/**
 * Accent tones taken from the app theme (light top, saturated base), the same idea as the liquid fill
 * of the volume / brightness bars: pale at the top, full color at the bottom.
 */
@Composable
private fun rememberGlassAccent(): Pair<Color, Color> {
    val primary = MaterialTheme.colorScheme.primary
    return remember(primary) { lerp(primary, Color.White, 0.72f) to primary }
}

/** Paints whatever is drawn by this composable (an icon) with a vertical accent gradient. */
private fun Modifier.accentGradientFill(top: Color, bottom: Color): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.verticalGradient(
                0f to lerp(top, Color.White, 0.45f),
                0.45f to top,
                1f to bottom,
            ),
            blendMode = BlendMode.SrcIn,
        )
    }

@Composable
private fun SkipCircleButton(
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean,
    form: PopForm,
) {
    val (accentTop, accentBase) = rememberGlassAccent()
    val k = if (enabled) 1f else 0.35f
    // nudge the icon towards the visual centre of the triangle
    val shift = when (form) {
        PopForm.TriangleRight -> (-4).dp
        PopForm.TriangleLeft -> 4.dp
        PopForm.Squircle -> 0.dp
    }
    GlassCircleButton(
        onClick = onClick,
        diameter = SKIP_SIZE,
        shape = PopShape(form),
        enabled = enabled,
        contentShift = shift,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier
                .size(30.dp)
                .accentGradientFill(accentTop.copy(alpha = k), accentBase.copy(alpha = k)),
        )
    }
}

/**
 * Domed dark glass, built only from cheap draw ops (no blur):
 *  - a near-black body that is a touch lighter at the upper-left, like a lit convex surface;
 *  - an accent glow pooling at the bottom and a soft one behind the icon;
 *  - a glossy highlight across the upper half, a specular arc and a tiny bright dot;
 *  - an inner edge shadow for depth and a two-line rim (bright top-left, accent bottom-right);
 *  - springy press (it also lights up a little more) plus a light haptic tick.
 */
@Composable
private fun GlassCircleButton(
    onClick: () -> Unit,
    diameter: Dp,
    shape: Shape,
    modifier: Modifier = Modifier,
    contentShift: Dp = 0.dp,
    enabled: Boolean = true,
    hero: Boolean = false,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "glass_button_scale",
    )
    // read in the draw phase only, so pressing never recomposes the button
    val press = animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = tween(durationMillis = 140),
        label = "glass_button_press",
    )
    val haptic = LocalHapticFeedback.current
    val clickEvent = LocalPlayerButtonsClickEvent.current
    val (accentTop, accentBase) = rememberGlassAccent()
    val k = if (enabled) 1f else 0.45f
    val pool = if (hero) 0.30f else 0.22f
    val rimAccent = if (hero) 0.85f else 0.60f

    Box(
        modifier = modifier
            .size(diameter)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .drawBehind {
                val w = size.width
                val h = size.height
                val d = size.minDimension
                val pr = press.value
                val outline = shape.createOutline(size, layoutDirection, this)
                val path = (outline as? Outline.Generic)?.path ?: Path().apply { addRect(Rect(Offset.Zero, size)) }

                // liquid glass body: a faint smoky base so icons stay readable on bright scenes,
                // then a clear white tint that is stronger at the upper-left (light hits the dome there)
                drawPath(path, color = Color.Black.copy(alpha = 0.16f * k))
                drawPath(
                    path,
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = (0.22f + 0.08f * pr) * k),
                            Color.White.copy(alpha = 0.05f * k),
                        ),
                        center = Offset(w * 0.32f, h * 0.24f),
                        radius = d * 1.0f,
                    ),
                )
                // very soft accent tint pooling at the bottom
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            accentBase.copy(alpha = ((pool + 0.15f * pr) * k).coerceIn(0f, 1f)),
                            Color.Transparent,
                        ),
                        center = Offset(w * 0.5f, h * 1.05f),
                        radius = d * 0.75f,
                    ),
                )
                // gloss across the upper part
                drawOval(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = (0.30f + 0.06f * pr) * k),
                            Color.Transparent,
                        ),
                        startY = h * 0.04f,
                        endY = h * 0.46f,
                    ),
                    topLeft = Offset(w * 0.16f, h * 0.05f),
                    size = Size(w * 0.68f, h * 0.40f),
                )
                // tiny specular dot
                drawCircle(
                    color = Color.White.copy(alpha = 0.70f * k),
                    radius = 1.5.dp.toPx(),
                    center = Offset(w * 0.30f, h * 0.22f),
                )
                // rim (stroke is clipped, so the visible width is half): bright top-left, accent bottom-right
                val rimWidth = (if (hero) 1.6.dp else 1.3.dp).toPx() * 2f
                drawPath(
                    path,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.75f * k),
                            Color.White.copy(alpha = 0.10f * k),
                            accentTop.copy(alpha = (rimAccent * k).coerceIn(0f, 1f)),
                        ),
                        start = Offset.Zero,
                        end = Offset(w, h),
                    ),
                    style = Stroke(width = rimWidth),
                )
            }
            .clickable(
                interactionSource = interaction,
                indication = ripple(color = accentTop),
                enabled = enabled,
            ) {
                clickEvent()
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.offset(x = contentShift), contentAlignment = Alignment.Center) {
            content()
        }
    }
}

private enum class PopForm { TriangleLeft, TriangleRight, Squircle }

/** "Gradient Pop" outlines: soft rounded triangles for skip, a squircle for play/pause. */
private class PopShape(private val form: PopForm) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val w = size.width
        val h = size.height
        val path = Path()
        if (form == PopForm.Squircle) {
            val r = size.minDimension * 0.32f
            path.addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(r, r)))
            return Outline.Generic(path)
        }
        val pts = if (form == PopForm.TriangleRight) {
            listOf(Offset(w * 0.04f, h * 0.02f), Offset(w * 0.99f, h * 0.5f), Offset(w * 0.04f, h * 0.98f))
        } else {
            listOf(Offset(w * 0.96f, h * 0.02f), Offset(w * 0.96f, h * 0.98f), Offset(w * 0.01f, h * 0.5f))
        }
        val round = 0.32f
        fun towards(a: Offset, b: Offset, t: Float) = Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
        for (i in pts.indices) {
            val prev = pts[(i + pts.size - 1) % pts.size]
            val cur = pts[i]
            val next = pts[(i + 1) % pts.size]
            val start = towards(cur, prev, round)
            val end = towards(cur, next, round)
            if (i == 0) path.moveTo(start.x, start.y) else path.lineTo(start.x, start.y)
            path.quadraticBezierTo(cur.x, cur.y, end.x, end.y)
        }
        path.close()
        return Outline.Generic(path)
    }
}
// ANZ <--
