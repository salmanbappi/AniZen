package tachiyomi.presentation.core.components

import android.view.ViewConfiguration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastMaxBy
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.roundToInt

@Composable
fun VerticalFastScroller(
    listState: LazyListState,
    modifier: Modifier = Modifier,
    thumbAllowed: () -> Boolean = { true },
    thumbColor: Color = MaterialTheme.colorScheme.primary,
    topContentPadding: Dp = Dp.Hairline,
    bottomContentPadding: Dp = Dp.Hairline,
    endContentPadding: Dp = Dp.Hairline,
    content: @Composable () -> Unit,
) {
    SubcomposeLayout(modifier = modifier) { constraints ->
        val contentPlaceable = subcompose("content", content).map { it.measure(constraints) }
        val contentHeight = contentPlaceable.fastMaxBy { it.height }?.height ?: 0
        val contentWidth = contentPlaceable.fastMaxBy { it.width }?.width ?: 0

        val scrollerConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val scrollerPlaceable = subcompose("scroller") {
            // All scroll-state reads are delegated to ListFastScrollThumb so the
            // SubcomposeLayout measure pass never touches listState.layoutInfo
            // (which changes every frame during scroll, causing per-frame recomposition).
            ListFastScrollThumb(
                listState = listState,
                thumbAllowed = thumbAllowed,
                thumbColor = thumbColor,
                topContentPadding = topContentPadding,
                bottomContentPadding = bottomContentPadding,
                endContentPadding = endContentPadding,
                contentHeightPx = contentHeight,
            )
        }.map { it.measure(scrollerConstraints) }
        val scrollerWidth = scrollerPlaceable.fastMaxBy { it.width }?.width ?: 0

        layout(contentWidth, contentHeight) {
            contentPlaceable.fastForEach { it.place(0, 0) }
            scrollerPlaceable.fastForEach { it.placeRelative(contentWidth - scrollerWidth, 0) }
        }
    }
}

/**
 * The fast-scroll thumb for a [LazyListState].
 *
 * Extracted into its own composable so **zero** [LazyListState.layoutInfo] or
 * [LazyListState.isScrollInProgress] reads happen during composition (which would
 * cause a recomposition on every scroll frame inside the [SubcomposeLayout]).
 * All reactive logic runs inside [LaunchedEffect] + [snapshotFlow].
 *
 * ### Proportion math
 * `proportion = itemsBefore / (totalItems - viewportItems)`
 * where `itemsBefore` is the fractional count of items above the viewport (negative
 * first-item offset divided by avg item size gives sub-item precision).
 * This expression maps to exactly **0.0** at the top and **1.0** at the bottom —
 * fixing the "thumb never reaches the end" bug from the previous estimation approach.
 */
