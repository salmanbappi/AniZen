package eu.kanade.tachiyomi.ui.browse.migration.anime

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.core.util.addOrRemove
import eu.kanade.tachiyomi.source.Source
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.anime.interactor.GetFavorites
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class MigrateAnimeScreenModel(
    private val sourceIds: List<Long>,
    private val sourceManager: SourceManager = Injekt.get(),
    private val getFavorites: GetFavorites = Injekt.get(),
) : StateScreenModel<MigrateAnimeScreenModel.State>(State()) {

    private val _events: Channel<MigrationAnimeEvent> = Channel()
    val events: Flow<MigrationAnimeEvent> = _events.receiveAsFlow()

    // KMK -->
    // First and last selected index in list
    private val selectedPositions: Array<Int> = arrayOf(-1, -1)
    private val selectedAnimeIds: HashSet<Long> = HashSet()
    // KMK <--

    init {
        screenModelScope.launch {
            val sources = sourceIds.map { sourceManager.getOrStub(it) }.toImmutableList()
            mutableState.update { state ->
                state.copy(sources = sources)
            }

            combine(
                sourceIds.map { getFavorites.subscribe(it) },
            ) { favoriteLists ->
                favoriteLists.flatMap { it }
            }
                .catch {
                    logcat(LogPriority.ERROR, it)
                    _events.send(MigrationAnimeEvent.FailedFetchingFavorites)
                    mutableState.update { state ->
                        state.copy(titles = persistentListOf(), selection = persistentListOf(), isLoading = false)
                    }
                }
                // KMK -->
                .map { anime ->
                    toMigrationAnimeScreenItems(anime)
                }
                // KMK <--
                .map { anime ->
                    anime
                        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.anime.title })
                        .toImmutableList()
                }
                .collectLatest { list ->
                    mutableState.update { state ->
                        state.copy(
                            titles = list,
                            selection = list.filter { it.selected }.toImmutableList(),
                            isLoading = false,
                        )
                    }
                }
        }
    }

    // KMK -->
    private fun toMigrationAnimeScreenItems(animes: List<Anime>): List<MigrateAnimeItem> {
        return animes.map { anime ->
            MigrateAnimeItem(
                anime = anime,
                selected = anime.id in selectedAnimeIds,
            )
        }
    }

    fun toggleSelection(
        item: MigrateAnimeItem,
        selected: Boolean,
        userSelected: Boolean = false,
        fromLongPress: Boolean = false,
    ) {
        mutableState.update { state ->
            val newItems = state.titles.toMutableList().apply {
                val selectedIndex = indexOfFirst { it.anime.id == item.anime.id }
                if (selectedIndex < 0) return@apply

                val selectedItem = get(selectedIndex)
                if (selectedItem.selected == selected) return@apply

                val firstSelection = none { it.selected }
                set(selectedIndex, selectedItem.copy(selected = selected))
                selectedAnimeIds.addOrRemove(item.anime.id, selected)

                if (selected && userSelected && fromLongPress) {
                    if (firstSelection) {
                        selectedPositions[0] = selectedIndex
                        selectedPositions[1] = selectedIndex
                    } else {
                        // Try to select the items in-between when possible
                        val range: IntRange
                        if (selectedIndex < selectedPositions[0]) {
                            range = selectedIndex + 1 until selectedPositions[0]
                            selectedPositions[0] = selectedIndex
                        } else if (selectedIndex > selectedPositions[1]) {
                            range = (selectedPositions[1] + 1) until selectedIndex
                            selectedPositions[1] = selectedIndex
                        } else {
                            // Just select itself
                            range = IntRange.EMPTY
                        }

                        range.forEach {
                            val inBetweenItem = get(it)
                            if (!inBetweenItem.selected) {
                                selectedAnimeIds.add(inBetweenItem.anime.id)
                                set(it, inBetweenItem.copy(selected = true))
                            }
                        }
                    }
                } else if (userSelected && !fromLongPress) {
                    if (!selected) {
                        if (selectedIndex == selectedPositions[0]) {
                            selectedPositions[0] = indexOfFirst { it.selected }
                        } else if (selectedIndex == selectedPositions[1]) {
                            selectedPositions[1] = indexOfLast { it.selected }
                        }
                    } else {
                        if (selectedIndex < selectedPositions[0]) {
                            selectedPositions[0] = selectedIndex
                        } else if (selectedIndex > selectedPositions[1]) {
                            selectedPositions[1] = selectedIndex
                        }
                    }
                }
            }.toImmutableList()
            state.copy(
                titles = newItems,
                selection = newItems.filter { it.selected }.toImmutableList(),
            )
        }
    }

    fun toggleAllSelection(selected: Boolean = true) {
        mutableState.update { state ->
            val newItems = state.titles.map {
                selectedAnimeIds.addOrRemove(it.anime.id, selected)
                it.copy(selected = selected)
            }.toImmutableList()
            state.copy(
                titles = newItems,
                selection = if (selected) newItems else persistentListOf(),
            )
        }

        selectedPositions[0] = -1
        selectedPositions[1] = -1
    }

    fun invertSelection() {
        mutableState.update { state ->
            val newItems = state.titles.map {
                selectedAnimeIds.addOrRemove(it.anime.id, !it.selected)
                it.copy(selected = !it.selected)
            }.toImmutableList()
            state.copy(
                titles = newItems,
                selection = newItems.filter { it.selected }.toImmutableList(),
            )
        }
        selectedPositions[0] = -1
        selectedPositions[1] = -1
    }

    fun clearSelection() {
        toggleAllSelection(false)
    }
    // KMK <--

    @Immutable
    data class State(
        val sources: ImmutableList<Source> = persistentListOf(),
        val titles: ImmutableList<MigrateAnimeItem> = persistentListOf(),
        val selection: ImmutableList<MigrateAnimeItem> = persistentListOf(),
        val isLoading: Boolean = true,
    ) {
        val isEmpty: Boolean get() = titles.isEmpty()
        val selectionMode: Boolean get() = selection.isNotEmpty()
    }
}

sealed interface MigrationAnimeEvent {
    data object FailedFetchingFavorites : MigrationAnimeEvent
}

// KMK -->
@Immutable
data class MigrateAnimeItem(
    val anime: Anime,
    val selected: Boolean,
)
// KMK <--
