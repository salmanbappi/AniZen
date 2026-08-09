package eu.kanade.tachiyomi.ui.browse.source.browse

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import eu.kanade.domain.connections.service.ConnectionsPreferences
import eu.kanade.tachiyomi.data.connections.discord.DiscordRPCService
import eu.kanade.tachiyomi.data.connections.discord.DiscordScreen
import uy.kohesive.injekt.injectLazy
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.paging.compose.collectAsLazyPagingItems
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.core.util.ifSourcesLoaded
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.anime.DuplicateAnimeDialog
import eu.kanade.presentation.browse.BrowseSourceContent
import eu.kanade.presentation.browse.components.BrowseSourceToolbar
import eu.kanade.presentation.browse.components.RemoveAnimeDialog
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.util.AssistContentScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.anime.AnimeScreen
import eu.kanade.tachiyomi.ui.browse.extension.details.SourcePreferencesScreen
import eu.kanade.tachiyomi.ui.browse.migration.search.MigrateDialog
import eu.kanade.tachiyomi.ui.browse.migration.search.MigrateDialogScreenModel
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreenModel.Listing
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import tachiyomi.core.common.Constants
import tachiyomi.domain.source.model.StubSource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.collectAsState as collectAsStatePref
import tachiyomi.source.localanime.LocalAnimeSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

