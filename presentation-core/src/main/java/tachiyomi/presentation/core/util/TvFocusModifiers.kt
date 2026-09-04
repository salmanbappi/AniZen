package tachiyomi.presentation.core.util

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Whether this device is driven by a D-pad / remote.
 *
 * The focus-highlight modifiers below only do anything on such a device, but they are applied
 * to every grid cell and list row. Checking a plain flag *before* entering [composed] keeps
 * the touch-device path free of the per-item `Animatable` + `LaunchedEffect` +
 * `onFocusChanged` node that `composed {}` would otherwise materialize for each item.
 *
 * Set once from `App.onCreate`; defaults to false so a missed initialization degrades to
 * "no focus highlight" rather than to a slow scroll.
 */
object TvDevice {
    @Volatile
    var isTv: Boolean = false
}

/**
 * Universal focus highlight modifier.
 * - By default (borderWidth = 0.dp, focusedScale = 1.0f): Uses clean Komikku / Material 3 container surface tint.
 * - When borderWidth > 0.dp or focusedScale > 1.0f: Renders high-contrast glowing theme outline & animated scale (used for Anime Cards & Setup).
 *
 * No-op on touch devices: nothing can take D-pad focus there, so the highlight would never
 * be drawn anyway.
 */
fun Modifier.tvFocusHighlight(
    shape: Shape = RoundedCornerShape(12.dp),
    borderWidth: Dp = 0.dp,
    focusedBorderColor: Color? = null,
    focusedScale: Float = 1.0f,
    focusedBackgroundAlpha: Float = 0.12f,
    onFocusChange: ((Boolean) -> Unit)? = null,
): Modifier {
    if (!TvDevice.isTv) return this
    return composed {
        var isFocused by remember { mutableStateOf(false) }
        val borderColor = focusedBorderColor ?: MaterialTheme.colorScheme.primary
        val scale by animateFloatAsState(
            targetValue = if (isFocused && focusedScale > 1f) focusedScale else 1f,
            animationSpec = tween(durationMillis = 150),
            label = "tvFocusScale",
        )

        this
            .onFocusChanged { state ->
                isFocused = state.isFocused
                onFocusChange?.invoke(state.isFocused)
            }
            .then(
                if (focusedScale > 1f) {
                    Modifier.scale(scale)
                } else {
                    Modifier
                },
            )
            .then(
                if (isFocused) {
                    Modifier.drawBehind {
                        val outline = shape.createOutline(size, layoutDirection, this)
                        if (focusedBackgroundAlpha > 0f) {
                            drawOutline(
                                outline = outline,
                                color = borderColor.copy(alpha = focusedBackgroundAlpha),
                            )
                        }
                        if (borderWidth > 0.dp) {
                            drawOutline(
                                outline = outline,
                                color = borderColor,
                                style = Stroke(width = borderWidth.toPx()),
                            )
                        }
                    }
                } else {
                    Modifier
                },
            )
    }
}

/**
 * Glowing focus highlight specifically for Anime Cards (Library & Browse grids) and Setup screens.
 */
fun Modifier.tvGlowFocusHighlight(
    shape: Shape = RoundedCornerShape(12.dp),
    borderWidth: Dp = 2.5.dp,
    focusedBorderColor: Color? = null,
    focusedScale: Float = 1.04f,
    focusedBackgroundAlpha: Float = 0.14f,
    onFocusChange: ((Boolean) -> Unit)? = null,
): Modifier = tvFocusHighlight(
    shape = shape,
    borderWidth = borderWidth,
    focusedBorderColor = focusedBorderColor,
    focusedScale = focusedScale,
    focusedBackgroundAlpha = focusedBackgroundAlpha,
    onFocusChange = onFocusChange,
)

/**
 * Subtle focus highlight modifier for list rows, settings items, and episode rows.
 */
fun Modifier.tvListItemFocusHighlight(
    shape: Shape = RoundedCornerShape(8.dp),
    borderWidth: Dp = 0.dp,
    focusedBorderColor: Color? = null,
    focusedBackgroundAlpha: Float = 0.12f,
    onFocusChange: ((Boolean) -> Unit)? = null,
): Modifier = tvFocusHighlight(
    shape = shape,
    borderWidth = borderWidth,
    focusedBorderColor = focusedBorderColor,
    focusedScale = 1.0f,
    focusedBackgroundAlpha = focusedBackgroundAlpha,
    onFocusChange = onFocusChange,
)

/**
 * Circular focus highlight modifier for circular icon buttons and player controls.
 */
fun Modifier.tvCircleFocusHighlight(
    borderWidth: Dp = 0.dp,
    focusedBorderColor: Color? = null,
    focusedScale: Float = 1.0f,
    focusedBackgroundAlpha: Float = 0.16f,
    onFocusChange: ((Boolean) -> Unit)? = null,
): Modifier = tvFocusHighlight(
    shape = RoundedCornerShape(50),
    borderWidth = borderWidth,
    focusedBorderColor = focusedBorderColor,
    focusedScale = focusedScale,
    focusedBackgroundAlpha = focusedBackgroundAlpha,
    onFocusChange = onFocusChange,
)
