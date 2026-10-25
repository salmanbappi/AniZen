package eu.kanade.tachiyomi.ui.home

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.source.interactor.DeleteFeedSavedSearchById
import tachiyomi.domain.source.interactor.DeleteFeedSavedSearchCategory
import tachiyomi.domain.source.interactor.GetFeedSavedSearchCategories
import tachiyomi.domain.source.interactor.GetFeedSavedSearchGlobal
import tachiyomi.domain.source.interactor.GetSavedSearchGlobalFeed
import tachiyomi.domain.source.interactor.InsertFeedSavedSearchCategory
import tachiyomi.domain.source.interactor.ReorderFeed
import tachiyomi.domain.source.interactor.UpdateFeedSavedSearch
import tachiyomi.domain.source.interactor.UpdateFeedSavedSearchCategory
import tachiyomi.domain.source.model.FeedSavedSearch
import tachiyomi.domain.source.model.FeedSavedSearchCategory
import tachiyomi.domain.source.model.FeedSavedSearchUpdate
import tachiyomi.domain.source.model.SavedSearch
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class FeedManageScreenModel(
    private val sourceManager: SourceManager = Injekt.get(),
    private val getFeedSavedSearchGlobal: GetFeedSavedSearchGlobal = Injekt.get(),
    private val getSavedSearchGlobalFeed: GetSavedSearchGlobalFeed = Injekt.get(),
    private val reorderFeed: ReorderFeed = Injekt.get(),
    private val deleteFeedSavedSearchById: DeleteFeedSavedSearchById = Injekt.get(),
    private val updateFeedSavedSearch: UpdateFeedSavedSearch = Injekt.get(),
    private val getSavedSearchBySourceId: tachiyomi.domain.source.interactor.GetSavedSearchBySourceId = Injekt.get(),
    private val insertFeedSavedSearch: tachiyomi.domain.source.interactor.InsertFeedSavedSearch = Injekt.get(),
    private val getFeedSavedSearchCategories: GetFeedSavedSearchCategories = Injekt.get(),
    private val insertFeedSavedSearchCategory: InsertFeedSavedSearchCategory = Injekt.get(),
    private val updateFeedSavedSearchCategory: UpdateFeedSavedSearchCategory = Injekt.get(),
    private val deleteFeedSavedSearchCategory: DeleteFeedSavedSearchCategory = Injekt.get(),
) : StateScreenModel<FeedManageScreenModel.State>(State()) {

    private val feedJobs = java.util.concurrent.ConcurrentHashMap<Long, Job>()

    init {
        screenModelScope.launchIO {
            getFeedSavedSearchCategories.subscribe().collectLatest { categories ->
                mutableState.update { it.copy(categories = categories.toImmutableList()) }
                setupFeedSubscriptions(categories)
            }
        }
    }

    private fun setupFeedSubscriptions(categories: List<FeedSavedSearchCategory>) {
        val categoryIds = categories.map { it.id }.toSet()
        val removedIds = feedJobs.keys - categoryIds
        removedIds.forEach { id ->
            feedJobs[id]?.cancel()
            feedJobs.remove(id)
        }

        categories.forEach { category ->
            if (!feedJobs.containsKey(category.id)) {
                feedJobs[category.id] = screenModelScope.launchIO {
                    combine(
                        getFeedSavedSearchGlobal.subscribe(category.id),
                        sourceManager.isInitialized,
                        ::Pair,
                    ).collectLatest { (feedSavedSearches, isInitialized) ->
                        if (!isInitialized) return@collectLatest

                        val savedSearches = getSavedSearchGlobalFeed.await(category.id)

                        val items = feedSavedSearches.map { feed ->
                            val source = sourceManager.get(feed.source)
                            val savedSearch = savedSearches.find { it.id == feed.savedSearch }

                            FeedItem(
                                feed = feed,
                                title = source?.name ?: "Unknown",
                                subtitle = savedSearch?.name ?: FeedSavedSearch.Type.from(feed.type).name,
                                source = source,
                            )
                        }

                        mutableState.update { state ->
                            val newItems = state.items.toMutableMap()
                            newItems[category.id] = items.toImmutableList()
                            state.copy(items = newItems.toImmutableMap())
                        }
                    }
                }
            }
        }
    }

    fun createCategory(name: String) {
        screenModelScope.launchIO {
            insertFeedSavedSearchCategory.await(name)
        }
    }

    fun deleteCategory(categoryId: Long) {
        screenModelScope.launchIO {
            deleteFeedSavedSearchCategory.await(categoryId)
        }
    }

    fun renameCategory(categoryId: Long, name: String) {
        screenModelScope.launchIO {
            updateFeedSavedSearchCategory.await(categoryId, name)
        }
    }

    suspend fun getSourceSavedSearches(sourceId: Long): List<SavedSearch> {
        return getSavedSearchBySourceId.await(sourceId)
    }

    fun updateFeed(feed: FeedSavedSearch, type: FeedSavedSearch.Type, savedSearchId: Long?) {
        screenModelScope.launchIO {
            updateFeedSavedSearch.await(
                FeedSavedSearchUpdate(
                    id = feed.id,
                    searchType = type.value.toLong(),
                    savedSearch = savedSearchId,
                    deleteSavedSearch = savedSearchId == null,
                ),
            )
        }
    }

    fun updateFeedCategory(feed: FeedSavedSearch, newCategoryId: Long) {
        screenModelScope.launchIO {
            // Get max order in new category to append at end
            val targetFeed = getFeedSavedSearchGlobal.await(newCategoryId)
            val nextOrder = (targetFeed.maxOfOrNull { it.feedOrder } ?: -1) + 1

            // Delete from old category (or update)
            // UpdateFeedSavedSearch supports updating category?
            // FeedSavedSearchUpdate needs category field?
            // Let's check FeedSavedSearchUpdate.kt
            // If not present, I might need to delete and insert.
            // But Insert generates new ID.
            // If I want to move, I should update.
            // I'll check FeedSavedSearchUpdate model first.
            // Assuming I can't update category easily without DB support,
            // I'll check if I added category to Update model.

            // Checking FeedSavedSearchUpdate.kt (from previous context or file)
            // It seems I only added category to FeedSavedSearch.
            // I should check if UpdateFeedSavedSearch supports it.
            // If not, I'll delete and re-insert.

            deleteFeedSavedSearchById.await(feed.id)
            insertFeedSavedSearch.await(
                feed.copy(
                    id = -1,
                    category = newCategoryId,
                    feedOrder = nextOrder,
                ),
            )
        }
    }

    fun duplicate(feed: FeedSavedSearch) {
        screenModelScope.launchIO {
            val currentFeed = getFeedSavedSearchGlobal.await(feed.category)
            val nextOrder = (currentFeed.maxOfOrNull { it.feedOrder } ?: -1) + 1
            insertFeedSavedSearch.await(
                feed.copy(
                    id = -1,
                    feedOrder = nextOrder,
                ),
            )
        }
    }

    fun moveUp(feed: FeedSavedSearch) {
        screenModelScope.launchIO {
            reorderFeed.moveUp(feed)
        }
    }

    fun moveDown(feed: FeedSavedSearch) {
        screenModelScope.launchIO {
            reorderFeed.moveDown(feed)
        }
    }

    fun reorder(categoryId: Long, items: List<FeedSavedSearch>) {
        screenModelScope.launchIO {
            val updates = items.mapIndexed { index, feed ->
                FeedSavedSearchUpdate(
                    id = feed.id,
                    feedOrder = index.toLong(),
                )
            }
            reorderFeed.execute(updates)
        }
    }

    fun delete(feed: FeedSavedSearch) {
        screenModelScope.launchIO {
            deleteFeedSavedSearchById.await(feed.id)
        }
    }

    @Immutable
    data class State(
        val items: ImmutableMap<Long, ImmutableList<FeedItem>> = persistentMapOf(),
        val categories: ImmutableList<FeedSavedSearchCategory> = persistentListOf(),
    )

    @Immutable
    data class FeedItem(
        val feed: FeedSavedSearch,
        val title: String,
        val subtitle: String,
        val source: eu.kanade.tachiyomi.source.Source?,
    )
}
