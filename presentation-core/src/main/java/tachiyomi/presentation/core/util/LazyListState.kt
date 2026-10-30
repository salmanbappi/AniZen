package tachiyomi.presentation.core.util

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow

@Composable
fun LazyListState.shouldExpandFAB(): Boolean {
    return remember {
        derivedStateOf {
            (firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0) ||
                lastScrolledBackward ||
                !canScrollForward
        }
    }
        .value
}

@Composable
fun LazyListState.isScrolledToStart(): Boolean {
    return remember {
        derivedStateOf {
            val firstItem = layoutInfo.visibleItemsInfo.firstOrNull()
            firstItem == null || firstItem.offset == layoutInfo.viewportStartOffset
        }
    }.value
}

@Composable
fun LazyListState.isScrolledToEnd(): Boolean {
    return remember {
        derivedStateOf {
            val lastItem = layoutInfo.visibleItemsInfo.lastOrNull()
            lastItem == null || lastItem.size + lastItem.offset <= layoutInfo.viewportEndOffset
        }
    }.value
}

@Composable
fun LazyListState.isScrollingUp(): Boolean {
    var isScrollingUp by remember { mutableStateOf(true) }

    LaunchedEffect(this) {
        var previousIndex = firstVisibleItemIndex
        var previousScrollOffset = firstVisibleItemScrollOffset

        snapshotFlow {
            val index = firstVisibleItemIndex.toLong()
            val offset = firstVisibleItemScrollOffset.toLong()
            (index shl 32) or (offset and 0xFFFFFFFFL)
        }.collect { packed ->
            val currentIndex = (packed shr 32).toInt()
            val currentOffset = packed.toInt()

            if (previousIndex != currentIndex) {
                isScrollingUp = previousIndex > currentIndex
            } else if (previousScrollOffset != currentOffset) {
                isScrollingUp = previousScrollOffset > currentOffset
            }
            previousIndex = currentIndex
            previousScrollOffset = currentOffset
        }
    }

    return isScrollingUp
}

@Composable
fun LazyListState.isScrollingDown(): Boolean {
    var isScrollingDown by remember { mutableStateOf(false) }

    LaunchedEffect(this) {
        var previousIndex = firstVisibleItemIndex
        var previousScrollOffset = firstVisibleItemScrollOffset

        snapshotFlow {
            val index = firstVisibleItemIndex.toLong()
            val offset = firstVisibleItemScrollOffset.toLong()
            (index shl 32) or (offset and 0xFFFFFFFFL)
        }.collect { packed ->
            val currentIndex = (packed shr 32).toInt()
            val currentOffset = packed.toInt()

            if (previousIndex != currentIndex) {
                isScrollingDown = previousIndex < currentIndex
            } else if (previousScrollOffset != currentOffset) {
                isScrollingDown = previousScrollOffset < currentOffset
            }
            previousIndex = currentIndex
            previousScrollOffset = currentOffset
        }
    }

    return isScrollingDown
}
