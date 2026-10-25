package eu.kanade.presentation.anime.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Input
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.BookmarkRemove
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RemoveDone
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.anime.DownloadAction
import eu.kanade.presentation.components.DownloadDropdownMenu
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.i18n.ank.AMR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.time.Duration.Companion.seconds

@Composable
fun AnimeBottomActionMenu(
    visible: Boolean,
    modifier: Modifier = Modifier,
    onBookmarkClicked: (() -> Unit)? = null,
    onRemoveBookmarkClicked: (() -> Unit)? = null,
    // AM (FILLERMARK) -->
    onFillermarkClicked: (() -> Unit)? = null,
    onRemoveFillermarkClicked: (() -> Unit)? = null,
    // <-- AM (FILLERMARK)
    onMarkAsSeenClicked: (() -> Unit)? = null,
    onMarkAsUnseenClicked: (() -> Unit)? = null,
    onMarkPreviousAsSeenClicked: (() -> Unit)? = null,
    onDownloadClicked: (() -> Unit)? = null,
    onDeleteClicked: (() -> Unit)? = null,
    onExternalClicked: (() -> Unit)? = null,
    onInternalClicked: (() -> Unit)? = null,
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(expandFrom = Alignment.Bottom),
        exit = shrinkVertically(shrinkTowards = Alignment.Bottom),
    ) {
        val scope = rememberCoroutineScope()
        val playerPreferences: PlayerPreferences = Injekt.get()
        Surface(
            modifier = modifier,
            shape = MaterialTheme.shapes.large.copy(
                bottomEnd = ZeroCornerSize,
                bottomStart = ZeroCornerSize,
            ),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            val haptic = LocalHapticFeedback.current
            // AM (FILLERMARK) -->
            val confirm = remember { mutableStateListOf(false, false, false, false, false, false, false, false, false, false, false) }
            val confirmRange = 0..<11
            // <-- AM (FILLERMARK)
            var resetJob: Job? = remember { null }
            val onLongClickItem: (Int) -> Unit = { toConfirmIndex ->
                (confirmRange).forEach { i -> confirm[i] = i == toConfirmIndex }
                resetJob?.cancel()
                resetJob = scope.launch {
                    delay(1.seconds)
                    if (isActive) confirm[toConfirmIndex] = false
                }
            }
            Row(
                modifier = Modifier
                    .padding(
                        WindowInsets.navigationBars
                            .only(WindowInsetsSides.Bottom)
                            .asPaddingValues(),
                    )
                    .padding(horizontal = 8.dp, vertical = 12.dp),
            ) {
                if (onBookmarkClicked != null) {
                    BottomMenuButton(
                        title = stringResource(MR.strings.action_bookmark_episode),
                        icon = Icons.Outlined.BookmarkAdd,
                        toConfirm = confirm[0],
                        onLongClick = { onLongClickItem(0) },
                        onClick = onBookmarkClicked,
                    )
                }
                if (onRemoveBookmarkClicked != null) {
                    BottomMenuButton(
                        title = stringResource(MR.strings.action_remove_bookmark_episode),
                        icon = Icons.Outlined.BookmarkRemove,
                        toConfirm = confirm[1],
                        onLongClick = { onLongClickItem(1) },
                        onClick = onRemoveBookmarkClicked,
                    )
                }
                // AM (FILLERMARK) -->
                if (onFillermarkClicked != null) {
                    BottomMenuButton(
                        title = stringResource(AMR.strings.action_fillermark_episode),
                        icon = ImageVector.vectorResource(id = R.drawable.ic_fillermark_24dp),
                        toConfirm = confirm[2],
                        onLongClick = { onLongClickItem(2) },
                        onClick = onFillermarkClicked,
                    )
                }
                if (onRemoveFillermarkClicked != null) {
                    BottomMenuButton(
                        title = stringResource(AMR.strings.action_remove_fillermark_episode),
                        icon = ImageVector.vectorResource(id = R.drawable.ic_fillermark_border_24dp),
                        toConfirm = confirm[3],
                        onLongClick = { onLongClickItem(3) },
                        onClick = onRemoveFillermarkClicked,
                    )
                }
                // <-- AM (FILLERMARK)
                if (onMarkAsSeenClicked != null) {
                    BottomMenuButton(
                        title = stringResource(MR.strings.action_mark_as_seen),
                        icon = Icons.Outlined.DoneAll,
                        toConfirm = confirm[4],
                        onLongClick = { onLongClickItem(4) },
                        onClick = onMarkAsSeenClicked,
                    )
                }
                if (onMarkAsUnseenClicked != null) {
                    BottomMenuButton(
                        title = stringResource(MR.strings.action_mark_as_unseen),
                        icon = Icons.Outlined.RemoveDone,
                        toConfirm = confirm[5],
                        onLongClick = { onLongClickItem(5) },
                        onClick = onMarkAsUnseenClicked,
                    )
                }
                if (onMarkPreviousAsSeenClicked != null) {
                    BottomMenuButton(
                        title = stringResource(MR.strings.action_mark_previous_as_seen),
                        icon = ImageVector.vectorResource(R.drawable.ic_done_prev_24dp),
                        toConfirm = confirm[6],
                        onLongClick = { onLongClickItem(6) },
                        onClick = onMarkPreviousAsSeenClicked,
                    )
                }
                if (onDownloadClicked != null) {
                    BottomMenuButton(
                        title = stringResource(MR.strings.action_download),
                        icon = Icons.Outlined.Download,
                        toConfirm = confirm[7],
                        onLongClick = { onLongClickItem(7) },
                        onClick = onDownloadClicked,
                    )
                }
                if (onDeleteClicked != null) {
                    BottomMenuButton(
                        title = stringResource(MR.strings.action_delete),
                        icon = Icons.Outlined.Delete,
                        toConfirm = confirm[8],
                        onLongClick = { onLongClickItem(8) },
                        onClick = onDeleteClicked,
                    )
                }
                if (onExternalClicked != null && !playerPreferences.alwaysUseExternalPlayer().get()) {
                    BottomMenuButton(
                        title = stringResource(MR.strings.action_play_externally),
                        icon = Icons.AutoMirrored.Outlined.OpenInNew,
                        toConfirm = confirm[9],
                        onLongClick = { onLongClickItem(9) },
                        onClick = onExternalClicked,
                    )
                }
                if (onInternalClicked != null && playerPreferences.alwaysUseExternalPlayer().get()) {
                    BottomMenuButton(
                        title = stringResource(MR.strings.action_play_internally),
                        icon = Icons.AutoMirrored.Outlined.Input,
                        toConfirm = confirm[10],
                        onLongClick = { onLongClickItem(10) },
                        onClick = onInternalClicked,
                    )
                }
            }
        }
    }
}

