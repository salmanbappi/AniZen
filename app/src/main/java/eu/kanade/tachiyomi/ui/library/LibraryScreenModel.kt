package eu.kanade.tachiyomi.ui.library

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastDistinctBy
import androidx.compose.ui.util.fastFilter
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastMap
import androidx.compose.ui.util.fastMapNotNull
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.core.preference.PreferenceMutableState
import eu.kanade.core.preference.asState
import eu.kanade.core.util.fastFilterNot
import eu.kanade.core.util.fastPartition
import eu.kanade.domain.anime.interactor.UpdateAnime
import eu.kanade.domain.source.interactor.GetSourcesWithFavoriteCount
import eu.kanade.domain.track.model.toDomainTrack
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.episode.interactor.SetSeenStatus
import eu.kanade.presentation.anime.DownloadAction
import eu.kanade.presentation.components.SEARCH_DEBOUNCE_MILLIS
import eu.kanade.presentation.library.components.LibraryToolbarTitle
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.ui.player.loader.EpisodeLoader
import eu.kanade.tachiyomi.ui.player.loader.HosterLoader
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.track.TrackStatus
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.source.model.SAnime
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.episode.getNextUnseen
import eu.kanade.tachiyomi.util.removeCovers
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.mutate
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.util.lang.compareToWithCollator
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.anime.interactor.GetLibraryAnime
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.model.AnimeUpdate
import tachiyomi.domain.anime.model.applyFilter
import tachiyomi.domain.anime.model.toAnimeUpdate
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetAnimeCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.episode.interactor.GetEpisodesByAnimeId
import tachiyomi.domain.episode.model.Episode
import tachiyomi.domain.history.interactor.GetNextEpisodes
import tachiyomi.domain.library.model.LibraryAnime
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibraryFolder
import tachiyomi.domain.library.model.LibraryGroup
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.model.sort
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.DeleteTrack
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.GetTracksPerAnime
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.domain.track.model.Track
import tachiyomi.i18n.sy.SYMR
import tachiyomi.source.localanime.LocalAnimeSource
import tachiyomi.source.localanime.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.random.Random

/**
 * Typealias for the library anime, using the category as keys, and list of anime as values.
 */
typealias AnimeLibraryMap = Map<Category, List<LibraryItem>>

