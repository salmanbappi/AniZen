package eu.kanade.tachiyomi.ui.browse

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.anime.interactor.UpdateAnime
import eu.kanade.domain.episode.interactor.SyncEpisodesWithSource
import eu.kanade.tachiyomi.data.cache.CoverCache
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.mutate
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetAnimeCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.episode.interactor.SetAnimeDefaultEpisodeFlags
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class BulkFavoriteScreenModel(
    initialState: State = State(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val setAnimeCategories: SetAnimeCategories = Injekt.get(),
    private val updateAnime: UpdateAnime = Injekt.get(),
    private val coverCache: CoverCache = Injekt.get(),
    private val setAnimeDefaultEpisodeFlags: SetAnimeDefaultEpisodeFlags = Injekt.get(),
    private val syncEpisodesWithSource: SyncEpisodesWithSource = Injekt.get(),
    val snackbarHostState: SnackbarHostState = SnackbarHostState(),
) : StateScreenModel<BulkFavoriteScreenModel.State>(initialState) {

    fun toggleSelectionMode(newMode: Boolean? = null) {
        if (state.value.selectionMode) {
            clearSelection()
        }
        mutableState.update { it.copy(selectionMode = newMode ?: !it.selectionMode) }
    }

    private fun clearSelection() {
        mutableState.update { it.copy(selection = persistentListOf()) }
    }

    fun toggleSelection(anime: Anime, toSelectedState: Boolean? = null) {
        mutableState.update { state ->
            val isSelected = state.selection.any { it.id == anime.id }
            val shouldSelect = toSelectedState ?: !isSelected
            val newSelection = state.selection.mutate { list ->
                if (shouldSelect && !isSelected) {
                    list.add(anime)
                } else if (!shouldSelect && isSelected) {
                    list.removeAll { it.id == anime.id }
                }
            }
            state.copy(
                selection = newSelection,
                selectionMode = newSelection.isNotEmpty(),
            )
        }
    }

    fun addFavorite() {
        screenModelScope.launch {
            val animeList = state.value.selection
            if (animeList.isEmpty()) return@launch

            val categories = getCategories()
            val defaultCategoryId = libraryPreferences.defaultCategory().get()
            val defaultCategory = categories.find { it.id == defaultCategoryId.toLong() }

            when {
                defaultCategory != null -> {
                    setAnimesCategories(animeList, listOf(defaultCategory.id), emptyList())
                }
                defaultCategoryId == 0 || categories.isEmpty() -> {
                    setAnimesCategories(animeList, emptyList(), emptyList())
                }
                else -> {
                    val preselected = categories.map { CheckboxState.State.None(it) }.toImmutableList()
                    setDialog(Dialog.ChangeAnimesCategory(animeList, preselected))
                }
            }
        }
    }

    fun setAnimesCategories(animeList: List<Anime>, addCategories: List<Long>, removeCategories: List<Long>) {
        screenModelScope.launchNonCancellable {
            startRunning()
            animeList.forEach { anime ->
                val categoryIds = getCategories.await(anime.id)
                    .map { it.id }
                    .subtract(removeCategories.toSet())
                    .plus(addCategories)
                    .toList()

                setAnimeCategories.await(anime.id, categoryIds)
                if (!anime.favorite) {
                    updateAnime.awaitUpdateFavorite(anime.id, true)
                }
            }
            stopRunning()
            toggleSelectionMode(false)
        }
    }

    private suspend fun getCategories(): List<Category> {
        return getCategories.subscribe()
            .firstOrNull()
            ?.filterNot { it.isSystemCategory }
            .orEmpty()
    }

    fun setDialog(dialog: Dialog?) {
        mutableState.update { it.copy(dialog = dialog) }
    }

    fun dismissDialog() {
        setDialog(null)
    }

    private fun startRunning() {
        mutableState.update { it.copy(isRunning = true) }
    }

    private fun stopRunning() {
        mutableState.update { it.copy(isRunning = false) }
    }

    sealed interface Dialog {
        data class ChangeAnimesCategory(
            val animes: List<Anime>,
            val initialSelection: kotlinx.collections.immutable.ImmutableList<CheckboxState<Category>>,
        ) : Dialog
    }

    @Immutable
    data class State(
        val dialog: Dialog? = null,
        val selection: PersistentList<Anime> = persistentListOf(),
        val selectionMode: Boolean = false,
        val isRunning: Boolean = false,
    )
}
