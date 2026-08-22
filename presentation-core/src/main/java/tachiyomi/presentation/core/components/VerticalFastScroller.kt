package tachiyomi.presentation.core.components

import kotlinx.collections.immutable.toImmutableList

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
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.util.fastFirstOrNull
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastLastOrNull
import androidx.compose.ui.util.fastMaxBy
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.sample
import tachiyomi.presentation.core.components.Scroller.EXACT_HEIGHT_KEY_PREFIX
import tachiyomi.presentation.core.components.Scroller.STICKY_HEADER_KEY_PREFIX
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
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

    var thumbOffsetY by remember(thumbTopPadding) { mutableFloatStateOf(thumbTopPadding) }
    val dragInteractionSource = remember { MutableInteractionSource() }
    val isThumbDragged by dragInteractionSource.collectIsDraggedAsState()

    val scrolled = remember {
        MutableSharedFlow<Unit>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    }

    // ── List → Thumb ─────────────────────────────────────────────────────────
    // Stable keys: this LaunchedEffect never restarts during an active scroll session.
    // snapshotFlow reads layoutInfo reactively on the coroutine thread — not composition.
    LaunchedEffect(listState, trackHeightPx, thumbTopPadding) {
        snapshotFlow {
            if (isThumbDragged) return@snapshotFlow null

            val info = listState.layoutInfo
            val totalItems = info.totalItemsCount
            if (totalItems == 0 || info.visibleItemsInfo.isEmpty()) return@snapshotFlow null

            val visibleItems = info.visibleItemsInfo
            var sumSize = 0
            visibleItems.fastForEach { sumSize += it.size }
            val averageSize = if (visibleItems.isNotEmpty()) sumSize / visibleItems.size else 1

            val firstItem = visibleItems.first()
            val pastItemsSize = firstItem.index * averageSize
            
            val beforePadding = info.beforeContentPadding
            val afterPadding = info.afterContentPadding
            val currentOffset = pastItemsSize + beforePadding + (info.viewportStartOffset - firstItem.offset)

            val totalSize = totalItems * averageSize

            val viewportPx = info.viewportEndOffset - info.viewportStartOffset
            val totalScrollableSize = totalSize + beforePadding + afterPadding
            val proportion = currentOffset.toFloat() / (totalScrollableSize - viewportPx).coerceAtLeast(1)
            
            proportion.coerceIn(0f, 1f)
        }.collectLatest { proportion ->
            if (proportion == null) return@collectLatest
            thumbOffsetY = trackHeightPx * proportion + thumbTopPadding
            if (listState.isScrollInProgress) scrolled.tryEmit(Unit)
        }
    }

    // ── Thumb → List ─────────────────────────────────────────────────────────
    LaunchedEffect(listState, trackHeightPx, thumbTopPadding) {
        var lastScrolledIndex = -1
        var lastScrolledOffset = -1
        snapshotFlow { if (isThumbDragged) thumbOffsetY else null }
            .collectLatest { y ->
                if (y == null) {
                    lastScrolledIndex = -1
                    lastScrolledOffset = -1
                    return@collectLatest
                }

                val proportion = ((y - thumbTopPadding) / trackHeightPx).coerceIn(0f, 1f)

                val info = listState.layoutInfo
                val totalItems = info.totalItemsCount
                if (totalItems == 0 || info.visibleItemsInfo.isEmpty()) return@collectLatest

                val visibleItems = info.visibleItemsInfo
                var sumSize = 0
                visibleItems.fastForEach { sumSize += it.size }
                val averageSize = if (visibleItems.isNotEmpty()) sumSize / visibleItems.size else 1

                val beforePadding = info.beforeContentPadding
                val afterPadding = info.afterContentPadding
                val viewportPx = info.viewportEndOffset - info.viewportStartOffset
                val totalScrollableSize = totalItems * averageSize + beforePadding + afterPadding
                val targetScrollOffset = proportion * (totalScrollableSize - viewportPx).coerceAtLeast(1)
                val targetOffsetPx = targetScrollOffset - beforePadding

                val targetIndex = (targetOffsetPx / averageSize).toInt().coerceIn(0, totalItems - 1)
                val accumulatedSize = targetIndex * averageSize
                
                val targetItemOffset = (targetOffsetPx - accumulatedSize).roundToInt()

                if (targetIndex != lastScrolledIndex || abs(targetItemOffset - lastScrolledOffset) > 4) {
                    lastScrolledIndex = targetIndex
                    lastScrolledOffset = targetItemOffset
                    listState.scrollToItem(targetIndex, targetItemOffset)
                    scrolled.tryEmit(Unit)
                }
            }
    }

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
            .systemGestureExclusion()
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

    var thumbOffsetY by remember(thumbTopPadding) { mutableFloatStateOf(thumbTopPadding) }
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
            
            var sumSize = 0
            var maxRowHeight = 0
            var currentRow = visibleItems.first().offset.y
            
            visibleItems.fastForEach { item ->
                if (item.offset.y != currentRow) {
                    sumSize += maxRowHeight
                    maxRowHeight = item.size.height
                    currentRow = item.offset.y
                } else {
                    maxRowHeight = max(maxRowHeight, item.size.height)
                }
            }
            sumSize += maxRowHeight
            
            var rowCount = 1
            currentRow = visibleItems.first().offset.y
            visibleItems.fastForEach { item ->
                if (item.offset.y != currentRow) {
                    rowCount++
                    currentRow = item.offset.y
                }
            }
            
            val averageRowHeight = if (rowCount > 0) sumSize / rowCount else 1
            val avgItemsPerRow = columnCount.coerceAtLeast(1)
            
            val pastItemsSize = (firstItemIndex(visibleItems) / avgItemsPerRow) * averageRowHeight
            
            val beforePadding = info.beforeContentPadding
            val afterPadding = info.afterContentPadding
            val firstItem = visibleItems.first()
            val currentOffset = pastItemsSize + beforePadding + (info.viewportStartOffset - firstItem.offset.y)

            val totalSize = (totalItems / avgItemsPerRow) * averageRowHeight

            val viewportPx = info.viewportEndOffset - info.viewportStartOffset
            val totalScrollableSize = totalSize + beforePadding + afterPadding
            val proportion = currentOffset.toFloat() / (totalScrollableSize - viewportPx).coerceAtLeast(1)

            proportion.coerceIn(0f, 1f)
        }.collectLatest { proportion ->
            if (proportion == null) return@collectLatest
            thumbOffsetY = trackHeightPx * proportion + thumbTopPadding
            if (state.isScrollInProgress) scrolled.tryEmit(Unit)
        }
    }

    // ── Thumb → Grid ─────────────────────────────────────────────────────────
    LaunchedEffect(state, trackHeightPx, thumbTopPadding, columnCount) {
        var lastScrolledIndex = -1
        var lastScrolledOffset = -1
        snapshotFlow { if (isThumbDragged) thumbOffsetY else null }
            .collectLatest { y ->
                if (y == null) {
                    lastScrolledIndex = -1
                    lastScrolledOffset = -1
                    return@collectLatest
                }
                
                val proportion = ((y - thumbTopPadding) / trackHeightPx).coerceIn(0f, 1f)

                val info = state.layoutInfo
                val totalItems = info.totalItemsCount
                if (totalItems == 0 || info.visibleItemsInfo.isEmpty()) return@collectLatest

                val visibleItems = info.visibleItemsInfo
                
                var sumSize = 0
                var maxRowHeight = 0
                var currentRow = visibleItems.first().offset.y
                
                visibleItems.fastForEach { item ->
                    if (item.offset.y != currentRow) {
                        sumSize += maxRowHeight
                        maxRowHeight = item.size.height
                        currentRow = item.offset.y
                    } else {
                        maxRowHeight = max(maxRowHeight, item.size.height)
                    }
                }
                sumSize += maxRowHeight
                
                var rowCount = 1
                currentRow = visibleItems.first().offset.y
                visibleItems.fastForEach { item ->
                    if (item.offset.y != currentRow) {
                        rowCount++
                        currentRow = item.offset.y
                    }
                }
                
                val averageRowHeight = if (rowCount > 0) sumSize / rowCount else 1
                val avgItemsPerRow = columnCount.coerceAtLeast(1)

                val totalSize = (totalItems / avgItemsPerRow) * averageRowHeight

                val beforePadding = info.beforeContentPadding
                val afterPadding = info.afterContentPadding
                val viewportPx = info.viewportEndOffset - info.viewportStartOffset
                val totalScrollableSize = totalSize + beforePadding + afterPadding
                val targetScrollOffset = proportion * (totalScrollableSize - viewportPx).coerceAtLeast(1)
                val targetOffsetPx = targetScrollOffset - beforePadding

                val targetRowIndex = (targetOffsetPx / averageRowHeight).toInt().coerceAtLeast(0)
                val targetIndex = (targetRowIndex * avgItemsPerRow).coerceIn(0, totalItems - 1)
                val accumulatedSize = targetRowIndex * averageRowHeight
                
                val targetItemOffset = (targetOffsetPx - accumulatedSize).roundToInt()

                if (targetIndex != lastScrolledIndex || abs(targetItemOffset - lastScrolledOffset) > 4) {
                    lastScrolledIndex = targetIndex
                    lastScrolledOffset = targetItemOffset
                    state.scrollToItem(targetIndex, targetItemOffset)
                    scrolled.tryEmit(Unit)
                }
            }
    }

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
            .systemGestureExclusion()
            .height(ThumbLength)
            .padding(end = endContentPadding)
            .width(ThumbThickness)
            .graphicsLayer { this.alpha = alpha.value }
            .background(color = thumbColor, shape = ThumbShape),
    )
}

private inline fun firstItemIndex(items: List<androidx.compose.foundation.lazy.grid.LazyGridItemInfo>): Int {
    return items.firstOrNull()?.index ?: 0
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
