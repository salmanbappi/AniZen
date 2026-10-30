package eu.kanade.tachiyomi.ui.browse.source.browse

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.core.preference.asState
import eu.kanade.domain.anime.interactor.UpdateAnime
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.ui.anime.AnimeScreenModel
import eu.kanade.tachiyomi.ui.anime.SuggestionSection
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.anime.interactor.GetAnime
import tachiyomi.domain.anime.interactor.GetLibraryAnime
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.model.toAnimeUpdate
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetAnimeCategories
import tachiyomi.domain.library.service.LibraryPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.Instant

class RelatedAnimeScreenModel(
    private val animeId: Long,
    private val getAnime: GetAnime = Injekt.get(),
    private val getLibraryAnime: GetLibraryAnime = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val setAnimeCategories: SetAnimeCategories = Injekt.get(),
    private val updateAnime: UpdateAnime = Injekt.get(),
    private val sourcePreferences: SourcePreferences = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
) : StateScreenModel<RelatedAnimeScreenModel.State>(State()) {

    var displayMode by sourcePreferences.relatedAnimeDisplayMode().asState(screenModelScope)

    init {
        screenModelScope.launchIO {
            val anime = getAnime.await(animeId) ?: return@launchIO
            mutableState.update { it.copy(title = anime.title) }

            // Reactive update: Listen for cache changes
            AnimeScreenModel.suggestionsUpdateFlow
                .filter { it == animeId }
                .onStart { emit(animeId) }
                .collect { _ ->
                    val cached = AnimeScreenModel.suggestionsCache.get(animeId)
                    if (cached != null) {
                        var newItems = persistentMapOf<String, ImmutableList<Anime>>()
                        cached.sections.forEach { section ->
                            if (section.items.isEmpty()) return@forEach
                            val title = when (section.type) {
                                SuggestionSection.Type.Franchise -> "Franchise"
                                SuggestionSection.Type.Similarity -> "Similar"
                                SuggestionSection.Type.Source -> section.title
                                SuggestionSection.Type.Tag -> "Tags"
                                else -> section.title
                            }
                            newItems = newItems.put(title, section.items)
                        }
                        mutableState.update { it.copy(items = newItems) }
                    } else {
                        AnimeScreenModel.triggerFetch(animeId)
                    }
                }
        }

        screenModelScope.launchIO {
            getLibraryAnime.subscribe()
                .collect { libraryAnime ->
                    val favoriteIds = libraryAnime.map { it.id }.toImmutableSet()
                    mutableState.update { it.copy(favoriteIds = favoriteIds) }
                }
        }
    }

    fun toggleSelection(anime: Anime) {
        mutableState.update { state ->
            val isSelected = state.selection.any { it.id == anime.id }
            val newSelection = if (isSelected) {
                state.selection.filterNot { it.id == anime.id }
            } else {
                state.selection + anime
            }
            state.copy(selection = newSelection.toImmutableList())
        }
    }

    fun clearSelection() {
        mutableState.update { it.copy(selection = persistentListOf()) }
    }

    fun selectAll() {
        mutableState.update { state ->
            val allItems = state.items.values.flatten().distinctBy { it.id }.toImmutableList()
            state.copy(selection = allItems)
        }
    }

    fun invertSelection() {
        mutableState.update { state ->
            val allItems = state.items.values.flatten().distinctBy { it.id }
            val newSelection = allItems.filterNot { anime -> state.selection.any { it.id == anime.id } }
            state.copy(selection = newSelection.toImmutableList())
        }
    }

    fun addSelectionToLibrary() {
        val selection = state.value.selection
        val favoriteIds = state.value.favoriteIds
        screenModelScope.launchIO {
            val categories = getCategories.await()
            val defaultCategoryId = libraryPreferences.defaultCategory().get()
            val defaultCategory = categories.find { it.id == defaultCategoryId.toLong() }

            selection.filter { it.id !in favoriteIds }.forEach { anime ->
                val categoryIds = listOfNotNull(defaultCategory?.id ?: if (categories.isEmpty()) null else categories.first().id)
                setAnimeCategories.await(anime.id, categoryIds)
                updateAnime.await(
                    anime.copy(
                        favorite = true,
                        dateAdded = Instant.now().toEpochMilli(),
                    ).toAnimeUpdate(),
                )
            }
            clearSelection()
        }
    }

    fun removeSelectionFromLibrary() {
        val selection = state.value.selection
        val favoriteIds = state.value.favoriteIds
        screenModelScope.launchIO {
            selection.filter { it.id in favoriteIds }.forEach { anime ->
                updateAnime.await(
                    anime.copy(
                        favorite = false,
                        dateAdded = 0,
                    ).toAnimeUpdate(),
                )
            }
            clearSelection()
        }
    }

    @Immutable
    data class State(
        val title: String = "",
        val items: PersistentMap<String, ImmutableList<Anime>> = persistentMapOf(),
        val selection: ImmutableList<Anime> = persistentListOf(),
        val favoriteIds: ImmutableSet<Long> = persistentSetOf(),
    ) {
        val selectionMode: Boolean get() = selection.isNotEmpty()
    }
}
