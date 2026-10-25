package eu.kanade.tachiyomi.ui.storage

import androidx.compose.ui.graphics.Color
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.presentation.more.storage.StorageItem
import eu.kanade.presentation.more.storage.StorageScreenState
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.service.LibraryPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.random.Random

abstract class CommonStorageScreenModel<T>(
    private val downloadCacheChanges: SharedFlow<Unit>,
    private val downloadCacheIsInitializing: StateFlow<Boolean>,
    private val libraries: Flow<List<T>>,
    private val categories: (Boolean) -> Flow<List<Category>>,
    private val getDownloadSize: T.() -> Long,
    private val getDownloadCount: T.() -> Int,
    private val getId: T.() -> Long,
    private val getCategoryId: T.() -> Long,
    private val getTitle: T.() -> String,
    private val getThumbnail: T.() -> String?,
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
) : StateScreenModel<StorageScreenState>(StorageScreenState.Loading) {

    private val selectedCategory = MutableStateFlow(AllCategory)
    private var calculationJob: Job? = null

    init {
        screenModelScope.launchIO {
            val showHiddenCategories = libraryPreferences.showHiddenCategories().get()

            combine(
                downloadCacheChanges,
                downloadCacheIsInitializing,
                libraries,
                categories(showHiddenCategories),
                selectedCategory,
            ) { _, _, libraries, categories, selectedCategory ->
                // Fast update for category switch
                val allCategories = listOf(AllCategory, *categories.toTypedArray())
                mutableState.update { state ->
                    if (state is StorageScreenState.Success) {
                        state.copy(
                            selectedCategory = selectedCategory,
                            categories = allCategories,
                            items = emptyList(),
                            isLoading = true,
                        )
                    } else {
                        StorageScreenState.Success(
                            selectedCategory = selectedCategory,
                            categories = allCategories,
                            items = emptyList(),
                            isLoading = true,
                        )
                    }
                }

                // Immediate cancellation of any previous job
                calculationJob?.cancel()

                calculationJob = launch {
                    coroutineScope {
                        val distinctLibraries = libraries.distinctBy { it.getId() }.filter { item ->
                            val categoryId = item.getCategoryId()
                            if (selectedCategory == AllCategory) {
                                categories.any { it.id == categoryId }
                            } else {
                                categoryId == selectedCategory.id
                            }
                        }

                        // Process in chunks to reduce state churn
                        distinctLibraries.chunked(10).forEachIndexed { index, chunk ->
                            if (!isActive) return@forEachIndexed

                            val newItems = chunk.map { library ->
                                val random = Random(library.getId())
                                StorageItem(
                                    id = library.getId(),
                                    title = library.getTitle(),
                                    size = library.getDownloadSize(),
                                    thumbnail = library.getThumbnail(),
                                    entriesCount = library.getDownloadCount(),
                                    color = Color(
                                        random.nextInt(255),
                                        random.nextInt(255),
                                        random.nextInt(255),
                                    ),
                                )
                            }

                            mutableState.update { state ->
                                if (state is StorageScreenState.Success && state.selectedCategory == selectedCategory) {
                                    val isLastChunk = index == (distinctLibraries.size / 10) || distinctLibraries.size <= 10
                                    state.copy(
                                        items = (state.items + newItems).sortedByDescending { it.size },
                                        isLoading = !isLastChunk,
                                    )
                                } else {
                                    state
                                }
                            }
                        }

                        // Final safety update to ensure isLoading is false
                        mutableState.update { state ->
                            if (state is StorageScreenState.Success && state.selectedCategory == selectedCategory) {
                                state.copy(isLoading = false)
                            } else {
                                state
                            }
                        }
                    }
                }
            }.collectLatest {}
        }
    }

    fun setSelectedCategory(category: Category) {
        selectedCategory.update { category }
    }

    abstract fun deleteEntry(id: Long)

    companion object {
        /**
         * A dummy category used to display all entries irrespective of the category.
         */
        private val AllCategory = Category(
            id = -1L,
            name = "All",
            order = 0L,
            flags = 0L,
            hidden = false,
        )
    }
}
