package eu.kanade.tachiyomi.ui.browse.source

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.interactor.GetEnabledSources
import eu.kanade.domain.source.interactor.ToggleSource
import eu.kanade.domain.source.interactor.ToggleSourcePin
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.browse.SourceUiModel
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.network.model.NodeStatus
import eu.kanade.tachiyomi.util.system.LAST_USED_KEY
import eu.kanade.tachiyomi.util.system.PINNED_KEY
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.source.interactor.GetFeedSavedSearchCategories
import tachiyomi.domain.source.interactor.InsertFeedSavedSearch
import tachiyomi.domain.source.interactor.InsertFeedSavedSearchCategory
import tachiyomi.domain.source.model.FeedSavedSearch
import tachiyomi.domain.source.model.FeedSavedSearchCategory
import tachiyomi.domain.source.model.Pin
import tachiyomi.domain.source.model.Source
import tachiyomi.domain.source.service.SourceHealthCache
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.TreeMap

class SourcesScreenModel(
    private val preferences: BasePreferences = Injekt.get(),
    private val sourcePreferences: SourcePreferences = Injekt.get(),
    private val getEnabledSources: GetEnabledSources = Injekt.get(),
    private val toggleSource: ToggleSource = Injekt.get(),
    private val toggleSourcePin: ToggleSourcePin = Injekt.get(),
    private val insertFeedSavedSearch: InsertFeedSavedSearch = Injekt.get(),
    private val getFeedSavedSearchCategories: GetFeedSavedSearchCategories = Injekt.get(),
    private val insertFeedSavedSearchCategory: InsertFeedSavedSearchCategory = Injekt.get(),
    private val extensionManager: ExtensionManager = Injekt.get(),
    private val mapper: SourceUiModelMapper = SourceUiModelMapper(),
) : StateScreenModel<SourcesScreenModel.State>(State()) {

    private val _events = Channel<Event>(Int.MAX_VALUE)
    val events = _events.receiveAsFlow()

    init {
        screenModelScope.launch {
            combine(
                state.map { it.searchQuery }.distinctUntilChanged().debounce(250L),
                state.map { it.nsfwOnly }.distinctUntilChanged(),
                getEnabledSources.subscribe(),
                extensionManager.installedExtensionsFlow,
                SourceHealthCache.healthMap,
            ) { query, nsfwOnly, sources, extensions, healthMap ->
                // Offload CPU-bound sorting and formatting to Default dispatcher
                withContext(Dispatchers.Default) {
                    collectLatestAnimeSources(sources, extensions, healthMap, query, nsfwOnly)
                }
            }.catch {
                logcat(LogPriority.ERROR, it)
                _events.send(Event.FailedFetchingSources)
            }.collectLatest {}
        }

        screenModelScope.launchIO {
            getFeedSavedSearchCategories.subscribe().collectLatest { categories ->
                mutableState.update { it.copy(categories = categories.toImmutableList()) }
            }
        }

        screenModelScope.launchIO {
            sourcePreferences.hideLatest().changes().collectLatest { hideLatest ->
                mutableState.update { it.copy(hideLatest = hideLatest) }
            }
        }
    }

    private suspend fun collectLatestAnimeSources(
        sources: List<Source>,
        extensions: List<eu.kanade.tachiyomi.extension.model.Extension.Installed>,
        healthMap: Map<Long, NodeStatus>,
        query: String?,
        nsfwOnly: Boolean,
    ) {
        // Map source IDs to their extension's NSFW status for reliable filtering
        val nsfwSourceIds = extensions.flatMap { ext ->
            if (ext.isNsfw) ext.sources.map { it.id } else emptyList()
        }.toSet()

        yield()

        val filteredSources = sources.filter { source ->
            val matchesQuery = query.isNullOrBlank() || source.name.contains(query, ignoreCase = true)
            val isNsfw = nsfwSourceIds.contains(source.id) || source.isNsfw
            val matchesNsfw = !nsfwOnly || isNsfw
            matchesQuery && matchesNsfw
        }.distinctBy { it.key() }

        val map = TreeMap<String, MutableList<Source>> { d1, d2 ->
            when {
                d1 == LAST_USED_KEY && d2 != LAST_USED_KEY -> -1
                d2 == LAST_USED_KEY && d1 != LAST_USED_KEY -> 1
                d1 == PINNED_KEY && d2 != PINNED_KEY -> -1
                d2 == PINNED_KEY && d1 != PINNED_KEY -> 1
                d1 == "" && d2 != "" -> 1
                d2 == "" && d1 != "" -> -1
                else -> d1.compareTo(d2)
            }
        }

        val byLang = filteredSources.groupByTo(map) {
            when {
                it.isUsedLast -> LAST_USED_KEY
                Pin.Actual in it.pin -> PINNED_KEY
                else -> it.lang
            }
        }

        yield()

        val items = byLang
            .flatMap { (lang, langSources) ->
                buildList<SourceUiModel> {
                    add(mapper.mapHeader(lang))
                    langSources.forEach { source ->
                        add(
                            mapper.map(
                                source = source,
                                headerKey = lang,
                                isNsfw = nsfwSourceIds.contains(source.id) || source.isNsfw,
                                status = healthMap[source.id],
                            ),
                        )
                    }
                }
            }
            .toImmutableList()

        mutableState.update { state ->
            state.copy(
                isLoading = false,
                items = items,
            )
        }
    }

    fun search(query: String?) {
        mutableState.update { it.copy(searchQuery = query) }
    }

    fun toggleNsfwOnly() {
        mutableState.update { it.copy(nsfwOnly = !it.nsfwOnly) }
    }

    fun toggleSource(source: Source) {
        toggleSource.await(source)
    }

    fun togglePin(source: Source) {
        toggleSourcePin.await(source)
    }

    fun onAddToFeedClicked(source: Source) {
        val categories = state.value.categories
        if (categories.isEmpty()) {
            screenModelScope.launchIO {
                insertFeedSavedSearchCategory.await("Global")
                val newCategories = getFeedSavedSearchCategories.await()
                val globalId = newCategories.firstOrNull()?.id ?: 1L
                addToFeed(source, globalId)
            }
            closeDialog()
        } else if (categories.size == 1) {
            addToFeed(source, categories.first().id)
            closeDialog()
        } else {
            mutableState.update { it.copy(dialog = Dialog.FeedCategorySelect(source)) }
        }
    }

    fun addToFeed(source: Source, categoryId: Long) {
        screenModelScope.launchIO {
            try {
                val currentFeed = insertFeedSavedSearch.feedSavedSearchRepository.getGlobal(categoryId)
                if (currentFeed.any { it.source == source.id && it.savedSearch == null }) {
                    return@launchIO
                }
                val nextOrder = (currentFeed.maxOfOrNull { it.feedOrder } ?: -1) + 1
                insertFeedSavedSearch.await(
                    FeedSavedSearch(
                        id = -1,
                        source = source.id,
                        savedSearch = null,
                        global = true,
                        feedOrder = nextOrder,
                        type = FeedSavedSearch.Type.Latest.value,
                        category = categoryId,
                    ),
                )
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to add source ${source.name} to feed category $categoryId" }
            }
        }
    }

    fun showSourceDialog(source: Source) {
        mutableState.update { it.copy(dialog = Dialog.SourceOptions(source)) }
    }

    fun closeDialog() {
        mutableState.update { it.copy(dialog = null) }
    }

    sealed interface Event {
        data object FailedFetchingSources : Event
    }

    sealed interface Dialog {
        val source: Source
        data class SourceOptions(override val source: Source) : Dialog
        data class FeedCategorySelect(override val source: Source) : Dialog
    }

    @Immutable
    data class State(
        val dialog: Dialog? = null,
        val isLoading: Boolean = true,
        val items: ImmutableList<SourceUiModel> = persistentListOf(),
        val categories: ImmutableList<FeedSavedSearchCategory> = persistentListOf(),
        val searchQuery: String? = null,
        val nsfwOnly: Boolean = false,
        val hideLatest: Boolean = false,
    ) {
        val isEmpty = items.isEmpty()
    }
}
