package eu.kanade.tachiyomi.ui.browse.source.globalsearch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.produceState
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import tachiyomi.domain.anime.model.toDomainAnime
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.util.ioCoroutineScope
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.mutate
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.core.common.preference.toggle
import tachiyomi.domain.anime.interactor.GetAnime
import tachiyomi.domain.anime.interactor.NetworkToLocalAnime
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

abstract class SearchScreenModel(
    initialState: State = State(),
    sourcePreferences: SourcePreferences = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val extensionManager: ExtensionManager = Injekt.get(),
    private val networkToLocalAnime: NetworkToLocalAnime = Injekt.get(),
    private val getAnime: GetAnime = Injekt.get(),
    private val preferences: SourcePreferences = Injekt.get(),
) : StateScreenModel<SearchScreenModel.State>(initialState) {

    // Bounded parallelism without leaking a fixed thread pool:
    // the previous newFixedThreadPool(5) was never shut down.
    private val coroutineDispatcher = Dispatchers.IO.limitedParallelism(5)
    private var searchJob: Job? = null

    private val enabledLanguages = sourcePreferences.enabledLanguages().get()
    private val disabledSources = sourcePreferences.disabledSources().get()
    protected val pinnedSources = sourcePreferences.pinnedSources().get()

    private var lastQuery: String? = null
    private var lastSourceFilter: SourceFilter? = null

    protected var extensionFilter: String? = null

    private val sortComparator = { map: Map<CatalogueSource, SearchItemResult> ->
        compareBy<CatalogueSource>(
            { (map[it] as? SearchItemResult.Success)?.isEmpty ?: true },
            { "${it.id}" !in pinnedSources },
            { "${it.name.lowercase()} (${it.lang})" },
        )
    }

    init {
        screenModelScope.launch {
            preferences.globalSearchFilterState().changes().collectLatest { state ->
                mutableState.update { it.copy(onlyShowHasResults = state) }
            }
        }
    }

    @Composable
    fun getAnime(initialAnime: Anime): androidx.compose.runtime.State<Anime> {
        return produceState(initialValue = initialAnime) {
            getAnime.subscribe(initialAnime.url, initialAnime.source)
                .filterNotNull()
                .collectLatest { anime ->
                    value = anime
                }
        }
    }

    open fun getEnabledSources(): List<CatalogueSource> {
        return sourceManager.getCatalogueSources()
            .filter { it.lang in enabledLanguages && "${it.id}" !in disabledSources }
            .sortedWith(
                compareBy(
                    { "${it.id}" !in pinnedSources },
                    { "${it.name.lowercase()} (${it.lang})" },
                ),
            )
    }

    private fun getSelectedSources(): List<CatalogueSource> {
        val enabledSources = getEnabledSources()

        val filter = extensionFilter
        if (filter.isNullOrEmpty()) {
            return enabledSources
        }

        return extensionManager.installedExtensionsFlow.value
            .filter { it: eu.kanade.tachiyomi.extension.model.Extension.Installed -> it.pkgName == filter }
            .flatMap { it: eu.kanade.tachiyomi.extension.model.Extension.Installed -> it.sources }
            .filterIsInstance<CatalogueSource>()
            .filter { it in enabledSources }
    }

    fun updateSearchQuery(query: String?) {
        mutableState.update { it.copy(searchQuery = query) }
    }

    fun setSourceFilter(filter: SourceFilter) {
        mutableState.update { it.copy(sourceFilter = filter) }
        search()
    }

    fun toggleFilterResults() {
        preferences.globalSearchFilterState().toggle()
    }

    fun search() {
        val query = state.value.searchQuery
        val sourceFilter = state.value.actualSourceFilter

        if (query.isNullOrBlank()) return
        val sameQuery = this.lastQuery == query
        if (sameQuery && this.lastSourceFilter == sourceFilter) return

        this.lastQuery = query
        this.lastSourceFilter = sourceFilter

        val sources = getSelectedSources()

        // Reuse previous results if possible
        if (sameQuery) {
            val existingResults = state.value.items
            updateItems(
                sources
                    .associateWith { existingResults[it] ?: SearchItemResult.Loading }
                    .toPersistentMap(),
            )
        } else {
            updateItems(
                sources
                    .associateWith { SearchItemResult.Loading }
                    .toPersistentMap(),
            )
        }

        searchJob = ioCoroutineScope.launch {
            sources.map { source ->
                async {
                    if (state.value.items[source] !is SearchItemResult.Loading) {
                        return@async
                    }
                    try {
                        val page = withContext(coroutineDispatcher) {
                            source.getSearchAnime(1, query, FilterList())
                        }

                        val titles = page.animes.map {
                            networkToLocalAnime.await(it.toDomainAnime(source.id))
                        }

                        if (isActive) {
                            updateItem(source, SearchItemResult.Success(titles))
                        }
                    } catch (e: Exception) {
                        if (isActive) {
                            updateItem(source, SearchItemResult.Error(e))
                        }
                    }
                }
            }
                .awaitAll()
        }
    }

    private fun updateItems(items: PersistentMap<CatalogueSource, SearchItemResult>) {
        mutableState.update {
            it.copy(
                items = items
                    .toSortedMap(sortComparator(items))
                    .toPersistentMap(),
            )
        }
    }

    private fun updateItem(source: CatalogueSource, result: SearchItemResult) {
        val newItems = state.value.items.mutate {
            it[source] = result
        }
        updateItems(newItems)
    }

    @Immutable
    data class State(
        val fromSourceId: Long? = null,
        val searchQuery: String? = null,
        val sourceFilter: SourceFilter? = null,
        val onlyShowHasResults: Boolean = false,
        val items: PersistentMap<CatalogueSource, SearchItemResult> = persistentMapOf(),
    ) {
        val actualSourceFilter: SourceFilter
            get() = sourceFilter ?: if (Injekt.get<SourcePreferences>().pinnedSources().get().isEmpty()) {
                SourceFilter.All
            } else {
                SourceFilter.PinnedOnly
            }

        val progress: Int = items.count { it.value !is SearchItemResult.Loading }
        val total: Int = items.size
        val filteredItems = items.filter { (_, result) -> result.isVisible(onlyShowHasResults) }
    }
}

enum class SourceFilter {
    All,
    PinnedOnly,
}

sealed interface SearchItemResult {
    data object Loading : SearchItemResult

    data class Error(
        val throwable: Throwable,
    ) : SearchItemResult

    data class Success(
        val result: List<Anime>,
    ) : SearchItemResult {
        val isEmpty: Boolean
            get() = result.isEmpty()
    }

    fun isVisible(onlyShowHasResults: Boolean): Boolean {
        return !onlyShowHasResults || (this is Success && !this.isEmpty)
    }
}
