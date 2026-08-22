package eu.kanade.presentation.anime

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.FabPosition
import kotlin.math.roundToInt
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAll
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastMap
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.anime.components.AnimeActionRow
import eu.kanade.presentation.anime.components.AnimeBottomActionMenu
import eu.kanade.presentation.anime.components.AnimeEpisodeListItem
import eu.kanade.presentation.anime.components.AnimeInfoBox
import eu.kanade.presentation.anime.components.AnimeSeasonListItem
import eu.kanade.presentation.anime.components.AnimeToolbar
import eu.kanade.presentation.anime.components.EpisodeDownloadAction
import eu.kanade.presentation.anime.components.EpisodeHeader
import eu.kanade.presentation.anime.components.CreditDetailsDialog
import eu.kanade.tachiyomi.animesource.model.Credit
import eu.kanade.presentation.anime.components.ExpandableAnimeDescription
import eu.kanade.presentation.anime.components.MissingEpisodeCountListItem
import eu.kanade.presentation.anime.components.NextEpisodeAiringListItem
import eu.kanade.presentation.components.relativeDateTimeText
import eu.kanade.presentation.theme.DynamicTachiyomiTheme
import eu.kanade.presentation.util.formatEpisodeNumber
import eu.kanade.tachiyomi.util.lang.formatTime
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.animesource.model.FetchType
import tachiyomi.domain.anime.model.SeasonDisplayMode
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.getNameForAnimeInfo
import eu.kanade.tachiyomi.source.model.SAnime
import eu.kanade.tachiyomi.ui.anime.AnimeScreenModel
import eu.kanade.tachiyomi.ui.anime.EpisodeList
import eu.kanade.tachiyomi.ui.browse.extension.details.SourcePreferencesScreen
import eu.kanade.tachiyomi.util.system.CoverColorObserver
import tachiyomi.presentation.core.util.collectAsState as collectAsStatePref
import eu.kanade.tachiyomi.util.system.copyToClipboard
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.model.asAnimeCover
import tachiyomi.domain.episode.model.Episode
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.source.model.StubSource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.ListGroupHeader
import tachiyomi.presentation.core.components.SkeletonAnimeCard
import tachiyomi.presentation.core.components.TwoPanelBox
import tachiyomi.presentation.core.components.VerticalFastScroller
import tachiyomi.presentation.core.components.material.ExtendedFloatingActionButton
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import tachiyomi.presentation.core.util.plus
import tachiyomi.presentation.core.util.shouldExpandFAB
import tachiyomi.presentation.core.util.tvFocusHighlight
import tachiyomi.source.localanime.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.time.Instant
import java.util.concurrent.TimeUnit

