package eu.kanade.tachiyomi.ui.browse.migration.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.paging.compose.collectAsLazyPagingItems
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.core.util.ifSourcesLoaded
import eu.kanade.presentation.browse.BrowseSourceContent
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.anime.AnimeScreen
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreenModel
import eu.kanade.tachiyomi.ui.browse.source.browse.SourceFilterSheet
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch
import tachiyomi.core.common.Constants
import tachiyomi.domain.anime.model.Anime
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.ExtendedFloatingActionButton
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.source.localanime.LocalAnimeSource

data class SourceSearchScreen(
    private val oldAnime: Anime,
    private val sourceId: Long,
    private val query: String?,
) : Screen() {

    @Composable
    override fun Content() {
        if (!ifSourcesLoaded()) {
            LoadingScreen()
            return
        }

        val uriHandler = LocalUriHandler.current
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()

        val screenModel = rememberScreenModel { BrowseSourceScreenModel(sourceId, query) }
        val state by screenModel.state.collectAsState()

        val snackbarHostState = remember { SnackbarHostState() }

        Scaffold(
            topBar = { scrollBehavior ->
                Column {
                    SearchToolbar(
                        searchQuery = state.toolbarQuery ?: "",
                        onChangeSearchQuery = screenModel::setToolbarQuery,
                        onClickCloseSearch = navigator::pop,
                        onSearch = screenModel::search,
                        scrollBehavior = scrollBehavior,
                    )

                    if (state.savedSearches.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            state.savedSearches.forEach { savedSearch ->
                                FilterChip(
                                    selected = state.currentSavedSearch?.id == savedSearch.id,
                                    onClick = { screenModel.loadSearch(savedSearch) },
                                    label = { Text(text = savedSearch.name) },
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            },
            floatingActionButton = {
                val filterLabel = stringResource(MR.strings.action_filter)
                AnimatedVisibility(visible = state.filters.isNotEmpty()) {
                    ExtendedFloatingActionButton(
                        text = { Text(text = filterLabel) },
                        icon = { Icon(Icons.Outlined.FilterList, contentDescription = "") },
                        onClick = screenModel::openFilterSheet,
                    )
                }
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        ) { paddingValues ->
            val pagingFlow by screenModel.animePagerFlowFlow.collectAsState()
            val openMigrateDialog: (Anime) -> Unit = {
                screenModel.setDialog(BrowseSourceScreenModel.Dialog.Migrate(newAnime = it, oldAnime = oldAnime))
            }
            BrowseSourceContent(
                source = screenModel.source,
                animeList = pagingFlow.collectAsLazyPagingItems(),
                columns = screenModel.getColumnsPreference(LocalConfiguration.current.orientation),
                displayMode = screenModel.displayMode,
                snackbarHostState = snackbarHostState,
                contentPadding = paddingValues,
                onWebViewClick = {
                    val source = screenModel.source as? HttpSource ?: return@BrowseSourceContent
                    navigator.push(
                        WebViewScreen(
                            url = source.baseUrl,
                            initialTitle = source.name,
                            sourceId = source.id,
                        ),
                    )
                },
                onHelpClick = { uriHandler.openUri(Constants.URL_HELP) },
                onLocalSourceHelpClick = { uriHandler.openUri(LocalAnimeSource.HELP_URL) },
                onAnimeClick = { anime, _ -> openMigrateDialog(anime) },
                onAnimeLongClick = { anime, _ -> navigator.push(AnimeScreen(anime.id, true)) },
                selection = state.selection.toImmutableList(),
                favoriteIds = state.favoriteIds,
            )
        }

        val onDismissRequest = { screenModel.setDialog(null) }
        when (val dialog = state.dialog) {
            is BrowseSourceScreenModel.Dialog.Filter -> {
                SourceFilterSheet(
                    onDismissRequest = onDismissRequest,
                    filters = state.filters,
                    onReset = screenModel::resetFilters,
                    onSave = {}, // Disable save search in migration for now
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
            is BrowseSourceScreenModel.Dialog.Migrate -> {
                MigrateDialog(
                    oldAnime = oldAnime,
                    newAnime = dialog.newAnime,
                    screenModel = rememberScreenModel { MigrateDialogScreenModel(oldAnime.id) },
                    onDismissRequest = onDismissRequest,
                    onClickTitle = { navigator.push(AnimeScreen(dialog.newAnime.id)) },
                    onPopScreen = {
                        scope.launch {
                            navigator.popUntilRoot()
                            HomeScreen.openTab(HomeScreen.HomeTab.Browse())
                            navigator.push(AnimeScreen(dialog.newAnime.id))
                        }
                    },
                )
            }
            else -> {}
        }
    }
}
