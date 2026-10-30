package eu.kanade.presentation.browse

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FindReplace
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.VerticalAlignBottom
import androidx.compose.material.icons.outlined.VerticalAlignTop
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.anime.components.BaseAnimeListItem
import eu.kanade.presentation.anime.components.BottomMenuButton
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.util.animateItemFastScroll
import eu.kanade.tachiyomi.ui.browse.migration.anime.MigrateAnimeItem
import eu.kanade.tachiyomi.ui.browse.migration.anime.MigrateAnimeScreenModel
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tachiyomi.domain.anime.model.Anime
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.util.selectedBackground
import kotlin.time.Duration.Companion.seconds

@Composable
fun MigrateAnimeScreen(
    navigateUp: () -> Unit,
    title: String?,
    state: MigrateAnimeScreenModel.State,
    onClickItem: (Anime) -> Unit,
    onClickCover: (Anime) -> Unit,
    onAnimeSelected: (MigrateAnimeItem, Boolean, Boolean, Boolean) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onInvertSelection: () -> Unit,
    onMultiMigrateClicked: (List<Anime>) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val lazyListState = rememberLazyListState()

    BackHandler(enabled = state.selectionMode) {
        onSelectAll(false)
    }

    val enableScrollToTop by remember {
        derivedStateOf {
            lazyListState.firstVisibleItemIndex > 0
        }
    }

    val enableScrollToBottom by remember {
        derivedStateOf {
            lazyListState.canScrollForward
        }
    }

    Scaffold(
        topBar = { scrollBehavior ->
            MigrateAnimeAppBar(
                title = title ?: "",
                navigateUp = navigateUp,
                itemCnt = state.titles.size,
                selectedCount = state.selection.size,
                onClickUnselectAll = { onSelectAll(false) },
                onClickSelectAll = { onSelectAll(true) },
                onClickInvertSelection = onInvertSelection,
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = {
            MigrateAnimeBottomBar(
                selected = state.selection,
                onMultiMigrateClicked = {
                    onMultiMigrateClicked(state.selection.map { it.anime })
                },
                enableScrollToTop = enableScrollToTop,
                enableScrollToBottom = enableScrollToBottom,
                scrollToTop = {
                    scope.launch {
                        lazyListState.scrollToItem(0)
                    }
                },
                scrollToBottom = {
                    scope.launch {
                        lazyListState.scrollToItem(state.titles.size - 1)
                    }
                },
            )
        },
    ) { contentPadding ->
        if (state.isEmpty) {
            EmptyScreen(
                stringRes = MR.strings.empty_screen,
                modifier = Modifier.padding(contentPadding),
            )
            return@Scaffold
        }

        MigrateAnimeContent(
            lazyListState = lazyListState,
            contentPadding = contentPadding,
            state = state,
            onAnimeSelected = onAnimeSelected,
            onClickItem = onClickItem,
            onClickCover = onClickCover,
        )
    }
}

@Composable
private fun MigrateAnimeContent(
    lazyListState: LazyListState,
    contentPadding: PaddingValues,
    state: MigrateAnimeScreenModel.State,
    onAnimeSelected: (MigrateAnimeItem, Boolean, Boolean, Boolean) -> Unit,
    onClickItem: (Anime) -> Unit,
    onClickCover: (Anime) -> Unit,
) {
    FastScrollLazyColumn(
        state = lazyListState,
        contentPadding = contentPadding,
    ) {
        itemsIndexed(
            items = state.titles,
            key = { index, it -> "migrate-${it.anime.id}-$index" },
        ) { _, item ->
            MigrateAnimeItem(
                anime = item.anime,
                isSelected = item.selected,
                onClickItem = {
                    if (state.selectionMode) {
                        onAnimeSelected(item, !item.selected, true, false)
                    } else {
                        onClickItem(item.anime)
                    }
                },
                onClickCover = { onClickCover(item.anime) },
                onLongClickItem = { onAnimeSelected(item, !item.selected, true, true) },
                modifier = Modifier.animateItemFastScroll(),
            )
        }
    }
}

@Composable
private fun MigrateAnimeItem(
    anime: Anime,
    isSelected: Boolean,
    onClickItem: () -> Unit,
    onClickCover: () -> Unit,
    onLongClickItem: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BaseAnimeListItem(
        modifier = modifier.selectedBackground(isSelected),
        anime = anime,
        onClickItem = onClickItem,
        onClickCover = onClickCover,
        onLongClickItem = onLongClickItem,
    )
}

@Composable
private fun MigrateAnimeAppBar(
    title: String,
    navigateUp: () -> Unit,
    itemCnt: Int,
    selectedCount: Int,
    onClickUnselectAll: () -> Unit,
    onClickSelectAll: () -> Unit,
    onClickInvertSelection: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    AppBar(
        title = title,
        navigateUp = navigateUp,
        actions = {
            if (itemCnt > 0) {
                AppBarActions(
                    persistentListOf(
                        AppBar.Action(
                            title = stringResource(MR.strings.action_select_all),
                            icon = Icons.Outlined.SelectAll,
                            onClick = onClickSelectAll,
                        ),
                    ),
                )
            }
        },
        actionModeCounter = selectedCount,
        onCancelActionMode = onClickUnselectAll,
        actionModeActions = {
            AppBarActions(
                persistentListOf(
                    AppBar.Action(
                        title = stringResource(MR.strings.action_select_all),
                        icon = Icons.Outlined.SelectAll,
                        onClick = onClickSelectAll,
                    ),
                    AppBar.Action(
                        title = stringResource(MR.strings.action_select_inverse),
                        icon = Icons.Outlined.FlipToBack,
                        onClick = onClickInvertSelection,
                    ),
                ),
            )
        },
        scrollBehavior = scrollBehavior,
    )
}

@Composable
private fun MigrateAnimeBottomBar(
    modifier: Modifier = Modifier,
    selected: List<MigrateAnimeItem>,
    onMultiMigrateClicked: () -> Unit,
    enableScrollToTop: Boolean,
    enableScrollToBottom: Boolean,
    scrollToTop: () -> Unit,
    scrollToBottom: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val animatedElevation by animateDpAsState(
        targetValue = if (selected.isNotEmpty()) 3.dp else 0.dp,
        label = "elevation",
    )
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large.copy(
            bottomEnd = ZeroCornerSize,
            bottomStart = ZeroCornerSize,
        ),
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(
            elevation = animatedElevation,
        ),
    ) {
        val haptic = LocalHapticFeedback.current
        val confirm = remember { mutableStateListOf(false, false, false) }
        var resetJob by remember { mutableStateOf<Job?>(null) }
        val onLongClickItem: (Int) -> Unit = { toConfirmIndex ->
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            confirm.indices.forEach { i -> confirm[i] = i == toConfirmIndex }
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
            BottomMenuButton(
                title = stringResource(MR.strings.action_scroll_to_top),
                icon = Icons.Outlined.VerticalAlignTop,
                toConfirm = confirm[0],
                onLongClick = { onLongClickItem(0) },
                onClick = scrollToTop,
                enabled = enableScrollToTop,
            )
            BottomMenuButton(
                title = stringResource(MR.strings.migrate),
                icon = Icons.Outlined.FindReplace,
                toConfirm = confirm[1],
                onLongClick = { onLongClickItem(1) },
                onClick = onMultiMigrateClicked,
                enabled = selected.isNotEmpty(),
            )
            BottomMenuButton(
                title = stringResource(MR.strings.action_scroll_to_bottom),
                icon = Icons.Outlined.VerticalAlignBottom,
                toConfirm = confirm[2],
                onLongClick = { onLongClickItem(2) },
                onClick = scrollToBottom,
                enabled = enableScrollToBottom,
            )
        }
    }
}
