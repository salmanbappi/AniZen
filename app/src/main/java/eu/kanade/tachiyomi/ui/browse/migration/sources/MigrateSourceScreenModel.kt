package eu.kanade.tachiyomi.ui.browse.migration.sources

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.source.interactor.GetSourcesWithFavoriteCount
import eu.kanade.domain.source.interactor.SetMigrateSorting
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.browse.SourceUiModel
import eu.kanade.presentation.components.SEARCH_DEBOUNCE_MILLIS
import eu.kanade.tachiyomi.ui.browse.source.SourceUiModelMapper
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.source.model.Pin
import tachiyomi.domain.source.model.Source
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class MigrateSourceScreenModel(
    private val preferences: SourcePreferences = Injekt.get(),
    private val getSourcesWithFavoriteCount: GetSourcesWithFavoriteCount = Injekt.get(),
    private val setMigrateSorting: SetMigrateSorting = Injekt.get(),
    private val mapper: SourceUiModelMapper = SourceUiModelMapper(),
) : StateScreenModel<MigrateSourceScreenModel.State>(State()) {

    private val _channel = Channel<Event>(Int.MAX_VALUE)
    val channel = _channel.receiveAsFlow()

    init {
        screenModelScope.launchIO {
            combine(
                state.map { it.searchQuery }.distinctUntilChanged().debounce(SEARCH_DEBOUNCE_MILLIS),
                getSourcesWithFavoriteCount.subscribe(),
            ) { searchQuery: String?, sourceCounts: List<Pair<Source, Long>> ->
                val queryFilter: (String?) -> ((Pair<Source, Long>) -> Boolean) = { query ->
                    filter@{ pair ->
                        val source = pair.first
                        if (query.isNullOrBlank()) return@filter true
                        query.split(",").any {
                            val input = it.trim()
                            if (input.isEmpty()) return@any false
                            source.name.contains(input, ignoreCase = true) ||
                                source.id == input.toLongOrNull()
                        }
                    }
                }

                val filtered = sourceCounts.filter(queryFilter(searchQuery))

                filtered.map { (source, count) ->
                    mapper.map(source) to count
                }
            }
                .catch {
                    logcat(LogPriority.ERROR, it)
                    _channel.send(Event.FailedFetchingSourcesWithCount)
                }
                .collectLatest { sources ->
                    mutableState.update {
                        it.copy(
                            isLoading = false,
                            items = sources.toImmutableList(),
                        )
                    }
                }
        }

        preferences.migrationSortingDirection().changes()
            .onEach { mutableState.update { state -> state.copy(sortingDirection = it) } }
            .launchIn(screenModelScope)

        preferences.migrationSortingMode().changes()
            .onEach { mutableState.update { state -> state.copy(sortingMode = it) } }
            .launchIn(screenModelScope)
    }

    fun toggleSortingMode() {
        with(state.value) {
            val newMode = when (sortingMode) {
                SetMigrateSorting.Mode.ALPHABETICAL -> SetMigrateSorting.Mode.TOTAL
                SetMigrateSorting.Mode.TOTAL -> SetMigrateSorting.Mode.ALPHABETICAL
            }

            setMigrateSorting.await(newMode, sortingDirection)
        }
    }

    fun toggleSortingDirection() {
        with(state.value) {
            val newDirection = when (sortingDirection) {
                SetMigrateSorting.Direction.ASCENDING -> SetMigrateSorting.Direction.DESCENDING
                SetMigrateSorting.Direction.DESCENDING -> SetMigrateSorting.Direction.ASCENDING
            }

            setMigrateSorting.await(sortingMode, newDirection)
        }
    }

    fun search(query: String?) {
        mutableState.update {
            it.copy(searchQuery = query)
        }
    }

    fun toggleSelection(sourceId: Long) {
        mutableState.update { state ->
            val isSelected = state.selectedSources.contains(sourceId)
            val selectedSources = if (isSelected) {
                state.selectedSources.minus(sourceId)
            } else {
                state.selectedSources.plus(sourceId)
            }
            state.copy(selectedSources = selectedSources.toImmutableSet())
        }
    }

    fun selectAll() {
        mutableState.update { state ->
            val allIds = state.items.map { it.first.source.id }.toSet().toImmutableSet()
            state.copy(selectedSources = allIds)
        }
    }

    fun selectNone() {
        mutableState.update { state ->
            state.copy(selectedSources = persistentSetOf())
        }
    }

    fun matchEnabled() {
        mutableState.update { state ->
            val enabledIds = state.items
                .filter { (item, _) -> !preferences.disabledSources().get().contains(item.source.id.toString()) }
                .map { it.first.source.id }
                .toSet()
                .toImmutableSet()
            state.copy(selectedSources = enabledIds)
        }
    }

    fun matchPinned() {
        mutableState.update { state ->
            val pinnedIds = state.items
                .filter { (item, _) -> item.source.pin != Pin.Pinned }
                .map { it.first.source.id }
                .toSet()
                .toImmutableSet()
            state.copy(selectedSources = pinnedIds)
        }
    }

    @Immutable
    data class State(
        val isLoading: Boolean = true,
        val items: ImmutableList<Pair<SourceUiModel.Item, Long>> = persistentListOf(),
        val selectedSources: ImmutableSet<Long> = persistentSetOf(),
        val sortingMode: SetMigrateSorting.Mode = SetMigrateSorting.Mode.ALPHABETICAL,
        val sortingDirection: SetMigrateSorting.Direction = SetMigrateSorting.Direction.ASCENDING,
        val searchQuery: String? = null,
    ) {
        val isEmpty = items.isEmpty()
        val selectionMode = selectedSources.isNotEmpty()
    }

    sealed interface Event {
        data object FailedFetchingSourcesWithCount : Event
    }
}
