@file:Suppress("PropertyName")

package eu.kanade.presentation.anime.components

import androidx.annotation.ColorInt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.util.system.CoverColorObserver
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.model.asAnimeCover
import tachiyomi.presentation.core.components.SkeletonItem
import tachiyomi.presentation.core.util.collectAsState as collectAsStatePref
import tachiyomi.domain.anime.model.AnimeCover as DomainMangaCover

enum class AnimeCover(val ratio: Float) {
    Square(1f / 1f),
    Book(2f / 3f),

    // KMK -->
    Panorama(3f / 2f),
    // KMK <--
    ;

    enum class Size {
        Normal,
        Medium,
        Big,
    }

    @Composable
    operator fun invoke(
        data: Any?,
        modifier: Modifier = Modifier,
        contentDescription: String = "",
        shape: Shape = MaterialTheme.shapes.medium,
        onClick: (() -> Unit)? = null,
        // KMK -->
        alpha: Float = 1f,
        bgColor: Color? = null,
        @ColorInt tint: Int? = null,
        /** Perform action when cover loaded, specifically generating color map. If the cover doesn't update, it won't be called */
        onCoverLoaded: ((DomainMangaCover, result: AsyncImagePainter.State.Success) -> Unit)? = null,
        size: Size = Size.Normal,
        scale: ContentScale = ContentScale.Crop,
        ratio: Float = this.ratio,
        shouldExtractColor: Boolean = false,
        /**
         * Whether this cover's aspect ratio needs to be measured, i.e. whether panorama mode
         * is in effect for it. Defaults to the global preference; pass the resolved value
         * when a screen can force panorama on or off independently.
         */
        measureRatio: Boolean = CoverSettings.panoramaCover,
        // KMK <--
    ) {
        val context = LocalContext.current
        val animatedTransitions = CoverSettings.animatedTransitions

        // Only covers that actually need the load state pay for observing it. Panorama mode
        // does need it (picking Book vs Panorama depends on the measured aspect ratio, which
        // only the loaded image provides), but measuring is just width/height — no bitmap.
        //
        // Everything else — the library grid with panorama off, which is the default —
        // renders placeholder and error through Coil's own painters, so a load completing
        // never recomposes the item. Observing state where nobody needs it used to recompose
        // every visible cell on every load transition during a scroll.
        val observeState = shouldExtractColor || measureRatio || onCoverLoaded != null

        if (observeState) {
            CoverWithState(
                data = data,
                modifier = modifier,
                contentDescription = contentDescription,
                shape = shape,
                onClick = onClick,
                alpha = alpha,
                bgColor = bgColor,
                tint = tint,
                onCoverLoaded = onCoverLoaded,
                size = size,
                scale = scale,
                ratio = ratio,
                shouldExtractColor = shouldExtractColor,
                measureRatio = measureRatio,
                animatedTransitions = animatedTransitions,
            )
            return
        }

        Box(
            modifier = modifier
                .aspectRatio(ratio)
                .then(
                    if (shape != RectangleShape) {
                        Modifier.graphicsLayer {
                            this.shape = shape
                            clip = true
                        }
                    } else {
                        Modifier
                    },
                )
                .then(
                    if (onClick != null) {
                        Modifier.clickable(
                            role = Role.Button,
                            onClick = onClick,
                        )
                    } else {
                        Modifier
                    },
                ),
        ) {
            AsyncImage(
                model = remember(data, animatedTransitions) {
                    ImageRequest.Builder(context)
                        .data(data)
                        .precision(coil3.size.Precision.INEXACT)
                        .crossfade(animatedTransitions)
                        .build()
                },
                contentDescription = contentDescription,
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (alpha < 1f) {
                            Modifier.graphicsLayer { this.alpha = alpha }
                        } else {
                            Modifier
                        },
                    ),
                contentScale = scale,
                // Placeholder rather than a background modifier: the loaded cover is cropped
                // to fill, so a background would just be permanent overdraw behind it.
                placeholder = coverPlaceholderPainter(bgColor),
                error = CoverErrorPainter.get(context),
            )
        }
    }

    /**
     * The state-observing implementation, used by covers that need the loaded image: the
     * entry details header (palette seed), any caller passing [onCoverLoaded], and — when
     * panorama mode is on — list/grid items that must measure the cover's aspect ratio.
     */
    @Composable
    private fun CoverWithState(
        data: Any?,
        modifier: Modifier,
        contentDescription: String,
        shape: Shape,
        onClick: (() -> Unit)?,
        alpha: Float,
        bgColor: Color?,
        @ColorInt tint: Int?,
        onCoverLoaded: ((DomainMangaCover, result: AsyncImagePainter.State.Success) -> Unit)?,
        size: Size,
        scale: ContentScale,
        ratio: Float,
        shouldExtractColor: Boolean,
        measureRatio: Boolean,
        animatedTransitions: Boolean,
    ) {
        val context = LocalContext.current

        var state by remember(data) { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }
        val isSuccess = state is AsyncImagePainter.State.Success
        val isError = state is AsyncImagePainter.State.Error

        val scope = rememberCoroutineScope()
        LaunchedEffect(state, data, shouldExtractColor, measureRatio) {
            if (!shouldExtractColor && !measureRatio && onCoverLoaded == null) return@LaunchedEffect
            val currentState = state
            if (currentState is AsyncImagePainter.State.Success) {
                val cover = when (data) {
                    is Anime -> data.asAnimeCover()
                    is DomainMangaCover -> data
                    else -> null
                }
                if (cover != null && (shouldExtractColor || measureRatio)) {
                    // Skip the extraction coroutine entirely when a current version of both
                    // color and ratio is already cached: zero main-thread work per cover
                    // during a fast fling over a large category.
                    val hasCachedColor = !shouldExtractColor ||
                        CoverColorObserver.get(cover.animeId, cover.lastModified) != null
                    val hasCachedRatio = CoverColorObserver.getRatio(cover.animeId, cover.lastModified) != null
                    if (!hasCachedColor || !hasCachedRatio) {
                        scope.launch {
                            eu.kanade.tachiyomi.util.system.CoverColorExtractor.extract(
                                cover = cover,
                                state = currentState,
                                extractColor = shouldExtractColor,
                            )
                        }
                    }
                }
                if (data is Anime) onCoverLoaded?.invoke(data.asAnimeCover(), currentState)
                if (data is DomainMangaCover) onCoverLoaded?.invoke(data, currentState)
            }
        }

        Box(
            modifier = modifier
                .aspectRatio(ratio)
                .then(
                    if (shape != RectangleShape) {
                        Modifier.graphicsLayer {
                            this.shape = shape
                            clip = true
                        }
                    } else {
                        Modifier
                    },
                )
                .then(
                    if (!isSuccess) {
                        Modifier.background(bgColor ?: CoverPlaceholderColor)
                    } else {
                        Modifier
                    },
                )
                .then(
                    if (onClick != null) {
                        Modifier.clickable(
                            role = Role.Button,
                            onClick = onClick,
                        )
                    } else {
                        Modifier
                    },
                ),
        ) {
            // Pulsing background
            if (animatedTransitions && !isSuccess) {
                CoverLoading(shape, bgColor)
            }

            AsyncImage(
                model = remember(data, animatedTransitions) {
                    ImageRequest.Builder(context)
                        .data(data)
                        .precision(coil3.size.Precision.INEXACT)
                        .crossfade(animatedTransitions)
                        .build()
                },
                contentDescription = contentDescription,
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (alpha < 1f) {
                            Modifier.graphicsLayer { this.alpha = alpha }
                        } else {
                            Modifier
                        },
                    ),
                contentScale = scale,
                onState = { state = it },
            )

            if (isError) {
                CoverError(size, tint, contentDescription)
            }
        }
    }

    companion object {
        val COVER_TEMPLATE_SIZE_BIG = 16.dp
        val COVER_TEMPLATE_SIZE_MEDIUM = 24.dp
        val COVER_TEMPLATE_SIZE_NORMAL = 32.dp

        @Composable
        private fun BoxScope.CoverLoading(shape: Shape, bgColor: Color?) {
            SkeletonItem(
                modifier = Modifier.fillMaxSize(),
                shape = shape,
                color = (bgColor ?: CoverPlaceholderColor).copy(alpha = 0.5f),
            )
        }

        @Composable
        private fun BoxScope.CoverError(size: Size, tint: Int?, contentDescription: String) {
            androidx.compose.foundation.Image(
                imageVector = ImageVector.vectorResource(R.drawable.cover_error_vector),
                contentDescription = contentDescription,
                modifier = Modifier
                    .size(
                        when (size) {
                            Size.Big -> COVER_TEMPLATE_SIZE_BIG
                            Size.Medium -> COVER_TEMPLATE_SIZE_MEDIUM
                            else -> COVER_TEMPLATE_SIZE_NORMAL
                        },
                    )
                    .align(Alignment.Center),
                colorFilter = ColorFilter.tint(
                    tint?.let { Color(it) } ?: CoverPlaceholderOnBgColor,
                ),
            )
        }

        @Composable
        fun getRatio(animeId: Long): Float {
            if (!CoverSettings.panoramaCover) return Book.ratio

            return remember(animeId) {
                CoverColorObserver.ratios.value[animeId] ?: Book.ratio
            }
        }

        @Composable
        fun getEntry(animeId: Long, usePanoramaOverride: Boolean? = null): Pair<AnimeCover, Float> {
            val usePanorama = usePanoramaOverride ?: CoverSettings.panoramaCover

            if (!usePanorama) return Book to Book.ratio

            val ratio = remember(animeId) {
                CoverColorObserver.ratios.value[animeId] ?: Book.ratio
            }

            return remember(ratio) {
                val entry = if (ratio > RatioSwitchToPanorama) Panorama else Book
                entry to ratio
            }
        }

        /**
         * Solid placeholder shown until the cover is drawn. The default colour reuses one
         * shared painter so a screenful of covers allocates none.
         */
        @Composable
        private fun coverPlaceholderPainter(bgColor: Color?): ColorPainter {
            if (bgColor == null) return DefaultCoverPlaceholder
            return remember(bgColor) { ColorPainter(bgColor) }
        }
    }
}

