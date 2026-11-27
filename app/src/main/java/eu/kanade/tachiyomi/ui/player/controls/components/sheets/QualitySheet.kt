package eu.kanade.tachiyomi.ui.player.controls.components.sheets

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.player.components.PlayerSheet
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.ui.player.utils.DefaultStreamSelector
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.DISABLED_ALPHA
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource

sealed class HosterState(open val name: String) {
    data class Idle(override val name: String) : HosterState(name)
    data class Loading(override val name: String) : HosterState(name)
    data class Error(override val name: String) : HosterState(name)
    data class Ready(
        override val name: String,
        val videoList: List<Video>,
        val videoState: List<Video.State>,
    ) : HosterState(name)
}

fun HosterState.Ready.getChangedAt(index: Int, newVideo: Video, newState: Video.State): HosterState.Ready {
    return HosterState.Ready(
        name = this.name,
        videoList = this.videoList.mapIndexed { idx, video ->
            if (idx == index) newVideo else video
        },
        videoState = this.videoState.mapIndexed { idx, state ->
            if (idx == index) newState else state
        },
    )
}

private data class DefaultScrollTarget(
    val hosterIndex: Int,
    val videoIndex: Int,
)

private val DefaultHighlightShape = RoundedCornerShape(14.dp)
private const val HostExpandSettleMs = 200L