@Composable
fun AnimeScreen(
    state: AnimeScreenModel.State.Success,
    snackbarHostState: SnackbarHostState,
    nextUpdate: Instant?,
    isTabletUi: Boolean,
    episodeSwipeStartAction: LibraryPreferences.EpisodeSwipeAction,
    episodeSwipeEndAction: LibraryPreferences.EpisodeSwipeAction,
    showNextEpisodeAirTime: Boolean,
    alwaysUseExternalPlayer: Boolean,
    showFileSize: Boolean,
    autoExpandDescription: Boolean,
    onBackClicked: () -> Unit,
    onEpisodeClicked: (episode: Episode, alt: Boolean) -> Unit,
    onDownloadEpisode: ((List<EpisodeList.Item>, EpisodeDownloadAction) -> Unit)?,
    onAddToLibraryClicked: () -> Unit,
    onWebViewClicked: (() -> Unit)?,
    onWebViewLongClicked: (() -> Unit)?,
    onTrackingClicked: () -> Unit,
    onTagSearch: (String) -> Unit,
    onFilterButtonClicked: () -> Unit,
    onRefresh: () -> Unit,
    onContinueWatching: (tachiyomi.domain.anime.model.SeasonAnime?) -> Unit,
    onSearch: (query: String, global: Boolean) -> Unit,
    onCoverClicked: () -> Unit,
    onShareClicked: (() -> Unit)?,
    onDownloadActionClicked: ((DownloadAction) -> Unit)?,
    onEditCategoryClicked: (() -> Unit)?,
    onEditNotesClicked: () -> Unit,
    onMigrateClicked: (() -> Unit)?,
    changeAnimeSkipIntro: (() -> Unit)?,
    onEditInfoClicked: () -> Unit,
    onClearAnimeClicked: () -> Unit,
    onOpenAnimeFolderClicked: () -> Unit,
    onMergeClicked: (() -> Unit)?,
    onMultiBookmarkClicked: (List<Episode>, bookmarked: Boolean) -> Unit,
    onMultiFillermarkClicked: (List<Episode>, fillermarked: Boolean) -> Unit,
    onMultiMarkAsSeenClicked: (List<Episode>, markAsSeen: Boolean) -> Unit,
    onMarkPreviousAsSeenClicked: (Episode) -> Unit,
    onMultiDeleteClicked: (List<Episode>) -> Unit,
    onEpisodeSwipe: (EpisodeList.Item, LibraryPreferences.EpisodeSwipeAction) -> Unit,
    onEpisodeSelected: (EpisodeList.Item, Boolean, Boolean, Boolean) -> Unit,
    onAllEpisodeSelected: (Boolean) -> Unit,
    onInvertSelection: () -> Unit,
    onEditIntervalClicked: (() -> Unit)?,
    onToggleDiscoveryExpansion: () -> Unit,
    onSettingsClicked: (() -> Unit)?,
    onSeasonSelected: (String?) -> Unit,
    // AY -->
    onSeasonClicked: (tachiyomi.domain.anime.model.SeasonAnime) -> Unit,
    // <-- AY
) {
    val sourcePreferences: SourcePreferences by injectLazy()
    val context = LocalContext.current
    var activeCreditIndex by remember { mutableStateOf<Int?>(null) }
    val onCopyTagToClipboard: (tag: String) -> Unit = {
        if (it.isNotEmpty()) {
            context.copyToClipboard(it, it)
        }
    }

    val navigator = LocalNavigator.currentOrThrow

    val combinedItems = remember(state.suggestionSections) {
        state.suggestionSections.flatMap { it.items }
            .distinctBy { it.id to it.url }
    }

    val onSuggestionsClicked = {
        navigator.push(eu.kanade.tachiyomi.ui.browse.source.browse.RelatedAnimeScreen(state.anime.id))
    }

    if (!isTabletUi) {
        AnimeScreenSmallImpl(
            state = state,
            sourcePreferences = sourcePreferences,
            snackbarHostState = snackbarHostState,
            nextUpdate = nextUpdate,
            episodeSwipeStartAction = episodeSwipeStartAction,
            episodeSwipeEndAction = episodeSwipeEndAction,
            showNextEpisodeAirTime = showNextEpisodeAirTime,
            alwaysUseExternalPlayer = alwaysUseExternalPlayer,
            showFileSize = showFileSize,
            autoExpandDescription = autoExpandDescription,
            onBackClicked = onBackClicked,
            onEpisodeClicked = onEpisodeClicked,
            onDownloadEpisode = onDownloadEpisode,
            onAddToLibraryClicked = onAddToLibraryClicked,
            onWebViewClicked = onWebViewClicked,
            onWebViewLongClicked = onWebViewLongClicked,
            onTrackingClicked = onTrackingClicked,
            onTagSearch = onTagSearch,
            onCopyTagToClipboard = onCopyTagToClipboard,
            onFilterClicked = onFilterButtonClicked,
            onRefresh = onRefresh,
            onContinueWatching = onContinueWatching,
            onSearch = onSearch,
            onCoverClicked = onCoverClicked,
            onSeasonSelected = onSeasonSelected,
            // AY -->
            onSeasonClicked = onSeasonClicked,
            // <-- AY
            onShareClicked = onShareClicked,
            onDownloadActionClicked = onDownloadActionClicked,
            onEditCategoryClicked = onEditCategoryClicked,
            onEditNotesClicked = onEditNotesClicked,
            onMigrateClicked = onMigrateClicked,
            onSuggestionsClicked = onSuggestionsClicked,
            changeAnimeSkipIntro = changeAnimeSkipIntro,
            onEditInfoClicked = onEditInfoClicked,
            onClearAnimeClicked = onClearAnimeClicked,
            onOpenAnimeFolderClicked = onOpenAnimeFolderClicked,
            onMultiBookmarkClicked = onMultiBookmarkClicked,
            onMultiFillermarkClicked = onMultiFillermarkClicked,
            onMultiMarkAsSeenClicked = onMultiMarkAsSeenClicked,
            onMarkPreviousAsSeenClicked = onMarkPreviousAsSeenClicked,
            onMultiDeleteClicked = onMultiDeleteClicked,
            onEpisodeSwipe = onEpisodeSwipe,
            onEpisodeSelected = onEpisodeSelected,
            onAllEpisodeSelected = onAllEpisodeSelected,
            onInvertSelection = onInvertSelection,
            onSettingsClicked = onSettingsClicked,
            onMergeClicked = onMergeClicked,
            onEditIntervalClicked = onEditIntervalClicked,
            onToggleDiscoveryExpansion = onToggleDiscoveryExpansion,
            combinedItems = combinedItems,
            onCastClick = { credit -> activeCreditIndex = state.anime.cast?.indexOf(credit)?.takeIf { it >= 0 } },
        )
    } else {
        AnimeScreenLargeImpl(
            state = state,
            sourcePreferences = sourcePreferences,
            snackbarHostState = snackbarHostState,
            nextUpdate = nextUpdate,
            episodeSwipeStartAction = episodeSwipeStartAction,
            episodeSwipeEndAction = episodeSwipeEndAction,
            showNextEpisodeAirTime = showNextEpisodeAirTime,
            alwaysUseExternalPlayer = alwaysUseExternalPlayer,
            showFileSize = showFileSize,
            autoExpandDescription = autoExpandDescription,
            onBackClicked = onBackClicked,
            onEpisodeClicked = onEpisodeClicked,
            onDownloadEpisode = onDownloadEpisode,
            onAddToLibraryClicked = onAddToLibraryClicked,
            onWebViewClicked = onWebViewClicked,
            onWebViewLongClicked = onWebViewLongClicked,
            onTrackingClicked = onTrackingClicked,
            onTagSearch = onTagSearch,
            onCopyTagToClipboard = onCopyTagToClipboard,
            onFilterButtonClicked = onFilterButtonClicked,
            onRefresh = onRefresh,
            onContinueWatching = onContinueWatching,
            onSearch = onSearch,
            onCoverClicked = onCoverClicked,
            onSeasonSelected = onSeasonSelected,
            // AY -->
            onSeasonClicked = onSeasonClicked,
            // <-- AY
            onShareClicked = onShareClicked,
            onDownloadActionClicked = onDownloadActionClicked,
            onEditCategoryClicked = onEditCategoryClicked,
            onEditNotesClicked = onEditNotesClicked,
            onMigrateClicked = onMigrateClicked,
            onSuggestionsClicked = onSuggestionsClicked,
            changeAnimeSkipIntro = changeAnimeSkipIntro,
            onEditInfoClicked = onEditInfoClicked,
            onClearAnimeClicked = onClearAnimeClicked,
            onOpenAnimeFolderClicked = onOpenAnimeFolderClicked,
            onMergeClicked = onMergeClicked,
            onMultiBookmarkClicked = onMultiBookmarkClicked,
            onMultiFillermarkClicked = onMultiFillermarkClicked,
            onMultiMarkAsSeenClicked = onMultiMarkAsSeenClicked,
            onMarkPreviousAsSeenClicked = onMarkPreviousAsSeenClicked,
            onMultiDeleteClicked = onMultiDeleteClicked,
            onEpisodeSwipe = onEpisodeSwipe,
            onEpisodeSelected = onEpisodeSelected,
            onAllEpisodeSelected = onAllEpisodeSelected,
            onInvertSelection = onInvertSelection,
            onEditIntervalClicked = onEditIntervalClicked,
            onToggleDiscoveryExpansion = onToggleDiscoveryExpansion,
            onSettingsClicked = onSettingsClicked,
            combinedItems = combinedItems,
            onCastClick = { credit -> activeCreditIndex = state.anime.cast?.indexOf(credit)?.takeIf { it >= 0 } },
        )
    }

    activeCreditIndex?.let { index ->
        val castList = state.anime.cast ?: emptyList()
        CreditDetailsDialog(
            cast = castList,
            initialIndex = index,
            onDismissRequest = { activeCreditIndex = null },
            onSearch = { onSearch(it, true) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnimeScreenSmallImpl(
    state: AnimeScreenModel.State.Success,
    sourcePreferences: SourcePreferences,
    snackbarHostState: SnackbarHostState,
    nextUpdate: Instant?,
    episodeSwipeStartAction: LibraryPreferences.EpisodeSwipeAction,
    episodeSwipeEndAction: LibraryPreferences.EpisodeSwipeAction,
    showNextEpisodeAirTime: Boolean,
    alwaysUseExternalPlayer: Boolean,
    showFileSize: Boolean,
    autoExpandDescription: Boolean,
    onBackClicked: () -> Unit,
    onEpisodeClicked: (Episode, Boolean) -> Unit,
    onDownloadEpisode: ((List<EpisodeList.Item>, EpisodeDownloadAction) -> Unit)?,
    onAddToLibraryClicked: () -> Unit,
    onWebViewClicked: (() -> Unit)?,
    onWebViewLongClicked: (() -> Unit)?,
    onTrackingClicked: () -> Unit,
    onTagSearch: (String) -> Unit,
    onCopyTagToClipboard: (tag: String) -> Unit,
    onFilterClicked: () -> Unit,
    onRefresh: () -> Unit,
    onContinueWatching: (tachiyomi.domain.anime.model.SeasonAnime?) -> Unit,
    onSearch: (query: String, global: Boolean) -> Unit,
    onCoverClicked: () -> Unit,
    onShareClicked: (() -> Unit)?,
    onDownloadActionClicked: ((DownloadAction) -> Unit)?,
    onEditCategoryClicked: (() -> Unit)?,
    onEditNotesClicked: () -> Unit,
    onMigrateClicked: (() -> Unit)?,
    onSuggestionsClicked: () -> Unit,
    changeAnimeSkipIntro: (() -> Unit)?,
    onSettingsClicked: (() -> Unit)?,
    onEditInfoClicked: () -> Unit,
    onClearAnimeClicked: () -> Unit,
    onOpenAnimeFolderClicked: () -> Unit,
    onMergeClicked: (() -> Unit)?,
    onMultiBookmarkClicked: (List<Episode>, bookmarked: Boolean) -> Unit,
    onMultiFillermarkClicked: (List<Episode>, fillermarked: Boolean) -> Unit,
    onMultiMarkAsSeenClicked: (List<Episode>, markAsSeen: Boolean) -> Unit,
    onMarkPreviousAsSeenClicked: (Episode) -> Unit,
    onMultiDeleteClicked: (List<Episode>) -> Unit,
    onEpisodeSwipe: (EpisodeList.Item, LibraryPreferences.EpisodeSwipeAction) -> Unit,
    onEpisodeSelected: (EpisodeList.Item, Boolean, Boolean, Boolean) -> Unit,
    onAllEpisodeSelected: (Boolean) -> Unit,
    onInvertSelection: () -> Unit,
    onEditIntervalClicked: (() -> Unit)?,
    onToggleDiscoveryExpansion: () -> Unit,
    onSeasonSelected: (String?) -> Unit,
    // AY -->
    onSeasonClicked: (tachiyomi.domain.anime.model.SeasonAnime) -> Unit,
    // <-- AY
    combinedItems: List<tachiyomi.domain.anime.model.Anime>,
    onCastClick: (Credit) -> Unit,
) {
    val navigator = LocalNavigator.currentOrThrow
    val episodeListState = rememberLazyListState()
    val episodes = state.processedEpisodes
    val listItem = remember(state.episodeListItems, state.selectedSeason, state.anime.seasonGroupingMode, state.availableSeasons) {
        if (state.anime.seasonGroupingMode != LibraryPreferences.SeasonGrouping.Tabs || state.selectedSeason == null || state.availableSeasons.size <= 1) {
            state.episodeListItems
        } else {
            var inSelectedSeason = false
            var currentSeason: String? = null
            state.episodeListItems.filter { item ->
                when (item) {
                    is EpisodeList.Season -> {
                        currentSeason = item.name
                        inSelectedSeason = currentSeason == state.selectedSeason
                        false
                    }
                    is EpisodeList.Item -> inSelectedSeason
                    is EpisodeList.MissingCount -> inSelectedSeason
                }
            }
        }
    }
    
    val currentSeasonCount = remember(listItem, state.anime.seasonGroupingMode) {
        if (state.anime.seasonGroupingMode == LibraryPreferences.SeasonGrouping.Tabs) {
            listItem.count { it is EpisodeList.Item }
        } else {
            state.processedEpisodes.size
        }
    }

    val isFirstItemVisible by remember {
        derivedStateOf { episodeListState.firstVisibleItemIndex == 0 }
    }

    val showSuggestions = sourcePreferences.relatedAnimeShowSource().collectAsState().value
    val expandSuggestions = sourcePreferences.relatedAnimeExpand().collectAsState().value
    val suggestionsInOverflow = sourcePreferences.relatedAnimeInOverflow().collectAsState().value

    val isAnySelected by remember(episodes) {
        derivedStateOf { episodes.fastAny { it.selected } }
    }

    val internalOnBackPressed = {
        if (isAnySelected) {
            onAllEpisodeSelected(false)
        } else {
            onBackClicked()
        }
    }
    BackHandler(onBack = internalOnBackPressed)

    val density = LocalDensity.current
    val vibrantColorState by remember(state.anime.id) {
        CoverColorObserver.vibrantColors
            .map { it[state.anime.id] }
            .distinctUntilChanged()
    }.collectAsState(initial = CoverColorObserver.get(state.anime.id))
    // Defer applying a new color seed to DynamicTachiyomiTheme while the list is actively
    // scrolling. When vibrantColorState first emits (palette extraction completing on first open),
    // it triggers a full recomposition of everything under DynamicTachiyomiTheme. Coinciding with
    // an active scroll fling causes the first-scroll freeze unique to AniZen (upstream has no
    // dynamic theme wrapper). The color is applied immediately when the user is not scrolling.
    var deferredVibrantColor by remember(state.anime.id) {
        mutableStateOf(vibrantColorState)
    }
    LaunchedEffect(vibrantColorState) {
        if (vibrantColorState == deferredVibrantColor) return@LaunchedEffect
        snapshotFlow { episodeListState.isScrollInProgress }
            .filter { !it }
            .first()
        deferredVibrantColor = vibrantColorState
    }
    val vibrantColor = deferredVibrantColor ?: state.anime.asAnimeCover().vibrantCoverColor

    DynamicTachiyomiTheme(colorSeed = vibrantColor) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            val scaffoldInsets = WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
            val isFABVisible = remember(state.anime.fetchType, episodes, isAnySelected) {
                state.anime.fetchType != FetchType.Seasons && !isAnySelected && episodes.fastAny { !it.episode.seen }
            }
            Scaffold(
                hazeEnabled = false,
                contentWindowInsets = scaffoldInsets,
                floatingActionButtonPosition = FabPosition.Center,
                floatingActionButton = {
                    AnimatedVisibility(
                        visible = isFABVisible,
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        val isWatching = remember(state.episodes) {
                            state.episodes.fastAny { it.episode.seen }
                        }
                        DraggableAnimeFAB(
                            isWatching = isWatching,
                            onContinueWatching = { onContinueWatching(null) },
                            shouldExpand = episodeListState.shouldExpandFAB(),
                        )
                    }
                },
                topBar = {
                    val selectedEpisodeCount: Int = remember(episodes) {
                        episodes.count { it.selected }
                    }
                    val isFirstItemScrolled by remember {
                        derivedStateOf { episodeListState.firstVisibleItemScrollOffset > 0 }
                    }
                    val animatedTitleAlphaState = animateFloatAsState(
                        targetValue = if (!isFirstItemVisible) 1f else 0f,
                        animationSpec = tween(200),
                        label = "Top Bar Title",
                    )
                    val animatedBgAlphaState = animateFloatAsState(
                        targetValue = if (!isFirstItemVisible || isFirstItemScrolled) 1f else 0f,
                        animationSpec = tween(200),
                        label = "Top Bar Background",
                    )
                    AnimeToolbar(
                        title = state.anime.title,
                        titleAlphaProvider = { animatedTitleAlphaState.value },
                        backgroundAlphaProvider = { animatedBgAlphaState.value },
                        hasFilters = state.filterActive,
                        onBackClicked = internalOnBackPressed,
                        onClickFilter = onFilterClicked,
                        onClickShare = onShareClicked,
                        onClickDownload = onDownloadActionClicked,
                        onClickEditCategory = onEditCategoryClicked,
                        onClickRefresh = onRefresh,
                        onClickMigrate = onMigrateClicked,
                        onClickSuggestions = onSuggestionsClicked.takeIf { suggestionsInOverflow },
                        onClickEditNotes = onEditNotesClicked,
                        onClickEditInfo = onEditInfoClicked.takeIf { state.anime.favorite },
                        onClickClearAnime = onClearAnimeClicked.takeIf { state.anime.favorite },
                        onClickOpenAnimeFolder = onOpenAnimeFolderClicked,
                        onClickSettings = onSettingsClicked,
                        onClickMerge = onMergeClicked,
                        changeAnimeSkipIntro = changeAnimeSkipIntro,
                        actionModeCounter = selectedEpisodeCount,
                        onSelectAll = { onAllEpisodeSelected(true) },
                        onInvertSelection = { onInvertSelection() },
                    )
                },
                bottomBar = {
                    val selectedEpisodes = remember(episodes) {
                        episodes.filter { it.selected }
                    }
                    SharedAnimeBottomActionMenu(
                        selected = selectedEpisodes,
                        anime = state.anime,
                        onEpisodeClicked = onEpisodeClicked,
                        onMultiBookmarkClicked = onMultiBookmarkClicked,
                        onMultiFillermarkClicked = onMultiFillermarkClicked,
                        onMultiMarkAsSeenClicked = onMultiMarkAsSeenClicked,
                        onMarkPreviousAsSeenClicked = onMarkPreviousAsSeenClicked,
                        onDownloadEpisode = onDownloadEpisode,
                        onMultiDeleteClicked = onMultiDeleteClicked,
                        onContinueWatching = { onContinueWatching(null) },
                        fillFraction = 1f,
                        alwaysUseExternalPlayer = alwaysUseExternalPlayer,
                    )
                },
                snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            ) { contentPadding ->
                val topPadding = contentPadding.calculateTopPadding()
                PullRefresh(
                    refreshing = state.isRefreshingData,
                    onRefresh = onRefresh,
                    enabled = !isAnySelected,
                    indicatorPadding = PaddingValues(top = topPadding),
                ) {
                    val layoutDirection = LocalLayoutDirection.current

                    androidx.compose.foundation.layout.BoxWithConstraints {
                        val containerHeight = with(density) { maxHeight.roundToPx() }
                        VerticalFastScroller(
                            listState = episodeListState,
                            topContentPadding = topPadding,
                            bottomContentPadding = contentPadding.calculateBottomPadding(),
                            endContentPadding = contentPadding.calculateEndPadding(layoutDirection),
                        ) {
                            LazyColumn(
                                modifier = Modifier.fillMaxHeight(),
                                state = episodeListState,
                                contentPadding = PaddingValues(
                                    start = contentPadding.calculateStartPadding(layoutDirection),
                                    end = contentPadding.calculateEndPadding(layoutDirection),
                                    bottom = contentPadding.calculateBottomPadding(),
                                ),
                            ) {
                                // item[0]: info-box only — drives isFirstItemVisible for the
                                // toolbar transparent→solid transition. Must stay separate.
                                item(key = "info-box-small", contentType = AnimeScreenItem.INFO_BOX) {
                                    AnimeInfoBox(
                                        isTabletUi = false,
                                        appBarPadding = topPadding,
                                        anime = state.anime,
                                        totalScore = state.totalScore,
                                        sourceName = remember { state.source.getNameForAnimeInfo() },
                                        isStubSource = remember { state.source is StubSource },
                                        onCoverClick = onCoverClicked,
                                        doSearch = onSearch,
                                        mergedSources = state.mergedSources,
                                        isRefreshing = state.isRefreshingData,
                                    )
                                }

                                item(key = "action-row-small", contentType = AnimeScreenItem.ACTION_ROW) {
                                    val isWatching = remember(state.episodes) {
                                        state.episodes.fastAny { it.episode.seen }
                                    }
                                    AnimeActionRow(
                                        favorite = state.anime.favorite,
                                        trackingCount = state.trackingCount,
                                        nextUpdate = nextUpdate,
                                        isUserIntervalMode = state.anime.fetchInterval < 0,
                                        fetchInterval = state.anime.fetchInterval,
                                        status = state.anime.status,
                                        onAddToLibraryClicked = onAddToLibraryClicked,
                                        onWebViewClicked = onWebViewClicked,
                                        onWebViewLongClicked = onWebViewLongClicked,
                                        onTrackingClicked = onTrackingClicked,
                                        onEditIntervalClicked = onEditIntervalClicked,
                                        onEditNotesClicked = onEditNotesClicked,
                                        onEditCategory = onEditCategoryClicked,
                                        onContinueWatching = { onContinueWatching(null) },
                                        isWatching = isWatching,
                                        mainTrackItem = remember(state.trackItems) { state.trackItems.firstOrNull() },
                                    )
                                }

                                item(key = "description-small", contentType = AnimeScreenItem.DESCRIPTION_WITH_TAG) {
                                    ExpandableAnimeDescription(
                                        modifier = Modifier.padding(bottom = 8.dp),
                                        defaultExpandState = autoExpandDescription,
                                        description = state.anime.description,
                                        note = state.anime.note,
                                        tagsProvider = { state.anime.genre },
                                        onTagSearch = onTagSearch,
                                        onCopyTagToClipboard = onCopyTagToClipboard,
                                    )
                                }

                                item(key = "relations-small") {
                                    eu.kanade.presentation.anime.components.PrequelSequelBox(
                                        anime = state.anime,
                                        relations = state.relations,
                                        onRelationClick = { onSearch(it, true) },
                                    )
                                }

                                val castInner = state.anime.cast
                                if (!castInner.isNullOrEmpty()) {
                                    item(key = "cast-small") {
                                        eu.kanade.presentation.anime.components.CastRow(
                                            cast = castInner,
                                            onClick = onCastClick,
                                        )
                                    }
                                }

                                if (showSuggestions && !suggestionsInOverflow) {
                                    item(key = "discovery-small") {
                                        Surface(
                                            modifier = Modifier
                                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                                .fillMaxWidth(),
                                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                                            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                                            tonalElevation = 2.dp,
                                        ) {
                                            Column(modifier = Modifier.padding(vertical = 12.dp)) {
                                                DiscoveryHeader(
                                                    onClick = { navigator.push(eu.kanade.tachiyomi.ui.browse.source.browse.RelatedAnimeScreen(state.anime.id)) },
                                                )
                                                if (expandSuggestions) {
                                                    if (combinedItems.isEmpty() && state.isSuggestionsLoading) {
                                                        androidx.compose.foundation.lazy.LazyRow(
                                                            modifier = Modifier.heightIn(min = 180.dp),
                                                            contentPadding = PaddingValues(horizontal = 12.dp),
                                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                            userScrollEnabled = false,
                                                        ) {
                                                            items(5, key = { "skeleton-small-$it" }) {
                                                                SkeletonAnimeCard()
                                                            }
                                                        }
                                                    } else if (combinedItems.isEmpty() && !state.isSuggestionsLoading) {
                                                        Text(
                                                            text = "No suggestions found for this entry",
                                                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp).heightIn(min = 40.dp),
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        )
                                                    } else {
                                                        androidx.compose.foundation.lazy.LazyRow(
                                                            modifier = Modifier.heightIn(min = 180.dp),
                                                            contentPadding = PaddingValues(horizontal = 12.dp),
                                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                        ) {
                                                            itemsIndexed(
                                                                items = combinedItems,
                                                                key = { _, anime: tachiyomi.domain.anime.model.Anime -> "suggestion-small-${anime.id}" },
                                                            ) { _, anime: tachiyomi.domain.anime.model.Anime ->
                                                                SuggestionItem(
                                                                    anime = anime,
                                                                    onClick = { navigator.push(eu.kanade.tachiyomi.ui.anime.AnimeScreen(anime.id)) },
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                if (state.anime.seasonGroupingMode == LibraryPreferences.SeasonGrouping.Tabs && state.availableSeasons.size > 1) {
                                    item(key = "season-selector-small") {
                                        SeasonSelector(
                                            seasons = state.availableSeasons,
                                            selectedSeason = state.selectedSeason,
                                            onSeasonSelected = onSeasonSelected,
                                        )
                                    }
                                }

                                if (state.anime.fetchType == FetchType.Seasons) {
                                    item(key = "season-header-small", contentType = AnimeScreenItem.EPISODE_HEADER) {
                                        EpisodeHeader(
                                            enabled = !isAnySelected,
                                            episodeCount = state.processedSeasonItems.size,
                                            missingEpisodeCount = 0,
                                            onClick = onFilterClicked,
                                            fetchType = FetchType.Seasons,
                                        )
                                    }
                                } else {
                                    item(key = "episode-header-small", contentType = AnimeScreenItem.EPISODE_HEADER) {
                                        EpisodeHeader(
                                            enabled = !isAnySelected,
                                            episodeCount = if (state.anime.seasonGroupingMode == LibraryPreferences.SeasonGrouping.Tabs) currentSeasonCount else episodes.size,
                                            missingEpisodeCount = state.missingEpisodeCount,
                                            onClick = onFilterClicked,
                                        )
                                    }
                                    if (state.airingTime > 0L) {
                                        item(key = "airing-time-small", contentType = AnimeScreenItem.AIRING_TIME) {
                                            var timer by remember(state.airingTime) { mutableLongStateOf(state.airingTime) }
                                            LaunchedEffect(key1 = timer) {
                                                if (timer > 0L) {
                                                    delay(1000L)
                                                    timer -= 1000L
                                                }
                                            }
                                            if (timer > 0L && showNextEpisodeAirTime && state.anime.status.toInt() != SAnime.COMPLETED) {
                                                NextEpisodeAiringListItem(
                                                    title = stringResource(
                                                        MR.strings.display_mode_episode,
                                                        formatEpisodeNumber(state.airingEpisodeNumber),
                                                    ),
                                                    date = formatTime(timer, useDayFormat = true),
                                                )
                                            }
                                        }
                                    }
                                }

                                // items[2+]: season or episode items (lazy)
                                if (state.anime.fetchType == FetchType.Seasons) {
                                    val columns = if (state.anime.seasonDisplayGridMode == SeasonDisplayMode.List) {
                                        1
                                    } else {
                                        state.anime.seasonDisplayGridSize.takeIf { it > 0 } ?: 2
                                    }
                                    val seasons = state.processedSeasonItems
                                    if (columns == 1) {
                                        items(
                                            items = seasons,
                                            key = { item -> item.seasonAnime.anime.id },
                                        ) { item ->
                                            AnimeSeasonListItem(
                                                anime = state.anime,
                                                item = item,
                                                containerHeight = containerHeight,
                                                onSeasonClicked = onSeasonClicked,
                                                onClickContinueWatching = {
                                                    onContinueWatching(item.seasonAnime)
                                                },
                                                listItemModifier = Modifier,
                                            )
                                        }
                                    } else {
                                        val rows = seasons.chunked(columns)
                                        rows.forEachIndexed { index, row ->
                                            item(key = "season-row-$index") {
                                                Row(
                                                    modifier = Modifier
                                                        .padding(horizontal = 8.dp)
                                                        .fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                ) {
                                                    row.forEach { item ->
                                                        Box(modifier = Modifier.weight(1f)) {
                                                            AnimeSeasonListItem(
                                                                anime = state.anime,
                                                                item = item,
                                                                containerHeight = containerHeight,
                                                                onSeasonClicked = onSeasonClicked,
                                                                onClickContinueWatching = {
                                                                    onContinueWatching(item.seasonAnime)
                                                                },
                                                                listItemModifier = Modifier,
                                                            )
                                                        }
                                                    }
                                                    repeat(columns - row.size) {
                                                        Spacer(modifier = Modifier.weight(1f))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    sharedEpisodeItems(
                                        anime = state.anime,
                                        source = state.source,
                                        showFileSize = showFileSize,
                                        showEpisodeSummary = state.showEpisodeSummary,
                                        showEpisodeThumbnail = state.showEpisodeThumbnail,
                                        episodes = listItem,
                                        fillerEpisodes = state.fillerEpisodes,
                                        isAnyEpisodeSelected = episodes.fastAny { it.selected },
                                        episodeSwipeStartAction = episodeSwipeStartAction,
                                        episodeSwipeEndAction = episodeSwipeEndAction,
                                        onEpisodeClicked = onEpisodeClicked,
                                        onDownloadEpisode = onDownloadEpisode,
                                        onEpisodeSelected = onEpisodeSelected,
                                        onEpisodeSwipe = onEpisodeSwipe,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnimeScreenLargeImpl(
    state: AnimeScreenModel.State.Success,
    sourcePreferences: SourcePreferences,
    snackbarHostState: SnackbarHostState,
    nextUpdate: Instant?,
    episodeSwipeStartAction: LibraryPreferences.EpisodeSwipeAction,
    episodeSwipeEndAction: LibraryPreferences.EpisodeSwipeAction,
    showNextEpisodeAirTime: Boolean,
    alwaysUseExternalPlayer: Boolean,
    showFileSize: Boolean,
    autoExpandDescription: Boolean,
    onBackClicked: () -> Unit,
    onEpisodeClicked: (Episode, Boolean) -> Unit,
    onDownloadEpisode: ((List<EpisodeList.Item>, EpisodeDownloadAction) -> Unit)?,
    onAddToLibraryClicked: () -> Unit,
    onWebViewClicked: (() -> Unit)?,
    onWebViewLongClicked: (() -> Unit)?,
    onTrackingClicked: () -> Unit,
    onTagSearch: (String) -> Unit,
    onCopyTagToClipboard: (tag: String) -> Unit,
    onFilterButtonClicked: () -> Unit,
    onRefresh: () -> Unit,
    onContinueWatching: (tachiyomi.domain.anime.model.SeasonAnime?) -> Unit,
    onSearch: (query: String, global: Boolean) -> Unit,
    onCoverClicked: () -> Unit,
    onShareClicked: (() -> Unit)?,
    onDownloadActionClicked: ((DownloadAction) -> Unit)?,
    onEditCategoryClicked: (() -> Unit)?,
    onEditNotesClicked: () -> Unit,
    onMigrateClicked: (() -> Unit)?,
    onSuggestionsClicked: () -> Unit,
    changeAnimeSkipIntro: (() -> Unit)?,
    onSettingsClicked: (() -> Unit)?,
    onEditInfoClicked: () -> Unit,
    onClearAnimeClicked: () -> Unit,
    onOpenAnimeFolderClicked: () -> Unit,
    onMergeClicked: (() -> Unit)?,
    onMultiBookmarkClicked: (List<Episode>, bookmarked: Boolean) -> Unit,
    onMultiFillermarkClicked: (List<Episode>, fillermarked: Boolean) -> Unit,
    onMultiMarkAsSeenClicked: (List<Episode>, markAsSeen: Boolean) -> Unit,
    onMarkPreviousAsSeenClicked: (Episode) -> Unit,
    onMultiDeleteClicked: (List<Episode>) -> Unit,
    onEpisodeSwipe: (EpisodeList.Item, LibraryPreferences.EpisodeSwipeAction) -> Unit,
    onEpisodeSelected: (EpisodeList.Item, Boolean, Boolean, Boolean) -> Unit,
    onAllEpisodeSelected: (Boolean) -> Unit,
    onInvertSelection: () -> Unit,
    onEditIntervalClicked: (() -> Unit)?,
    onToggleDiscoveryExpansion: () -> Unit,
    onSeasonSelected: (String?) -> Unit,
    // AY -->
    onSeasonClicked: (tachiyomi.domain.anime.model.SeasonAnime) -> Unit,
    // <-- AY
    combinedItems: List<tachiyomi.domain.anime.model.Anime>,
    onCastClick: (Credit) -> Unit,
) {
    val navigator = LocalNavigator.currentOrThrow
    val layoutDirection = LocalLayoutDirection.current
    val density = LocalDensity.current
    val episodes = state.processedEpisodes
    val listItem = remember(state.episodeListItems, state.selectedSeason, state.anime.seasonGroupingMode, state.availableSeasons) {
        if (state.anime.seasonGroupingMode != LibraryPreferences.SeasonGrouping.Tabs || state.selectedSeason == null || state.availableSeasons.size <= 1) {
            state.episodeListItems
        } else {
            var inSelectedSeason = false
            var currentSeason: String? = null
            state.episodeListItems.filter { item ->
                when (item) {
                    is EpisodeList.Season -> {
                        currentSeason = item.name
                        inSelectedSeason = currentSeason == state.selectedSeason
                        false
                    }
                    is EpisodeList.Item -> inSelectedSeason
                    is EpisodeList.MissingCount -> inSelectedSeason
                }
            }
        }
    }
    
    val currentSeasonCount = remember(listItem, state.anime.seasonGroupingMode) {
        if (state.anime.seasonGroupingMode == LibraryPreferences.SeasonGrouping.Tabs) {
            listItem.count { it is EpisodeList.Item }
        } else {
            state.processedEpisodes.size
        }
    }

    val showSuggestions = sourcePreferences.relatedAnimeShowSource().collectAsState().value
    val expandSuggestions = sourcePreferences.relatedAnimeExpand().collectAsState().value
    val suggestionsInOverflow = sourcePreferences.relatedAnimeInOverflow().collectAsState().value

    val isAnySelected by remember(episodes) {
        derivedStateOf { episodes.fastAny { it.selected } }
    }
    val insetPadding = WindowInsets.systemBars.only(WindowInsetsSides.Horizontal).asPaddingValues()
    var topBarHeight by remember { mutableIntStateOf(0) }
    val episodeListState = rememberLazyListState()
    val infoScrollState = rememberScrollState()

    val isFirstItemVisible by remember {
        derivedStateOf { episodeListState.firstVisibleItemIndex == 0 }
    }

    val internalOnBackPressed = {
        if (isAnySelected) {
            onAllEpisodeSelected(false)
        } else {
            onBackClicked()
        }
    }
    BackHandler(onBack = internalOnBackPressed)

    val vibrantColorState by remember(state.anime.id) {
        CoverColorObserver.vibrantColors
            .map { it[state.anime.id] }
            .distinctUntilChanged()
    }.collectAsState(initial = CoverColorObserver.get(state.anime.id))
    val vibrantColor = vibrantColorState ?: state.anime.asAnimeCover().vibrantCoverColor

    DynamicTachiyomiTheme(colorSeed = vibrantColor) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            val scaffoldInsets = WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
            val isFABVisible = remember(state.anime.fetchType, episodes, isAnySelected) {
                state.anime.fetchType != FetchType.Seasons && !isAnySelected && episodes.fastAny { !it.episode.seen }
            }
            Scaffold(
                hazeEnabled = false,
                contentWindowInsets = scaffoldInsets,
                floatingActionButtonPosition = FabPosition.Center,
                floatingActionButton = {
                    AnimatedVisibility(
                        visible = isFABVisible,
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        val isWatching = remember(state.episodes) {
                            state.episodes.fastAny { it.episode.seen }
                        }
                        DraggableAnimeFAB(
                            isWatching = isWatching,
                            onContinueWatching = { onContinueWatching(null) },
                            shouldExpand = episodeListState.shouldExpandFAB(),
                        )
                    }
                },
                topBar = {
                    val selectedEpisodeCount = remember(episodes) {
                        episodes.count { it.selected }
                    }
                    val isFirstItemScrolled by remember {
                        derivedStateOf { episodeListState.firstVisibleItemScrollOffset > 0 }
                    }
                    val animatedTitleAlphaState = animateFloatAsState(
                        targetValue = if (!isFirstItemVisible) 1f else 0f,
                        animationSpec = tween(200),
                        label = "Top Bar Title",
                    )
                    val animatedBgAlphaState = animateFloatAsState(
                        targetValue = if (!isFirstItemVisible || isFirstItemScrolled) 1f else 0f,
                        animationSpec = tween(200),
                        label = "Top Bar Background",
                    )
                    AnimeToolbar(
                        modifier = Modifier.onSizeChanged { topBarHeight = it.height },
                        title = state.anime.title,
                        titleAlphaProvider = { if (isAnySelected) 1f else animatedTitleAlphaState.value },
                        backgroundAlphaProvider = { animatedBgAlphaState.value },
                        hasFilters = state.filterActive,
                        onBackClicked = internalOnBackPressed,
                        onClickFilter = onFilterButtonClicked,
                        onClickShare = onShareClicked,
                        onClickDownload = onDownloadActionClicked,
                        onClickEditCategory = onEditCategoryClicked,
                        onClickRefresh = onRefresh,
                        onClickMigrate = onMigrateClicked,
                        onClickSuggestions = onSuggestionsClicked.takeIf { suggestionsInOverflow },
                        onClickEditNotes = onEditNotesClicked,
                        onClickEditInfo = onEditInfoClicked.takeIf { state.anime.favorite },
                        onClickClearAnime = onClearAnimeClicked.takeIf { state.anime.favorite },
                        onClickOpenAnimeFolder = onOpenAnimeFolderClicked,
                        onClickSettings = onSettingsClicked,
                        onClickMerge = onMergeClicked,
                        changeAnimeSkipIntro = changeAnimeSkipIntro,
                        actionModeCounter = selectedEpisodeCount,
                        onSelectAll = { onAllEpisodeSelected(true) },
                        onInvertSelection = { onInvertSelection() },
                    )
                },
                bottomBar = {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.BottomEnd,
                    ) {
                        val selectedEpisodes = remember(episodes) {
                            episodes.filter { it.selected }
                        }
                        SharedAnimeBottomActionMenu(
                            selected = selectedEpisodes,
                            anime = state.anime,
                            onEpisodeClicked = onEpisodeClicked,
                            onMultiBookmarkClicked = onMultiBookmarkClicked,
                            onMultiFillermarkClicked = onMultiFillermarkClicked,
                            onMultiMarkAsSeenClicked = onMultiMarkAsSeenClicked,
                            onMarkPreviousAsSeenClicked = onMarkPreviousAsSeenClicked,
                            onDownloadEpisode = onDownloadEpisode,
                            onMultiDeleteClicked = onMultiDeleteClicked,
                            onContinueWatching = { onContinueWatching(null) },
                            fillFraction = 0.5f,
                            alwaysUseExternalPlayer = alwaysUseExternalPlayer,
                        )
                    }
                },
                snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            ) { contentPadding ->
                PullRefresh(
                    refreshing = state.isRefreshingData,
                    onRefresh = onRefresh,
                    enabled = !isAnySelected,
                    indicatorPadding = PaddingValues(
                        start = insetPadding.calculateStartPadding(layoutDirection),
                        top = with(density) { topBarHeight.toDp() },
                        end = insetPadding.calculateEndPadding(layoutDirection),
                    ),
                ) {
                    androidx.compose.foundation.layout.BoxWithConstraints {
                        val containerHeight = with(density) { maxHeight.roundToPx() }
                        TwoPanelBox(
                            modifier = Modifier.padding(
                                start = contentPadding.calculateStartPadding(layoutDirection),
                                end = contentPadding.calculateEndPadding(layoutDirection),
                            ),
                            startContent = {
                                Column(
                                    modifier = Modifier
                                        .verticalScroll(infoScrollState)
                                        .padding(bottom = contentPadding.calculateBottomPadding()),
                                ) {
                                    AnimeInfoBox(
                                        isTabletUi = true,
                                        appBarPadding = contentPadding.calculateTopPadding(),
                                        anime = state.anime,
                                        totalScore = state.totalScore,
                                        sourceName = remember { state.source.getNameForAnimeInfo() },
                                        isStubSource = remember { state.source is StubSource },
                                        onCoverClick = onCoverClicked,
                                        doSearch = onSearch,
                                        mergedSources = state.mergedSources,
                                        isRefreshing = state.isRefreshingData,
                                    )

                                    val isWatching = remember(state.episodes) {
                                        state.episodes.fastAny { it.episode.seen }
                                    }
                                    AnimeActionRow(
                                        favorite = state.anime.favorite,
                                        trackingCount = state.trackingCount,
                                        nextUpdate = nextUpdate,
                                        isUserIntervalMode = state.anime.fetchInterval < 0,
                                        fetchInterval = state.anime.fetchInterval,
                                        status = state.anime.status,
                                        onAddToLibraryClicked = onAddToLibraryClicked,
                                        onWebViewClicked = onWebViewClicked,
                                        onWebViewLongClicked = onWebViewLongClicked,
                                        onTrackingClicked = onTrackingClicked,
                                        onEditIntervalClicked = onEditIntervalClicked,
                                        onEditNotesClicked = onEditNotesClicked,
                                        onEditCategory = onEditCategoryClicked,
                                        onContinueWatching = { onContinueWatching(null) },
                                        isWatching = isWatching,
                                        mainTrackItem = remember(state.trackItems) { state.trackItems.firstOrNull() },
                                    )

                                    ExpandableAnimeDescription(
                                        defaultExpandState = autoExpandDescription,
                                        description = state.anime.description,
                                        note = state.anime.note,
                                        tagsProvider = { state.anime.genre },
                                        onTagSearch = onTagSearch,
                                        onCopyTagToClipboard = onCopyTagToClipboard,
                                    )

                                    eu.kanade.presentation.anime.components.PrequelSequelBox(
                                        anime = state.anime,
                                        relations = state.relations,
                                        onRelationClick = { onSearch(it, true) }
                                    )

                                    // Cast Row — placed below tags
                                    val castLarge = state.anime.cast
                                    if (!castLarge.isNullOrEmpty()) {
                                        eu.kanade.presentation.anime.components.CastRow(
                                            cast = castLarge,
                                            onClick = onCastClick,
                                        )
                                    }

                                    if (showSuggestions && !suggestionsInOverflow) {
                                        Surface(
                                            modifier = Modifier
                                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                                .fillMaxWidth(),
                                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                                            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                                            tonalElevation = 2.dp,
                                        ) {
                                            Column(modifier = Modifier.padding(vertical = 12.dp)) {
                                                DiscoveryHeader(
                                                    onClick = { navigator.push(eu.kanade.tachiyomi.ui.browse.source.browse.RelatedAnimeScreen(state.anime.id)) }
                                                )

                                                if (expandSuggestions) {
                                                        if (combinedItems.isEmpty() && state.isSuggestionsLoading) {
                                                            androidx.compose.foundation.lazy.LazyRow(
                                                                modifier = Modifier.heightIn(min = 180.dp),
                                                                contentPadding = PaddingValues(horizontal = 12.dp),
                                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                                userScrollEnabled = false,
                                                            ) {
                                                                items(5, key = { "skeleton-large-$it" }) {
                                                                    SkeletonAnimeCard()
                                                                }
                                                            }
                                                        } else if (combinedItems.isEmpty() && !state.isSuggestionsLoading) {
                                                            Text(
                                                                text = "No suggestions found for this entry",
                                                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp).heightIn(min = 40.dp),
                                                                style = MaterialTheme.typography.bodyMedium,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                                            )
                                                        } else {
                                                            androidx.compose.foundation.lazy.LazyRow(
                                                                modifier = Modifier.heightIn(min = 180.dp),
                                                                contentPadding = PaddingValues(horizontal = 12.dp),
                                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                            ) {
                                                                itemsIndexed(
                                                                    items = combinedItems,
                                                                    key = { _, anime: tachiyomi.domain.anime.model.Anime -> "suggestion-large-${anime.id}" },
                                                                ) { _, anime: tachiyomi.domain.anime.model.Anime ->
                                                                    SuggestionItem(
                                                                        anime = anime,
                                                                        onClick = { navigator.push(eu.kanade.tachiyomi.ui.anime.AnimeScreen(anime.id)) }
                                                                    )
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                }
                            },
                                        
                            endContent = {
                                VerticalFastScroller(
                                    listState = episodeListState,
                                    topContentPadding = contentPadding.calculateTopPadding(),
                                    bottomContentPadding = contentPadding.calculateBottomPadding(),
                                    endContentPadding = contentPadding.calculateEndPadding(layoutDirection),
                                ) {
                                    LazyColumn(
                                        modifier = Modifier.fillMaxHeight(),
                                        state = episodeListState,
                                        contentPadding = PaddingValues(
                                            top = contentPadding.calculateTopPadding(),
                                            end = contentPadding.calculateEndPadding(layoutDirection),
                                            bottom = contentPadding.calculateBottomPadding(),
                                        ),
                                    ) {
                                        if (state.anime.seasonGroupingMode == LibraryPreferences.SeasonGrouping.Tabs && state.availableSeasons.size > 1) {
                                            item(key = "season-selector-large", contentType = "season-selector") {
                                                SeasonSelector(
                                                    seasons = state.availableSeasons,
                                                    selectedSeason = state.selectedSeason,
                                                    onSeasonSelected = onSeasonSelected,
                                                )
                                            }
                                        }
                                        
                                        if (state.anime.fetchType == FetchType.Seasons) {
                                            item(key = "season-header-large", contentType = AnimeScreenItem.EPISODE_HEADER) {
                                                EpisodeHeader(
                                                    enabled = !isAnySelected,
                                                    episodeCount = state.processedSeasonItems.size,
                                                    missingEpisodeCount = 0,
                                                    onClick = onFilterButtonClicked,
                                                    fetchType = FetchType.Seasons,
                                                )
                                            }
                                            val columns = if (state.anime.seasonDisplayGridMode == SeasonDisplayMode.List) {
                                                1
                                            } else {
                                                state.anime.seasonDisplayGridSize.takeIf { it > 0 } ?: 5
                                            }
                                            val seasons = state.processedSeasonItems
                                            if (columns == 1) {
                                                items(
                                                    items = seasons,
                                                    key = { item -> item.seasonAnime.anime.id },
                                                ) { item ->
                                                    AnimeSeasonListItem(
                                                        anime = state.anime,
                                                        item = item,
                                                        containerHeight = containerHeight,
                                                        onSeasonClicked = onSeasonClicked,
                                                        onClickContinueWatching = {
                                                            onContinueWatching(item.seasonAnime)
                                                        },
                                                        listItemModifier = Modifier,
                                                    )
                                                }
                                            } else {
                                                val rows = seasons.chunked(columns)
                                                rows.forEachIndexed { index, row ->
                                                    item(key = "season-row-large-$index") {
                                                        Row(
                                                            modifier = Modifier
                                                                .padding(horizontal = 8.dp)
                                                                .fillMaxWidth(),
                                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                        ) {
                                                            row.forEach { item ->
                                                                Box(modifier = Modifier.weight(1f)) {
                                                                    AnimeSeasonListItem(
                                                                        anime = state.anime,
                                                                        item = item,
                                                                        containerHeight = containerHeight,
                                                                        onSeasonClicked = onSeasonClicked,
                                                                        onClickContinueWatching = {
                                                                            onContinueWatching(item.seasonAnime)
                                                                        },
                                                                        listItemModifier = Modifier,
                                                                    )
                                                                }
                                                            }
                                                            repeat(columns - row.size) {
                                                                Spacer(modifier = Modifier.weight(1f))
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        } else {
                                            item(key = "episode-header-large", contentType = AnimeScreenItem.EPISODE_HEADER) {
                                                EpisodeHeader(
                                                    enabled = !isAnySelected,
                                                    episodeCount = if (state.anime.seasonGroupingMode == LibraryPreferences.SeasonGrouping.Tabs) currentSeasonCount else episodes.size,
                                                    missingEpisodeCount = state.missingEpisodeCount,
                                                    onClick = onFilterButtonClicked,
                                                )
                                            }
                                            if (state.airingTime > 0L) {
                                                item(key = "airing-time-large", contentType = AnimeScreenItem.AIRING_TIME) {
                                                    var timer by remember(state.airingTime) { mutableLongStateOf(state.airingTime) }
                                                    LaunchedEffect(key1 = timer) {
                                                        if (timer > 0L) {
                                                            delay(1000L)
                                                            timer -= 1000L
                                                        }
                                                    }
                                                    if (timer > 0L && showNextEpisodeAirTime && state.anime.status.toInt() != SAnime.COMPLETED) {
                                                        NextEpisodeAiringListItem(
                                                            title = stringResource(
                                                                MR.strings.display_mode_episode,
                                                                formatEpisodeNumber(state.airingEpisodeNumber),
                                                            ),
                                                            date = formatTime(timer, useDayFormat = true),
                                                        )
                                                    }
                                                }
                                            }
                                            sharedEpisodeItems(
                                                anime = state.anime,
                                                source = state.source,
                                                showFileSize = showFileSize,
                                                showEpisodeSummary = state.showEpisodeSummary,
                                                showEpisodeThumbnail = state.showEpisodeThumbnail,
                                                episodes = listItem,
                                                fillerEpisodes = state.fillerEpisodes,
                                                isAnyEpisodeSelected = episodes.fastAny { it.selected },
                                                episodeSwipeStartAction = episodeSwipeStartAction,
                                                episodeSwipeEndAction = episodeSwipeEndAction,
                                                onEpisodeClicked = onEpisodeClicked,
                                                onDownloadEpisode = onDownloadEpisode,
                                                onEpisodeSelected = onEpisodeSelected,
                                                onEpisodeSwipe = onEpisodeSwipe,
                                            )
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeItemWrapper(
    item: EpisodeList,
    anime: Anime,
    source: Source,
    showFileSize: Boolean,
    showEpisodeSummary: Boolean,
    showEpisodeThumbnail: Boolean,
    fillerEpisodes: Set<Float>,
    isAnyEpisodeSelected: Boolean,
    episodeSwipeStartAction: LibraryPreferences.EpisodeSwipeAction,
    episodeSwipeEndAction: LibraryPreferences.EpisodeSwipeAction,
    onEpisodeClicked: (Episode, Boolean) -> Unit,
    onDownloadEpisode: ((List<EpisodeList.Item>, EpisodeDownloadAction) -> Unit)?,
    onEpisodeSelected: (EpisodeList.Item, Boolean, Boolean, Boolean) -> Unit,
    onEpisodeSwipe: (EpisodeList.Item, LibraryPreferences.EpisodeSwipeAction) -> Unit,
) {
    when (item) {
        is EpisodeList.Season -> {
            ListGroupHeader(
                text = item.name,
                modifier = Modifier.fillMaxWidth()
            )
        }
        is EpisodeList.MissingCount -> {
            MissingEpisodeCountListItem(count = item.count)
        }
        is EpisodeList.Item -> {
            var fileSizeAsync: Long? by remember { mutableStateOf(item.fileSize) }
            val isEpisodeDownloaded = item.downloadState == Download.State.DOWNLOADED
            if (isEpisodeDownloaded && showFileSize && fileSizeAsync == null) {
                LaunchedEffect(item, Unit) {
                    fileSizeAsync = withIOContext {
                        downloadProvider.getEpisodeFileSize(
                            item.episode.name,
                            item.episode.url,
                            item.episode.scanlator,
                            anime.ogTitle,
                            source,
                        )
                    }
                    item.fileSize = fileSizeAsync
                }
            }
            AnimeEpisodeListItem(
                title = if (anime.displayMode == Anime.EPISODE_DISPLAY_NUMBER) {
                    stringResource(
                        MR.strings.display_mode_episode,
                        formatEpisodeNumber(item.episode.episodeNumber),
                    )
                } else {
                    item.episode.name
                },
                date = relativeDateTimeText(item.episode.dateUpload),
                watchProgress = item.episode.lastSecondSeen
                    .takeIf { !item.episode.seen && it > 0L }
                    ?.let {
                        "${formatTime(it)} / ${formatTime(item.episode.totalSeconds)}"
                    },
                scanlator = item.episode.scanlator.takeIf { !it.isNullOrBlank() },
                seen = item.episode.seen,
                bookmark = item.episode.bookmark,
                fillermark = item.episode.fillermark,
                isAutoFiller = fillerEpisodes.contains(item.episode.episodeNumber.toFloat()),
                summary = item.episode.summary.takeIf { showEpisodeSummary },
                previewUrl = item.episode.previewUrl.takeIf { showEpisodeThumbnail },
                selected = item.selected,
                isAnyEpisodeSelected = isAnyEpisodeSelected,
                downloadIndicatorEnabled = !isAnyEpisodeSelected && !anime.isLocal(),
                downloadStateProvider = { item.downloadState },
                downloadProgressProvider = { item.downloadProgress },
                episodeSwipeStartAction = episodeSwipeStartAction,
                episodeSwipeEndAction = episodeSwipeEndAction,
                onLongClick = {
                    onEpisodeSelected(item, !item.selected, true, true)
                },
                onClick = {
                    onEpisodeItemClick(
                        episodeItem = item,
                        isAnyEpisodeSelected = isAnyEpisodeSelected,
                        onToggleSelection = { onEpisodeSelected(item, !item.selected, true, false) },
                        onEpisodeClicked = onEpisodeClicked,
                    )
                },
                onDownloadClick = if (onDownloadEpisode != null) {
                    { onDownloadEpisode(listOf(item), it) }
                } else {
                    null
                },
                onEpisodeSwipe = {
                    onEpisodeSwipe(item, it)
                },
                fileSize = fileSizeAsync,
            )
        }
    }
}

@Composable
private fun SharedAnimeBottomActionMenu(
    selected: List<EpisodeList.Item>,
    anime: Anime,
    onEpisodeClicked: (Episode, Boolean) -> Unit,
    onMultiBookmarkClicked: (List<Episode>, bookmarked: Boolean) -> Unit,
    onMultiFillermarkClicked: (List<Episode>, fillermarked: Boolean) -> Unit,
    onMultiMarkAsSeenClicked: (List<Episode>, markAsSeen: Boolean) -> Unit,
    onMarkPreviousAsSeenClicked: (Episode) -> Unit,
    onDownloadEpisode: ((List<EpisodeList.Item>, EpisodeDownloadAction) -> Unit)?,
    onMultiDeleteClicked: (List<Episode>) -> Unit,
    onContinueWatching: () -> Unit,
    fillFraction: Float,
    alwaysUseExternalPlayer: Boolean,
    modifier: Modifier = Modifier,
) {
    AnimeBottomActionMenu(
        visible = selected.isNotEmpty(),
        modifier = modifier.fillMaxWidth(fillFraction),
        onBookmarkClicked = {
            onMultiBookmarkClicked.invoke(selected.fastMap { it.episode }, true)
        }.takeIf { selected.fastAny { !it.episode.bookmark } },
        onRemoveBookmarkClicked = {
            onMultiBookmarkClicked.invoke(selected.fastMap { it.episode }, false)
        }.takeIf { selected.fastAll { it.episode.bookmark } },
        onFillermarkClicked = {
            onMultiFillermarkClicked.invoke(selected.fastMap { it.episode }, true)
        }.takeIf { selected.fastAny { !it.episode.fillermark } },
        onRemoveFillermarkClicked = {
            onMultiFillermarkClicked.invoke(selected.fastMap { it.episode }, false)
        }.takeIf { selected.fastAll { it.episode.fillermark } },
        onMarkAsSeenClicked = {
            onMultiMarkAsSeenClicked(selected.fastMap { it.episode }, true)
        }.takeIf { selected.fastAny { !it.episode.seen } },
        onMarkAsUnseenClicked = {
            onMultiMarkAsSeenClicked(selected.fastMap { it.episode }, false)
        }.takeIf { selected.fastAny { it.episode.seen || it.episode.lastSecondSeen > 0L } },
        onMarkPreviousAsSeenClicked = {
            onMarkPreviousAsSeenClicked(selected[0].episode)
        }.takeIf { selected.size == 1 },
        onDownloadClicked = {
            onDownloadEpisode!!(selected.toList(), EpisodeDownloadAction.START)
        }.takeIf {
            onDownloadEpisode != null && selected.fastAny { it.downloadState != Download.State.DOWNLOADED }
        },
        onDeleteClicked = {
            onMultiDeleteClicked(selected.fastMap { it.episode })
        }.takeIf {
            (onDownloadEpisode != null || anime.isLocal()) && selected.fastAny { it.downloadState == Download.State.DOWNLOADED }
        },
        onExternalClicked = {
            onEpisodeClicked(selected.fastMap { it.episode }.first(), true)
        }.takeIf { !alwaysUseExternalPlayer && selected.size == 1 },
        onInternalClicked = {
            onEpisodeClicked(selected.fastMap { it.episode }.first(), true)
        }.takeIf { alwaysUseExternalPlayer && selected.size == 1 }
    )
}

private fun onEpisodeItemClick(
    episodeItem: EpisodeList.Item,
    isAnyEpisodeSelected: Boolean,
    onToggleSelection: (Boolean) -> Unit,
    onEpisodeClicked: (Episode, Boolean) -> Unit,
) {
    when {
        episodeItem.selected -> onToggleSelection(false)
        isAnyEpisodeSelected -> onToggleSelection(true)
        else -> onEpisodeClicked(episodeItem.episode, false)
    }
}

private val downloadProvider: DownloadProvider by injectLazy()

@Composable
private fun SuggestionItem(
    anime: Anime,
    onClick: () -> Unit,
) {
    val uiPreferences = remember { Injekt.get<UiPreferences>() }
    val globalPanorama by uiPreferences.panoramaCover().collectAsStatePref() as androidx.compose.runtime.State<Boolean>
    val (entry, ratio) = eu.kanade.presentation.anime.components.AnimeCover.getEntry(anime.id, usePanoramaOverride = globalPanorama)
    val width = remember(entry) { if (entry == eu.kanade.presentation.anime.components.AnimeCover.Panorama) 200.dp else 104.dp }

    Column(
        modifier = Modifier.width(width),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box {
            entry(
                data = anime,
                onClick = onClick,
                modifier = Modifier.fillMaxWidth(),
                ratio = ratio,
            )
            
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp),
            ) {
                eu.kanade.presentation.browse.components.InLibraryBadge(enabled = anime.favorite)
            }

            if (anime.score != null && anime.score!! > 0) {
                val scoreText = remember(anime.score) { String.format("%.1f", anime.score) }
                tachiyomi.presentation.core.components.BadgeGroup(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp),
                ) {
                    tachiyomi.presentation.core.components.Badge(
                        text = scoreText,
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        textColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
        }
        Text(
            text = anime.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun DiscoveryHeader(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = stringResource(tachiyomi.i18n.sy.SYMR.strings.az_recommends),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = stringResource(MR.strings.label_more),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

private fun LazyListScope.sharedEpisodeItems(
    anime: Anime,
    source: Source,
    showFileSize: Boolean,
    showEpisodeSummary: Boolean,
    showEpisodeThumbnail: Boolean,
    episodes: List<EpisodeList>,
    fillerEpisodes: Set<Float>,
    isAnyEpisodeSelected: Boolean,
    episodeSwipeStartAction: LibraryPreferences.EpisodeSwipeAction,
    episodeSwipeEndAction: LibraryPreferences.EpisodeSwipeAction,
    onEpisodeClicked: (Episode, Boolean) -> Unit,
    onDownloadEpisode: ((List<EpisodeList.Item>, EpisodeDownloadAction) -> Unit)?,
    onEpisodeSelected: (EpisodeList.Item, Boolean, Boolean, Boolean) -> Unit,
    onEpisodeSwipe: (EpisodeList.Item, LibraryPreferences.EpisodeSwipeAction) -> Unit,
) {
    itemsIndexed(
        items = episodes,
        key = { index, item ->
            when (item) {
                is EpisodeList.Item -> "anime-ep-${item.episode.id}"
                is EpisodeList.MissingCount -> "anime-ms-${item.id}"
                is EpisodeList.Season -> "anime-sn-${item.name}-$index"
            }
        },
        contentType = { _, item ->
            when (item) {
                is EpisodeList.Item -> "episode"
                is EpisodeList.MissingCount -> "missing-count"
                is EpisodeList.Season -> "season"
            }
        },
    ) { _, item ->
        EpisodeItemWrapper(
            item = item,
            anime = anime,
            source = source,
            showFileSize = showFileSize,
            showEpisodeSummary = showEpisodeSummary,
            showEpisodeThumbnail = showEpisodeThumbnail,
            fillerEpisodes = fillerEpisodes,
            isAnyEpisodeSelected = isAnyEpisodeSelected,
            episodeSwipeStartAction = episodeSwipeStartAction,
            episodeSwipeEndAction = episodeSwipeEndAction,
            onEpisodeClicked = onEpisodeClicked,
            onDownloadEpisode = onDownloadEpisode,
            onEpisodeSelected = onEpisodeSelected,
            onEpisodeSwipe = onEpisodeSwipe,
        )
    }
}

@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
private fun SeasonSelector(
    seasons: List<String>,
    selectedSeason: String?,
    onSeasonSelected: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.lazy.LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(seasons, key = { _, it -> "season-$it" }) { _, season ->
            androidx.compose.material3.FilterChip(
                selected = season == selectedSeason,
                onClick = { onSeasonSelected(season) },
                label = {
                    Text(
                        text = season,
                        style = MaterialTheme.typography.labelLarge,
                    )
                },
                shape = androidx.compose.foundation.shape.CircleShape,
                colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
                border = null,
            )
        }
    }
}

@Composable
private fun DraggableAnimeFAB(
    isWatching: Boolean,
    onContinueWatching: () -> Unit,
    shouldExpand: Boolean,
) {
    val uiPreferences = remember { Injekt.get<UiPreferences>() }
    val fabOnLeftPref = remember { uiPreferences.animeDetailsFabOnLeft() }
    val isFabOnLeft by fabOnLeftPref.collectAsStatePref()

    var containerWidth by remember { mutableStateOf(0f) }
    var fabWidth by remember { mutableStateOf(0f) }
    var dragOffset by remember { mutableStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    var isInitialized by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val paddingPx = remember { with(density) { 16.dp.toPx() } }

    val targetOffset = if (isFabOnLeft) {
        0f
    } else {
        (containerWidth - 2 * paddingPx - fabWidth).coerceAtLeast(0f)
    }

    if (containerWidth > 0f && fabWidth > 0f) {
        LaunchedEffect(Unit) {
            delay(50)
            isInitialized = true
        }
    }

    val animatedOffset by animateFloatAsState(
        targetValue = targetOffset + dragOffset,
        animationSpec = if (!isInitialized || isDragging) snap() else spring(stiffness = Spring.StiffnessMediumLow),
        label = "FAB Position"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { containerWidth = it.width.toFloat() }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.BottomStart
    ) {
        ExtendedFloatingActionButton(
            modifier = Modifier
                .onSizeChanged { fabWidth = it.width.toFloat() }
                .offset { IntOffset(x = animatedOffset.roundToInt(), y = 0) }
                .tvFocusHighlight(
                    shape = RoundedCornerShape(16.dp),
                    focusedScale = 1.08f,
                    borderWidth = 3.dp,
                    focusedBackgroundAlpha = 0.2f,
                )
                .pointerInput(isFabOnLeft, containerWidth, fabWidth) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            isDragging = true
                        },
                        onDragEnd = {
                            isDragging = false
                            val threshold = (containerWidth - 2 * paddingPx - fabWidth) / 3f
                            val toggled = if (isFabOnLeft) {
                                dragOffset > threshold
                            } else {
                                dragOffset < -threshold
                            }
                            if (toggled) {
                                scope.launch {
                                    fabOnLeftPref.set(!isFabOnLeft)
                                }
                            }
                            dragOffset = 0f
                        },
                        onDragCancel = {
                            isDragging = false
                            dragOffset = 0f
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            dragOffset += dragAmount
                        }
                    )
                },
            text = {
                Text(text = stringResource(if (isWatching) MR.strings.action_resume else MR.strings.action_start))
            },
            icon = {
                Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = null)
            },
            onClick = { onContinueWatching() },
            expanded = shouldExpand,
        )
    }
}