@Composable
private fun ListFastScrollThumb(
    listState: LazyListState,
    thumbAllowed: () -> Boolean,
    thumbColor: Color,
    topContentPadding: Dp,
    bottomContentPadding: Dp,
    endContentPadding: Dp,
    contentHeightPx: Int,
) {
    val density = LocalDensity.current

    val thumbTopPadding = remember(density, topContentPadding) {
        with(density) { topContentPadding.toPx() }
    }
    val thumbBottomPadding = remember(density, bottomContentPadding) {
        with(density) { bottomContentPadding.toPx() }
    }
    val thumbHeightPx = remember(density) { with(density) { ThumbLength.toPx() } }
    val trackHeightPx = remember(contentHeightPx, thumbTopPadding, thumbBottomPadding, thumbHeightPx) {
        (contentHeightPx - thumbTopPadding - thumbBottomPadding - thumbHeightPx).coerceAtLeast(0f)
    }

    // ANZ -->
    var thumbOffsetY by remember(thumbTopPadding) { mutableFloatStateOf(thumbTopPadding) } // ANZ
    val dragInteractionSource = remember { MutableInteractionSource() }
    val isThumbDragged by dragInteractionSource.collectIsDraggedAsState()

    val scrolled = remember {
        MutableSharedFlow<Unit>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    }

    // ── List → Thumb ─────────────────────────────────────────────────────────
    LaunchedEffect(listState, trackHeightPx, thumbTopPadding) {
        snapshotFlow {
            if (isThumbDragged) return@snapshotFlow null

            val info = listState.layoutInfo
            val totalItems = info.totalItemsCount
            if (totalItems == 0 || info.visibleItemsInfo.isEmpty()) return@snapshotFlow null

            val visibleItems = info.visibleItemsInfo
            val firstItem = visibleItems.first()
            val lastItem = visibleItems.last()

            if (lastItem.index == totalItems - 1 && firstItem.index == 0) {
                return@snapshotFlow 0f
            }

            val isAtBottom = lastItem.index == totalItems - 1 &&
                (lastItem.offset + lastItem.size) <= info.viewportEndOffset

            if (isAtBottom) {
                1f
            } else {
                val firstItemSize = firstItem.size.coerceAtLeast(1).toFloat()
                val itemsBefore = firstItem.index.toFloat() +
                    (info.viewportStartOffset - firstItem.offset).toFloat() / firstItemSize
                val visibleCount = visibleItems.size.toFloat()
                val scrollableItems = (totalItems.toFloat() - visibleCount).coerceAtLeast(1f)
                (itemsBefore / scrollableItems).coerceIn(0f, 1f)
            }
        }.collectLatest { proportion ->
            if (proportion == null) return@collectLatest
            thumbOffsetY = trackHeightPx * proportion + thumbTopPadding
            if (listState.isScrollInProgress) scrolled.tryEmit(Unit)
        }
    }

    // ── Thumb → List ─────────────────────────────────────────────────────────
    LaunchedEffect(listState, trackHeightPx, thumbTopPadding) {
        var lastScrolledIndex = -1
        snapshotFlow { if (isThumbDragged) thumbOffsetY else null }
            .collectLatest { y ->
                if (y == null) {
                    lastScrolledIndex = -1
                    return@collectLatest
                }

                val proportion = if (trackHeightPx > 0f) {
                    ((y - thumbTopPadding) / trackHeightPx).coerceIn(0f, 1f)
                } else {
                    0f
                }

                val info = listState.layoutInfo
                val totalItems = info.totalItemsCount
                if (totalItems == 0) return@collectLatest

                val visibleCount = info.visibleItemsInfo.size // ANZ
                val scrollableItems = (totalItems - visibleCount).coerceAtLeast(1)
                val targetIndex = (proportion * scrollableItems).roundToInt().coerceIn(0, totalItems - 1)
                if (targetIndex != lastScrolledIndex) {
                    lastScrolledIndex = targetIndex
                    listState.scrollToItem(targetIndex, 0)
                    scrolled.tryEmit(Unit)
                }
            }
    }
    // ANZ <--

    // ── Visibility ───────────────────────────────────────────────────────────
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(listState, isThumbDragged) {
        snapshotFlow {
            listState.isScrollInProgress || isThumbDragged
        }.collectLatest { active ->
            if (active) {
                val info = listState.layoutInfo
                val isLongList = info.totalItemsCount > info.visibleItemsInfo.size * 1.25f
                if (thumbAllowed() && isLongList) {
                    alpha.snapTo(1f)
                }
            } else {
                delay(ScrollBarVisibilityDurationMillis)
                alpha.animateTo(0f, animationSpec = ImmediateFadeOutAnimationSpec)
            }
        }
    }

    val draggableState = rememberDraggableState { delta ->
        val newOffsetY = thumbOffsetY + delta
        thumbOffsetY = newOffsetY.coerceIn(
            thumbTopPadding,
            thumbTopPadding + trackHeightPx,
        )
    }

    Box(
        modifier = Modifier
            .offset { IntOffset(0, thumbOffsetY.roundToInt()) }
            .draggable(
                interactionSource = dragInteractionSource,
                orientation = Orientation.Vertical,
                state = draggableState,
            )
            // ANZ -->
            .then(if (isThumbDragged) Modifier.systemGestureExclusion() else Modifier) // ANZ
            // ANZ <--
            .height(ThumbLength)
            .padding(end = endContentPadding)
            .width(ThumbThickness)
            .graphicsLayer { this.alpha = alpha.value }
            .background(color = thumbColor, shape = ThumbShape),
    )
}