@Composable
fun QualitySheet(
    isLoadingHosters: Boolean,
    hosterState: List<HosterState>,
    expandedState: List<Boolean>,
    selectedVideoIndex: Pair<Int, Int>,
    onClickHoster: (Int) -> Unit,
    onClickVideo: (Int, Int) -> Unit,
    onEnsureHosterExpanded: (Int) -> Unit = {},
    defaultStreamSelector: String,
    highlightDefaultStream: Boolean = true,
    autoScrollToDefault: Boolean = true,
    sheetActive: Boolean = true,
    displayHosters: Pair<Boolean, Boolean>,
    onDismissRequest: () -> Unit,
    dismissSheet: Boolean,
    modifier: Modifier = Modifier,
) {
    PlayerSheet(
        onDismissRequest = {
            onDismissRequest()
        },
        dismissEvent = dismissSheet,
        modifier = modifier,
    ) {
        Column {
            Text(
                text = stringResource(MR.strings.player_sheets_qualities_title),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(
                    top = MaterialTheme.padding.medium,
                    start = MaterialTheme.padding.medium,
                    bottom = MaterialTheme.padding.extraSmall,
                ),
            )

            AnimatedVisibility(
                visible = isLoadingHosters,
                enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
            ) {
                Box(
                    modifier = modifier
                        .fillMaxWidth()
                        .padding(vertical = MaterialTheme.padding.medium),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            val qualitySheetPadding = PaddingValues(
                bottom = MaterialTheme.padding.medium,
                start = MaterialTheme.padding.medium,
                end = MaterialTheme.padding.medium,
            )

            AnimatedVisibility(
                visible = !isLoadingHosters,
                enter = fadeIn() + slideInVertically(
                    initialOffsetY = { it / 2 },
                ),
                exit = fadeOut() + slideOutVertically(
                    targetOffsetY = { it / 2 },
                ),
            ) {
                val effectiveSelector = if (highlightDefaultStream) defaultStreamSelector else ""
                val defaultVideoByHoster = remember(hosterState, effectiveSelector) {
                    buildDefaultVideoByHosterIndex(hosterState, effectiveSelector)
                }

                if (autoScrollToDefault) {
                    EnsureDefaultHostersExpanded(
                        sheetActive = sheetActive,
                        defaultVideoByHoster = defaultVideoByHoster,
                        onEnsureHosterExpanded = onEnsureHosterExpanded,
                    )
                }

                if (hosterState.size == 1 &&
                    hosterState.first().name == Hoster.NO_HOSTER_LIST &&
                    hosterState.first() is HosterState.Ready
                ) {
                    QualitySheetVideoContent(
                        videoList = (hosterState.first() as HosterState.Ready).videoList,
                        videoState = (hosterState.first() as HosterState.Ready).videoState,
                        selectedVideoIndex = selectedVideoIndex.second,
                        onClickVideo = onClickVideo,
                        defaultStreamSelector = effectiveSelector,
                        highlightDefaultStream = highlightDefaultStream,
                        autoScrollToDefault = autoScrollToDefault,
                        sheetActive = sheetActive,
                        modifier = modifier.padding(paddingValues = qualitySheetPadding),
                    )
                } else {
                    QualitySheetHosterContent(
                        hosterState = hosterState,
                        expandedState = expandedState,
                        selectedVideoIndex = selectedVideoIndex,
                        onClickHoster = onClickHoster,
                        onClickVideo = onClickVideo,
                        onEnsureHosterExpanded = onEnsureHosterExpanded,
                        defaultStreamSelector = effectiveSelector,
                        highlightDefaultStream = highlightDefaultStream,
                        defaultVideoByHoster = defaultVideoByHoster,
                        autoScrollToDefault = autoScrollToDefault,
                        sheetActive = sheetActive,
                        displayHosters = displayHosters,
                        modifier = modifier.padding(paddingValues = qualitySheetPadding),
                    )
                }
            }
        }
    }
}

// ANZ -->
private fun buildDefaultVideoByHosterIndex(
    hosterState: List<HosterState>,
    defaultStreamSelector: String,
): Map<Int, Int> {
    if (defaultStreamSelector.isBlank()) return emptyMap()
    return hosterState.mapIndexedNotNull { hosterIdx, state ->
        if (state !is HosterState.Ready) return@mapIndexedNotNull null
        val videoIdx = findDefaultVideoIndex(state.videoList, defaultStreamSelector, state.name)
        if (videoIdx >= 0) hosterIdx to videoIdx else null
    }.toMap()
}
// ANZ <--

private fun findDefaultVideoIndex(
    videos: List<Video>,
    defaultStreamSelector: String,
    hosterName: String = "",
): Int = DefaultStreamSelector.findBestMatchIndex(defaultStreamSelector, videos, hosterName)

/** Lazy list index for a video row when hosters use flattened header + per-video items. */
@Composable
private fun EnsureDefaultHostersExpanded(
    sheetActive: Boolean,
    defaultVideoByHoster: Map<Int, Int>,
    onEnsureHosterExpanded: (Int) -> Unit,
) {
    LaunchedEffect(sheetActive, defaultVideoByHoster) {
        if (!sheetActive) return@LaunchedEffect
        defaultVideoByHoster.keys.forEach { hosterIndex ->
            onEnsureHosterExpanded(hosterIndex)
        }
    }
}

private fun computeFlatVideoLazyIndex(
    hosters: List<IndexedValue<HosterState>>,
    expandedState: List<Boolean>,
    targetHosterIndex: Int,
    targetVideoIndex: Int,
): Int {
    var lazyIndex = 0
    for ((hosterIdx, hoster) in hosters) {
        if (hosterIdx == targetHosterIndex) {
            if (hoster !is HosterState.Ready) return -1
            if (expandedState.getOrNull(hosterIdx) != true) return -1
            return lazyIndex + 1 + targetVideoIndex
        }
        lazyIndex++
        if (hoster is HosterState.Ready && expandedState.getOrNull(hosterIdx) == true) {
            lazyIndex += hoster.videoList.size
        }
    }
    return -1
}

@Composable
private fun ScrollToDefaultEffect(
    target: DefaultScrollTarget?,
    autoScrollToDefault: Boolean,
    sheetActive: Boolean,
    flatHosters: List<IndexedValue<HosterState>>,
    expandedState: List<Boolean>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onEnsureHosterExpanded: (Int) -> Unit,
) {
    LaunchedEffect(sheetActive, target, autoScrollToDefault, flatHosters, expandedState) {
        if (!sheetActive || !autoScrollToDefault || target == null || target.videoIndex < 0) {
            return@LaunchedEffect
        }

        if (target.hosterIndex >= 0 && expandedState.getOrNull(target.hosterIndex) != true) {
            onEnsureHosterExpanded(target.hosterIndex)
            snapshotFlow { expandedState.getOrNull(target.hosterIndex) == true }
                .filter { it }
                .first()
            delay(HostExpandSettleMs)
        }

        val lazyIndex = if (target.hosterIndex < 0) {
            target.videoIndex
        } else {
            computeFlatVideoLazyIndex(
                hosters = flatHosters,
                expandedState = expandedState,
                targetHosterIndex = target.hosterIndex,
                targetVideoIndex = target.videoIndex,
            )
        }
        if (lazyIndex < 0) return@LaunchedEffect

        snapshotFlow { listState.layoutInfo.totalItemsCount }
            .filter { it > lazyIndex }
            .first()

        listState.scrollToItem(lazyIndex)
    }
}

@Composable
fun QualitySheetVideoContent(
    videoList: List<Video>,
    videoState: List<Video.State>,
    selectedVideoIndex: Int,
    onClickVideo: (Int, Int) -> Unit,
    defaultStreamSelector: String = "",
    highlightDefaultStream: Boolean = true,
    autoScrollToDefault: Boolean = true,
    sheetActive: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val defaultVideoIndex = remember(videoList, defaultStreamSelector, highlightDefaultStream) {
        if (!highlightDefaultStream) -1 else findDefaultVideoIndex(videoList, defaultStreamSelector, Hoster.NO_HOSTER_LIST)
    }
    val scrollTarget = remember(defaultVideoIndex) {
        if (defaultVideoIndex >= 0) {
            DefaultScrollTarget(hosterIndex = -1, videoIndex = defaultVideoIndex)
        } else {
            null
        }
    }

    ScrollToDefaultEffect(
        target = scrollTarget,
        autoScrollToDefault = autoScrollToDefault,
        sheetActive = sheetActive,
        flatHosters = emptyList(),
        expandedState = emptyList(),
        listState = listState,
        onEnsureHosterExpanded = {},
    )

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
    ) {
        itemsIndexed(
            items = videoList,
            key = { index, video -> "video-$index-${video.url.hashCode()}" },
        ) { videoIdx, video ->
            VideoTrack(
                video = video,
                videoState = videoState[videoIdx],
                selected = selectedVideoIndex == videoIdx,
                defaultSelected = highlightDefaultStream && videoIdx == defaultVideoIndex,
                onClick = { onClickVideo(0, videoIdx) },
                noHoster = true,
            )
        }
    }
}

private fun findDefaultScrollTarget(
    hosterState: List<HosterState>,
    defaultStreamSelector: String,
): DefaultScrollTarget? {
    if (defaultStreamSelector.isBlank()) return null
    val bestMatch = DefaultStreamSelector.findBestInHosters(defaultStreamSelector, hosterState)
    return bestMatch?.let { (hIdx, vIdx) ->
        DefaultScrollTarget(hosterIndex = hIdx, videoIndex = vIdx)
    }
}

@Composable
fun QualitySheetHosterContent(
    hosterState: List<HosterState>,
    expandedState: List<Boolean>,
    selectedVideoIndex: Pair<Int, Int>,
    onClickHoster: (Int) -> Unit,
    onClickVideo: (Int, Int) -> Unit,
    onEnsureHosterExpanded: (Int) -> Unit = {},
    defaultStreamSelector: String = "",
    highlightDefaultStream: Boolean = true,
    defaultVideoByHoster: Map<Int, Int> = emptyMap(),
    autoScrollToDefault: Boolean = true,
    sheetActive: Boolean = true,
    displayHosters: Pair<Boolean, Boolean>,
    modifier: Modifier = Modifier,
) {
    val validHosters = hosterState.withIndex().filter { (_, state) ->
        state is HosterState.Idle ||
            state is HosterState.Loading ||
            (state is HosterState.Ready && state.videoList.isNotEmpty())
    }
    val failedHosters = hosterState.withIndex().filter { (_, state) ->
        state is HosterState.Error
    }
    val emptyHosters = hosterState.withIndex().filter { (_, state) ->
        state is HosterState.Ready && state.videoList.isEmpty()
    }

    val listState = rememberLazyListState()
    val scrollTarget = remember(hosterState, defaultStreamSelector) {
        findDefaultScrollTarget(hosterState, defaultStreamSelector)
    }

    val flatHostersForScroll = remember(validHosters, failedHosters, emptyHosters, displayHosters) {
        buildList {
            addAll(validHosters)
            if (displayHosters.first) addAll(failedHosters)
            if (displayHosters.second) addAll(emptyHosters)
        }
    }

    ScrollToDefaultEffect(
        target = scrollTarget,
        autoScrollToDefault = autoScrollToDefault,
        sheetActive = sheetActive,
        flatHosters = flatHostersForScroll,
        expandedState = expandedState,
        listState = listState,
        onEnsureHosterExpanded = onEnsureHosterExpanded,
    )

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
    ) {
        hosterContent(
            hosters = validHosters,
            expandedState = expandedState,
            selectedVideoIndex = selectedVideoIndex,
            onClickHoster = onClickHoster,
            onClickVideo = onClickVideo,
            defaultVideoByHoster = defaultVideoByHoster,
            highlightDefaultStream = highlightDefaultStream,
        )

        if (displayHosters.first) {
            hosterContent(
                hosters = failedHosters,
                expandedState = expandedState,
                selectedVideoIndex = selectedVideoIndex,
                onClickHoster = onClickHoster,
                onClickVideo = onClickVideo,
                defaultVideoByHoster = defaultVideoByHoster,
                highlightDefaultStream = highlightDefaultStream,
            )
        }

        if (displayHosters.second) {
            hosterContent(
                hosters = emptyHosters,
                expandedState = expandedState,
                selectedVideoIndex = selectedVideoIndex,
                onClickHoster = onClickHoster,
                onClickVideo = onClickVideo,
                defaultVideoByHoster = defaultVideoByHoster,
                highlightDefaultStream = highlightDefaultStream,
            )
        }
    }
}

