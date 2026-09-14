package eu.kanade.tachiyomi.ui.browse.source.browse

import android.content.res.Configuration
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAny
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import androidx.paging.filter
import androidx.paging.map
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.core.preference.asState
import eu.kanade.domain.anime.interactor.UpdateAnime
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.presentation.util.ioCoroutineScope
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.util.removeCovers
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.mutate
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.mapAsCheckboxState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.anime.interactor.GetAnime
import tachiyomi.domain.anime.interactor.GetDuplicateLibraryAnime
import tachiyomi.domain.anime.interactor.NetworkToLocalAnime
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.model.toAnimeUpdate
import tachiyomi.domain.anime.model.toDomainAnime
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetAnimeCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.episode.interactor.SetAnimeDefaultEpisodeFlags
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.source.interactor.DeleteSavedSearchById
import tachiyomi.domain.source.interactor.GetRemoteAnime
import tachiyomi.domain.source.interactor.GetSavedSearchById
import tachiyomi.domain.source.interactor.GetSavedSearchBySourceId
import tachiyomi.domain.source.interactor.InsertSavedSearch
import tachiyomi.domain.source.model.SavedSearch
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.Instant
import eu.kanade.tachiyomi.animesource.model.AnimeFilter as AnimeSourceModelFilter

class BrowseSourceScreenModel(
    private val sourceId: Long,
    listingQuery: String?,
    private val savedSearchId: Long? = null,
    sourceManager: SourceManager = Injekt.get(),
    sourcePreferences: SourcePreferences = Injekt.get(),
    basePreferences: BasePreferences = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val coverCache: CoverCache = Injekt.get(),
    private val getRemoteAnime: GetRemoteAnime = Injekt.get(),
    private val getDuplicateAnimelibAnime: GetDuplicateLibraryAnime = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val setAnimeCategories: SetAnimeCategories = Injekt.get(),
    private val setAnimeDefaultEpisodeFlags: SetAnimeDefaultEpisodeFlags = Injekt.get(),
    private val getAnime: GetAnime = Injekt.get(),
    private val networkToLocalAnime: NetworkToLocalAnime = Injekt.get(),
    private val updateAnime: UpdateAnime = Injekt.get(),
    private val addTracks: AddTracks = Injekt.get(),
    private val getSavedSearchById: GetSavedSearchById = Injekt.get(),
    private val getSavedSearchBySourceId: GetSavedSearchBySourceId = Injekt.get(),
    private val insertSavedSearch: InsertSavedSearch = Injekt.get(),
    private val deleteSavedSearchById: DeleteSavedSearchById = Injekt.get(),
    private val filterSerializer: FilterSerializer = Injekt.get(),
    private val getFavorites: tachiyomi.domain.anime.interactor.GetFavorites = Injekt.get(),
    private val trackPreferences: eu.kanade.domain.track.service.TrackPreferences = Injekt.get(),
) : StateScreenModel<BrowseSourceScreenModel.State>(State(Listing.valueOf(listingQuery))) {

    var displayMode by sourcePreferences.sourceDisplayMode().asState(screenModelScope)

    val source = sourceManager.getOrStub(sourceId)

    init {
        if (savedSearchId != null) {
            screenModelScope.launch {
                val savedSearch = getSavedSearchById.awaitOrNull(savedSearchId)
                if (savedSearch != null) {
                    loadSearch(savedSearch)
                }
            }
        }

        if (source is CatalogueSource) {
            mutableState.update {
                var query: String? = null
                var listing = it.listing

                if (listing is Listing.Search) {
                    query = listing.query
                    listing = Listing.Search(query, source.getFilterList())
                }

                it.copy(
                    listing = listing,
                    filters = source.getFilterList(),
                    toolbarQuery = query,
                )
            }
        }

        // debounce search updates
        screenModelScope.launch {
            state.map { it.toolbarQuery }
                .distinctUntilChanged()
                .scan<String?, Pair<String?, String?>>(null to null) { acc, new ->
                    acc.second to new
                }
                .filter { sourcePreferences.autoSearch().get() }
                .drop(2) // ignore initial state
                .transformLatest { (prev, current) ->
                    val isDeletion = (current?.length ?: 0) < (prev?.length ?: 0)
                    kotlinx.coroutines.delay(if (isDeletion) 800L else 500L)
                    emit(current)
                }
                .collectLatest { query ->
                    val currentListing = state.value.listing
                    if (currentListing.query != query) {
                        if (query.isNullOrEmpty() && currentListing !is Listing.Search) {
                            return@collectLatest
                        }
                        search(query)
                    }
                }
        }

        if (!basePreferences.incognitoMode().get()) {
            sourcePreferences.lastUsedSource().set(source.id)
        }

        getSavedSearchBySourceId.subscribe(sourceId)
            .onEach { savedSearches ->
                mutableState.update { it.copy(savedSearches = savedSearches.toImmutableList()) }
            }
            .launchIn(screenModelScope)

        getFavorites.subscribe(sourceId)
            .onEach { favorites ->
                mutableState.update { it.copy(favoriteIds = favorites.map { fav -> fav.id }.toImmutableSet()) }
            }
            .launchIn(screenModelScope)
    }

    /**
     * Flow of Pager flow tied to [State.listing]
     */
    private val hideInLibraryItems = sourcePreferences.hideInAnimeLibraryItems().get()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val animePagerFlowFlow = state.map { it.listing }
        .distinctUntilChanged()
        .map { listing ->
            Pager(
                PagingConfig(
                    pageSize = 20,
                    prefetchDistance = 5,
                    initialLoadSize = 40,
                ),
            ) {
                getRemoteAnime.subscribe(sourceId, listing.query ?: "", listing.filters)
            }.flow.map { pagingData ->
                pagingData.map {
                    val localAnime = networkToLocalAnime.getLocal(it.toDomainAnime(sourceId))
                    getAnime.subscribe(localAnime.url, localAnime.source)
                        .filterNotNull()
                        .distinctUntilChanged()
                        .stateIn(ioCoroutineScope, SharingStarted.WhileSubscribed(5000), localAnime)
                }
                    .filter { !hideInLibraryItems || !it.value.favorite }
            }.cachedIn(ioCoroutineScope)
                .cachedIn(screenModelScope)
        }
        .stateIn(screenModelScope, SharingStarted.Lazily, emptyFlow())

    fun getColumnsPreference(orientation: Int): GridCells {
        val isLandscape = orientation == Configuration.ORIENTATION_LANDSCAPE
        val columns = if (isLandscape) {
            libraryPreferences.landscapeColumns()
        } else {
            libraryPreferences.portraitColumns()
        }.get()
        return if (columns == 0) GridCells.Adaptive(128.dp) else GridCells.Fixed(columns)
    }

    // returns the number from the size slider
    fun getColumnsPreferenceForCurrentOrientation(orientation: Int): Int {
        val isLandscape = orientation == Configuration.ORIENTATION_LANDSCAPE
        return if (isLandscape) {
            libraryPreferences.landscapeColumns()
        } else {
            libraryPreferences.portraitColumns()
        }.get()
    }

    fun resetFilters() {
        if (source !is CatalogueSource) return

        mutableState.update { it.copy(filters = source.getFilterList()) }
    }

    fun setListing(listing: Listing) {
        mutableState.update { it.copy(listing = listing, toolbarQuery = null, currentSavedSearch = null) }
    }

    fun setFilters(filters: FilterList) {
        if (source !is CatalogueSource) return

        mutableState.update {
            it.copy(
                filters = filters,
            )
        }
    }

    /**
     * Updated to correctly update filter state without aggressive clearing
     */
    fun onFilterUpdate(filter: AnimeSourceModelFilter<*>) {
        if (source !is CatalogueSource) return

        mutableState.update { it.copy(filtersId = java.util.Random().nextLong()) }
    }

    fun saveSearch(name: String) {
        val query = state.value.toolbarQuery
        val filters = state.value.filters
        val filtersJson = if (filters.isNotEmpty()) filterSerializer.serialize(filters) else null

        screenModelScope.launchIO {
            insertSavedSearch.await(
                SavedSearch(
                    id = -1,
                    source = sourceId,
                    name = name,
                    query = query,
                    filtersJson = filtersJson,
                ),
            )
        }
    }

    fun deleteSearch(savedSearchId: Long) {
        screenModelScope.launchIO {
            deleteSavedSearchById.await(savedSearchId)
        }
    }

    fun loadSearch(savedSearch: SavedSearch) {
        if (source !is CatalogueSource) return

        val filters = source.getFilterList()
        savedSearch.filtersJson?.let {
            filterSerializer.deserialize(filters, it)
        }

        mutableState.update {
            it.copy(
                filters = filters,
                toolbarQuery = savedSearch.query,
                listing = Listing.Search(
                    query = savedSearch.query,
                    filters = filters,
                    randomId = java.util.Random().nextLong(),
                ),
                currentSavedSearch = savedSearch,
            )
        }
    }

    fun search(query: String? = null, filters: FilterList? = null) {
        val resolvedFilters = when {
            filters != null -> {
                if (filters.isPlaceholderOrEmpty()) {
                    FilterList()
                } else {
                    FilterList(filters)
                }
            }
            !query.isNullOrBlank() -> {
                val currentFilters = state.value.filters
                if (currentFilters.isPlaceholderOrEmpty()) {
                    FilterList()
                } else {
                    FilterList(currentFilters)
                }
            }
            else -> FilterList(state.value.filters)
        }
        val nextListing = Listing.Search(
            query = query,
            filters = resolvedFilters,
            randomId = java.util.Random().nextLong(),
        )
        mutableState.update { it.copy(listing = nextListing) }
    }

    fun searchGenre(genreName: String) {
        if (source !is CatalogueSource) return

        val defaultFilters = source.getFilterList()
        var genreExists = false

        filter@ for (sourceFilter in defaultFilters) {
            if (sourceFilter is AnimeSourceModelFilter.Group<*>) {
                for (filter in sourceFilter.state) {
                    if (filter is AnimeSourceModelFilter<*> && filter.name.equals(genreName, true)) {
                        when (filter) {
                            is AnimeSourceModelFilter.TriState -> filter.state = 1
                            is AnimeSourceModelFilter.CheckBox -> filter.state = true
                            else -> {}
                        }
                        genreExists = true
                        break@filter
                    }
                }
            } else if (sourceFilter is AnimeSourceModelFilter.Select<*>) {
                val index = sourceFilter.values.filterIsInstance<String>()
                    .indexOfFirst { it.equals(genreName, true) }

                if (index != -1) {
                    sourceFilter.state = index
                    genreExists = true
                    break
                }
            }
        }
        mutableState.update {
            val listing = if (genreExists) {
                Listing.Search(
                    query = null,
                    filters = defaultFilters,
                    randomId = java.util.Random().nextLong(),
                )
            } else {
                Listing.Search(
                    query = genreName,
                    filters = defaultFilters,
                    randomId = java.util.Random().nextLong(),
                )
            }
            it.copy(
                filters = defaultFilters,
                listing = listing,
                toolbarQuery = listing.query,
            )
        }
    }

    /**
     * Adds or removes an anime from the library.
     *
     * @param anime the anime to update.
     */
    fun changeAnimeFavorite(anime: Anime) {
        screenModelScope.launch {
            var new = anime.copy(
                favorite = !anime.favorite,
                dateAdded = when (anime.favorite) {
                    true -> 0
                    false -> Instant.now().toEpochMilli()
                },
            )

            if (!new.favorite) {
                new = new.removeCovers(coverCache)
            } else {
                setAnimeDefaultEpisodeFlags.await(anime)
                if (trackPreferences.trackOnAddingToLibrary().get()) {
                    addTracks.bindEnhancedTrackers(anime, source)
                }
            }

            updateAnime.await(new.toAnimeUpdate())
        }
    }

    suspend fun addFavorite(anime: Anime) {
        val categories = getCategories()
        val defaultCategoryId = libraryPreferences.defaultCategory().get()
        val defaultCategory = categories.find { it.id == defaultCategoryId.toLong() }

        when {
            // Default category set
            defaultCategory != null -> {
                moveAnimeToCategories(anime, defaultCategory)

                changeAnimeFavorite(anime)
            }
            // Automatic 'Default' or no categories
            defaultCategoryId == 0 || categories.isEmpty() -> {
                moveAnimeToCategories(anime)

                changeAnimeFavorite(anime)
            }

            // Choose a category
            else -> {
                val preselectedIds = getCategories.await(anime.id).map { it.id }
                setDialog(
                    Dialog.ChangeAnimeCategory(
                        listOf(anime),
                        categories.mapAsCheckboxState { it.id in preselectedIds }.toImmutableList(),
                    ),
                )
            }
        }
    }

    fun toggleSelection(anime: Anime, index: Int = -1) {
        mutableState.update { state ->
            val newSelection = state.selection.mutate { list ->
                if (list.fastAny { it.id == anime.id }) {
                    list.removeAll { it.id == anime.id }
                } else {
                    list.add(anime)
                }
            }
            state.copy(
                selection = newSelection,
                isSelectAllMode = false,
                lastSelectedIndex = if (newSelection.isNotEmpty()) index else null,
            )
        }
    }

    fun selectRange(animeList: List<Anime?>, fromIndex: Int, toIndex: Int) {
        mutableState.update { state ->
            val start = minOf(fromIndex, toIndex)
            val end = maxOf(fromIndex, toIndex)
            val rangeItems = animeList.subList(start, end + 1).filterNotNull()

            val newSelection = state.selection.mutate { list ->
                rangeItems.forEach { anime ->
                    if (list.none { it.id == anime.id }) {
                        list.add(anime)
                    }
                }
            }
            state.copy(
                selection = newSelection,
                isSelectAllMode = false,
                lastSelectedIndex = toIndex,
            )
        }
    }

    fun selectAll(animeList: List<Anime>) {
        mutableState.update { state ->
            val newTarget = state.targetCount + 60
            val newSelection = state.selection.mutate { list ->
                animeList.take(newTarget).forEach { anime ->
                    if (list.none { it.id == anime.id }) {
                        list.add(anime)
                    }
                }
            }
            state.copy(
                selection = newSelection,
                isSelectAllMode = true,
                targetCount = newTarget,
            )
        }
    }

    fun updateSelection(animeList: List<Anime>) {
        mutableState.update { state ->
            if (!state.isSelectAllMode) return@update state
            val currentIds = state.selection.map { it.id }.toSet()
            val newItems = animeList.filter { it.id !in currentIds }
            if (newItems.isEmpty()) return@update state

            state.copy(selection = state.selection.addAll(newItems))
        }
    }

    fun setTargetCount(count: Int) {
        mutableState.update { it.copy(targetCount = count) }
    }

    fun invertSelection(animeList: List<Anime>) {
        mutableState.update { state ->
            val newSelection = state.selection.mutate { list ->
                animeList.forEach { anime ->
                    val index = list.indexOfFirst { it.id == anime.id }
                    if (index != -1) {
                        list.removeAt(index)
                    } else {
                        list.add(anime)
                    }
                }
            }
            // Invert selection turns off select all mode usually as it's a specific manual action
            state.copy(selection = newSelection, isSelectAllMode = false, targetCount = 0, lastSelectedIndex = null)
        }
    }

    fun clearSelection() {
        mutableState.update { it.copy(selection = persistentListOf(), isSelectAllMode = false, targetCount = 0, lastSelectedIndex = null) }
    }

    fun addSelectionToLibrary() {
        val selection = state.value.selection
        val favoriteIds = state.value.favoriteIds
        screenModelScope.launch {
            val categories = getCategories()
            val defaultCategoryId = libraryPreferences.defaultCategory().get()
            val defaultCategory = categories.find { it.id == defaultCategoryId.toLong() }

            val toAdd = selection.filter { it.id !in favoriteIds }
            if (toAdd.isEmpty()) {
                clearSelection()
                return@launch
            }

            if (defaultCategory != null || defaultCategoryId == 0 || categories.isEmpty()) {
                val categoryIds = defaultCategory?.let { listOf(it.id) } ?: emptyList()
                toAdd.forEach { anime ->
                    moveAnimeToCategories(anime, categoryIds)
                    changeAnimeFavorite(anime)
                }
            } else {
                // Just add to default if no specific category chosen yet,
                // but usually we should show category dialog for the first one and apply to all if multiple
                // For simplicity and to fix the "only one added" bug, we'll show the dialog for the whole selection
                val preselectedIds = emptyList<Long>() // New additions
                setDialog(
                    Dialog.ChangeAnimeCategory(
                        toAdd, // Pass the whole selection
                        categories.mapAsCheckboxState { it.id in preselectedIds }.toImmutableList(),
                    ),
                )
            }
            if (state.value.dialog !is Dialog.ChangeAnimeCategory) {
                clearSelection()
            }
        }
    }

    fun removeSelectionFromLibrary() {
        val selection = state.value.selection
        val favoriteIds = state.value.favoriteIds
        screenModelScope.launch {
            selection.forEach { anime ->
                if (anime.id in favoriteIds) {
                    // Force set favorite to false to ensure removal
                    val newAnime = anime.copy(
                        favorite = false,
                        dateAdded = 0,
                    )
                    newAnime.removeCovers(coverCache)
                    updateAnime.await(newAnime.toAnimeUpdate())
                }
            }
            clearSelection()
        }
    }

    /**
     * Get user categories.
     *
     * @return List of categories, not including the default category
     */
    suspend fun getCategories(): List<Category> {
        return getCategories.subscribe()
            .firstOrNull()
            ?.filterNot { it.isSystemCategory }
            .orEmpty()
    }

    suspend fun getDuplicateAnimelibAnime(anime: Anime): Anime? {
        return getDuplicateAnimelibAnime.await(anime).getOrNull(0)
    }

    private fun moveAnimeToCategories(anime: Anime, vararg categories: Category) {
        moveAnimeToCategories(anime, categories.filter { it.id != 0L }.map { it.id })
    }

    fun moveAnimeToCategories(anime: Anime, categoryIds: List<Long>) {
        screenModelScope.launchIO {
            setAnimeCategories.await(
                mangaId = anime.id,
                categoryIds = categoryIds.toList(),
            )
        }
    }

    fun openFilterSheet() {
        setDialog(Dialog.Filter)
    }

    fun setDialog(dialog: Dialog?) {
        mutableState.update { it.copy(dialog = dialog) }
    }

    fun setToolbarQuery(query: String?) {
        mutableState.update { it.copy(toolbarQuery = query) }
    }

    sealed class Listing(open val query: String?, open val filters: FilterList) {
        data object Popular : Listing(
            query = GetRemoteAnime.QUERY_POPULAR,
            filters = FilterList(),
        )
        data object Latest : Listing(
            query = GetRemoteAnime.QUERY_LATEST,
            filters = FilterList(),
        )
        data class Search(
            override val query: String?,
            override val filters: FilterList,
            val randomId: Long = Math.random().toLong(),
        ) : Listing(
            query = query,
            filters = filters,
        )

        companion object {
            fun valueOf(query: String?): Listing {
                return when (query) {
                    GetRemoteAnime.QUERY_POPULAR -> Popular
                    GetRemoteAnime.QUERY_LATEST -> Latest
                    else -> Search(query = query, filters = FilterList()) // filters are filled in later
                }
            }
        }
    }

    sealed interface Dialog {
        data object Filter : Dialog
        data class RemoveAnime(val anime: Anime) : Dialog
        data class AddDuplicateAnime(val anime: Anime, val duplicate: Anime) : Dialog
        data class ChangeAnimeCategory(
            val animes: List<Anime>,
            val initialSelection: ImmutableList<CheckboxState.State<Category>>,
        ) : Dialog
        data class Migrate(val newAnime: Anime, val oldAnime: Anime) : Dialog
        data object SaveSearch : Dialog
        data class DeleteSavedSearch(val savedSearch: SavedSearch) : Dialog
    }

    @Immutable
    data class State(
        val listing: Listing,
        val filters: FilterList = FilterList(),
        val filtersId: Long = java.util.Random().nextLong(),
        val toolbarQuery: String? = null,
        val savedSearches: ImmutableList<SavedSearch> = persistentListOf(),
        val currentSavedSearch: SavedSearch? = null,
        val dialog: Dialog? = null,
        val selection: PersistentList<Anime> = persistentListOf(),
        val isSelectAllMode: Boolean = false,
        val favoriteIds: ImmutableSet<Long> = persistentSetOf(),
        val targetCount: Int = 0,
        val lastSelectedIndex: Int? = null,
    ) {
        val isUserQuery get() = listing is Listing.Search && !listing.query.isNullOrEmpty()
        val selectionMode get() = selection.isNotEmpty()
    }
}
