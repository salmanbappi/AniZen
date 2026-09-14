package eu.kanade.presentation.browse

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.domain.source.interactor.SetMigrateSorting
import eu.kanade.domain.ui.ContainerStyle
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.anime.components.BottomMenuButton
import eu.kanade.presentation.browse.components.BaseSourceItem
import eu.kanade.presentation.browse.components.SourceIcon
import eu.kanade.presentation.components.SOURCE_SEARCH_BOX_HEIGHT
import eu.kanade.presentation.components.SourcesSearchBox
import eu.kanade.presentation.util.animateItemFastScroll
import eu.kanade.tachiyomi.ui.browse.migration.sources.MigrateSourceScreenModel
import eu.kanade.tachiyomi.util.system.copyToClipboard
import kotlinx.collections.immutable.ImmutableList
import tachiyomi.domain.source.model.Source
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.components.Badge
import tachiyomi.presentation.core.components.BadgeGroup
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.icons.FlagEmoji
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.collectAsState
import tachiyomi.presentation.core.util.isScrollingUp
import tachiyomi.presentation.core.util.secondaryItemAlpha
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
fun MigrateSourceScreen(
    state: MigrateSourceScreenModel.State,
    contentPadding: PaddingValues,
    onClickItem: (Source) -> Unit,
    onToggleSortingDirection: () -> Unit,
    onToggleSortingMode: () -> Unit,
    onChangeSearchQuery: (String?) -> Unit,
    onToggleSelection: (Long) -> Unit,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
    onMatchEnabled: () -> Unit,
    onMatchPinned: () -> Unit,
    onMigrate: () -> Unit,
) {
    val context = LocalContext.current
    when {
        state.isLoading -> LoadingScreen(Modifier.padding(contentPadding))
        state.searchQuery == null && state.isEmpty -> EmptyScreen(
            stringRes = MR.strings.information_empty_library,
            modifier = Modifier.padding(contentPadding),
        )
        else ->
            MigrateSourceList(
                list = state.items,
                contentPadding = contentPadding,
                onClickItem = onClickItem,
                onLongClickItem = { item ->
                    val sourceId = item.source.id.toString()
                    context.copyToClipboard(sourceId, sourceId)
                },
                sortingMode = state.sortingMode,
                onToggleSortingMode = onToggleSortingMode,
                sortingDirection = state.sortingDirection,
                onToggleSortingDirection = onToggleSortingDirection,
                state = state,
                onChangeSearchQuery = onChangeSearchQuery,
                onToggleSelection = onToggleSelection,
                onSelectAll = onSelectAll,
                onSelectNone = onSelectNone,
                onMatchEnabled = onMatchEnabled,
                onMatchPinned = onMatchPinned,
                onMigrate = onMigrate,
            )
    }
}

@Composable
private fun MigrateSourceList(
    list: ImmutableList<Pair<SourceUiModel.Item, Long>>,
    contentPadding: PaddingValues,
    onClickItem: (Source) -> Unit,
    onLongClickItem: (SourceUiModel.Item) -> Unit,
    sortingMode: SetMigrateSorting.Mode,
    onToggleSortingMode: () -> Unit,
    sortingDirection: SetMigrateSorting.Direction,
    onToggleSortingDirection: () -> Unit,
    state: MigrateSourceScreenModel.State,
    onChangeSearchQuery: (String?) -> Unit,
    onToggleSelection: (Long) -> Unit,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
    onMatchEnabled: () -> Unit,
    onMatchPinned: () -> Unit,
    onMigrate: () -> Unit,
) {
    val uiPreferences = remember { Injekt.get<UiPreferences>() }
    val containerStyles by uiPreferences.containerStyles().collectAsState()
    val useContainer = remember(containerStyles) { ContainerStyle.BROWSE in containerStyles }

    val lazyListState = rememberLazyListState()

    BackHandler(enabled = !state.searchQuery.isNullOrBlank()) {
        onChangeSearchQuery("")
    }

    Box(
        modifier = Modifier
            .fillMaxSize(),
    ) {
        val searchBoxHeight = SOURCE_SEARCH_BOX_HEIGHT

        FastScrollLazyColumn(
            state = lazyListState,
            contentPadding = PaddingValues(
                start = contentPadding.calculateStartPadding(LocalLayoutDirection.current),
                top = searchBoxHeight,
                end = contentPadding.calculateEndPadding(LocalLayoutDirection.current),
                bottom = contentPadding.calculateBottomPadding() + if (state.selectionMode) 80.dp else 0.dp,
            ),
        ) {
            items(
                items = list,
                key = { (item, _) -> "migrate-${item.source.id}" },
            ) { (item, count) ->
                val isSelected = state.selectedSources.contains(item.source.id)
                if (useContainer) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 2.dp),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        tonalElevation = 2.dp,
                    ) {
                        MigrateSourceItem(
                            modifier = Modifier.animateItemFastScroll(),
                            item = item,
                            count = count,
                            isSelected = isSelected,
                            isSelectionMode = state.selectionMode,
                            onClickItem = {
                                if (state.selectionMode) {
                                    onToggleSelection(item.source.id)
                                } else {
                                    onClickItem(item.source)
                                }
                            },
                            onLongClickItem = { onToggleSelection(item.source.id) },
                        )
                    }
                } else {
                    MigrateSourceItem(
                        modifier = Modifier.animateItemFastScroll(),
                        item = item,
                        count = count,
                        isSelected = isSelected,
                        isSelectionMode = state.selectionMode,
                        onClickItem = {
                            if (state.selectionMode) {
                                onToggleSelection(item.source.id)
                            } else {
                                onClickItem(item.source)
                            }
                        },
                        onLongClickItem = { onToggleSelection(item.source.id) },
                    )
                }
            }
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = lazyListState.isScrollingUp(),
            enter = androidx.compose.animation.expandVertically(),
            exit = androidx.compose.animation.shrinkVertically(),
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter),
        ) {
            SourcesSearchBox(
                searchQuery = state.searchQuery,
                onChangeSearchQuery = onChangeSearchQuery,
                placeholderText = stringResource(MR.strings.action_search_for_source),
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.background)
                    .padding(
                        horizontal = MaterialTheme.padding.medium,
                        vertical = MaterialTheme.padding.small,
                    ),
            )
        }

        MigrateBottomActionMenu(
            modifier = Modifier.align(Alignment.BottomCenter),
            visible = state.selectionMode,
            onSelectAll = onSelectAll,
            onSelectNone = onSelectNone,
            onMatchEnabled = onMatchEnabled,
            onMatchPinned = onMatchPinned,
            onMigrate = onMigrate,
        )
    }
}

