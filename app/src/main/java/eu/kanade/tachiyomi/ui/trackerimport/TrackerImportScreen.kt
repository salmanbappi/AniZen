package eu.kanade.tachiyomi.ui.trackerimport

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FindReplace
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.anime.components.AnimeCover
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.track.ImportStatusFilter
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.persistentListOf
import mihon.feature.migration.config.MigrationConfigScreen
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.selectedBackground

class TrackerImportScreen(val trackerId: Long) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val screenModel = rememberScreenModel { TrackerImportScreenModel(trackerId) }
        val state by screenModel.state.collectAsState()

        var isImporting by remember { mutableStateOf(false) }

        if (isImporting) {
            AlertDialog(
                onDismissRequest = {},
                confirmButton = {},
                title = { Text(text = "Importing from ${state.trackerName}") },
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(text = "Preparing migration...")
                    }
                },
            )
        }

        val title = screenModel.tracker?.let { "Import from ${it.name}" }
            ?: stringResource(MR.strings.pref_import_from_anilist)

        val noticeRes = screenModel.tracker?.getNoticeStringRes() ?: MR.strings.anilist_import_notice

        TrackerImportScreen(
            title = title,
            noticeRes = noticeRes,
            state = state,
            onSelectAll = screenModel::toggleAllSelection,
            onInvertSelection = screenModel::invertSelection,
            onItemClicked = { item ->
                screenModel.toggleSelection(item, !item.selected)
            },
            onImportClicked = {
                isImporting = true
                screenModel.importSelected(
                    onSuccess = { createdAnimeIds ->
                        isImporting = false
                        if (createdAnimeIds.isNotEmpty()) {
                            navigator.push(MigrationConfigScreen(createdAnimeIds))
                        } else {
                            context.toast("No items imported")
                        }
                    },
                    onFailure = { error ->
                        isImporting = false
                        context.toast(error.message ?: "Failed to import")
                    },
                )
            },
            onToggleStatusFilter = screenModel::toggleStatusFilter,
            onToggleExcludeLibraryMatches = screenModel::setExcludeLibraryMatches,
            navigateUp = navigator::pop,
        )
    }
}

@Composable
fun TrackerImportScreen(
    title: String,
    noticeRes: dev.icerock.moko.resources.StringResource,
    state: TrackerImportScreenState,
    onSelectAll: (Boolean) -> Unit,
    onInvertSelection: () -> Unit,
    onItemClicked: (TrackerImportItem) -> Unit,
    onImportClicked: () -> Unit,
    onToggleStatusFilter: (ImportStatusFilter) -> Unit,
    onToggleExcludeLibraryMatches: (Boolean) -> Unit,
    navigateUp: () -> Unit,
) {
    BackHandler(enabled = state.selectionMode, onBack = { onSelectAll(false) })

    val listState = rememberLazyListState()

    Scaffold(
        topBar = { scrollBehavior ->
            TrackerImportAppBar(
                title = title,
                navigateUp = navigateUp,
                selectedCount = state.selected.size,
                onClickUnselectAll = { onSelectAll(false) },
                onClickSelectAll = { onSelectAll(true) },
                onClickInvertSelection = onInvertSelection,
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = {
            if (state.selected.isNotEmpty()) {
                Surface(
                    shape = MaterialTheme.shapes.large.copy(
                        bottomEnd = ZeroCornerSize,
                        bottomStart = ZeroCornerSize,
                    ),
                    color = MaterialTheme.colorScheme.surfaceColorAtElevation(elevation = 3.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .padding(
                                WindowInsets.navigationBars
                                    .only(WindowInsetsSides.Bottom)
                                    .asPaddingValues(),
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Button(
                            onClick = onImportClicked,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.FindReplace,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                            Text(text = stringResource(MR.strings.migrate))
                        }
                    }
                }
            }
        },
    ) { contentPadding ->
        if (state.isLoading) {
            LoadingScreen(modifier = Modifier.padding(contentPadding))
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            ) {
                // Filter chips row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ImportStatusFilter.entries.forEach { filter ->
                        FilterChip(
                            selected = state.selectedStatuses.contains(filter),
                            onClick = { onToggleStatusFilter(filter) },
                            label = { Text(text = stringResource(filter.titleRes)) },
                        )
                    }
                }

                // Exclude matches setting row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleExcludeLibraryMatches(!state.excludeLibraryMatches) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Exclude anime already in library",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Switch(
                        checked = state.excludeLibraryMatches,
                        onCheckedChange = { onToggleExcludeLibraryMatches(it) },
                    )
                }

                if (state.items.isEmpty()) {
                    EmptyScreen(
                        message = "No importable anime found on ${state.trackerName}",
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    FastScrollLazyColumn(
                        modifier = Modifier.weight(1f),
                        state = listState,
                    ) {
                        item(key = "notice") {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f),
                                ),
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.HelpOutline,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(end = 12.dp),
                                    )
                                    Text(
                                        text = stringResource(noticeRes),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                }
                            }
                        }

                        items(
                            items = state.items,
                            key = { "tracker-import-${it.item.remoteId}" },
                        ) { uiModel ->
                            TrackerImportItemRow(
                                item = uiModel,
                                selected = uiModel.selected,
                                selectionMode = state.selectionMode,
                                onClick = {
                                    onItemClicked(uiModel)
                                },
                                onLongClick = {
                                    onItemClicked(uiModel)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TrackerImportItemRow(
    item: TrackerImportItem,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .selectedBackground(selected)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    onLongClick()
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                },
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimeCover.Square(
            modifier = Modifier.height(48.dp),
            data = item.item.coverUrl,
        )
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .weight(1f),
        ) {
            Text(
                text = item.item.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "Progress: ${item.item.episodesSeen} episodes",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TrackerImportAppBar(
    title: String,
    navigateUp: () -> Unit,
    selectedCount: Int,
    onClickUnselectAll: () -> Unit,
    onClickSelectAll: () -> Unit,
    onClickInvertSelection: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    AppBar(
        title = title,
        navigateUp = navigateUp,
        actions = {},
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
