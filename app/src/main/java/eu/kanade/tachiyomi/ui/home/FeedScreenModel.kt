package eu.kanade.tachiyomi.ui.home

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.ui.browse.source.browse.FilterSerializer
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.anime.interactor.GetAnime
import tachiyomi.domain.anime.interactor.NetworkToLocalAnime
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.model.toDomainAnime
import tachiyomi.domain.history.interactor.LogActivity
import tachiyomi.domain.history.model.ActivityLog
import tachiyomi.domain.source.interactor.GetFeedSavedSearchCategories
import tachiyomi.domain.source.interactor.GetFeedSavedSearchGlobal
import tachiyomi.domain.source.interactor.GetSavedSearchGlobalFeed
import tachiyomi.domain.source.interactor.InsertFeedSavedSearchCategory
import tachiyomi.domain.source.model.FeedSavedSearch
import tachiyomi.domain.source.model.FeedSavedSearchCategory
import tachiyomi.domain.source.model.SavedSearch
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class FeedScreenModel(
    private val sourceManager: SourceManager = Injekt.get(),
    private val getFeedSavedSearchGlobal: GetFeedSavedSearchGlobal = Injekt.get(),
    private val getSavedSearchGlobalFeed: GetSavedSearchGlobalFeed = Injekt.get(),
    private val networkToLocalAnime: NetworkToLocalAnime = Injekt.get(),
    private val getAnime: GetAnime = Injekt.get(),
    private val getFeedSavedSearchCategories: GetFeedSavedSearchCategories = Injekt.get(),
    private val insertFeedSavedSearchCategory: InsertFeedSavedSearchCategory = Injekt.get(),
    private val updateFeedSavedSearch: tachiyomi.domain.source.interactor.UpdateFeedSavedSearch = Injekt.get(),
    private val logActivity: LogActivity = Injekt.get(),
    private val filterSerializer: FilterSerializer = Injekt.get(),
) : StateScreenModel<FeedScreenModel.State>(State()) {

    private val feedJobs = java.util.concurrent.ConcurrentHashMap<Long, kotlinx.coroutines.Job>()

    init {
        screenModelScope.launchIO {
            // Subscribe FIRST to ensure we don't miss any category updates (e.g. from Global creation)
            getFeedSavedSearchCategories.subscribe()
                .onEach { updatedCategories ->
                    val categoriesToUse = if (updatedCategories.isEmpty()) {
                        // Create Global if it doesn't exist, but don't block state update
                        screenModelScope.launchIO {
                            insertFeedSavedSearchCategory.await("Global")
                        }
                        // Use a temporary Global category to avoid blank screen
                        persistentListOf(FeedSavedSearchCategory(id = 1, name = "Global", order = 0))
                    } else {
                        updatedCategories.toImmutableList()
                    }

                    mutableState.update { state ->
                        val newItems = state.items.toMutableMap()
                        categoriesToUse.forEach { category ->
                            if (!newItems.containsKey(category.id)) {
                                newItems[category.id] = persistentListOf()
                            }
                        }
                        state.copy(
                            categories = categoriesToUse,
                            items = newItems.toImmutableMap(),
                        )
                    }

                    if (updatedCategories.isNotEmpty()) {
                        setupFeedSubscriptions(updatedCategories)
                    }
                }
                .launchIn(screenModelScope)
        }
    }

    fun onAnimeClicked(anime: Anime, feedId: Long?) {
        screenModelScope.launchIO {
            logActivity.await(anime.source, ActivityLog.TYPE_OPEN, feedId = feedId, animeId = anime.id)
        }
    }

    private fun setupFeedSubscriptions(categories: List<FeedSavedSearchCategory>) {
        // Cancel jobs for removed categories
        val categoryIds = categories.map { it.id }.toSet()
        val removedIds = feedJobs.keys - categoryIds
        removedIds.forEach { id ->
            feedJobs[id]?.cancel()
            feedJobs.remove(id)
        }

        // Start jobs for new categories or existing ones if not running
        categories.forEach { category ->
            if (!feedJobs.containsKey(category.id)) {
                feedJobs[category.id] = screenModelScope.launchIO {
                    combine(
                        getFeedSavedSearchGlobal.subscribe(category.id),
                        sourceManager.isInitialized,
                    ) { feedSavedSearches, isInitialized ->
                        feedSavedSearches to isInitialized
                    }.collectLatest { (feedSavedSearches, isInitialized) ->
                        if (!isInitialized) return@collectLatest

                        // 1. Establish structural placeholders immediately and CLEAN UP removed feeds
                        // This ensures that even if sources aren't loaded, we show the containers
                        val savedSearches = getSavedSearchGlobalFeed.await(category.id)
                        val initialItems = feedSavedSearches.mapNotNull { feed ->
                            val source = sourceManager.get(feed.source) as? AnimeCatalogueSource

                            // Preserve existing anime list if it exists to avoid flickering
                            val existingAnime = mutableState.value.items[category.id]?.find { it.feed.id == feed.id }?.animeList ?: persistentListOf<Anime>()

                            FeedItem(
                                feed = feed,
                                source = source ?: return@mapNotNull null,
                                savedSearch = savedSearches.find { it.id == feed.savedSearch },
                                animeList = existingAnime,
                                isLoading = true,
                            )
                        }.toImmutableList()

                        mutableState.update { state ->
                            val newItemsMap = state.items.toMutableMap()
                            newItemsMap[category.id] = initialItems
                            state.copy(items = newItemsMap.toImmutableMap())
                        }

                        // 2. Load content in parallel with TIMEOUT and NO RETRIES to prevent hangs
                        coroutineScope {
                            feedSavedSearches.forEach { feed ->
                                launch {
                                    val source = sourceManager.get(feed.source) as? AnimeCatalogueSource
                                    if (source == null) return@launch

                                    try {
                                        // Use withTimeout to ensure a slow source cannot hang the UI infinitely
                                        kotlinx.coroutines.withTimeout(15000L) {
                                            val results = when (FeedSavedSearch.Type.from(feed.type)) {
                                                FeedSavedSearch.Type.Latest -> {
                                                    try {
                                                        source.getLatestUpdates(1).animes
                                                    } catch (e: Exception) {
                                                        source.getPopularAnime(1).animes
                                                    }
                                                }
                                                FeedSavedSearch.Type.Popular -> source.getPopularAnime(1).animes
                                                FeedSavedSearch.Type.SavedSearch -> {
                                                    val savedSearch = savedSearches.find { it.id == feed.savedSearch }
                                                    if (savedSearch != null) {
                                                        val filters = source.getFilterList()
                                                        savedSearch.filtersJson?.let {
                                                            filterSerializer.deserialize(filters, it)
                                                        }
                                                        try {
                                                            source.getSearchAnime(1, savedSearch.query ?: "", filters).animes
                                                        } catch (e: Exception) {
                                                            logcat(LogPriority.ERROR, e) { "Saved search failed for source: ${source.name} (ID: ${source.id})" }
                                                            emptyList()
                                                        }
                                                    } else {
                                                        emptyList()
                                                    }
                                                }
                                            }

                                            val animeList = results.map {
                                                async {
                                                    val domainAnime = it.toDomainAnime(source.id)
                                                    networkToLocalAnime.await(domainAnime)
                                                }
                                            }.awaitAll().filterNotNull().distinctBy { it.id }.toImmutableList()

                                            // Delta tracking for stats (PERSISTENT)
                                            val currentTopUrl = results.firstOrNull()?.url
                                            val previousTopUrl = feed.lastTopUrl
                                            if (currentTopUrl != null && currentTopUrl != previousTopUrl) {
                                                val newItemsCount = if (previousTopUrl == null) {
                                                    results.size
                                                } else {
                                                    val index = results.indexOfFirst { it.url == previousTopUrl }
                                                    if (index == -1) results.size else index
                                                }

                                                if (newItemsCount > 0) {
                                                    logActivity.await(source.id, ActivityLog.TYPE_FEED_UPDATE, feedId = feed.id, count = newItemsCount.toLong())
                                                }
                                                updateFeedSavedSearch.await(
                                                    tachiyomi.domain.source.model.FeedSavedSearchUpdate(
                                                        id = feed.id,
                                                        lastTopUrl = currentTopUrl,
                                                    ),
                                                )
                                            }

                                            mutableState.update { state ->
                                                val currentCategoryItems = state.items[category.id] ?: initialItems
                                                val updatedItems = currentCategoryItems.map { item ->
                                                    if (item.feed.id == feed.id) {
                                                        item.copy(animeList = animeList, isLoading = false)
                                                    } else {
                                                        item
                                                    }
                                                }.toImmutableList()

                                                val newItemsMap = state.items.toMutableMap()
                                                newItemsMap[category.id] = updatedItems
                                                state.copy(items = newItemsMap.toImmutableMap())
                                            }
                                        }
                                    } catch (e: Exception) {
                                        logcat(LogPriority.ERROR, e) { "Feed fetch failed or timed out for ${source.name}" }
                                        // On failure, update state to stop loading
                                        mutableState.update { state ->
                                            val currentCategoryItems = state.items[category.id] ?: initialItems
                                            val updatedItems = currentCategoryItems.map { item ->
                                                if (item.feed.id == feed.id) {
                                                    item.copy(isLoading = false)
                                                } else {
                                                    item
                                                }
                                            }.toImmutableList()

                                            val newItemsMap = state.items.toMutableMap()
                                            newItemsMap[category.id] = updatedItems
                                            state.copy(items = newItemsMap.toImmutableMap())
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Immutable
    data class State(
        val items: ImmutableMap<Long, ImmutableList<FeedItem>> = persistentMapOf(),
        val categories: ImmutableList<FeedSavedSearchCategory> = persistentListOf(),
    ) {
        val isLoading: Boolean
            get() = items.isEmpty() && categories.isNotEmpty() // Simplified loading check
    }

    @Immutable
    data class FeedItem(
        val feed: FeedSavedSearch,
        val source: AnimeCatalogueSource,
        val savedSearch: SavedSearch?,
        val animeList: ImmutableList<Anime>,
        val isLoading: Boolean = false,
    )
}
