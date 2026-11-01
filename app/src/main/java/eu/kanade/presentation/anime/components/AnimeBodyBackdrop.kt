package eu.kanade.presentation.anime.components

import androidx.compose.animation.core.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import eu.kanade.presentation.library.components.CoverTonesCache
import kotlin.math.roundToInt

// ANZ -->
// Painted at 1/3 of the resolution and stretched back up (like AuroraBackdrop): the colours are soft anyway.
private const val LowRes = 3

// Where the soft top edge of the body surface rests at scroll 0 (fraction of the screen height): just under the
// cover, so what is behind the cover itself stays as it was. The edge then moves up with the content.
private const val BodyStart = 0.66f
private const val BodyFade = 0.10f

// How solid the surface is (a little of the moving lights still shows through) and how strong the cover colours are.
private const val BodyOpacity = 0.95f
private const val BodyMosaic = 0.85f
private const val BodyVeil = 0.05f

/**
 * The part of the details page below the cover (info, genres, cast, episodes) gets its own calm surface in the colours
 * of the cover: a dark tint of the cover colour with the blurred picture of the cover (see CoverTones) over it.
 * It rises from under the cover and slides up together with the content, so the backdrop behind the cover is left alone
 * and the reading area stays calm and readable.
 */
@Composable
fun AnimeBodyBackdrop(
    animeId: Long,
    coverVersion: Long,
    ambient: AnimeAmbient,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val tones = CoverTonesCache.get(animeId, coverVersion)
    val mosaic = tones?.mosaic
    // a mid-tone of the cover colour (not the near-black page tint): no black patch behind the episodes
    val base by animateColorAsState(
        // (a black-and-white cover has no lights and its tones are plain black: use the page tint then)
        targetValue = tones?.takeIf { it.lights.isNotEmpty() }?.mid ?: ambient.top,
        animationSpec = tween(durationMillis = 700),
        label = "bodyBase",
    )
    val mosaicAlpha by animateFloatAsState(
        targetValue = if (mosaic != null) BodyMosaic else 0f,
        animationSpec = tween(durationMillis = 700),
        label = "bodyMosaic",
    )
    // height of the first list item (the cover header), remembered while it is on screen
    val headerHeight = remember { FloatArray(1) }
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
            .drawBehind {
                val w = size.width
                val h = size.height
                // how far the content has moved up, in screen pixels
                var scrolled = listState.firstVisibleItemScrollOffset.toFloat()
                if (listState.firstVisibleItemIndex == 0) {
                    val first = listState.layoutInfo.visibleItemsInfo.firstOrNull()
                    if (first != null && first.index == 0) headerHeight[0] = first.size.toFloat()
                } else {
                    scrolled += headerHeight[0]
                }
                val edge = h * BodyStart - scrolled / LowRes
                if (edge >= h) return@drawBehind
                val fade = h * BodyFade

                drawRect(color = base)
                val image = mosaic
                if (image != null && mosaicAlpha > 0.01f) {
                    drawImage(
                        image = image,
                        srcSize = IntSize(image.width, image.height),
                        dstSize = IntSize(w.roundToInt(), h.roundToInt()),
                        alpha = mosaicAlpha,
                        filterQuality = FilterQuality.Low,
                    )
                }
                drawRect(color = Color.Black.copy(alpha = BodyVeil))
                // the soft top edge: everything above it is cut away, below it the surface fades in
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.Transparent,
                        1f to Color.Black.copy(alpha = BodyOpacity),
                        startY = edge,
                        endY = edge + fade,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            },
    )
}
// ANZ <--