data class BrowseSourceScreen(
    private val sourceId: Long,
    private val listingQuery: String? = null,
    private val savedSearchId: Long? = null,
) : Screen(), AssistContentScreen {

    private var assistUrl: String? = null

    override fun onProvideAssistUrl() = assistUrl

    @Composable
    override fun Content() {
        if (!ifSourcesLoaded()) {
            LoadingScreen()
            return
        }

        val uiPreferences = remember { Injekt.get<UiPreferences>() }
        val screenModel = rememberScreenModel {
            BrowseSourceScreenModel(
                sourceId = sourceId,
                listingQuery = listingQuery,
                savedSearchId = savedSearchId,
            )
        }
        val state by screenModel.state.collectAsState()

        val navigator = LocalNavigator.currentOrThrow
        val navigateUp: () -> Unit = {
            when {
                state.selectionMode -> screenModel.clearSelection()
                !state.isUserQuery && state.toolbarQuery != null -> screenModel.setToolbarQuery(null)
                else -> navigator.pop()
            }
        }

        BackHandler(enabled = state.selectionMode, onBack = navigateUp)

        val scope = rememberCoroutineScope()
        val haptic = LocalHapticFeedback.current
        val uriHandler = LocalUriHandler.current
        val snackbarHostState = remember { SnackbarHostState() }

        val context = LocalContext.current

        LaunchedEffect(screenModel.source) {
            val connectionsPreferences: ConnectionsPreferences by injectLazy()
            if (connectionsPreferences.enableDiscordRPC().get()) {
                DiscordRPCService.setAnimeScreen(
                    context = context,
                    discordScreen = DiscordScreen.BROWSE,
                    customState = screenModel.source.name,
                )
            }
        }

        val onHelpClick = { uriHandler.openUri(LocalAnimeSource.HELP_URL) }
        val onWebViewClick = f@{
            val source = screenModel.source as? HttpSource ?: return@f
            navigator.push(
                WebViewScreen(
                    url = source.baseUrl,
                    initialTitle = source.name,
                    sourceId = source.id,
                ),
            )
        }

        val pagingFlow by screenModel.animePagerFlowFlow.collectAsState()
        val animeList = pagingFlow.collectAsLazyPagingItems()

        if (screenModel.source is StubSource) {
            eu.kanade.presentation.browse.BrowseSourceScreen(
                source = screenModel.source,
                animeList = animeList,
                columns = screenModel.getColumnsPreference(LocalConfiguration.current.orientation),
                displayMode = screenModel.displayMode,
                snackbarHostState = snackbarHostState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp), // Not used for stub
                onWebViewClick = onWebViewClick,
                onHelpClick = onHelpClick,
                onLocalSourceHelpClick = onHelpClick,
                onAnimeClick = { _, _ -> },
                onAnimeLongClick = { _, _ -> },
                onBatchIncrement = {},
                selection = persistentListOf(),
                favoriteIds = persistentSetOf(),
                entries = screenModel.getColumnsPreferenceForCurrentOrientation(LocalConfiguration.current.orientation),
            )
            return
        }

        val entries = screenModel.getColumnsPreferenceForCurrentOrientation(LocalConfiguration.current.orientation)
        val hazeEnabled by uiPreferences.hazeEnabled().collectAsStatePref()

        Scaffold(
            topBar = {
                Column(
                    modifier = Modifier.background(MaterialTheme.colorScheme.surface),
                ) {
                    BrowseSourceToolbar(
                        searchQuery = state.toolbarQuery,
                        onSearchQueryChange = screenModel::setToolbarQuery,
                        source = screenModel.source,
                        displayMode = screenModel.displayMode,
                        onDisplayModeChange = { screenModel.displayMode = it },
                        navigateUp = navigateUp,
                        onWebViewClick = onWebViewClick,
                        onHelpClick = onHelpClick,
                        onSettingsClick = { navigator.push(SourcePreferencesScreen(sourceId)) },
                        onSearch = screenModel::search,
                        selectedCount = state.selection.size,
                        onUnselectAll = screenModel::clearSelection,
                        onSelectAll = {
                            val items = animeList.itemSnapshotList.items.filterNotNull().map { it.value }
                            if (items.isNotEmpty()) {
                                screenModel.selectAll(items)
                            }
                        },
                        onInvertSelection = {
                            screenModel.invertSelection(animeList.itemSnapshotList.items.filterNotNull().map { it.value })
                        },
                    )

                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = MaterialTheme.padding.small),
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                    ) {
                        FilterChip(
                            selected = state.listing == Listing.Popular,
                            onClick = {
                                screenModel.resetFilters()
                                screenModel.setListing(Listing.Popular)
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.Favorite,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(FilterChipDefaults.IconSize),
                                )
                            },
                            label = {
                                Text(text = stringResource(MR.strings.popular))
                            },
                        )
                        val catalogueSource = screenModel.source as? CatalogueSource
                        if (catalogueSource?.supportsLatest == true) {
                            FilterChip(
                                selected = state.listing == Listing.Latest,
                                onClick = {
                                    screenModel.resetFilters()
                                    screenModel.setListing(Listing.Latest)
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.NewReleases,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(FilterChipDefaults.IconSize),
                                    )
                                },
                                label = {
                                    Text(text = stringResource(MR.strings.latest))
                                },
                            )
                        }
                        if (state.filters.isNotEmpty()) {
                            FilterChip(
                                selected = state.listing is Listing.Search && state.currentSavedSearch == null,
                                onClick = screenModel::openFilterSheet,
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.FilterList,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(FilterChipDefaults.IconSize),
                                    )
                                },
                                label = {
                                    Text(text = stringResource(MR.strings.action_filter))
                                },
                            )
                        }

                        if (state.savedSearches.isNotEmpty()) {
                            state.savedSearches.forEach { savedSearch ->
                                FilterChip(
                                    selected = state.currentSavedSearch?.id == savedSearch.id,
                                    onClick = { screenModel.loadSearch(savedSearch) },
                                    label = { Text(text = savedSearch.name) },
                                )
                            }
                        }
                    }

                    HorizontalDivider()
                }
            },
            bottomBar = {
                androidx.compose.animation.AnimatedVisibility(
                    visible = state.selectionMode,
                    enter = androidx.compose.animation.expandVertically(),
                    exit = androidx.compose.animation.shrinkVertically(),
                ) {
                    val allFavorite = remember(state.selection, state.favoriteIds) {
                        state.selection.all { it.id in state.favoriteIds }
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = MaterialTheme.shapes.large.copy(
                            bottomEnd = androidx.compose.foundation.shape.ZeroCornerSize,
                            bottomStart = androidx.compose.foundation.shape.ZeroCornerSize,
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(
                                    WindowInsets.navigationBars
                                        .only(WindowInsetsSides.Bottom)
                                        .asPaddingValues(),
                                )
                                .padding(horizontal = 8.dp, vertical = 12.dp)
                                .fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            TextButton(
                                onClick = { 
                                    if (allFavorite) {
                                        screenModel.removeSelectionFromLibrary()
                                    } else {
                                        screenModel.addSelectionToLibrary()
                                    }
                                },
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        imageVector = if (allFavorite) Icons.Outlined.Delete else Icons.Outlined.Favorite,
                                        contentDescription = null,
                                        tint = if (allFavorite) MaterialTheme.colorScheme.error else LocalContentColor.current
                                    )
                                    Text(
                                        text = stringResource(
                                            if (allFavorite) MR.strings.action_remove else MR.strings.add_to_library
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (allFavorite) MaterialTheme.colorScheme.error else LocalContentColor.current
                                    )
                                }
                            }
                        }
                    }
                }
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            hazeEnabled = hazeEnabled,
        ) { paddingValues ->
            var isPoking by remember { mutableStateOf(false) }
            val isSelectAllMode = state.isSelectAllMode
            
            // Reactive Selection Engine: Observes load state to expand selection in 'Select All' mode.
            // It selects items in batches of 60 and uses 'safe boundary access' to trigger Paging 3 fetches.
            LaunchedEffect(isSelectAllMode) {
                if (!isSelectAllMode) {
                    isPoking = false
                    return@LaunchedEffect
                }

                snapshotFlow { 
                    if (!state.isSelectAllMode) return@snapshotFlow null
                    val target = state.targetCount
                    val current = state.selection.size
                    val total = animeList.itemCount
                    Triple(target, current, total)
                }
                .collectLatest { data ->
                    val (target, current, total) = data ?: return@collectLatest
                    // Expand selection to available items, capped at the current targetCount.
                    val snapshot = animeList.itemSnapshotList
                    val loadedItems = snapshot.items.filterNotNull()
                    
                    if (loadedItems.size > current) {
                        val nextBatch = loadedItems.take(target).map { it.value }
                        if (nextBatch.size > current) {
                            screenModel.updateSelection(nextBatch)
                        }
                    }

                    // TRIGGER THE NEXT PAGE (The Safe Poke) only if we haven't reached the manual targetCount
                    // AND we are not already poking.
                    if (current < target && total > 0 && total < target && !isPoking) {
                        val appendState = animeList.loadState.append
                        if (appendState is androidx.paging.LoadState.NotLoading && !appendState.endOfPaginationReached) {
                            isPoking = true
                            try {
                                animeList[total - 1]
                            } catch (e: Exception) {}
                        }
                    }
                }
            }
            
            // Reset poking state when loading completes or state changes
            LaunchedEffect(animeList.loadState.append) {
                if (animeList.loadState.append is androidx.paging.LoadState.NotLoading) {
                    isPoking = false
                }
            }

            BrowseSourceContent(
                source = screenModel.source,
                animeList = animeList,
                columns = screenModel.getColumnsPreference(LocalConfiguration.current.orientation),
                entries = screenModel.getColumnsPreferenceForCurrentOrientation(LocalConfiguration.current.orientation),
                displayMode = screenModel.displayMode,
                snackbarHostState = snackbarHostState,
                contentPadding = paddingValues,
                onWebViewClick = onWebViewClick,
                onHelpClick = { uriHandler.openUri(Constants.URL_HELP) },
                onLocalSourceHelpClick = onHelpClick,
                onAnimeClick = { anime, index ->
                    if (state.selectionMode) {
                        screenModel.toggleSelection(anime, index)
                    } else {
                        navigator.push((AnimeScreen(anime.id, true)))
                    }
                },
                onAnimeLongClick = { anime, index ->
                    val lastIndex = state.lastSelectedIndex
                    if (state.selectionMode && lastIndex != null) {
                        val items = animeList.itemSnapshotList.items.mapNotNull { it?.value }
                        screenModel.selectRange(items, lastIndex, index)
                    } else {
                        screenModel.toggleSelection(anime, index)
                    }
                },
                selection = state.selection.toImmutableList(),
                favoriteIds = state.favoriteIds,
                onBatchIncrement = { /* Manual increment only via Select All button */ },
            )
        }

        val onDismissRequest = { screenModel.setDialog(null) }
        when (val dialog = state.dialog) {
            is BrowseSourceScreenModel.Dialog.Filter -> {
                SourceFilterSheet(
                    onDismissRequest = onDismissRequest,
                    filters = state.filters,
                    onReset = screenModel::resetFilters,
                    onSave = { screenModel.setDialog(BrowseSourceScreenModel.Dialog.SaveSearch) },
                    onFilter = { screenModel.search(filters = state.filters) },
                    onUpdate = screenModel::onFilterUpdate,
                    savedSearches = state.savedSearches,
                    currentSavedSearchId = state.currentSavedSearch?.id,
                    onSavedSearchClick = {
                        screenModel.loadSearch(it)
                        onDismissRequest()
                    },
                    onSavedSearchLongClick = {
                        screenModel.setDialog(BrowseSourceScreenModel.Dialog.DeleteSavedSearch(it))
                    },
                    filtersId = state.filtersId,
                )
            }
            is BrowseSourceScreenModel.Dialog.DeleteSavedSearch -> {
                AlertDialog(
                    onDismissRequest = onDismissRequest,
                    title = { Text(text = "Delete Saved Search?") },
                    text = { Text(text = "Are you sure you want to delete '${dialog.savedSearch.name}'?") },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                screenModel.deleteSearch(dialog.savedSearch.id)
                                onDismissRequest()
                            },
                        ) {
                            Text(text = stringResource(MR.strings.action_ok))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = onDismissRequest) {
                            Text(text = stringResource(MR.strings.action_cancel))
                        }
                    },
                )
            }
            is BrowseSourceScreenModel.Dialog.SaveSearch -> {
                var name by remember { mutableStateOf("") }
                AlertDialog(
                    onDismissRequest = onDismissRequest,
                    title = { Text(text = "Save current search query?") },
                    text = {
                        TextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text(text = "Name") },
                            singleLine = true,
                        )
                    },
                    confirmButton = {
                        TextButton(
                            enabled = name.isNotBlank(),
                            onClick = {
                                screenModel.saveSearch(name)
                                onDismissRequest()
                            },
                        ) {
                            Text(text = stringResource(MR.strings.action_ok))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = onDismissRequest) {
                            Text(text = stringResource(MR.strings.action_cancel))
                        }
                    },
                )
            }
            is BrowseSourceScreenModel.Dialog.AddDuplicateAnime -> {
                DuplicateAnimeDialog(
                    onDismissRequest = onDismissRequest,
                    onConfirm = { scope.launch { screenModel.addFavorite(dialog.anime) } },
                    onOpenAnime = { navigator.push(AnimeScreen(dialog.duplicate.id)) },
                    onMigrate = {
                        screenModel.setDialog(
                            BrowseSourceScreenModel.Dialog.Migrate(dialog.anime, dialog.duplicate),
                        )
                    },
                )
            }

            is BrowseSourceScreenModel.Dialog.Migrate -> {
                MigrateDialog(
                    oldAnime = dialog.oldAnime,
                    newAnime = dialog.newAnime,
                    screenModel = MigrateDialogScreenModel(dialog.oldAnime.id),
                    onDismissRequest = onDismissRequest,
                    onClickTitle = { navigator.push(AnimeScreen(dialog.oldAnime.id)) },
                    onPopScreen = {
                        onDismissRequest()
                    },
                )
            }
            is BrowseSourceScreenModel.Dialog.RemoveAnime -> {
                RemoveAnimeDialog(
                    onDismissRequest = onDismissRequest,
                    onConfirm = {
                        screenModel.changeAnimeFavorite(dialog.anime)
                    },
                    animeToRemove = dialog.anime.title,
                )
            }
            is BrowseSourceScreenModel.Dialog.ChangeAnimeCategory -> {
                ChangeCategoryDialog(
                    initialSelection = dialog.initialSelection,
                    onDismissRequest = onDismissRequest,
                    onEditCategories = { navigator.push(CategoryScreen) },
                    onConfirm = { include, _ ->
                        dialog.animes.forEach { anime ->
                            screenModel.changeAnimeFavorite(anime)
                            screenModel.moveAnimeToCategories(anime, include)
                        }
                        screenModel.clearSelection()
                    },
                )
            }
            else -> {}
        }

        // Search query observer: Handles text and genre search events.
        LaunchedEffect(Unit) {
            queryEvent.receiveAsFlow()
                .collectLatest {
                    when (it) {
                        is SearchType.Genre -> screenModel.searchGenre(it.txt)
                        is SearchType.Text -> screenModel.search(it.txt)
                    }
                }
        }
    }

    suspend fun search(query: String) = queryEvent.send(SearchType.Text(query))
    suspend fun searchGenre(name: String) = queryEvent.send(SearchType.Genre(name))

    companion object {
        private val queryEvent = Channel<SearchType>()
    }

    sealed class SearchType(val txt: String) {
        class Text(txt: String) : SearchType(txt)
        class Genre(txt: String) : SearchType(txt)
    }
}