internal fun LazyListScope.hosterContent(
    hosters: List<IndexedValue<HosterState>>,
    expandedState: List<Boolean>,
    selectedVideoIndex: Pair<Int, Int>,
    onClickHoster: (Int) -> Unit,
    onClickVideo: (Int, Int) -> Unit,
    defaultVideoByHoster: Map<Int, Int>,
    highlightDefaultStream: Boolean = true,
) {
    hosters.forEach { (hosterIdx, hoster) ->
        val isExpanded = expandedState.getOrNull(hosterIdx) ?: false
        val defaultVideoIdx = if (highlightDefaultStream) defaultVideoByHoster[hosterIdx] ?: -1 else -1

        item(key = "hoster-header-$hosterIdx-${hoster.name}") {
            HosterTrack(
                hoster = hoster,
                selected = selectedVideoIndex.first == hosterIdx,
                isExpanded = isExpanded,
                onClick = { onClickHoster(hosterIdx) },
            )
        }

        if (hoster is HosterState.Ready && isExpanded) {
            itemsIndexed(
                items = hoster.videoList,
                key = { videoIdx, video -> "video-$hosterIdx-$videoIdx-${video.videoUrl.hashCode()}" },
            ) { videoIdx, video ->
                VideoTrack(
                    video = video,
                    videoState = hoster.videoState[videoIdx],
                    selected = selectedVideoIndex == Pair(hosterIdx, videoIdx),
                                      defaultSelected = highlightDefaultStream && videoIdx == defaultVideoIdx,
                    onClick = { onClickVideo(hosterIdx, videoIdx) },
                    noHoster = false,
                )
            }
        }
    }
}

