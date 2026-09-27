package mihon.feature.announcements

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AnnouncementsScreenModel(
    private val repository: AnnouncementsRepository = AnnouncementsRepository(),
    private val preferences: AnnouncementsPreferences = Injekt.get(),
) : StateScreenModel<AnnouncementsScreenModel.State>(State.Loading) {

    sealed interface State {
        data object Loading : State

        data class Success(
            val allEntries: ImmutableList<AnnouncementEntry>,
            val selectedCategory: AnnouncementCategory?,
            val selectedYear: Int?,
            val sort: AnnouncementSort,
            val includeAdult: Boolean,
            val autoRefresh: AnnouncementAutoRefresh,
            val fromCache: Boolean,
        ) : State {
            val filteredEntries: ImmutableList<AnnouncementEntry>
                get() = allEntries
                    .asSequence()
                    .filter { selectedCategory == null || it.category == selectedCategory }
                    .filter { selectedYear == null || it.expectedYear == selectedYear }
                    .filter { includeAdult || !it.isAdult }
                    .let { entries ->
                        when (sort) {
                            AnnouncementSort.AIRING_SOON -> entries.sortedWith(
                                compareBy<AnnouncementEntry> { it.exactReleaseDate ?: Long.MAX_VALUE }
                                    .thenBy { it.title.lowercase() },
                            )
                            AnnouncementSort.LATEST_ADDED -> entries.sortedWith(
                                compareByDescending<AnnouncementEntry> { it.mediaId }
                                    .thenBy { it.title.lowercase() },
                            )
                            AnnouncementSort.POPULARITY -> entries.sortedWith(
                                compareByDescending<AnnouncementEntry> { it.popularity ?: -1 }
                                    .thenBy { it.title.lowercase() },
                            )
                            AnnouncementSort.SCORE -> entries.sortedWith(
                                compareByDescending<AnnouncementEntry> { it.score ?: -1 }
                                    .thenBy { it.title.lowercase() },
                            )
                            AnnouncementSort.TITLE -> entries.sortedBy { it.title.lowercase() }
                        }
                    }
                    .toList()
                    .toImmutableList()

            val availableYears: List<Int>
                get() = allEntries
                    .mapNotNull { it.expectedYear }
                    .distinct()
                    .sortedDescending()
        }

        data class Error(val cachedEntries: ImmutableList<AnnouncementEntry>) : State
    }

    init {
        load()
    }

    fun load(forceRefresh: Boolean = false) {
        screenModelScope.launch {
            val previous = state as? State.Success
            mutableState.value = State.Loading
            when (val result = repository.getAnnouncements(forceRefresh)) {
                is AnnouncementsRepository.Result.Success -> {
                    mutableState.value = State.Success(
                        allEntries = result.entries.toImmutableList(),
                        selectedCategory = previous?.selectedCategory,
                        selectedYear = previous?.selectedYear ?: preferences.yearFilter().get().toIntOrNull(),
                        sort = previous?.sort ?: preferences.sort().get(),
                        includeAdult = previous?.includeAdult ?: preferences.includeAdult().get(),
                        autoRefresh = previous?.autoRefresh ?: preferences.autoRefresh().get(),
                        fromCache = result.fromCache,
                    )
                }
                is AnnouncementsRepository.Result.Failure -> {
                    mutableState.value = State.Error(result.cachedEntries.toImmutableList())
                }
            }
        }
    }

    fun showCachedData() {
        val cached = (state as? State.Error)?.cachedEntries ?: return
        mutableState.value = State.Success(
            allEntries = cached,
            selectedCategory = null,
            selectedYear = preferences.yearFilter().get().toIntOrNull(),
            sort = preferences.sort().get(),
            includeAdult = preferences.includeAdult().get(),
            autoRefresh = preferences.autoRefresh().get(),
            fromCache = true,
        )
    }

    fun selectCategory(category: AnnouncementCategory?) {
        val current = state as? State.Success ?: return
        mutableState.value = current.copy(selectedCategory = category)
    }

    fun selectYear(year: Int?) {
        val current = state as? State.Success ?: return
        preferences.yearFilter().set(year?.toString().orEmpty())
        mutableState.value = current.copy(selectedYear = year)
    }

    fun setSort(sort: AnnouncementSort) {
        val current = state as? State.Success ?: return
        preferences.sort().set(sort)
        mutableState.value = current.copy(sort = sort)
    }

    fun setIncludeAdult(include: Boolean) {
        val current = state as? State.Success ?: return
        preferences.includeAdult().set(include)
        mutableState.value = current.copy(includeAdult = include)
    }

    fun setAutoRefresh(autoRefresh: AnnouncementAutoRefresh) {
        val current = state as? State.Success ?: return
        preferences.autoRefresh().set(autoRefresh)
        mutableState.value = current.copy(autoRefresh = autoRefresh)
    }
}