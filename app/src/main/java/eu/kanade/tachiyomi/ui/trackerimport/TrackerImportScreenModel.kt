package eu.kanade.tachiyomi.ui.trackerimport

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.track.model.toDomainTrack
import eu.kanade.tachiyomi.data.track.ImportStatusFilter
import eu.kanade.tachiyomi.data.track.ImportableEntry
import eu.kanade.tachiyomi.data.track.ImportableTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.anime.interactor.GetLibraryAnime
import tachiyomi.domain.anime.interactor.NetworkToLocalAnime
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Locale
import eu.kanade.tachiyomi.data.database.models.Track as DbTrack

@Immutable
data class TrackerImportScreenState(
    val trackerName: String = "",
    val isLoading: Boolean = true,
    val rawItems: List<TrackerImportItem> = emptyList(),
    val excludeLibraryMatches: Boolean = true,
    val selectedStatuses: Set<ImportStatusFilter> = ImportStatusFilter.entries.toSet(),
) {
    val items: List<TrackerImportItem>
        get() = rawItems.filter { item ->
            val matchingFilter = item.item.statusFilter == null || selectedStatuses.contains(item.item.statusFilter)
            if (!matchingFilter) return@filter false
            if (excludeLibraryMatches && item.isLibraryMatch) return@filter false
            true
        }

    val selected = items.filter { it.selected }
    val selectionMode = selected.isNotEmpty()
}

@Immutable
data class TrackerImportItem(
    val item: ImportableEntry,
    val selected: Boolean = false,
    val isLibraryMatch: Boolean = false,
)