@Suppress("LargeClass")
class LibraryScreenModel(
    private val context: android.content.Context = Injekt.get(),
    private val getLibraryAnime: GetLibraryAnime = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val getAnime: tachiyomi.domain.anime.interactor.GetAnime = Injekt.get(),
    private val getTracksPerAnime: GetTracksPerAnime = Injekt.get(),
    private val getNextEpisodes: GetNextEpisodes = Injekt.get(),
    private val getEpisodesByAnimeId: GetEpisodesByAnimeId = Injekt.get(),
    private val setSeenStatus: SetSeenStatus = Injekt.get(),
    private val updateAnime: UpdateAnime = Injekt.get(),
    private val setAnimeCategories: SetAnimeCategories = Injekt.get(),
    private val getSourcesWithFavoriteCount: GetSourcesWithFavoriteCount = Injekt.get(),
    private val preferences: BasePreferences = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val trackPreferences: TrackPreferences = Injekt.get(),
    private val downloadPreferences: DownloadPreferences = Injekt.get(),
    private val coverCache: CoverCache = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val downloadManager: DownloadManager = Injekt.get(),
    private val downloadCache: DownloadCache = Injekt.get(),
    private val trackerManager: TrackerManager = Injekt.get(),
    // SY -->
    private val getTracks: GetTracks = Injekt.get(),
    private val insertTrack: InsertTrack = Injekt.get(),
    private val deleteTrack: DeleteTrack = Injekt.get(),
    // SY <--
) : StateScreenModel<LibraryScreenModel.State>(State()) {

    val useExternalDownloader = downloadPreferences.useExternalDownloader().get()

    var activeCategoryIndex: Int by libraryPreferences.lastUsedCategory().asState(
        screenModelScope,
    )

    /**
     * Memoized [LibraryDisplayItem] lists, keyed by page and by the settings that change the
     * shape of the list.
     *
     * The grid list used to be built inline in the library composable, so *every*
     * recomposition of the page re-ran the folder grouping and handed `LazyVerticalGrid` a
     * brand new list instance — which makes it rebuild its item provider and re-diff every
     * key, on the frame it is trying to scroll. Caching here ties the work to the data: one
     * build per data change per page, and a cheap map hit for everything else.
     *
     * Only the three [State] fields the output actually depends on take part in
     * invalidation, and the two collections are compared by *identity*: they are rebuilt
     * wholesale whenever the library reloads, and comparing them structurally would itself
     * be an O(library) walk on the caller's thread. Unrelated state updates (selection,
     * dialogs, download progress) therefore keep the cached lists, so the grid is not
     * re-provided while the user is selecting items.
     */
    private val displayItemsCache = HashMap<DisplayItemsKey, ImmutableList<LibraryDisplayItem>>()
    private var cachedLibrary: AnimeLibraryMap? = null
    private var cachedFolders: List<LibraryFolder>? = null
    private var cachedSearchQuery: String? = null

    private data class DisplayItemsKey(
        val page: Int,
        val collapseFolders: Boolean,
        val defaultCategoryTitle: String,
    )

    /**
     * The rows to render for [page] of [libraryState].
     *
     * [libraryState] is passed in rather than read from [state] so the result always matches
     * the state the caller is currently composing with.
     *
     * @param defaultCategoryTitle localized name of the system category; resolved by the
     *   caller because it needs a Context, and part of the key so a locale change cannot
     *   serve a stale header.
     */
    fun getDisplayItemsForPage(
        libraryState: State,
        page: Int,
        collapseFolders: Boolean,
        defaultCategoryTitle: String,
    ): ImmutableList<LibraryDisplayItem> {
        val searchQuery = libraryState.searchQuery
        val key = DisplayItemsKey(
            // Search flattens every category into a single page, so the page index is not
            // part of the identity of the result.
            page = if (searchQuery.isNullOrEmpty()) page else 0,
            collapseFolders = collapseFolders,
            defaultCategoryTitle = defaultCategoryTitle,
        )
        return synchronized(displayItemsCache) {
            if (cachedLibrary !== libraryState.library ||
                cachedFolders !== libraryState.folders ||
                cachedSearchQuery != searchQuery
            ) {
                cachedLibrary = libraryState.library
                cachedFolders = libraryState.folders
                cachedSearchQuery = searchQuery
                displayItemsCache.clear()
            }
            displayItemsCache.getOrPut(key) {
                libraryState.buildDisplayItemsForPage(
                    page = page,
                    collapseFolders = collapseFolders,
                    defaultCategoryTitle = defaultCategoryTitle,
                )
            }
        }
    }

    init {
        screenModelScope.launchIO {
            combine(
                state.map { it.searchQuery }.debounce(SEARCH_DEBOUNCE_MILLIS),
                combine(getLibraryFlow(), getTracksPerAnime.subscribe(), ::Pair),
                combine(
                    getTrackingFilterFlow(),
                    state.map { it.groupType }.distinctUntilChanged(),
                    ::Pair,
                ),
                combine(
                    libraryPreferences.sortingMode().changes(),
                    libraryPreferences.showHiddenCategories().changes(),
                    ::Pair,
                ),
            ) { searchQuery, (library, tracks), (trackingFilter, groupType), (sort, showHidden) ->
                library
                    .applyGrouping(groupType, tracks)
                    .applyFilters(tracks, trackingFilter, showHidden)
                    .applySort(tracks, sort.takeIf { groupType != LibraryGroup.BY_DEFAULT }, trackingFilter.keys)
                    .mapValues { (_, value) ->
                        if (searchQuery != null) {
                            value.filter { it.matches(searchQuery) }
                        } else {
                            value
                        }
                    }
            }
                .collectLatest { library ->
                    val categoriesCount = library.size
                    if (activeCategoryIndex >= categoriesCount && categoriesCount > 0) {
                        activeCategoryIndex = categoriesCount - 1
                    }
                    mutableState.update { state ->
                        state.copy(
                            isLoading = false,
                            library = library,
                        )
                    }
                }
        }

        combine(
            libraryPreferences.libraryFolders().changes(),
            getCategories.subscribe(),
        ) { folderSet, categories ->
            val folders = folderSet.mapNotNull { 
                val parts = it.split("|")
                if (parts.size >= 3) {
                    val id = parts[0].toLongOrNull() ?: return@mapNotNull null
                    val categoryName = parts.subList(1, parts.size - 1).joinToString("|")
                    val name = parts.last()
                    
                    val category = categories.find { c -> c.name == categoryName }
                    if (category != null) {
                        tachiyomi.domain.library.model.LibraryFolder(
                            id = id,
                            categoryId = category.id,
                            name = name
                        )
                    } else {
                        // fallback for old format: id|categoryId|name
                        val categoryId = parts[1].toLongOrNull()
                        if (categoryId != null) {
                            tachiyomi.domain.library.model.LibraryFolder(
                                id = id,
                                categoryId = categoryId,
                                name = parts[2]
                            )
                        } else null
                    }
                } else null
            }
            folders
        }
            .onEach { folders ->
                mutableState.update { state -> state.copy(folders = folders) }
            }
            .launchIn(screenModelScope)

        combine(
            libraryPreferences.categoryTabs().changes(),
            libraryPreferences.categoryNumberOfItems().changes(),
            libraryPreferences.showContinueWatchingButton().changes(),
            libraryPreferences.showEmptyCategoriesSearch().changes(),
        ) { a, b, c, d -> arrayOf(a, b, c, d) }
            .onEach { (showCategoryTabs, showAnimeCount, showAnimeContinueButton, showEmptyCategoriesSearch) ->
                mutableState.update { state ->
                    state.copy(
                        showCategoryTabs = showCategoryTabs,
                        showAnimeCount = showAnimeCount,
                        showAnimeContinueButton = showAnimeContinueButton,
                        showEmptyCategoriesSearch = showEmptyCategoriesSearch,
                    )
                }
            }
            .launchIn(screenModelScope)

        combine(
            getAnimelibItemPreferencesFlow(),
            getTrackingFilterFlow(),
        ) { prefs, trackFilter ->
            (
                listOf(
                    prefs.filterDownloaded,
                    prefs.filterUnseen,
                    prefs.filterStarted,
                    prefs.filterBookmarked,
                    // AM (FILLERMARK) -->
                    prefs.filterFillermarked,
                    // <-- AM (FILLERMARK)
                    prefs.filterCompleted,
                    prefs.filterIntervalCustom,
                ) + trackFilter.values
                ).any { it != TriState.DISABLED }
        }
            .distinctUntilChanged()
            .onEach {
                mutableState.update { state ->
                    state.copy(hasActiveFilters = it)
                }
            }
            .launchIn(screenModelScope)

        // SY -->
        libraryPreferences.groupLibraryBy().changes()
            .onEach {
                mutableState.update { state ->
                    state.copy(groupType = it)
                }
            }
            .launchIn(screenModelScope)
        // SY <--
    }

    private suspend fun AnimeLibraryMap.applyFilters(
        trackMap: Map<Long, List<Track>>,
        trackingFilter: Map<Long, TriState>,
        hidden: Boolean,
    ): AnimeLibraryMap {
        val prefs = getAnimelibItemPreferencesFlow().first()
        val downloadedOnly = prefs.globalFilterDownloaded
        val skipOutsideReleasePeriod = prefs.skipOutsideReleasePeriod
        val filterDownloaded = if (downloadedOnly) TriState.ENABLED_IS else prefs.filterDownloaded
        val filterUnseen = prefs.filterUnseen
        val filterStarted = prefs.filterStarted
        val filterBookmarked = prefs.filterBookmarked
        // AM (FILLERMARK) -->
        val filterFillermarked = prefs.filterFillermarked
        // <-- AM (FILLERMARK)
        val filterCompleted = prefs.filterCompleted
        val filterIntervalCustom = prefs.filterIntervalCustom

        val isNotLoggedInAnyTrack = trackingFilter.isEmpty()

        val excludedTracks = trackingFilter.mapNotNull { if (it.value == TriState.ENABLED_NOT) it.key else null }
        val includedTracks = trackingFilter.mapNotNull { if (it.value == TriState.ENABLED_IS) it.key else null }
        val trackFiltersIsIgnored = includedTracks.isEmpty() && excludedTracks.isEmpty()

        val filterFnDownloaded: (LibraryItem) -> Boolean = {
            applyFilter(filterDownloaded) {
                it.libraryAnime.anime.isLocal() ||
                    it.downloadCount > 0 ||
                    downloadManager.getDownloadCount(it.libraryAnime.anime) > 0
            }
        }

        val filterFnUnseen: (LibraryItem) -> Boolean = {
            applyFilter(filterUnseen) { it.libraryAnime.unseenCount > 0 }
        }

        val filterFnStarted: (LibraryItem) -> Boolean = {
            applyFilter(filterStarted) { it.libraryAnime.hasStarted }
        }

        val filterFnBookmarked: (LibraryItem) -> Boolean = {
            applyFilter(filterBookmarked) { it.libraryAnime.hasBookmarks }
        }

        // AM (FILLERMARK) -->
        val filterFnFillermarked: (LibraryItem) -> Boolean = {
            applyFilter(filterFillermarked) { it.libraryAnime.hasFillermarks }
        }
        // <-- AM (FILLERMARK)

        val filterFnCompleted: (LibraryItem) -> Boolean = {
            applyFilter(filterCompleted) { it.libraryAnime.anime.status.toInt() == SAnime.COMPLETED }
        }

        val filterFnIntervalCustom: (LibraryItem) -> Boolean = {
            if (skipOutsideReleasePeriod) {
                applyFilter(filterIntervalCustom) { it.libraryAnime.anime.fetchInterval < 0 }
            } else {
                true
            }
        }

        val filterFnTracking: (LibraryItem) -> Boolean = tracking@{ item ->
            if (isNotLoggedInAnyTrack || trackFiltersIsIgnored) return@tracking true

            val animeTracks = trackMap
                .mapValues { entry -> entry.value.map { it.trackerId } }[item.libraryAnime.id]
                .orEmpty()

            val isExcluded = excludedTracks.isNotEmpty() && animeTracks.fastAny { it in excludedTracks }
            val isIncluded = includedTracks.isEmpty() || animeTracks.fastAny { it in includedTracks }

            !isExcluded && isIncluded
        }

        val filterFn: (LibraryItem) -> Boolean = {
            filterFnDownloaded(it) &&
                filterFnUnseen(it) &&
                filterFnStarted(it) &&
                filterFnBookmarked(it) &&
                // AM (FILLERMARK) -->
                filterFnFillermarked(it) &&
                // <-- AM (FILLERMARK)
                filterFnCompleted(it) &&
                filterFnIntervalCustom(it) &&
                filterFnTracking(it)
        }

        return this
            .filter { (category, _) -> hidden || !category.hidden || category.id == 0L }
            .mapValues { (_, value) -> value.fastFilter(filterFn) }
    }

    private fun AnimeLibraryMap.applySort(
        trackMap: Map<Long, List<Track>>,
        groupSort: LibrarySort? = null,
        loggedInTrackerIds: Set<Long>,
    ): AnimeLibraryMap {
        val sortAlphabetically: (LibraryItem, LibraryItem) -> Int = { i1, i2 ->
            i1.libraryAnime.anime.title.lowercase().compareToWithCollator(i2.libraryAnime.anime.title.lowercase())
        }

        val defaultTrackerScoreSortValue = -1.0
        val trackerScores by lazy {
            val trackerMap = trackerManager.getAll(loggedInTrackerIds).associateBy { e -> e.id }
            trackMap.mapValues { entry ->
                when {
                    entry.value.isEmpty() -> null
                    else ->
                        entry.value
                            .mapNotNull { trackerMap[it.trackerId]?.animeService?.get10PointScore(it) }
                            .average()
                }
            }
        }

        val keys = this.keys
        fun LibrarySort.comparator(): Comparator<LibraryItem> = Comparator { i1, i2 ->
            // SY -->
            // Use groupSort when provided, otherwise use the sort from the category
            val sort = groupSort ?: keys.find { it.id == i1.libraryAnime.category }?.sort ?: LibrarySort.default
            // SY <--
            when (sort.type) {
                LibrarySort.Type.Alphabetical -> {
                    sortAlphabetically(i1, i2)
                }
                LibrarySort.Type.LastSeen -> {
                    i1.libraryAnime.lastSeen.compareTo(i2.libraryAnime.lastSeen)
                }
                LibrarySort.Type.LastUpdate -> {
                    i1.libraryAnime.anime.lastUpdate.compareTo(i2.libraryAnime.anime.lastUpdate)
                }
                LibrarySort.Type.UnseenCount -> when {
                    // Ensure unseen content comes first
                    i1.libraryAnime.unseenCount == i2.libraryAnime.unseenCount -> 0
                    i1.libraryAnime.unseenCount == 0L -> if (sort.isAscending) 1 else -1
                    i2.libraryAnime.unseenCount == 0L -> if (sort.isAscending) -1 else 1
                    else -> i1.libraryAnime.unseenCount.compareTo(i2.libraryAnime.unseenCount)
                }
                LibrarySort.Type.TotalEpisodes -> {
                    i1.libraryAnime.totalEpisodes.compareTo(i2.libraryAnime.totalEpisodes)
                }
                LibrarySort.Type.LatestEpisode -> {
                    i1.libraryAnime.latestUpload.compareTo(i2.libraryAnime.latestUpload)
                }
                LibrarySort.Type.EpisodeFetchDate -> {
                    i1.libraryAnime.episodeFetchedAt.compareTo(i2.libraryAnime.episodeFetchedAt)
                }
                LibrarySort.Type.DateAdded -> {
                    i1.libraryAnime.anime.dateAdded.compareTo(i2.libraryAnime.anime.dateAdded)
                }
                LibrarySort.Type.TrackerMean -> {
                    val item1Score = trackerScores[i1.libraryAnime.id] ?: defaultTrackerScoreSortValue
                    val item2Score = trackerScores[i2.libraryAnime.id] ?: defaultTrackerScoreSortValue
                    item1Score.compareTo(item2Score)
                }
                LibrarySort.Type.AiringTime -> when {
                    i1.libraryAnime.anime.nextEpisodeAiringAt == 0L && i2.libraryAnime.anime.nextEpisodeAiringAt == 0L ->
                        i1.libraryAnime.unseenCount.compareTo(i2.libraryAnime.unseenCount)
                    i1.libraryAnime.anime.nextEpisodeAiringAt == 0L -> if (sort.isAscending) 1 else -1
                    i2.libraryAnime.anime.nextEpisodeAiringAt == 0L -> if (sort.isAscending) -1 else 1
                    i1.libraryAnime.unseenCount == i2.libraryAnime.unseenCount ->
                        i1.libraryAnime.anime.nextEpisodeAiringAt.compareTo(
                            i2.libraryAnime.anime.nextEpisodeAiringAt,
                        )
                    else -> i1.libraryAnime.unseenCount.compareTo(i2.libraryAnime.unseenCount)
                }
                LibrarySort.Type.Random -> {
                    error("Why Are We Still Here? Just To Suffer?")
                }
                else -> {
                    error("Why Are We Still Here? Just To Suffer?")
                }
            }
        }

        return mapValues { (key, value) ->
            if (key.sort.type == LibrarySort.Type.Random) {
                return@mapValues value.shuffled(Random(libraryPreferences.randomSortSeed().get()))
            }

            // Use groupSort if we're in a grouped mode, otherwise use the category's sort
            val sortMode = groupSort ?: key.sort
            val comparator = sortMode.comparator()
                .let { if (sortMode.isAscending) it else it.reversed() }
                .thenComparator(sortAlphabetically)

            value.sortedWith(comparator)
        }
    }

    private fun getAnimelibItemPreferencesFlow(): Flow<ItemPreferences> {
        return combine(
            libraryPreferences.downloadBadge().changes(),
            libraryPreferences.localBadge().changes(),
            libraryPreferences.languageBadge().changes(),
            libraryPreferences.autoUpdateAnimeRestrictions.changes(),

            preferences.downloadedOnly().changes(),
            libraryPreferences.filterDownloaded().changes(),
            libraryPreferences.filterUnseen().changes(),
            libraryPreferences.filterStarted().changes(),
            libraryPreferences.filterBookmarked().changes(),
            // AM (FILLERMARK) -->
            libraryPreferences.filterFillermarkedAnime().changes(),
            // <-- AM (FILLERMARK)
            libraryPreferences.filterCompleted().changes(),
            libraryPreferences.filterIntervalCustom().changes(),
            libraryPreferences.showSourceIcon().changes(),
            libraryPreferences.showLanguageIcon().changes(),
            transform = {
                ItemPreferences(
                    downloadBadge = it[0] as Boolean,
                    localBadge = it[1] as Boolean,
                    languageBadge = it[2] as Boolean,
                    skipOutsideReleasePeriod = LibraryPreferences.ANIME_OUTSIDE_RELEASE_PERIOD in (it[3] as Set<*>),
                    globalFilterDownloaded = it[4] as Boolean,
                    filterDownloaded = it[5] as TriState,
                    filterUnseen = it[6] as TriState,
                    filterStarted = it[7] as TriState,
                    filterBookmarked = it[8] as TriState,
                    // AM (FILLERMARK) -->
                    filterFillermarked = it[9] as TriState,
                    filterCompleted = it[10] as TriState,
                    filterIntervalCustom = it[11] as TriState,
                    // <-- AM (FILLERMARK)
                    showSourceIcon = it[12] as Boolean,
                    showLanguageIcon = it[13] as Boolean,
                )
            },
        )
    }

    /**
     * Get the categories and all its anime from the database.
     */
    private fun getLibraryFlow(): Flow<AnimeLibraryMap> {
        val animelibAnimesFlow = combine(
            getLibraryAnime.subscribe(),
            getAnimelibItemPreferencesFlow(),
            getSourcesWithFavoriteCount.subscribe(),
            libraryPreferences.animeFolderMap().changes(),
            downloadCache.changes.debounce(500L),
        ) { libraryMangaList, prefs, sources, folderMapStringSet, _ ->
            val animeSourceUrlMap = libraryMangaList.associate { (it.anime.source to it.anime.url) to it.id }
            val folderMap = mutableMapOf<Long, Long>()
            for (item in folderMapStringSet) {
                val parts = item.split("|")
                if (parts.size >= 3) {
                    val folderId = parts.last().toLongOrNull() ?: continue
                    val source = parts[0].toLongOrNull() ?: continue
                    val url = parts.subList(1, parts.size - 1).joinToString("|")
                    val animeId = animeSourceUrlMap[source to url]
                    if (animeId != null) {
                        folderMap[animeId] = folderId
                    }
                } else if (parts.size == 2) {
                    val animeId = parts[0].toLongOrNull() ?: continue
                    val folderId = parts[1].toLongOrNull() ?: continue
                    folderMap[animeId] = folderId
                }
            }

            libraryMangaList
                .map { libraryManga ->
                    val mangaWithFolder = libraryManga.copy(folderId = folderMap[libraryManga.id])
                    // Display mode based on user preference: take it from global library setting or category
                    LibraryItem(
                        mangaWithFolder,
                        downloadCount = if (prefs.downloadBadge) {
                            downloadManager.getDownloadCount(mangaWithFolder.anime).toLong()
                        } else {
                            0
                        },
                        unseenCount = mangaWithFolder.unseenCount,
                        isLocal = if (prefs.localBadge) mangaWithFolder.anime.isLocal() else false,
                        sourceLanguage = if (prefs.languageBadge || prefs.showLanguageIcon) {
                            sourceManager.getOrStub(mangaWithFolder.anime.source).lang
                        } else {
                            ""
                        },
                        showSourceIcon = prefs.showSourceIcon,
                        showLanguageIcon = prefs.showLanguageIcon,
                        domainSource = sources.find { it.first.id == mangaWithFolder.anime.source }?.first,
                    )
                }
                .groupBy { it.libraryAnime.category }
        }

        return combine(getCategories.subscribe(), animelibAnimesFlow) { categories, animelibAnime ->
            val hasUserCategories = categories.any { !it.isSystemCategory }
            val hasAnimeInDefault = !animelibAnime[0L].isNullOrEmpty()
            val displayCategories = if (hasUserCategories && !hasAnimeInDefault) {
                categories.fastFilterNot { it.isSystemCategory }
            } else {
                categories
            }

            displayCategories.associateWith { animelibAnime[it.id].orEmpty() }
        }
    }

    // SY -->
    private fun AnimeLibraryMap.applyGrouping(groupType: Int, tracks: Map<Long, List<Track>>): AnimeLibraryMap {
        val items = when (groupType) {
            LibraryGroup.BY_DEFAULT -> this
            LibraryGroup.UNGROUPED -> {
                mapOf(
                    Category(
                        0,
                        preferences.context.getString(R.string.ungrouped),
                        0,
                        0,
                        false,
                    ) to
                        values.flatten().distinctBy { it.libraryAnime.anime.id },
                )
            }
            else -> {
                getGroupedAnimeItems(
                    groupType = groupType,
                    libraryAnime = this.values.flatten().distinctBy { it.libraryAnime.anime.id },
                    tracks = tracks,
                )
            }
        }

        return items
    }
    // SY <--

    /**
     * Flow of tracking filter preferences
     *
     * @return map of track id with the filter value
     */
    private fun getTrackingFilterFlow(): Flow<Map<Long, TriState>> {
        return trackerManager.loggedInTrackersFlow().flatMapLatest { loggedInTrackers ->
            if (loggedInTrackers.isEmpty()) return@flatMapLatest flowOf(emptyMap())

            val prefFlows = loggedInTrackers.map { tracker ->
                libraryPreferences.filterTracking(tracker.id.toInt()).changes()
            }
            combine(prefFlows) {
                loggedInTrackers
                    .mapIndexed { index, tracker -> tracker.id to it[index] }
                    .toMap()
            }
        }
    }

    /**
     * Returns the common categories for the given list of anime.
     *
     * @param animes the list of anime.
     */
    private suspend fun getCommonCategories(animes: List<Anime>): Collection<Category> {
        if (animes.isEmpty()) return emptyList()
        return animes
            .map { getCategories.await(it.id).toSet() }
            .reduce { set1, set2 -> set1.intersect(set2) }
    }

    suspend fun getNextUnseenEpisode(anime: Anime): Episode? {
        return getEpisodesByAnimeId.await(anime.id).getNextUnseen(anime, downloadManager)
    }

    /**
     * Returns the mix (non-common) categories for the given list of anime.
     *
     * @param animes the list of anime.
     */
    private suspend fun getMixCategories(animes: List<Anime>): Collection<Category> {
        if (animes.isEmpty()) return emptyList()
        val nimeCategories = animes.map { getCategories.await(it.id).toSet() }
        val common = nimeCategories.reduce { set1, set2 -> set1.intersect(set2) }
        return nimeCategories.flatten().distinct().subtract(common)
    }

    fun runDownloadActionSelection(action: DownloadAction) {
        val selection = state.value.selection
        val animes = selection.map { it.anime }.toList()
        runDownloadAction(action, animes)
        clearSelection()
    }

    fun runDownloadAction(action: DownloadAction, animes: List<Anime>) {
        when (action) {
            DownloadAction.NEXT_1_EPISODE -> downloadUnseenEpisodes(animes, 1)
            DownloadAction.NEXT_5_EPISODES -> downloadUnseenEpisodes(animes, 5)
            DownloadAction.NEXT_10_EPISODES -> downloadUnseenEpisodes(animes, 10)
            DownloadAction.NEXT_25_EPISODES -> downloadUnseenEpisodes(animes, 25)
            DownloadAction.UNSEEN_EPISODES -> downloadUnseenEpisodes(animes, null)
        }
    }

    /**
     * Queues the amount specified of unseen episodes from the list of animes given.
     *
     * @param animes the list of anime.
     * @param amount the amount to queue or null to queue all
     */
    private fun downloadUnseenEpisodes(animes: List<Anime>, amount: Int?) {
        screenModelScope.launchNonCancellable {
            animes.forEach { anime ->
                val episodes = getNextEpisodes.await(anime.id)
                    .fastFilterNot { episode ->
                        downloadManager.getQueuedDownloadOrNull(episode.id) != null ||
                            downloadManager.isEpisodeDownloaded(
                                episode.name,
                                episode.scanlator,
                                anime.title,
                                anime.source,
                                episodeNumber = episode.episodeNumber,
                            )
                    }
                    .let { if (amount != null) it.take(amount) else it }

                downloadManager.downloadEpisodes(anime, episodes, true, useExternalDownloader)
            }
        }
    }

    fun resetInfo() {
        state.value.selection.fastForEach { (anime) ->
            val animeInfo = AnimeUpdate(
                id = anime.id,
                title = null,
                author = null,
                artist = null,
                thumbnailUrl = null,
                description = null,
                genre = null,
                status = null,
            )
            screenModelScope.launchNonCancellable {
                updateAnime.await(animeInfo)
            }
        }
        clearSelection()
    }

    fun updateSelection() {
        val selection = state.value.selection.toList()
        screenModelScope.launchIO {
            selection.forEach {
                eu.kanade.tachiyomi.data.library.LibraryUpdateJob.startNow(context, category = Category(it.category, "", 0L, 0L, false))
            }
        }
        clearSelection()
    }

    /**
     * Marks animes' episodes seen status.
     */
    fun markSeenSelection(seen: Boolean) {
        val animes = state.value.selection.toList()
        markSeen(animes, seen)
        clearSelection()
    }

    fun markSeen(items: List<LibraryAnime>, seen: Boolean) {
        screenModelScope.launchNonCancellable {
            items.forEach { anime ->
                setSeenStatus.await(
                    anime = anime.anime,
                    seen = seen,
                )
            }
        }
    }

    fun toggleFavoriteSelection() {
        val selection = state.value.selection.map { it.anime }
        toggleFavorite(selection)
        clearSelection()
    }

    fun toggleFavorite(animes: List<Anime>) {
        val allFavorite = animes.all { it.favorite }
        val newFavorite = !allFavorite
        screenModelScope.launchNonCancellable {
            val animeUpdates = animes.map {
                it.copy(
                    favorite = newFavorite,
                    dateAdded = if (newFavorite) java.time.Instant.now().toEpochMilli() else 0,
                ).toAnimeUpdate()
            }
            updateAnime.awaitAll(animeUpdates)
        }
    }

    /**
     * Remove the selected anime.
     *
     * @param animeList the list of anime to delete.
     * @param deleteFromLibrary whether to delete anime from library.
     * @param deleteEpisodes whether to delete downloaded episodes.
     */
    fun removeAnimes(animeList: List<Anime>, deleteFromLibrary: Boolean, deleteEpisodes: Boolean) {
        screenModelScope.launchNonCancellable {
            val animeToDelete = animeList.distinctBy { it.id }

            if (deleteFromLibrary) {
                val toDelete = animeToDelete.map {
                    it.removeCovers(coverCache)
                    AnimeUpdate(
                        favorite = false,
                        id = it.id,
                    )
                }
                updateAnime.awaitAll(toDelete)


            }

            if (deleteEpisodes) {
                animeToDelete.forEach { anime ->
                    val source = sourceManager.get(anime.source) as? HttpSource
                    if (source != null) {
                        downloadManager.deleteAnime(anime, source)
                    }
                }
            }
        }
    }

    /**
     * Bulk update categories of anime using old and new common categories.
     *
     * @param animeList the list of anime to move.
     * @param addCategories the categories to add for all animes.
     * @param removeCategories the categories to remove in all animes.
     */
    fun setAnimeCategories(
        animeList: List<Anime>,
        addCategories: List<Long>,
        removeCategories: List<Long>,
    ) {
        screenModelScope.launchNonCancellable {
            animeList.forEach { anime ->
                val categoryIds = getCategories.await(anime.id)
                    .map { it.id }
                    .subtract(removeCategories.toSet())
                    .plus(addCategories)
                    .toList()

                setAnimeCategories.await(anime.id, categoryIds)
            }
        }
    }

    fun getDisplayMode(): PreferenceMutableState<LibraryDisplayMode> {
        return libraryPreferences.displayMode().asState(screenModelScope)
    }

    fun getColumnsPreferenceForCurrentOrientation(isLandscape: Boolean): PreferenceMutableState<Int> {
        return (
            if (isLandscape) {
                libraryPreferences.landscapeColumns()
            } else {
                libraryPreferences.portraitColumns()
            }
            ).asState(
            screenModelScope,
        )
    }

    suspend fun getRandomAnimelibItemForCurrentCategory(): LibraryItem? {
        val categories = state.value.categories
        if (categories.isEmpty()) return null

        return withIOContext {
            val categoryId = categories.getOrNull(activeCategoryIndex)?.id ?: categories.last().id
            state.value
                .getAnimelibItemsByCategoryId(categoryId)
                ?.randomOrNull()
        }
    }

    fun showSettingsDialog() {
        mutableState.update { it.copy(dialog = Dialog.SettingsSheet) }
    }

    fun clearSelection() {
        mutableState.update { it.copy(selection = persistentListOf()) }
    }

    fun toggleSelection(anime: LibraryAnime) {
        mutableState.update { state ->
            val newSelection = state.selection.mutate { list ->
                if (list.fastAny { it.id == anime.id }) {
                    list.removeAll { it.id == anime.id }
                } else {
                    list.add(anime)
                }
            }
            state.copy(selection = newSelection)
        }
    }

    /**
     * Selects all nimes between and including the given anime and the last pressed anime from the
     * same category as the given anime
     */
    fun toggleRangeSelection(anime: LibraryAnime, categoryId: Long) {
        mutableState.update { state ->
            val newSelection = state.selection.mutate { list ->
                val lastSelected = list.lastOrNull()
                val items = state.getAnimelibItemsByCategoryId(categoryId)
                    ?.fastMap { it.libraryAnime }.orEmpty()

                if (lastSelected == null || !items.fastAny { it.id == lastSelected.id }) {
                    list.add(anime)
                    return@mutate
                }

                val lastAnimeIndex = items.indexOfFirst { it.id == lastSelected.id }
                val curAnimeIndex = items.indexOfFirst { it.id == anime.id }

                val selectedIds = list.fastMap { it.id }
                val selectionRange = when {
                    lastAnimeIndex < curAnimeIndex -> IntRange(lastAnimeIndex, curAnimeIndex)
                    curAnimeIndex < lastAnimeIndex -> IntRange(curAnimeIndex, lastAnimeIndex)
                    // We shouldn't reach this point
                    else -> return@mutate
                }
                val newSelections = selectionRange.mapNotNull { index ->
                    items[index].takeUnless { it.id in selectedIds }
                }
                list.addAll(newSelections)
            }
            state.copy(selection = newSelection)
        }
    }

    fun selectAll(index: Int) {
        mutableState.update { state ->
            val newSelection = state.selection.mutate { list ->
                val categoryId = state.categories.getOrNull(index)?.id ?: -1
                val selectedIds = list.fastMap { it.id }
                state.getAnimelibItemsByCategoryId(categoryId)
                    ?.fastMapNotNull { item ->
                        item.libraryAnime.takeUnless { it.id in selectedIds }
                    }
                    ?.let { list.addAll(it) }
            }
            state.copy(selection = newSelection)
        }
    }

    fun invertSelection(index: Int) {
        mutableState.update { state ->
            val newSelection = state.selection.mutate { list ->
                val categoryId = state.categories.getOrNull(index)?.id ?: return@mutate
                val items = state.getAnimelibItemsByCategoryId(categoryId)?.fastMap { it.libraryAnime }.orEmpty()
                val selectedIds = list.fastMap { it.id }
                val (toRemove, toAdd) = items.fastPartition { it.id in selectedIds }
                val toRemoveIds = toRemove.fastMap { it.id }
                list.removeAll { it.id in toRemoveIds }
                list.addAll(toAdd)
            }
            state.copy(selection = newSelection)
        }
    }

    fun search(query: String?) {
        mutableState.update { it.copy(searchQuery = query) }
    }

    fun setOpenFolder(folderId: Long?) {
        mutableState.update { it.copy(openFolderId = folderId) }
    }

    fun openChangeCategoryDialog() {
        screenModelScope.launchIO {
            // Create a copy of selected anime
            val animeList = state.value.selection.map { it.anime }

            // Hide the default category because it has a different behavior than the ones from db.
            val categories = getCategories.await().filterNot { it.isSystemCategory }

            // Get indexes of the common categories to preselect.
            val common = getCommonCategories(animeList)
            // Get indexes of the mix categories to preselect.
            val mix = getMixCategories(animeList)
            val preselected = categories
                .map {
                    when (it) {
                        in common -> CheckboxState.State.Checked(it)
                        in mix -> CheckboxState.TriState.Exclude(it)
                        else -> CheckboxState.State.None(it)
                    }
                }
                .toImmutableList()
            mutableState.update { it.copy(dialog = Dialog.ChangeCategory(animeList, preselected)) }
        }
    }

    fun openDeleteAnimeDialog() {
        val nimeList = state.value.selection.map { it.anime }
        openDeleteAnimeDialog(nimeList)
    }

    fun openDeleteAnimeDialog(animes: List<Anime>) {
        mutableState.update { it.copy(dialog = Dialog.DeleteAnime(animes)) }
    }

    fun closeDialog() {
        mutableState.update { it.copy(dialog = null) }
    }

    sealed interface Dialog {
        data object SettingsSheet : Dialog
        data class ChangeCategory(
            val anime: List<Anime>,
            val initialSelection: ImmutableList<CheckboxState<Category>>,
        ) : Dialog
        data class DeleteAnime(val anime: List<Anime>) : Dialog
    }

    // SY -->
    /** Returns first unread chapter of a anime */
    suspend fun getFirstUnseen(anime: Anime): Episode? {
        return getNextEpisodes.await(anime.id).firstOrNull()
    }

    @Suppress("MagicNumber", "LongMethod", "CyclomaticComplexMethod")
    private fun getGroupedAnimeItems(
        groupType: Int,
        libraryAnime: List<LibraryItem>,
        tracks: Map<Long, List<Track>>,
    ): AnimeLibraryMap {
        val context = preferences.context
        val thirtyDaysAgo = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000L)
        
        return when (groupType) {
            LibraryGroup.BY_TRACK_STATUS -> {
                libraryAnime.groupBy { item ->
                    val trackStatus = tracks[item.libraryAnime.anime.id]?.firstNotNullOfOrNull { track ->
                        TrackStatus.parseTrackerStatus(track.trackerId, track.status)
                    } ?: TrackStatus.OTHER

                    val isStale = item.libraryAnime.hasStarted && 
                                 item.libraryAnime.lastSeen < thirtyDaysAgo && 
                                 item.libraryAnime.unseenCount > 0

                    val status = when {
                        trackStatus == TrackStatus.WATCHING && isStale && item.libraryAnime.anime.favorite -> TrackStatus.PAUSED
                        trackStatus == TrackStatus.WATCHING && isStale && !item.libraryAnime.anime.favorite -> TrackStatus.DROPPED
                        else -> trackStatus
                    }

                    status.int
                }.mapKeys { (id) ->
                    Category(
                        id = id.toLong(),
                        name = TrackStatus.entries
                            .find { it.int == id }
                            .let { it ?: TrackStatus.OTHER }
                            .let { context.getString(it.res) },
                        order = TrackStatus.entries.toTypedArray().indexOfFirst {
                            it.int == id
                        }.takeUnless { it == -1 }?.toLong() ?: TrackStatus.OTHER.ordinal.toLong(),
                        flags = 0,
                        hidden = false,
                    )
                }
            }
            LibraryGroup.BY_SOURCE -> {
                val sources: List<Long>
                libraryAnime.groupBy { item ->
                    item.libraryAnime.anime.source
                }.also {
                    sources = it.keys
                        .map {
                            sourceManager.getOrStub(it)
                        }
                        .sortedWith(
                            compareBy(String.CASE_INSENSITIVE_ORDER) { it.name.ifBlank { it.id.toString() } },
                        )
                        .map { it.id }
                }.mapKeys {
                    Category(
                        id = it.key,
                        name = if (it.key == LocalAnimeSource.ID) {
                            context.getString(R.string.local_source)
                        } else {
                            val source = sourceManager.getOrStub(it.key)
                            source.name.ifBlank { source.id.toString() }
                        },
                        order = sources.indexOf(it.key).takeUnless { it == -1 }?.toLong() ?: Long.MAX_VALUE,
                        flags = 0,
                        hidden = false,
                    )
                }
            }
            LibraryGroup.BY_TAG -> {
                val defaultTag = context.stringResource(SYMR.strings.ungrouped)
                val tags = libraryAnime.flatMap { it.libraryAnime.anime.genre.orEmpty() }.distinct()
                val groupedAnime = libraryAnime.flatMap { item ->
                    val genres = item.libraryAnime.anime.genre
                    if (genres.isNullOrEmpty()) {
                        listOf(defaultTag to item)
                    } else {
                        genres.map { it to item }
                    }
                }.groupBy({ it.first }, { it.second }).toList()

                val (bigGroups, defaultGroups) = groupedAnime.partition { (genre, groups) -> genre != defaultTag }
                val groupedEntries = bigGroups.flatMap { it.second }
                val defaultGroupEntries = defaultGroups.flatMap { it.second }.distinct().filterNot { it in groupedEntries }

                val finalGroups = if (defaultGroupEntries.isNotEmpty()) {
                    bigGroups + (defaultTag to defaultGroupEntries)
                } else {
                    bigGroups
                }

                finalGroups.toMap().mapKeys { (genre, _) ->
                    Category(
                        id = genre.hashCode().toLong(),
                        name = genre,
                        order = tags.indexOf(genre).takeUnless { it == -1 }?.toLong() ?: Long.MAX_VALUE,
                        flags = 0,
                        hidden = false,
                    )
                }
            }
            LibraryGroup.BY_STATUS -> {
                libraryAnime.groupBy { item ->
                    item.libraryAnime.anime.status
                }.mapKeys {
                    Category(
                        id = it.key + 1,
                        name = when (it.key) {
                            SAnime.ONGOING.toLong() -> context.getString(R.string.ongoing)
                            SAnime.LICENSED.toLong() -> context.getString(R.string.licensed)
                            SAnime.CANCELLED.toLong() -> context.getString(R.string.cancelled)
                            SAnime.ON_HIATUS.toLong() -> context.getString(R.string.on_hiatus)
                            SAnime.PUBLISHING_FINISHED.toLong() -> context.getString(
                                R.string.publishing_finished,
                            )
                            SAnime.COMPLETED.toLong() -> context.getString(R.string.completed)
                            SAnime.UPCOMING.toLong() -> context.getString(R.string.upcoming)
                            else -> context.getString(R.string.unknown)
                        },
                        order = when (it.key) {
                            SAnime.ONGOING.toLong() -> 1
                            SAnime.LICENSED.toLong() -> 2
                            SAnime.CANCELLED.toLong() -> 3
                            SAnime.ON_HIATUS.toLong() -> 4
                            SAnime.PUBLISHING_FINISHED.toLong() -> 5
                            SAnime.COMPLETED.toLong() -> 6
                            SAnime.UPCOMING.toLong() -> 7
                            else -> 8
                        },
                        flags = 0,
                        hidden = false,
                    )
                }
            }
            else -> emptyMap()
        }.filterValues { it.isNotEmpty() }.toSortedMap(compareBy { it.order })
    }

    fun createFolder(animeIds: List<Long>, categoryId: Long, folderName: String) {
        screenModelScope.launchIO {
            val id = System.currentTimeMillis()
            val category = getCategories.await().find { it.id == categoryId } ?: return@launchIO
            val newFolderStr = "$id|${category.name}|$folderName"
            val currentFolders = libraryPreferences.libraryFolders().get().toMutableSet()
            currentFolders.add(newFolderStr)
            libraryPreferences.libraryFolders().set(currentFolders)

            addAnimeToFolder(animeIds, categoryId, id)
        }
    }

    fun renameFolder(folderId: Long, newName: String) {
        screenModelScope.launchIO {
            val folders = libraryPreferences.libraryFolders().get().toMutableSet()
            val folderStr = folders.find { it.startsWith("$folderId|") } ?: return@launchIO
            val parts = folderStr.split("|")
            if (parts.size >= 3) {
                val categoryName = parts.subList(1, parts.size - 1).joinToString("|")
                folders.remove(folderStr)
                folders.add("$folderId|$categoryName|$newName")
                libraryPreferences.libraryFolders().set(folders)
            }
        }
    }

    fun deleteFolder(folderId: Long) {
        screenModelScope.launchIO {
            val folders = libraryPreferences.libraryFolders().get().toMutableSet()
            val folderStr = folders.find { it.startsWith("$folderId|") }
            if (folderStr != null) {
                folders.remove(folderStr)
                libraryPreferences.libraryFolders().set(folders)
            }

            val map = libraryPreferences.animeFolderMap().get().toMutableSet()
            val toRemove = map.filter { it.endsWith("|$folderId") }
            if (toRemove.isNotEmpty()) {
                map.removeAll(toRemove.toSet())
                libraryPreferences.animeFolderMap().set(map)
            }
        }
    }

    fun addAnimeToFolder(animeIds: List<Long>, categoryId: Long, folderId: Long?) {
        screenModelScope.launchIO {
            val map = libraryPreferences.animeFolderMap().get().toMutableSet()
            for (animeId in animeIds) {
                val anime = getAnime.await(animeId) ?: continue
                val keyPrefix = "${anime.source}|${anime.url}|"
                
                // remove existing mapping for this anime
                val existing = map.find { it.startsWith(keyPrefix) } ?: map.find { it.startsWith("$animeId|") }
                if (existing != null) map.remove(existing)
                
                if (folderId != null) {
                    map.add("$keyPrefix$folderId")
                }
            }
            libraryPreferences.animeFolderMap().set(map)
        }
    }

    @Immutable
    data class ItemPreferences(
        val downloadBadge: Boolean,
        val localBadge: Boolean,
        val languageBadge: Boolean,
        val skipOutsideReleasePeriod: Boolean,

        val globalFilterDownloaded: Boolean,
        val filterDownloaded: TriState,
        val filterUnseen: TriState,
        val filterStarted: TriState,
        val filterBookmarked: TriState,
        // AM (FILLERMARK) -->
        val filterFillermarked: TriState,
        // <-- AM (FILLERMARK)
        val filterCompleted: TriState,
        val filterIntervalCustom: TriState,
        val showSourceIcon: Boolean,
        val showLanguageIcon: Boolean,
    )

    @Immutable
    data class State(
        val isLoading: Boolean = true,
        val library: AnimeLibraryMap = emptyMap(),
        val searchQuery: String? = null,
        val selection: PersistentList<LibraryAnime> = persistentListOf(),
        val hasActiveFilters: Boolean = false,
        val showCategoryTabs: Boolean = false,
        val showAnimeCount: Boolean = false,
        val showAnimeContinueButton: Boolean = false,
        val dialog: Dialog? = null,
        // KMK -->
        val showEmptyCategoriesSearch: Boolean = true,
        // KMK <--
        // SY -->
        val groupType: Int = LibraryGroup.BY_DEFAULT,        // SY <--
        val folders: List<tachiyomi.domain.library.model.LibraryFolder> = emptyList(),
        val openFolderId: Long? = null,
    ) {
        private val libraryCount by lazy {
            library.values
                .flatten()
                .fastDistinctBy { it.libraryAnime.anime.id }
                .size
        }

        val isLibraryEmpty by lazy { libraryCount == 0 }

        val selectionMode = selection.isNotEmpty()

        val categories = library.keys.toList()

        val showResetInfo: Boolean by lazy {
            selection.fastAny { (anime) ->
                anime.title != anime.ogTitle ||
                    anime.author != anime.ogAuthor ||
                    anime.artist != anime.ogArtist ||
                    anime.description != anime.ogDescription ||
                    anime.genre != anime.ogGenre ||
                    anime.status != anime.ogStatus
            }
        }
        fun getAnimelibItemsByCategoryId(categoryId: Long): List<LibraryItem>? {
            return library.firstNotNullOfOrNull { (k, v) -> v.takeIf { k.id == categoryId } }
        }

        fun getAnimelibItemsByPage(page: Int): List<LibraryItem> {
            return library.values.elementAtOrNull(page).orEmpty()
        }

        /**
         * The rows to render for [page]: anime, folder tiles when [collapseFolders] is on,
         * and category headers while searching.
         *
         * Callers should go through [LibraryScreenModel.getDisplayItemsForPage], which
         * memoizes the result across state updates that cannot change it.
         *
         * @param defaultCategoryTitle localized name for the system category, resolved by the
         *   caller because it needs a Context.
         */
        fun buildDisplayItemsForPage(
            page: Int,
            collapseFolders: Boolean,
            defaultCategoryTitle: String,
        ): ImmutableList<LibraryDisplayItem> {
            return if (!searchQuery.isNullOrEmpty()) {
                buildSearchDisplayItems(collapseFolders, defaultCategoryTitle)
            } else {
                buildCategoryDisplayItems(page, collapseFolders)
            }
        }

        /**
         * Search mode shows matches from all categories in one flattened grid. An anime can
         * belong to several categories and a folder can contain anime from several
         * categories, so each anime (grid key `library-grid-<anime id>`) and each folder
         * (grid key `library-folder-<folder id>`) must be emitted exactly once to keep the
         * LazyGrid keys unique. A category header is only added when the category actually
         * contributes an item.
         */
        private fun buildSearchDisplayItems(
            collapseFolders: Boolean,
            defaultCategoryTitle: String,
        ): ImmutableList<LibraryDisplayItem> {
            val displayItems = mutableListOf<LibraryDisplayItem>()
            val seenAnimeIds = mutableSetOf<Long>()
            val seenFolderIds = mutableSetOf<Long>()

            // Folder members are looked up per folder id; grouping once keeps this linear
            // instead of re-scanning every category for each folder that is emitted.
            // Deduped by anime id because an anime in several categories would otherwise
            // appear twice inside the folder.
            val itemsByFolderId: Map<Long, List<LibraryItem>> by lazy {
                library.values.flatten()
                    .fastDistinctBy { it.libraryAnime.anime.id }
                    .fastFilter { it.libraryAnime.folderId != null }
                    .groupBy { it.libraryAnime.folderId!! }
            }
            val foldersById = folders.associateBy { it.id }

            categories.fastForEach { cat ->
                val catItems = library[cat] ?: emptyList()
                val categoryItems = mutableListOf<LibraryDisplayItem>()
                val processedFolderIds = mutableSetOf<Long>()
                for (item in catItems) {
                    if (!seenAnimeIds.add(item.libraryAnime.anime.id)) {
                        // Already shown under a previous category or inside a folder
                        continue
                    }
                    val folderId = item.libraryAnime.folderId
                    val folder = if (collapseFolders && folderId != null && folderId !in seenFolderIds) {
                        foldersById[folderId]
                    } else {
                        null
                    }
                    if (folderId == null || folder == null || !processedFolderIds.add(folderId)) {
                        // Folders disabled, anime not in a folder, folder record gone, or this
                        // folder was already emitted: show the anime on its own.
                        categoryItems.add(LibraryDisplayItem.Anime(item))
                        continue
                    }
                    // A folder can span categories: collect all of its search hits so it
                    // renders once without dropping members that only match in another
                    // category.
                    val folderItems = itemsByFolderId[folderId].orEmpty()
                    folderItems.fastForEach { seenAnimeIds.add(it.libraryAnime.anime.id) }
                    seenFolderIds.add(folderId)
                    categoryItems.add(LibraryDisplayItem.Folder(folder, folderItems))
                }
                if (categoryItems.isNotEmpty()) {
                    val label = if (cat.isSystemCategory) defaultCategoryTitle else cat.name
                    displayItems.add(LibraryDisplayItem.Header(label))
                    displayItems.addAll(categoryItems)
                }
            }
            return displayItems.toImmutableList()
        }

        private fun buildCategoryDisplayItems(
            page: Int,
            collapseFolders: Boolean,
        ): ImmutableList<LibraryDisplayItem> {
            val items = getAnimelibItemsByPage(page)
            if (!collapseFolders) {
                return items.fastMap { LibraryDisplayItem.Anime(it) }.toImmutableList()
            }

            val displayItems = mutableListOf<LibraryDisplayItem>()
            val processedFolderIds = mutableSetOf<Long>()
            val grouped = items.groupBy { it.libraryAnime.folderId }
            val foldersById = folders.associateBy { it.id }

            for (item in items) {
                val folderId = item.libraryAnime.folderId
                if (folderId == null) {
                    displayItems.add(LibraryDisplayItem.Anime(item))
                } else if (processedFolderIds.add(folderId)) {
                    val folder = foldersById[folderId]
                    if (folder != null) {
                        displayItems.add(LibraryDisplayItem.Folder(folder, grouped[folderId] ?: emptyList()))
                    } else {
                        displayItems.add(LibraryDisplayItem.Anime(item))
                        processedFolderIds.remove(folderId)
                    }
                }
            }
            return displayItems.toImmutableList()
        }

        fun getAnimeCountForCategory(category: Category): Int? {
            return if (showAnimeCount || (!searchQuery.isNullOrEmpty() && showEmptyCategoriesSearch)) library[category]?.size else null
        }

        fun getToolbarTitle(
            defaultTitle: String,
            defaultCategoryTitle: String,
            page: Int,
        ): LibraryToolbarTitle {
            val category = categories.getOrNull(page) ?: return LibraryToolbarTitle(defaultTitle)
            val categoryName = category.let {
                if (it.isSystemCategory) defaultCategoryTitle else it.name
            }
            val title = if (showCategoryTabs) defaultTitle else categoryName
            val count = when {
                !showAnimeCount -> null
                !showCategoryTabs -> getAnimeCountForCategory(category)
                // Whole library count
                else -> libraryCount
            }

            return LibraryToolbarTitle(title, count)
        }
    }
}
