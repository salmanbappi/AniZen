package eu.kanade.presentation.updates

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.anime.components.AnimeCover
import eu.kanade.presentation.anime.components.DotSeparatorText
import eu.kanade.presentation.anime.components.EpisodeDownloadAction
import eu.kanade.presentation.anime.components.EpisodeDownloadIndicator
import eu.kanade.presentation.components.relativeDateText
import eu.kanade.presentation.util.relativeTimeSpanString
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.updates.UpdatesItem
import eu.kanade.tachiyomi.ui.updates.UpdatesScreenModel
import eu.kanade.tachiyomi.ui.updates.groupByDateAndAnime
import eu.kanade.tachiyomi.util.lang.formatTime
import tachiyomi.domain.updates.model.UpdatesWithRelations
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.ListGroupHeader
import tachiyomi.presentation.core.components.material.DISABLED_ALPHA
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.selectedBackground

internal fun LazyListScope.updatesLastUpdatedItem(
    lastUpdated: Long,
) {
    item(key = "animeUpdates-lastUpdated") {
        Box(
            modifier = Modifier
                .padding(
                    horizontal = MaterialTheme.padding.medium,
                    vertical = MaterialTheme.padding.small,
                ),
        ) {
            Text(
                text = stringResource(MR.strings.updates_last_update_info, relativeTimeSpanString(lastUpdated)),
                fontStyle = FontStyle.Italic,
            )
        }
    }
}

