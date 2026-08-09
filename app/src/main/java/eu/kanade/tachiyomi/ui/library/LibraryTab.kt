package eu.kanade.tachiyomi.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.util.fastAll
import androidx.compose.ui.util.fastAny
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.anime.components.LibraryBottomActionMenu
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.library.DeleteLibraryAnimeDialog
import eu.kanade.presentation.library.LibrarySettingsDialog
import eu.kanade.presentation.library.components.FolderContextMenu
import eu.kanade.presentation.library.components.FolderOverlay
import eu.kanade.presentation.library.components.LibraryContent
import eu.kanade.presentation.library.components.LibraryToolbar
import eu.kanade.presentation.more.onboarding.GETTING_STARTED_URL
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.connections.discord.DiscordRPCService
import eu.kanade.tachiyomi.data.connections.discord.DiscordScreen
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import eu.kanade.tachiyomi.data.sync.SyncDataJob
import eu.kanade.tachiyomi.ui.anime.AnimeScreen
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.player.PlayerActivity
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import mihon.feature.migration.config.MigrationConfigScreen
import tachiyomi.core.common.i18n.stringResource as stringResourceCommon
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.episode.model.Episode
import tachiyomi.domain.library.model.LibraryAnime
import tachiyomi.domain.library.model.LibraryGroup
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.source.localanime.isLocal
import tachiyomi.presentation.core.util.collectAsState as collectAsStatePref
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

data object LibraryTab : Tab {