@Composable
private fun MigrateBottomActionMenu(
    visible: Boolean,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
    onMatchEnabled: () -> Unit,
    onMatchPinned: () -> Unit,
    onMigrate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(animationSpec = tween(delayMillis = 300)),
        exit = shrinkVertically(animationSpec = tween()),
    ) {
        Surface(
            modifier = modifier,
            shape = MaterialTheme.shapes.large.copy(bottomEnd = CornerSize(0.dp), bottomStart = CornerSize(0.dp)),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                modifier = Modifier
                    .windowInsetsPadding(
                        WindowInsets.navigationBars
                            .only(WindowInsetsSides.Bottom),
                    )
                    .padding(horizontal = 8.dp, vertical = 12.dp),
            ) {
                BottomMenuButton(
                    title = stringResource(MR.strings.migrate),
                    icon = Icons.Outlined.Checklist,
                    toConfirm = false,
                    onLongClick = {},
                    onClick = onMigrate,
                )
                BottomMenuButton(
                    title = stringResource(MR.strings.action_select_all),
                    icon = Icons.Outlined.SelectAll,
                    toConfirm = false,
                    onLongClick = {},
                    onClick = onSelectAll,
                )
                BottomMenuButton(
                    title = stringResource(SYMR.strings.select_none),
                    icon = Icons.Outlined.Checklist,
                    toConfirm = false,
                    onLongClick = {},
                    onClick = onSelectNone,
                )
                BottomMenuButton(
                    title = stringResource(SYMR.strings.match_enabled_sources),
                    icon = Icons.Outlined.NewReleases,
                    toConfirm = false,
                    onLongClick = {},
                    onClick = onMatchEnabled,
                )
                BottomMenuButton(
                    title = stringResource(SYMR.strings.match_pinned_sources),
                    icon = Icons.Outlined.PushPin,
                    toConfirm = false,
                    onLongClick = {},
                    onClick = onMatchPinned,
                )
            }
        }
    }
}

@Composable
private fun MigrateSourceItem(
    item: SourceUiModel.Item,
    count: Long,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClickItem: () -> Unit,
    onLongClickItem: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isUnselected = isSelectionMode && !isSelected
    val textDecoration = if (isUnselected) {
        TextDecoration.LineThrough
    } else {
        null
    }

    BaseSourceItem(
        modifier = modifier.alpha(if (isUnselected) 0.5f else 1f),
        item = item,
        onClickItem = onClickItem,
        onLongClickItem = onLongClickItem,
        icon = { SourceIcon(source = item.source) },
        action = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                BadgeGroup(
                    modifier = Modifier.secondaryItemAlpha(),
                ) {
                    Badge(text = "$count")
                }
                if (isSelected) {
                    Checkbox(
                        checked = true,
                        onCheckedChange = { onClickItem() },
                    )
                }
            }
        },
        content = { _ ->
            Column(
                modifier = Modifier
                    .padding(horizontal = MaterialTheme.padding.medium)
                    .weight(1f),
            ) {
                Text(
                    text = item.displayName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    textDecoration = textDecoration,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (item.secondaryText.isNotEmpty()) {
                        Text(
                            modifier = Modifier.secondaryItemAlpha(),
                            text = FlagEmoji.getEmojiLangFlag(item.source.lang) + " " + item.secondaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            textDecoration = textDecoration,
                        )
                    }
                    if (item.isStub) {
                        Text(
                            modifier = Modifier.secondaryItemAlpha(),
                            text = stringResource(MR.strings.not_installed),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            textDecoration = textDecoration,
                        )
                    }
                }
            }
        },
    )
}