@Composable
fun HosterTrack(
    hoster: HosterState,
    selected: Boolean,
    isExpanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(32.dp)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = hoster.name,
            fontStyle = if (selected) FontStyle.Italic else FontStyle.Normal,
            fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Normal,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = MaterialTheme.padding.small),
        )

        when (hoster) {
            is HosterState.Idle -> {
                Text(
                    text = stringResource(MR.strings.player_hoster_tap_to_load),
                    modifier = Modifier.alpha(DISABLED_ALPHA),
                )
            }
            is HosterState.Error -> {
                Text(
                    text = stringResource(MR.strings.player_hoster_failed),
                    modifier = Modifier.alpha(DISABLED_ALPHA),
                )
                Spacer(modifier = Modifier.weight(1f))
                Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
            }
            is HosterState.Loading -> {
                Spacer(modifier = Modifier.weight(1f))
                CircularProgressIndicator(
                    modifier = Modifier.then(Modifier.size(24.dp)),
                    strokeWidth = 2.dp,
                )
            }
            is HosterState.Ready -> {
                Text(
                    text = pluralStringResource(
                        MR.plurals.hoster_video_count,
                        hoster.videoList.size,
                        hoster.videoList.size,
                    ),
                    modifier = Modifier.alpha(DISABLED_ALPHA),
                )
                Spacer(modifier = Modifier.weight(1f))
                if (isExpanded) {
                    Icon(Icons.Default.KeyboardArrowUp, null)
                } else {
                    Icon(Icons.Default.KeyboardArrowDown, null)
                }
            }
        }
    }
}

