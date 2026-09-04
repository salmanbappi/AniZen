package tachiyomi.presentation.core.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Process-wide "is the user scrolling a lazy list right now" signal.
 *
 * Work that is expensive but not needed to draw the current frame (palette extraction,
 * bitmap readbacks, …) can suspend on [awaitIdle] so it never competes with a fling for
 * CPU/GPU. Because such work is normally launched from a composable's own coroutine scope,
 * an item that gets flung past is disposed and its pending work is cancelled rather than
 * running late — the scroll stays smooth and nothing piles up behind it.
 *
 * Counting rather than a plain boolean keeps nested/sibling scrollables correct: the signal
 * only goes idle once every tracked list has settled.
 */
object ScrollActivity {

    private val activeScrollers = MutableStateFlow(0)

    /** True while at least one tracked lazy list is scrolling. */
    val isScrolling: Boolean
        get() = activeScrollers.value > 0

    internal fun acquire() {
        activeScrollers.update { it + 1 }
    }

    internal fun release() {
        activeScrollers.update { (it - 1).coerceAtLeast(0) }
    }

    /**
     * Suspends until no tracked list has been scrolling for [settleMillis].
     *
     * Returns immediately when nothing is scrolling, so callers pay nothing in the common
     * case. The settle window only applies after an actual scroll, where it keeps deferred
     * work from starting during the last frames of a fling or between two quick flicks —
     * exactly when it would be most visible.
     */
    suspend fun awaitIdle(settleMillis: Long = SETTLE_MILLIS) {
        if (activeScrollers.value == 0) return
        while (true) {
            activeScrollers.first { it == 0 }
            val resumed = withTimeoutOrNull(settleMillis) {
                activeScrollers.first { it > 0 }
            } != null
            if (!resumed) return
        }
    }

    private const val SETTLE_MILLIS = 150L
}

/**
 * Reports the scroll state of a lazy list to [ScrollActivity] for as long as this composable
 * stays in composition.
 *
 * [isScrollInProgress] is read inside a `snapshotFlow`, so scroll frames never recompose the
 * caller.
 */
@Composable
fun TrackScrollActivity(isScrollInProgress: () -> Boolean) {
    LaunchedEffect(Unit) {
        var counted = false
        try {
            snapshotFlow(isScrollInProgress)
                .distinctUntilChanged()
                .collect { active ->
                    if (active && !counted) {
                        counted = true
                        ScrollActivity.acquire()
                    } else if (!active && counted) {
                        counted = false
                        ScrollActivity.release()
                    }
                }
        } finally {
            // Also runs on cancellation (screen left, list disposed), so a count is never leaked.
            if (counted) ScrollActivity.release()
        }
    }
}