enum class AnimeCoverHide(private val ratio: Float) {
    Square(1f / 1f),
    Book(2f / 3f),
    ;

    @Composable
    operator fun invoke(
        modifier: Modifier = Modifier,
        contentDescription: String = "",
        shape: Shape = MaterialTheme.shapes.medium,
        onClick: (() -> Unit)? = null,
        // KMK -->
        /** background color, which used for loading/error indicator */
        bgColor: Color? = CoverPlaceholderColor,
        /** onBackground color, which used for loading/error indicator */
        @ColorInt tint: Int? = null,
    ) {
        val modifierColored = modifier
            .aspectRatio(ratio)
            .clip(shape)
            .background(bgColor ?: CoverPlaceholderColor)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        role = Role.Button,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            )

        Box(
            modifier = modifierColored,
        ) {
            androidx.compose.foundation.Image(
                imageVector = ImageVector.vectorResource(R.drawable.ic_baseline_menu_book_24),
                contentDescription = contentDescription,
                modifier = Modifier
                    .size(32.dp)
                    .align(Alignment.Center),
                colorFilter = ColorFilter.tint(
                    tint?.let { Color(it) } ?: CoverPlaceholderOnBgColor,
                ),
            )
        }
    }
}

internal const val RatioSwitchToPanorama = 1.1f

internal val CoverPlaceholderColor = Color(0x1F888888)
internal val CoverPlaceholderOnBgColor = Color(0x8F888888)

/** Shared placeholder painter for the default cover background colour. */
private val DefaultCoverPlaceholder = ColorPainter(CoverPlaceholderColor)