@Composable
fun VideoTrack(
    video: Video,
    videoState: Video.State,
    selected: Boolean,
    defaultSelected: Boolean,
    onClick: () -> Unit,
    noHoster: Boolean,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val highlightModifier = if (defaultSelected) {
        Modifier
            .clip(DefaultHighlightShape)
            .background(
                color = primary.copy(alpha = 0.14f),
                shape = DefaultHighlightShape,
            )
            .border(
                width = 2.dp,
                color = primary,
                shape = DefaultHighlightShape,
            )
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = MaterialTheme.padding.extraSmall)
            .then(highlightModifier)
            .clickable(onClick = onClick)
            .padding(
                horizontal = MaterialTheme.padding.small,
                vertical = if (noHoster) MaterialTheme.padding.small else MaterialTheme.padding.extraSmall,
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        ) {
            if (noHoster) {
                VideoText(
                    video = video,
                    selected = selected,
                    defaultSelected = defaultSelected,
                    noHoster = true,
                    modifier = Modifier.weight(1f),
                )
                VideoIcon(
                    videoState = videoState,
                    noHoster = true,
                )
            } else {
                VideoIcon(
                    videoState = videoState,
                    noHoster = false,
                )
                VideoText(
                    video = video,
                    selected = selected,
                    defaultSelected = defaultSelected,
                    noHoster = false,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun VideoIcon(
    videoState: Video.State,
    noHoster: Boolean,
) {
    Box(
        modifier = Modifier.size(if (noHoster) 28.dp else 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (videoState) {
            Video.State.QUEUE, Video.State.READY -> {}
            Video.State.LOAD_VIDEO -> {
                CircularProgressIndicator(
                    modifier = Modifier.fillMaxSize(),
                    strokeWidth = 2.dp,
                )
            }
            Video.State.ERROR -> {
                Icon(
                    Icons.Default.ErrorOutline,
                    null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun VideoText(
    video: Video,
    selected: Boolean,
    defaultSelected: Boolean,
    noHoster: Boolean,
    modifier: Modifier = Modifier,
) {
    // ANZ -->
    Text(
        text = video.videoTitle,
        fontStyle = if (selected) FontStyle.Italic else FontStyle.Normal,
        fontWeight = when {
            selected -> FontWeight.ExtraBold
            defaultSelected -> FontWeight.Bold
            else -> FontWeight.Normal
        },
        style = MaterialTheme.typography.bodyMedium,
        color = when {
            selected || defaultSelected -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurface
        },
        modifier = modifier,
    )
    // ANZ <--
}