@Composable
fun LibraryBottomActionMenu(
    visible: Boolean,
    onChangeCategoryClicked: () -> Unit,
    onMarkAsSeenClicked: () -> Unit,
    onMarkAsUnseenClicked: () -> Unit,
    onFavoriteClicked: (() -> Unit)?,
    onDownloadClicked: ((DownloadAction) -> Unit)?,
    onDeleteClicked: () -> Unit,
    // KMK -->
    onMigrateClicked: () -> Unit,
    onMergeClicked: () -> Unit,
    onSelectionUpdateClicked: () -> Unit,
    // KMK <--
    // SY -->
    onClickCollectRecommendations: (() -> Unit)?,
    onClickResetInfo: (() -> Unit)?,
    // SY <--
    onFolderClicked: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(animationSpec = tween(delayMillis = 300)),
        exit = shrinkVertically(animationSpec = tween()),
    ) {
        val scope = rememberCoroutineScope()
        Surface(
            modifier = modifier,
            shape = MaterialTheme.shapes.large.copy(bottomEnd = ZeroCornerSize, bottomStart = ZeroCornerSize),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            val haptic = LocalHapticFeedback.current
            val confirm =
                remember {
                    mutableStateListOf(false, false, false, false, false, false)
                }
            var resetJob: Job? = remember { null }
            val onLongClickItem: (Int) -> Unit = { toConfirmIndex ->
                (0..5).forEach { i -> confirm[i] = i == toConfirmIndex }
                resetJob?.cancel()
                resetJob = scope.launch {
                    delay(1.seconds)
                    if (isActive) confirm[toConfirmIndex] = false
                }
            }
            Row(
                modifier = Modifier
                    .windowInsetsPadding(
                        WindowInsets.navigationBars
                            .only(WindowInsetsSides.Bottom),
                    )
                    .padding(horizontal = 8.dp, vertical = 12.dp),
            ) {
                BottomMenuButton(
                    title = stringResource(MR.strings.action_move_category),
                    icon = Icons.AutoMirrored.Outlined.Label,
                    toConfirm = confirm[0],
                    onLongClick = { onLongClickItem(0) },
                    onClick = onChangeCategoryClicked,
                )
                if (onDownloadClicked != null) {
                    var downloadExpanded by remember { mutableStateOf(false) }
                    BottomMenuButton(
                        title = stringResource(MR.strings.action_download),
                        icon = Icons.Outlined.Download,
                        toConfirm = confirm[1],
                        onLongClick = { onLongClickItem(1) },
                        onClick = { downloadExpanded = !downloadExpanded },
                    ) {
                        val onDismissRequest = { downloadExpanded = false }
                        DownloadDropdownMenu(
                            expanded = downloadExpanded,
                            onDismissRequest = onDismissRequest,
                            onDownloadClicked = onDownloadClicked,
                        )
                    }
                }
                BottomMenuButton(
                    title = stringResource(MR.strings.action_delete),
                    icon = Icons.Outlined.Delete,
                    toConfirm = confirm[2],
                    onLongClick = { onLongClickItem(2) },
                    onClick = onDeleteClicked,
                )
                BottomMenuButton(
                    title = stringResource(MR.strings.action_mark_as_seen),
                    icon = Icons.Outlined.DoneAll,
                    toConfirm = confirm[3],
                    onLongClick = { onLongClickItem(3) },
                    onClick = onMarkAsSeenClicked,
                )
                BottomMenuButton(
                    title = stringResource(MR.strings.action_mark_as_unseen),
                    icon = Icons.Outlined.RemoveDone,
                    toConfirm = confirm[4],
                    onLongClick = { onLongClickItem(4) },
                    onClick = onMarkAsUnseenClicked,
                )

                var overflowMenuOpen by remember { mutableStateOf(false) }
                BottomMenuButton(
                    title = stringResource(MR.strings.label_more),
                    icon = Icons.Outlined.MoreVert,
                    toConfirm = confirm[5],
                    onLongClick = { onLongClickItem(5) },
                    onClick = { overflowMenuOpen = true },
                ) {
                    DropdownMenu(
                        expanded = overflowMenuOpen,
                        onDismissRequest = { overflowMenuOpen = false },
                    ) {
                        if (onFolderClicked != null) {
                            DropdownMenuItem(
                                text = { Text(text = "Create folder") },
                                onClick = {
                                    overflowMenuOpen = false
                                    onFolderClicked()
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(text = stringResource(MR.strings.action_update)) },
                            onClick = {
                                overflowMenuOpen = false
                                onSelectionUpdateClicked()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(text = stringResource(MR.strings.migrate)) },
                            onClick = {
                                overflowMenuOpen = false
                                onMigrateClicked()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(text = stringResource(MR.strings.action_merge)) },
                            onClick = {
                                overflowMenuOpen = false
                                onMergeClicked()
                            },
                        )
                        if (onClickCollectRecommendations != null) {
                            DropdownMenuItem(
                                text = { Text(text = stringResource(SYMR.strings.rec_search_short)) },
                                onClick = {
                                    overflowMenuOpen = false
                                    onClickCollectRecommendations()
                                },
                            )
                        }
                        if (onClickResetInfo != null) {
                            DropdownMenuItem(
                                text = { Text(text = stringResource(SYMR.strings.reset_info)) },
                                onClick = {
                                    overflowMenuOpen = false
                                    onClickResetInfo()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