class TrackerImportScreenModel(
    val trackerId: Long,
    private val getLibraryAnime: GetLibraryAnime = Injekt.get(),
    private val getTracks: GetTracks = Injekt.get(),
    private val networkToLocalAnime: NetworkToLocalAnime = Injekt.get(),
    private val insertTrack: InsertTrack = Injekt.get(),
    private val trackerManager: TrackerManager = Injekt.get(),
) : StateScreenModel<TrackerImportScreenState>(TrackerImportScreenState()) {

    val tracker = trackerManager.get(trackerId) as? ImportableTracker

    init {
        mutableState.update { it.copy(trackerName = tracker?.name ?: "") }
        loadItems()
        observeLibraryChanges()
    }

    private fun loadItems() {
        screenModelScope.launchIO {
            try {
                mutableState.update { it.copy(isLoading = true) }
                val currentTracker = tracker
                if (currentTracker == null || !currentTracker.isLoggedIn) {
                    mutableState.update { it.copy(isLoading = false, rawItems = emptyList()) }
                    return@launchIO
                }
                val currentUserId = try {
                    currentTracker.getUsername()
                } catch (e: Exception) {
                    ""
                }
                val now = System.currentTimeMillis()
                val cacheKey = "${trackerId}_$currentUserId"
                val remoteItems = if (cachedRemoteItems[cacheKey] != null &&
                    now - lastCacheTime.getOrDefault(cacheKey, 0L) < CACHE_DURATION
                ) {
                    cachedRemoteItems[cacheKey]!!
                } else {
                    val fetched = currentTracker.getImportableList()
                    cachedRemoteItems[cacheKey] = fetched
                    lastCacheTime[cacheKey] = now
                    fetched
                }
                val localTracks = getTracks.await()
                val alreadyTrackedRemoteIds = localTracks
                    .filter { it.trackerId == trackerId }
                    .map { it.remoteId }
                    .toSet()

                val libraryAnime = getLibraryAnime.await()
                val libraryAnimeNormalizedTitles = libraryAnime
                    .map { it.anime.title.normalizeTitle() }
                    .toSet()

                val allItems = remoteItems.map { item ->
                    val isAlreadyTracked = item.remoteId in alreadyTrackedRemoteIds
                    val title = item.title
                    val titlesToCompare = listOf(title).map { it.normalizeTitle() }
                    val hasTitleMatch = titlesToCompare.any { it in libraryAnimeNormalizedTitles }
                    val isLibraryMatch = isAlreadyTracked || hasTitleMatch
                    TrackerImportItem(
                        item = item,
                        isLibraryMatch = isLibraryMatch,
                    )
                }

                mutableState.update { it.copy(isLoading = false, rawItems = allItems) }
            } catch (e: Exception) {
                mutableState.update { it.copy(isLoading = false, rawItems = emptyList()) }
            }
        }
    }

    private fun String.normalizeTitle(): String {
        return this.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
    }

    fun setExcludeLibraryMatches(exclude: Boolean) {
        mutableState.update { it.copy(excludeLibraryMatches = exclude) }
    }

    fun toggleStatusFilter(filter: ImportStatusFilter) {
        mutableState.update { state ->
            val newStatuses = if (state.selectedStatuses.contains(filter)) {
                state.selectedStatuses - filter
            } else {
                state.selectedStatuses + filter
            }
            state.copy(selectedStatuses = newStatuses)
        }
    }

    fun toggleSelection(item: TrackerImportItem, selected: Boolean) {
        mutableState.update { state ->
            val newItems = state.rawItems.map {
                if (it.item.remoteId == item.item.remoteId) {
                    it.copy(selected = selected)
                } else {
                    it
                }
            }
            state.copy(rawItems = newItems)
        }
    }

    fun toggleAllSelection(selected: Boolean) {
        mutableState.update { state ->
            val visibleIds = state.items.map { it.item.remoteId }.toSet()
            val newItems = state.rawItems.map {
                if (it.item.remoteId in visibleIds) {
                    it.copy(selected = selected)
                } else {
                    it
                }
            }
            state.copy(rawItems = newItems)
        }
    }

    fun invertSelection() {
        mutableState.update { state ->
            val visibleIds = state.items.map { it.item.remoteId }.toSet()
            val newItems = state.rawItems.map {
                if (it.item.remoteId in visibleIds) {
                    it.copy(selected = !it.selected)
                } else {
                    it
                }
            }
            state.copy(rawItems = newItems)
        }
    }

    fun importSelected(onSuccess: (List<Long>) -> Unit, onFailure: (Throwable) -> Unit) {
        val selectedItems = state.value.selected
        if (selectedItems.isEmpty()) return

        screenModelScope.launchIO {
            try {
                val createdAnimeIds = mutableListOf<Long>()
                for (selected in selectedItems) {
                    val item = selected.item
                    val pathPrefix = if (trackerId == 1L) "mal" else "anilist"
                    val anime = Anime.create().copy(
                        url = "/anime/$pathPrefix-import/${item.remoteId}",
                        ogTitle = item.title,
                        ogThumbnailUrl = item.coverUrl,
                        source = trackerId,
                        favorite = false,
                        dateAdded = System.currentTimeMillis(),
                    )
                    val savedAnime = networkToLocalAnime.await(anime)
                    createdAnimeIds.add(savedAnime.id)

                    val dbTrack = DbTrack.create(trackerId).apply {
                        anime_id = savedAnime.id
                        remote_id = item.remoteId
                        library_id = item.remoteId
                        title = item.title
                        last_episode_seen = item.episodesSeen.toDouble()
                        total_episodes = item.totalEpisodes
                        status = item.status
                        score = item.score
                        tracking_url = item.trackingUrl
                        started_watching_date = item.startDate
                        finished_watching_date = item.finishDate
                    }
                    val domainTrack = dbTrack.toDomainTrack(idRequired = false)!!
                    insertTrack.await(domainTrack)
                }
                onSuccess(createdAnimeIds)
            } catch (t: Throwable) {
                onFailure(t)
            }
        }
    }

    private fun observeLibraryChanges() {
        screenModelScope.launchIO {
            getLibraryAnime.subscribe().collectLatest { libraryAnime ->
                val libraryAnimeNormalizedTitles = libraryAnime
                    .map { it.anime.title.normalizeTitle() }
                    .toSet()

                val localTracks = getTracks.await()
                val alreadyTrackedRemoteIds = localTracks
                    .filter { it.trackerId == trackerId }
                    .map { it.remoteId }
                    .toSet()

                mutableState.update { state ->
                    val updatedItems = state.rawItems.map { item ->
                        val isAlreadyTracked = item.item.remoteId in alreadyTrackedRemoteIds
                        val title = item.item.title
                        val titlesToCompare = listOf(title).map { it.normalizeTitle() }
                        val hasTitleMatch = titlesToCompare.any { it in libraryAnimeNormalizedTitles }
                        val isLibraryMatch = isAlreadyTracked || hasTitleMatch
                        item.copy(
                            isLibraryMatch = isLibraryMatch,
                            selected = if (isLibraryMatch) false else item.selected,
                        )
                    }
                    state.copy(rawItems = updatedItems)
                }
            }
        }
    }

    companion object {
        private val cachedRemoteItems = java.util.concurrent.ConcurrentHashMap<String, List<ImportableEntry>>()
        private val lastCacheTime = java.util.concurrent.ConcurrentHashMap<String, Long>()
        private const val CACHE_DURATION = 5 * 60 * 1000 // 5 minutes
    }
}