internal fun LazyListScope.updatesUiItems(
    uiModels: List<UpdatesUiModel>,
    expandedState: Set<String>,
    onToggleExpand: (String) -> Unit,
    selectionMode: Boolean,
    onUpdateSelected: (UpdatesItem, UpdatesScreenModel.UpdateSelectionOptions) -> Unit,
    onClickCover: (UpdatesItem) -> Unit,
    onClickUpdate: (UpdatesItem, altPlayer: Boolean) -> Unit,
    onDownloadEpisode: (List<UpdatesItem>, EpisodeDownloadAction) -> Unit,
    useContainer: Boolean,
    usePanorama: Boolean = false,
) {
    items(
        items = uiModels,
        key = { model ->
            when (model) {
                is UpdatesUiModel.Header -> "animeUpdatesHeader-${model.date}"
                is UpdatesUiModel.Item -> "animeUpdate-${model.item.update.episodeId}"
            }
        },
        contentType = { model ->
            when (model) {
                is UpdatesUiModel.Header -> "header"
                is UpdatesUiModel.Item -> "item"
            }
        },
    ) { model ->
        when (model) {
            is UpdatesUiModel.Header -> {
                ListGroupHeader(
                    modifier = Modifier,
                    text = relativeDateText(model.date),
                )
            }
            is UpdatesUiModel.Item -> {
                val updatesItem = model.item
                val isLeader = model is UpdatesUiModel.Leader
                val isExpanded = expandedState.contains(updatesItem.update.groupByDateAndAnime())

                AnimatedVisibility(
                    visible = isLeader || isExpanded,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    val shape = when {
                        model.position == UpdatesUiModel.ItemPosition.SINGLE || (isLeader && !isExpanded) -> MaterialTheme.shapes.large
                        model.position == UpdatesUiModel.ItemPosition.TOP -> MaterialTheme.shapes.large.copy(
                            bottomEnd = ZeroCornerSize,
                            bottomStart = ZeroCornerSize,
                        )
                        model.position == UpdatesUiModel.ItemPosition.MIDDLE -> RectangleShape
                        model.position == UpdatesUiModel.ItemPosition.BOTTOM -> MaterialTheme.shapes.large.copy(
                            topEnd = ZeroCornerSize,
                            topStart = ZeroCornerSize,
                        )
                        else -> RectangleShape
                    }

                    if (useContainer) {
                        val topPadding = if (model.position == UpdatesUiModel.ItemPosition.SINGLE || model.position == UpdatesUiModel.ItemPosition.TOP || (isLeader && !isExpanded)) 4.dp else 0.dp
                        val bottomPadding = if (model.position == UpdatesUiModel.ItemPosition.SINGLE || model.position == UpdatesUiModel.ItemPosition.BOTTOM || (isLeader && !isExpanded)) 4.dp else 0.dp

                        Surface(
                            modifier = Modifier
                                .padding(horizontal = 12.dp)
                                .padding(top = topPadding, bottom = bottomPadding)
                                .fillMaxWidth(),
                            shape = shape,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            tonalElevation = 2.dp,
                        ) {
                            UpdatesUiItem(
                                modifier = Modifier.clip(shape),
                                update = updatesItem.update,
                                selected = updatesItem.selected,
                                onClick = {
                                    when {
                                        selectionMode -> onUpdateSelected(
                                            updatesItem,
                                            UpdatesScreenModel.UpdateSelectionOptions(
                                                selected = !updatesItem.selected,
                                                userSelected = true,
                                                fromLongPress = false,
                                                isGroup = isLeader && model.isExpandable,
                                                isExpanded = isExpanded,
                                            ),
                                        )
                                        else -> onClickUpdate(updatesItem, false)
                                    }
                                },
                                onLongClick = {
                                    onUpdateSelected(
                                        updatesItem,
                                        UpdatesScreenModel.UpdateSelectionOptions(
                                            selected = !updatesItem.selected,
                                            userSelected = true,
                                            fromLongPress = true,
                                            isGroup = isLeader && model.isExpandable,
                                            isExpanded = isExpanded,
                                        ),
                                    )
                                },
                                onClickCover = { onClickCover(updatesItem) },
                                onDownloadEpisode = { onDownloadEpisode(listOf(updatesItem), it) },
                                downloadStateProvider = updatesItem.downloadStateProvider,
                                downloadProgressProvider = updatesItem.downloadProgressProvider,
                                isLeader = isLeader,
                                isExpandable = model.isExpandable,
                                expanded = isExpanded,
                                onToggleExpand = { onToggleExpand(updatesItem.update.groupByDateAndAnime()) },
                                usePanorama = usePanorama,
                                updatesItem = updatesItem,
                            )
                        }
                    } else {
                        UpdatesUiItem(
                            modifier = Modifier.clip(shape),
                            update = updatesItem.update,
                            selected = updatesItem.selected,
                            onClick = {
                                when {
                                    selectionMode -> onUpdateSelected(
                                        updatesItem,
                                        UpdatesScreenModel.UpdateSelectionOptions(
                                            selected = !updatesItem.selected,
                                            userSelected = true,
                                            fromLongPress = false,
                                            isGroup = isLeader && model.isExpandable,
                                            isExpanded = isExpanded,
                                        ),
                                    )
                                    else -> onClickUpdate(updatesItem, false)
                                }
                            },
                            onLongClick = {
                                onUpdateSelected(
                                    updatesItem,
                                    UpdatesScreenModel.UpdateSelectionOptions(
                                        selected = !updatesItem.selected,
                                        userSelected = true,
                                        fromLongPress = true,
                                        isGroup = isLeader && model.isExpandable,
                                        isExpanded = isExpanded,
                                    ),
                                )
                            },
                            onClickCover = { onClickCover(updatesItem) },
                            onDownloadEpisode = { onDownloadEpisode(listOf(updatesItem), it) },
                            downloadStateProvider = updatesItem.downloadStateProvider,
                            downloadProgressProvider = updatesItem.downloadProgressProvider,
                            isLeader = isLeader,
                            isExpandable = model.isExpandable,
                            expanded = isExpanded,
                            onToggleExpand = { onToggleExpand(updatesItem.update.groupByDateAndAnime()) },
                            usePanorama = usePanorama,
                            updatesItem = updatesItem,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdatesUiItem(
    update: UpdatesWithRelations,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onClickCover: (() -> Unit)?,
    onDownloadEpisode: ((EpisodeDownloadAction) -> Unit)?,
    // Download Indicator
    downloadStateProvider: () -> Download.State,
    downloadProgressProvider: () -> Int,
    isLeader: Boolean,
    isExpandable: Boolean,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    usePanorama: Boolean,
    updatesItem: UpdatesItem,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val textAlpha = if (update.seen) DISABLED_ALPHA else 1f

    val itemHeight = if (isLeader) 96.dp else 48.dp
    val verticalPadding = if (isLeader) MaterialTheme.padding.small else 0.dp

    Row(
        modifier = modifier
            .selectedBackground(selected)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    onLongClick()
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                },
            )
            .height(itemHeight)
            .padding(horizontal = MaterialTheme.padding.medium, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isLeader) {
            val (entry, ratio) = AnimeCover.getEntry(update.animeId, usePanoramaOverride = usePanorama)
            entry(
                modifier = Modifier.fillMaxHeight(),
                data = update.coverData,
                onClick = onClickCover,
                ratio = ratio,
            )
        } else {
            val (_, ratio) = AnimeCover.getEntry(update.animeId, usePanoramaOverride = usePanorama)
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(80.dp * ratio), // Maintain alignment with Leader's text
            )
        }

        Column(
            modifier = Modifier
                .padding(start = MaterialTheme.padding.medium, end = MaterialTheme.padding.small)
                .weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            if (isLeader) {
                Text(
                    text = update.animeTitle,
                    maxLines = 1,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = textAlpha),
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!update.seen) {
                    Icon(
                        imageVector = Icons.Filled.Circle,
                        contentDescription = stringResource(MR.strings.unseen),
                        modifier = Modifier
                            .height(8.dp)
                            .padding(end = 4.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                if (update.bookmark) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = stringResource(MR.strings.action_filter_bookmarked),
                        modifier = Modifier
                            .size(MaterialTheme.typography.bodySmall.fontSize.value.dp)
                            .graphicsLayer { alpha = textAlpha },
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    DotSeparatorText()
                }
                Text(
                    text = update.episodeName,
                    maxLines = 1,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = textAlpha),
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(weight = 1f, fill = false),
                )

                val watchProgress = update.lastSecondSeen
                    .takeIf { !update.seen && it > 0L }
                    ?.let {
                        "${formatTime(it)} / ${formatTime(update.totalSeconds)}"
                    }
                if (watchProgress != null) {
                    DotSeparatorText()
                    Text(
                        text = watchProgress,
                        maxLines = 1,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA),
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                val fileSize = updatesItem.fileSize
                if (fileSize != null) {
                    val formattedFileSize = remember(fileSize) {
                        "%.2f MB".format(fileSize.toDouble() / (1024 * 1024))
                    }
                    DotSeparatorText()
                    Text(
                        text = formattedFileSize,
                        maxLines = 1,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.graphicsLayer { alpha = textAlpha },
                    )
                }
            }
        }

        if (isLeader && isExpandable) {
            CollapseButton(
                expanded = expanded,
                onClick = onToggleExpand,
            )
        }

        EpisodeDownloadIndicator(
            enabled = onDownloadEpisode != null,
            modifier = Modifier.padding(start = 4.dp),
            downloadStateProvider = downloadStateProvider,
            downloadProgressProvider = downloadProgressProvider,
            onClick = { onDownloadEpisode?.invoke(it) },
            fileSize = updatesItem.fileSize,
        )
    }
}

@Composable
private fun CollapseButton(
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val painter = rememberAnimatedVectorPainter(
        AnimatedImageVector.animatedVectorResource(R.drawable.anim_caret_down),
        !expanded,
    )

    Box(
        modifier = modifier
            .size(IndicatorSize + MaterialTheme.padding.extraSmall),
        contentAlignment = Alignment.TopCenter,
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.size(IndicatorSize),
        ) {
            Icon(
                painter = painter,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private val IndicatorSize = 24.dp