@Composable
fun VerticalGridFastScroller(
    state: LazyGridState,
    columns: GridCells,
    arrangement: Arrangement.Horizontal,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    thumbAllowed: () -> Boolean = { true },
    thumbColor: Color = MaterialTheme.colorScheme.primary,
    topContentPadding: Dp = Dp.Hairline,
    bottomContentPadding: Dp = Dp.Hairline,
    endContentPadding: Dp = Dp.Hairline,
    content: @Composable () -> Unit,
) {
    val slotSizesSums = rememberColumnWidthSums(
        columns = columns,
        horizontalArrangement = arrangement,
        contentPadding = contentPadding,
    )

    SubcomposeLayout(modifier = modifier) { constraints ->
        val contentPlaceable = subcompose("content", content).map { it.measure(constraints) }
        val contentHeight = contentPlaceable.fastMaxBy { it.height }?.height ?: 0
        val contentWidth = contentPlaceable.fastMaxBy { it.width }?.width ?: 0

        val columnCount = slotSizesSums(constraints).size.coerceAtLeast(1)
        val scrollerConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val scrollerPlaceable = subcompose("scroller") {
            GridFastScrollThumb(
                state = state,
                columnCount = columnCount,
                thumbAllowed = thumbAllowed,
                thumbColor = thumbColor,
                topContentPadding = topContentPadding,
                bottomContentPadding = bottomContentPadding,
                endContentPadding = endContentPadding,
                contentHeightPx = contentHeight,
            )
        }.map { it.measure(scrollerConstraints) }
        val scrollerWidth = scrollerPlaceable.fastMaxBy { it.width }?.width ?: 0

        layout(contentWidth, contentHeight) {
            contentPlaceable.fastForEach { it.place(0, 0) }
            scrollerPlaceable.fastForEach { it.placeRelative(contentWidth - scrollerWidth, 0) }
        }
    }
}

@Composable
private fun GridFastScrollThumb(
    state: LazyGridState,
    columnCount: Int,
    thumbAllowed: () -> Boolean,
    thumbColor: Color,
    topContentPadding: Dp,
    bottomContentPadding: Dp,
    endContentPadding: Dp,
    contentHeightPx: Int,
) {
    val density = LocalDensity.current
    val thumbTopPadding = remember(density, topContentPadding) {
        with(density) { topContentPadding.toPx() }
    }
    val thumbBottomPadding = remember(density, bottomContentPadding) {
        with(density) { bottomContentPadding.toPx() }
    }
    val thumbHeightPx = remember(density) { with(density) { ThumbLength.toPx() } }
    val trackHeightPx = remember(contentHeightPx, thumbTopPadding, thumbBottomPadding, thumbHeightPx) {
        (contentHeightPx - thumbTopPadding - thumbBottomPadding - thumbHeightPx).coerceAtLeast(0f)
    }

    // ANZ -->
    var thumbOffsetY by remember(thumbTopPadding) { mutableFloatStateOf(thumbTopPadding) } // ANZ
    val dragInteractionSource = remember { MutableInteractionSource() }
    val isThumbDragged by dragInteractionSource.collectIsDraggedAsState()
    val scrolled = remember {
        MutableSharedFlow<Unit>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    }

    // ── Grid → Thumb ─────────────────────────────────────────────────────────
    LaunchedEffect(state, trackHeightPx, thumbTopPadding, columnCount) {
        snapshotFlow {
            if (isThumbDragged) return@snapshotFlow null

            val info = state.layoutInfo
            val totalItems = info.totalItemsCount
            if (totalItems == 0 || info.visibleItemsInfo.isEmpty()) return@snapshotFlow null

            val visibleItems = info.visibleItemsInfo
            val firstItem = visibleItems.first()
            val lastItem = visibleItems.last()

            val cols = columnCount.coerceAtLeast(1)
            val totalRows = (totalItems + cols - 1) / cols

            val firstRow = firstItem.index / cols
            val lastRow = lastItem.index / cols

            if (firstRow == 0 && lastRow == totalRows - 1) {
                return@snapshotFlow 0f
            }

            val isAtBottom = lastItem.index == totalItems - 1 &&
                (lastItem.offset.y + lastItem.size.height) <= info.viewportEndOffset

            if (isAtBottom) {
                1f
            } else {
                val firstItemHeight = firstItem.size.height.coerceAtLeast(1).toFloat()
                val rowsBefore = firstRow.toFloat() +
                    (info.viewportStartOffset - firstItem.offset.y).toFloat() / firstItemHeight
                val visibleRows = (lastRow - firstRow + 1).toFloat()
                val scrollableRows = (totalRows.toFloat() - visibleRows).coerceAtLeast(1f)
                (rowsBefore / scrollableRows).coerceIn(0f, 1f)
            }
        }.collectLatest { proportion ->
            if (proportion == null) return@collectLatest
            thumbOffsetY = trackHeightPx * proportion + thumbTopPadding
            if (state.isScrollInProgress) scrolled.tryEmit(Unit)
        }
    }

    // ── Thumb → Grid ─────────────────────────────────────────────────────────
    LaunchedEffect(state, trackHeightPx, thumbTopPadding, columnCount) {
        var lastScrolledIndex = -1
        snapshotFlow { if (isThumbDragged) thumbOffsetY else null }
            .collectLatest { y ->
                if (y == null) {
                    lastScrolledIndex = -1
                    return@collectLatest
                }

                val proportion = if (trackHeightPx > 0f) {
                    ((y - thumbTopPadding) / trackHeightPx).coerceIn(0f, 1f)
                } else {
                    0f
                }

                val info = state.layoutInfo
                val totalItems = info.totalItemsCount
                if (totalItems == 0) return@collectLatest

                val cols = columnCount.coerceAtLeast(1)
                val totalRows = (totalItems + cols - 1) / cols
                val visibleItems = info.visibleItemsInfo
                val visibleRows = if (visibleItems.isNotEmpty()) {
                    (visibleItems.last().index / cols - visibleItems.first().index / cols + 1)
                } else {
                    1
                }
                val scrollableRows = (totalRows - visibleRows).coerceAtLeast(1)
                val targetRow = (proportion * scrollableRows).roundToInt().coerceIn(0, totalRows - 1)
                val targetIndex = (targetRow * cols).coerceIn(0, totalItems - 1)

                if (targetIndex != lastScrolledIndex) {
                    lastScrolledIndex = targetIndex
                    state.scrollToItem(targetIndex, 0)
                    scrolled.tryEmit(Unit)
                }
            }
    }
    // ANZ <--

    val alpha = remember { Animatable(0f) }
    LaunchedEffect(state, isThumbDragged) {
        snapshotFlow {
            state.isScrollInProgress || isThumbDragged
        }.collectLatest { active ->
            if (active) {
                val info = state.layoutInfo
                val isLongList = info.totalItemsCount > info.visibleItemsInfo.size * 1.25f
                if (thumbAllowed() && isLongList) {
                    alpha.snapTo(1f)
                }
            } else {
                delay(ScrollBarVisibilityDurationMillis)
                alpha.animateTo(0f, animationSpec = ImmediateFadeOutAnimationSpec)
            }
        }
    }

    val draggableState = rememberDraggableState { delta ->
        val newOffsetY = thumbOffsetY + delta
        thumbOffsetY = newOffsetY.coerceIn(
            thumbTopPadding,
            thumbTopPadding + trackHeightPx,
        )
    }

    Box(
        modifier = Modifier
            .offset { IntOffset(0, thumbOffsetY.roundToInt()) }
            .draggable(
                interactionSource = dragInteractionSource,
                orientation = Orientation.Vertical,
                state = draggableState,
            )
            // ANZ -->
            .then(if (isThumbDragged) Modifier.systemGestureExclusion() else Modifier) // ANZ
            // ANZ <--
            .height(ThumbLength)
            .padding(end = endContentPadding)
            .width(ThumbThickness)
            .graphicsLayer { this.alpha = alpha.value }
            .background(color = thumbColor, shape = ThumbShape),
    )
}