    override val options: TabOptions
        @Composable
        get() {
            val title = MR.strings.label_library
            return TabOptions(
                index = 0u,
                title = stringResource(title),
                icon = rememberAnimatedVectorPainter(AnimatedImageVector.animatedVectorResource(R.drawable.anim_library_enter), false),
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
        requestOpenSettingsSheet()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val haptic = LocalHapticFeedback.current

        val screenModel = rememberScreenModel { LibraryScreenModel() }
        val settingsScreenModel = rememberScreenModel { LibrarySettingsScreenModel() }
        val state by screenModel.state.collectAsState()
        val collapseFolders by settingsScreenModel.libraryPreferences.collapseFolders().collectAsStatePref()

        // Folder overlay state: which folder is tapped to show the full overlay
        val openFolderId = state.openFolderId
        var folderLongClickItem by remember { mutableStateOf<LibraryDisplayItem.Folder?>(null) }
        // Context menu state: which anime had long-press to add to folder
        var folderContextAnimeList by remember { mutableStateOf<List<LibraryAnime>?>(null) }
        // Active category folders for context menu
        val activeCategoryFolders = remember(state.folders, screenModel.activeCategoryIndex, state.categories) {
            val activeCategoryId = state.categories.getOrNull(screenModel.activeCategoryIndex)?.id
            if (activeCategoryId != null) state.folders.filter { it.categoryId == activeCategoryId } else emptyList()
        }

        val openFolderItem = remember(openFolderId, state.folders, state.library, screenModel.activeCategoryIndex) {
            if (openFolderId == null) return@remember null
            val folder = state.folders.find { it.id == openFolderId } ?: return@remember null
            val filteredItems = state.getAnimelibItemsByPage(screenModel.activeCategoryIndex)
            val currentFolderItems = filteredItems.filter { it.libraryAnime.folderId == openFolderId }
            LibraryDisplayItem.Folder(folder, currentFolderItems)
        }

        val snackbarHostState = remember { SnackbarHostState() }


        val onClickRefresh: (Category?) -> Boolean = { category ->
            // SY -->
            val started = LibraryUpdateJob.startNow(
                context = context,
                category = category,
                group = state.groupType,
                groupExtra = null,
            )
            // SY <--
            scope.launch {
                val msgRes = if (started) {
                    if (category == null) MR.strings.updating_library else MR.strings.updating_category
                } else {
                    MR.strings.update_already_running
                }
                snackbarHostState.showSnackbar(context.stringResourceCommon(msgRes))
            }
            started
        }

        suspend fun openEpisode(episode: Episode) {
            val playerPreferences: PlayerPreferences by injectLazy()
            val extPlayer = playerPreferences.alwaysUseExternalPlayer().get()
            MainActivity.startPlayerActivity(
                context,
                episode.animeId,
                episode.id,
                extPlayer,
            )
        }

        val defaultTitle = stringResource(MR.strings.label_library)

        val uiPreferences = remember { Injekt.get<UiPreferences>() }
        val globalPanorama by uiPreferences.panoramaCover().collectAsStatePref()
        val libraryMode by uiPreferences.libraryPanoramaMode().collectAsStatePref()
        val effectivePanorama = remember(globalPanorama, libraryMode) { libraryMode.resolve(globalPanorama) }
        val configuration = androidx.compose.ui.platform.LocalConfiguration.current
        val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val displayMode by screenModel.getDisplayMode()
        val columns by screenModel.getColumnsPreferenceForCurrentOrientation(isLandscape)

        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                topBar = { scrollBehavior ->
                    val title = state.getToolbarTitle(
                        defaultTitle = defaultTitle,
                        defaultCategoryTitle = stringResource(MR.strings.label_default),
                        page = screenModel.activeCategoryIndex,
                    )
                    val tabVisible = state.showCategoryTabs && state.categories.size > 1
                    LibraryToolbar(
                        hasActiveFilters = state.hasActiveFilters,
                        selectedCount = state.selection.size,
                        title = title,
                        onClickUnselectAll = screenModel::clearSelection,
                        onClickSelectAll = { screenModel.selectAll(screenModel.activeCategoryIndex) },
                        onClickInvertSelection = {
                            screenModel.invertSelection(
                                screenModel.activeCategoryIndex,
                            )
                        },
                        onClickFilter = screenModel::showSettingsDialog,
                        onClickRefresh = {
                            state.categories.getOrNull(screenModel.activeCategoryIndex)?.let {
                                onClickRefresh(it)
                            } ?: false
                        },
                        onClickGlobalUpdate = { onClickRefresh(null) },
                        onClickOpenRandomEntry = {
                            scope.launch {
                                val randomItem = screenModel.getRandomAnimelibItemForCurrentCategory()
                                if (randomItem != null) {
                                    navigator.push(AnimeScreen(randomItem.libraryAnime.anime.id))
                                } else {
                                    snackbarHostState.showSnackbar(
                                        context.stringResourceCommon(MR.strings.information_no_entries_found),
                                    )
                                }
                            }
                        },
                        onClickSyncNow = {
                            if (!SyncDataJob.isRunning(context)) {
                                SyncDataJob.startNow(context)
                            } else {
                                context.toast(SYMR.strings.sync_in_progress)
                            }
                        },
                        searchQuery = state.searchQuery,
                        onSearchQueryChange = screenModel::search,
                        scrollBehavior = scrollBehavior.takeIf { !tabVisible }, // For scroll overlay when no tab
                    )
                },
                bottomBar = {
                    LibraryBottomActionMenu(
                        visible = state.selectionMode,
                        onChangeCategoryClicked = screenModel::openChangeCategoryDialog,
                        onMarkAsSeenClicked = { screenModel.markSeenSelection(true) },
                        onMarkAsUnseenClicked = { screenModel.markSeenSelection(false) },
                        onFavoriteClicked = { screenModel.toggleFavoriteSelection() },
                        onDownloadClicked = screenModel::runDownloadActionSelection
                            .takeIf { state.selection.fastAll { !it.anime.isLocal() } },
                        onDeleteClicked = screenModel::openDeleteAnimeDialog,
                        onMigrateClicked = {
                            val animeIds = state.selection.map { it.anime.id }
                            screenModel.clearSelection()
                            navigator.push(MigrationConfigScreen(animeIds))
                        },
                        onMergeClicked = {
                            // TODO: Implement bulk merge? For now just show for single
                        },
                        onSelectionUpdateClicked = {
                            screenModel.updateSelection()
                        },
                        onClickResetInfo = screenModel::resetInfo.takeIf { state.showResetInfo },
                        onClickCollectRecommendations = {
                            // TODO: Implement bulk recommendations
                        },
                        onFolderClicked = {
                            folderContextAnimeList = state.selection
                        },
                    )
                },
                snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            ) { contentPadding ->
                when {
                    state.isLoading -> LoadingScreen(Modifier.padding(contentPadding))
                    state.searchQuery.isNullOrEmpty() && !state.hasActiveFilters && state.isLibraryEmpty -> {
                        val handler = LocalUriHandler.current
                        EmptyScreen(
                            stringRes = MR.strings.information_empty_library,
                            modifier = Modifier.padding(contentPadding),
                            actions = persistentListOf(
                                EmptyScreenAction(
                                    stringRes = MR.strings.getting_started_guide,
                                    icon = Icons.AutoMirrored.Outlined.HelpOutline,
                                    onClick = { handler.openUri(GETTING_STARTED_URL) },
                                ),
                            ),
                        )
                    }
                    else -> {
                        LibraryContent(
                            categories = if (state.searchQuery.isNullOrEmpty()) state.categories.toImmutableList() else persistentListOf(Category(0, "Search Results", 0, 0, false)),
                            searchQuery = state.searchQuery,
                            selection = state.selection.toImmutableList(),
                            contentPadding = contentPadding,
                            currentPage = { if (state.searchQuery.isNullOrEmpty()) screenModel.activeCategoryIndex else 0 },
                            hasActiveFilters = state.hasActiveFilters,
                            showPageTabs = state.showCategoryTabs && state.searchQuery.isNullOrEmpty(),
                            onChangeCurrentPage = { screenModel.activeCategoryIndex = it },
                            onAnimeClicked = { navigator.push(AnimeScreen(it)) },
                            onContinueWatchingClicked = { it: LibraryAnime ->
                                scope.launchIO {
                                    val episode = screenModel.getNextUnseenEpisode(it.anime)
                                    if (episode != null) openEpisode(episode)
                                }
                                Unit
                            }.takeIf { state.showAnimeContinueButton },
                            onToggleSelection = screenModel::toggleSelection,
                            onToggleRangeSelection = { anime, categoryId ->
                                screenModel.toggleRangeSelection(anime, categoryId)
                            },
                            onRefresh = onClickRefresh,
                            onGlobalSearchClicked = {
                                val currentQuery = screenModel.state.value.searchQuery ?: ""
                                screenModel.search(null)
                                navigator.push(GlobalSearchScreen(currentQuery))
                            },
                            getNumberOfAnimeForCategory = { state.getAnimeCountForCategory(it) },
                            getDisplayMode = { screenModel.getDisplayMode() },
                            getColumnsForOrientation = {
                                screenModel.getColumnsPreferenceForCurrentOrientation(
                                    it,
                                )
                            },
                            onFolderClick = { folderItem ->
                                screenModel.setOpenFolder(folderItem.folder.id)
                            },
                            onFolderLongClick = { folderItem ->
                                folderLongClickItem = folderItem
                            },
                            getAnimeLibraryForPage = { page ->
                                if (!state.searchQuery.isNullOrEmpty()) {
                                    val displayItems = mutableListOf<eu.kanade.tachiyomi.ui.library.LibraryDisplayItem>()
                                    state.categories.forEach { cat ->
                                        val catItems = state.library[cat] ?: emptyList()
                                        if (catItems.isNotEmpty()) {
                                            displayItems.add(eu.kanade.tachiyomi.ui.library.LibraryDisplayItem.Header(cat.name))
                                            val processedFolderIds = mutableSetOf<Long>()
                                            val grouped = catItems.groupBy { it.libraryAnime.folderId }
                                            for (item in catItems) {
                                                if (!collapseFolders) {
                                                    displayItems.add(eu.kanade.tachiyomi.ui.library.LibraryDisplayItem.Anime(item))
                                                } else {
                                                    val folderId = item.libraryAnime.folderId
                                                    if (folderId == null) {
                                                        displayItems.add(eu.kanade.tachiyomi.ui.library.LibraryDisplayItem.Anime(item))
                                                    } else if (processedFolderIds.add(folderId)) {
                                                        val folder = state.folders.find { it.id == folderId }
                                                        val folderItems = grouped[folderId] ?: emptyList()
                                                        if (folder != null) {
                                                            displayItems.add(eu.kanade.tachiyomi.ui.library.LibraryDisplayItem.Folder(folder, folderItems))
                                                        } else {
                                                            displayItems.add(eu.kanade.tachiyomi.ui.library.LibraryDisplayItem.Anime(item))
                                                            processedFolderIds.remove(folderId)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    displayItems.toImmutableList()
                                } else {
                                    val items = state.getAnimelibItemsByPage(page)
                                    if (!collapseFolders) {
                                        items.map { eu.kanade.tachiyomi.ui.library.LibraryDisplayItem.Anime(it) }.toImmutableList()
                                    } else {
                                        val displayItems = mutableListOf<eu.kanade.tachiyomi.ui.library.LibraryDisplayItem>()
                                        val processedFolderIds = mutableSetOf<Long>()
                                        val grouped = items.groupBy { it.libraryAnime.folderId }
        
                                        for (item in items) {
                                            val folderId = item.libraryAnime.folderId
                                            if (folderId == null) {
                                                displayItems.add(eu.kanade.tachiyomi.ui.library.LibraryDisplayItem.Anime(item))
                                            } else if (processedFolderIds.add(folderId)) {
                                                val folder = state.folders.find { it.id == folderId }
                                                val folderItems = grouped[folderId] ?: emptyList()
                                                if (folder != null) {
                                                    displayItems.add(eu.kanade.tachiyomi.ui.library.LibraryDisplayItem.Folder(folder, folderItems))
                                                } else {
                                                    displayItems.add(eu.kanade.tachiyomi.ui.library.LibraryDisplayItem.Anime(item))
                                                    processedFolderIds.remove(folderId)
                                                }
                                            }
                                        }
                                        displayItems.toImmutableList()
                                    }
                                }
                            },
                        )
                    }
                }
            }

            // Folder Overlay - moved outside Scaffold to hide navigation
            FolderOverlay(
                folder = openFolderItem?.folder,
                items = openFolderItem?.items ?: emptyList(),
                displayMode = displayMode,
                columns = columns,
                usePanorama = effectivePanorama,
                onDismiss = { screenModel.setOpenFolder(null) },
                onRenameFolder = { newName ->
                    val folderId = openFolderItem?.folder?.id
                    if (folderId != null) screenModel.renameFolder(folderId, newName)
                },
                onDeleteFolder = {
                    val folderId = openFolderItem?.folder?.id
                    if (folderId != null) {
                        screenModel.deleteFolder(folderId)
                        screenModel.setOpenFolder(null)
                    }
                },
                onClickAnime = { libraryItem ->
                    navigator.push(AnimeScreen(libraryItem.libraryAnime.anime.id))
                },
                onLongClickAnime = { libraryItem ->
                    folderContextAnimeList = listOf(libraryItem.libraryAnime)
                },
                onFolderActionClicked = { items ->
                    folderContextAnimeList = items.map { it.libraryAnime }
                },
                onDownloadClicked = { items, action ->
                    screenModel.runDownloadAction(action, items.map { it.libraryAnime.anime })
                },
                onDeleteAnimeClicked = { items ->
                    screenModel.openDeleteAnimeDialog(items.map { it.libraryAnime.anime })
                },
                onMarkAsSeenClicked = { items ->
                    screenModel.markSeen(items.map { it.libraryAnime }, true)
                },
                onMarkAsUnseenClicked = { items ->
                    screenModel.markSeen(items.map { it.libraryAnime }, false)
                },
                onFavoriteClicked = { items ->
                    screenModel.toggleFavorite(items.map { it.libraryAnime.anime })
                },
                onClickFilter = screenModel::showSettingsDialog,
                onClickContinueWatching = { it: tachiyomi.domain.library.model.LibraryAnime ->
                    scope.launchIO {
                        val episode = screenModel.getNextUnseenEpisode(it.anime)
                        if (episode != null) openEpisode(episode)
                    }
                    Unit
                }.takeIf { state.showAnimeContinueButton },
            )
        }

        folderLongClickItem?.let { folderDisplayItem ->
            eu.kanade.presentation.library.components.FolderActionDialog(
                folder = folderDisplayItem.folder,
                onDismiss = { folderLongClickItem = null },
                onRenameFolder = { newName ->
                    screenModel.renameFolder(folderDisplayItem.folder.id, newName)
                    folderLongClickItem = null
                },
                onDeleteFolder = {
                    screenModel.deleteFolder(folderDisplayItem.folder.id)
                    folderLongClickItem = null
                },
            )
        }

        // Folder Context Menu – shown for adding anime to a folder
        folderContextAnimeList?.let { animeList ->
            val activeCategoryId = state.categories.getOrNull(screenModel.activeCategoryIndex)?.id
            val title = if (animeList.size == 1) animeList.first().anime.title else "${animeList.size} items selected"
            val currentFolderId = if (animeList.size == 1) animeList.first().folderId else null

            FolderContextMenu(
                animeTitle = title,
                currentFolderId = currentFolderId,
                folders = activeCategoryFolders,
                onDismiss = { folderContextAnimeList = null },
                onAddToFolder = { folderId ->
                    if (activeCategoryId != null) {
                        screenModel.addAnimeToFolder(
                            animeIds = animeList.map { it.anime.id },
                            categoryId = activeCategoryId,
                            folderId = folderId,
                        )
                    }
                    folderContextAnimeList = null
                    screenModel.clearSelection()
                },
                onCreateNewFolder = { folderName ->
                    if (activeCategoryId != null) {
                        screenModel.createFolder(
                            animeIds = animeList.map { it.anime.id },
                            categoryId = activeCategoryId,
                            folderName = folderName,
                        )
                    }
                    folderContextAnimeList = null
                    screenModel.clearSelection()
                },
            )
        }

        val onDismissRequest = screenModel::closeDialog
        when (val dialog = state.dialog) {
            is LibraryScreenModel.Dialog.SettingsSheet -> run {
                val category = state.categories.getOrNull(screenModel.activeCategoryIndex)
                LibrarySettingsDialog(
                    onDismissRequest = onDismissRequest,
                    screenModel = settingsScreenModel,
                    category = category,
                    // SY -->
                    hasCategories = state.categories.fastAny { !it.isSystemCategory },
                    // SY <--
                )
            }
            is LibraryScreenModel.Dialog.ChangeCategory -> {
                ChangeCategoryDialog(
                    initialSelection = dialog.initialSelection,
                    onDismissRequest = onDismissRequest,
                    onEditCategories = {
                        screenModel.clearSelection()
                        navigator.push(CategoryScreen)
                    },
                    onConfirm = { include, exclude ->
                        screenModel.clearSelection()
                        screenModel.setAnimeCategories(dialog.anime, include, exclude)
                    },
                )
            }
            is LibraryScreenModel.Dialog.DeleteAnime -> {
                DeleteLibraryAnimeDialog(
                    containsLocalAnime = dialog.anime.any(Anime::isLocal),
                    onDismissRequest = onDismissRequest,
                    onConfirm = { deleteAnime, deleteEpisode ->
                        screenModel.removeAnimes(dialog.anime, deleteAnime, deleteEpisode)
                        screenModel.clearSelection()
                    },
                )
            }
            null -> {}
        }

        BackHandler(enabled = state.selectionMode || state.searchQuery != null || state.openFolderId != null) {
            when {
                state.selectionMode -> screenModel.clearSelection()
                state.openFolderId != null -> screenModel.setOpenFolder(null)
                state.searchQuery != null -> screenModel.search(null)
            }
        }

        LaunchedEffect(state.selectionMode, state.dialog, state.openFolderId) {
            HomeScreen.showBottomNav(!state.selectionMode && state.openFolderId == null)
        }

        LaunchedEffect(state.isLoading) {
            if (!state.isLoading) {
                (context as? MainActivity)?.ready = true
                // AM (DISCORD) -->
                DiscordRPCService.setAnimeScreen(context, DiscordScreen.APP)
                // <-- AM (DISCORD)
            }
        }

        LaunchedEffect(Unit) {
            launch { queryEvent.receiveAsFlow().collect(screenModel::search) }
            launch { requestSettingsSheetEvent.receiveAsFlow().collectLatest { screenModel.showSettingsDialog() } }
        }
    }

    // For invoking search from other screen
    private val queryEvent = Channel<String>()
    suspend fun search(query: String) = queryEvent.send(query)

    // For opening settings sheet in LibraryController
    private val requestSettingsSheetEvent = Channel<Unit>()
    private suspend fun requestOpenSettingsSheet() = requestSettingsSheetEvent.send(Unit)
}