@Composable
private fun rememberColumnWidthSums(
    columns: GridCells,
    horizontalArrangement: Arrangement.Horizontal,
    contentPadding: PaddingValues,
) = remember<Density.(Constraints) -> kotlinx.collections.immutable.ImmutableList<Int>>(
    columns,
    horizontalArrangement,
    contentPadding,
) {
    { constraints ->
        require(constraints.maxWidth != Constraints.Infinity) {
            "LazyVerticalGrid's width should be bound by parent"
        }
        val horizontalPadding = contentPadding.calculateStartPadding(LayoutDirection.Ltr) +
            contentPadding.calculateEndPadding(LayoutDirection.Ltr)
        val gridWidth = constraints.maxWidth - horizontalPadding.roundToPx()
        with(columns) {
            calculateCrossAxisCellSizes(
                gridWidth,
                horizontalArrangement.spacing.roundToPx(),
            ).toMutableList().apply {
                for (i in 1..<size) {
                    this[i] += this[i - 1]
                }
            }.toImmutableList()
        }
    }
}

private class MutableData<T>(var value: T)

object Scroller {
    const val STICKY_HEADER_KEY_PREFIX = "sticky:"
    const val EXACT_HEIGHT_KEY_PREFIX = "exact:"
}

private val ThumbLength = 48.dp
private val ThumbThickness = 12.dp
private val ThumbShape = RoundedCornerShape(ThumbThickness / 2)
private val ScrollBarVisibilityDurationMillis = 2000L
private val ImmediateFadeOutAnimationSpec = tween<Float>(
    durationMillis = ViewConfiguration.getScrollBarFadeDuration(),
)
private val LazyListItemInfo.top: Int
    get() = offset

private val LazyListItemInfo.bottom: Int
    get() = offset + size
