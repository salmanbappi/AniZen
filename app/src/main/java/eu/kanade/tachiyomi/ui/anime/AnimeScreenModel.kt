package eu.kanade.tachiyomi.ui.anime

import android.content.Context
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Immutable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.core.util.addOrRemove
import eu.kanade.core.util.insertSeparators
import eu.kanade.domain.anime.interactor.SetAnimeViewerFlags
import eu.kanade.domain.anime.interactor.UpdateAnime
import eu.kanade.domain.anime.model.downloadedFilter
import eu.kanade.domain.anime.model.episodesFiltered
import tachiyomi.domain.anime.model.toSAnime
import tachiyomi.domain.anime.model.toDomainAnime
import eu.kanade.domain.episode.interactor.SetSeenStatus
import eu.kanade.domain.episode.interactor.SyncEpisodesWithSource
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.domain.track.interactor.TrackEpisode
import eu.kanade.domain.track.model.AutoTrackState
import eu.kanade.domain.track.model.toDomainTrack
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.presentation.anime.DownloadAction
import eu.kanade.presentation.anime.components.EpisodeDownloadAction
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.torrentServer.service.TorrentServerService
import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.isSourceForTorrents
import eu.kanade.tachiyomi.torrentServer.TorrentServerUtils
import eu.kanade.tachiyomi.ui.anime.track.TrackItem
import eu.kanade.tachiyomi.ui.player.settings.GesturePreferences
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import eu.kanade.tachiyomi.util.AniChartApi
import eu.kanade.tachiyomi.util.episode.getNextUnseen
import eu.kanade.tachiyomi.util.removeCovers
import eu.kanade.tachiyomi.util.system.toast
import exh.util.nullIfEmpty
import exh.util.trimOrNull
import java.util.Collections
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.domain.episode.interactor.FilterEpisodesForDownload
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.preference.mapAsCheckboxState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.source.NoResultsException
import tachiyomi.domain.anime.interactor.GetAnimeWithEpisodes
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.GetTracksPerAnime
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.anime.interactor.GetDuplicateLibraryAnime
import tachiyomi.domain.anime.interactor.SetAnimeEpisodeFlags
import tachiyomi.domain.anime.interactor.SetCustomAnimeInfo
import tachiyomi.domain.anime.interactor.GetAnime
import tachiyomi.domain.anime.interactor.NetworkToLocalAnime
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.model.AnimeUpdate
import tachiyomi.domain.anime.model.CustomAnimeInfo
import tachiyomi.domain.anime.model.Season
import tachiyomi.domain.anime.model.applyFilter
import tachiyomi.domain.anime.model.toAnimeUpdate
import tachiyomi.domain.anime.repository.AnimeRepository
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetAnimeCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.episode.interactor.SetAnimeDefaultEpisodeFlags
import tachiyomi.domain.episode.interactor.UpdateEpisode
import tachiyomi.domain.episode.model.Episode
import tachiyomi.domain.episode.model.EpisodeUpdate
import tachiyomi.domain.episode.service.calculateChapterGap
import tachiyomi.domain.episode.service.getEpisodeSort
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import tachiyomi.domain.anime.interactor.CalculateUserAffinity
import tachiyomi.domain.anime.interactor.GetLibraryAnime
import tachiyomi.domain.library.model.LibraryAnime
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.source.interactor.GetRelatedAnime
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StoragePreferences
import tachiyomi.domain.track.interactor.DeleteTrack
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.Serializable
import java.util.Calendar
import kotlin.math.floor

import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.domain.episode.model.applyFilters

class AnimeScreenModel(
    private val context: Context,
    private val lifecycle: Lifecycle,
    private val animeId: Long,
    private val isFromSource: Boolean,
    private val downloadPreferences: DownloadPreferences = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val trackPreferences: TrackPreferences = Injekt.get(),
    internal val playerPreferences: PlayerPreferences = Injekt.get(),
    internal val gesturePreferences: GesturePreferences = Injekt.get(),
    private val trackerManager: TrackerManager = Injekt.get(),
    private val trackEpisode: TrackEpisode = Injekt.get(),
    private val downloadManager: DownloadManager = Injekt.get(),
    private val downloadCache: DownloadCache = Injekt.get(),
    private val downloadProvider: eu.kanade.tachiyomi.data.download.DownloadProvider = Injekt.get(),
    private val getAnimeAndEpisodes: GetAnimeWithEpisodes = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val setCustomAnimeInfo: SetCustomAnimeInfo = Injekt.get(),
    private val getDuplicateLibraryAnime: GetDuplicateLibraryAnime = Injekt.get(),
    private val setAnimeEpisodeFlags: SetAnimeEpisodeFlags = Injekt.get(),
    private val setAnimeDefaultEpisodeFlags: SetAnimeDefaultEpisodeFlags = Injekt.get(),
    private val setSeenStatus: SetSeenStatus = Injekt.get(),
    private val updateEpisode: UpdateEpisode = Injekt.get(),
    private val updateAnime: UpdateAnime = Injekt.get(),
    private val syncEpisodesWithSource: SyncEpisodesWithSource = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val getTracks: GetTracks = Injekt.get(),
    private val insertTrack: InsertTrack = Injekt.get(),
    private val deleteTrack: DeleteTrack = Injekt.get(),
    private val addTracks: AddTracks = Injekt.get(),
    private val setAnimeCategories: SetAnimeCategories = Injekt.get(),
    private val animeRepository: AnimeRepository = Injekt.get(),
    private val filterEpisodesForDownload: FilterEpisodesForDownload = Injekt.get(),
    internal val setAnimeViewerFlags: SetAnimeViewerFlags = Injekt.get(),
    val snackbarHostState: SnackbarHostState = SnackbarHostState(),
    private val storagePreferences: StoragePreferences = Injekt.get(),
    private val getAnime: GetAnime = Injekt.get(),
    private val networkToLocalAnime: NetworkToLocalAnime = Injekt.get(),
    private val getRelatedAnime: GetRelatedAnime = Injekt.get(),
    private val calculateUserAffinity: CalculateUserAffinity = Injekt.get(),
    private val getLibraryAnime: GetLibraryAnime = Injekt.get(),
    private val getSeasonsByAnimeId: tachiyomi.domain.anime.interactor.GetSeasonsByAnimeId = Injekt.get(),
    private val discoverSeasons: tachiyomi.domain.anime.interactor.DiscoverSeasons = Injekt.get(),
) : StateScreenModel<AnimeScreenModel.State>(State.Loading) {

    private val successState: State.Success?
        get() = state.value as? State.Success

    val anime: Anime?
        get() = successState?.anime

    val source: Source?
        get() = successState?.source

    private val isFavorited: Boolean
        get() = anime?.favorite ?: false

    private val processedEpisodes: List<EpisodeList.Item>?
        get() = successState?.processedEpisodes

    val episodeSwipeStartAction = libraryPreferences.swipeEpisodeEndAction().get()
    val episodeSwipeEndAction = libraryPreferences.swipeEpisodeStartAction().get()
    var autoTrackState = trackPreferences.autoUpdateTrackOnMarkRead().get()

    val showNextEpisodeAirTime = trackPreferences.showNextEpisodeAiringTime().get()
    val alwaysUseExternalPlayer = playerPreferences.alwaysUseExternalPlayer().get()
    val useExternalDownloader = downloadPreferences.useExternalDownloader().get()

    val isUpdateIntervalEnabled =
        LibraryPreferences.ANIME_OUTSIDE_RELEASE_PERIOD in libraryPreferences.autoUpdateAnimeRestrictions().get()

    private val selectedPositions: Array<Int> = arrayOf(-1, -1)
    private val selectedEpisodeIds: HashSet<Long> = HashSet()

    internal var isFromChangeCategory: Boolean = false

    internal val autoOpenTrack: Boolean
        get() = successState?.trackingAvailable == true && trackPreferences.trackOnAddingToLibrary().get()

    val showFileSize = storagePreferences.showEpisodeFileSize().get()

    private inline fun updateSuccessState(func: (State.Success) -> State.Success) {
        mutableState.update {
            when (it) {
                State.Loading -> it
                is State.Success -> func(it)
            }
        }
    }

    init {
        screenModelScope.launchIO {
            val initialAnime = getAnimeAndEpisodes.awaitManga(animeId)
            val initialEpisodes = getAnimeAndEpisodes.awaitChapters(animeId).toEpisodeListItems(initialAnime)

            if (!initialAnime.favorite) {
                setAnimeDefaultEpisodeFlags.await(initialAnime)
            }

            val animeSource = Injekt.get<SourceManager>().getOrStub(initialAnime.source)
            if (animeSource.isSourceForTorrents()) {
                TorrentServerService.start()
                TorrentServerService.wait(10)
                TorrentServerUtils.setTrackersList()
            }

            // Set initial state from database
            mutableState.update {
                State.Success(
                    anime = initialAnime,
                    source = animeSource,
                    isFromSource = isFromSource,
                    episodes = initialEpisodes,
                    isRefreshingData = !initialAnime.initialized || initialEpisodes.isEmpty(),
                    dialog = null,
                )
            }

            // Reactive stream for all subsequent updates (DB changes, downloads, etc.)
            combine(
                getAnimeAndEpisodes.subscribe(animeId).distinctUntilChanged(),
                downloadCache.changes,
                downloadManager.queueState,
            ) { animeAndEpisodes, _, _ -> animeAndEpisodes }
                .onEach { (anime, episodes) ->
                    val oldAnime = successState?.anime
                    updateSuccessState {
                        it.copy(
                            anime = anime,
                            episodes = episodes.toEpisodeListItems(anime),
                        )
                    }
                    // If details were just loaded (genre added), retry suggestions
                    if (oldAnime?.genre.isNullOrEmpty() && !anime.genre.isNullOrEmpty() && successState?.suggestionSections.isNullOrEmpty()) {
                        fetchSuggestions(anime)
                    }
                }
                .launchIn(this)
            
            observeDownloads()
            observeTrackers()
            observeSeasons()

            if (isActive) {
                val needRefreshInfo = !initialAnime.initialized
                val needRefreshEpisode = initialEpisodes.isEmpty()
                
                // Refresh if not initialized or if it's been a while (smart auto-refresh)
                val isStale = System.currentTimeMillis() - initialAnime.lastUpdate > 10 * 60 * 1000L // 10 minutes
                
                if (needRefreshInfo || needRefreshEpisode || isStale) {
                    val fetchFromSourceTasks = listOf(
                        async { fetchAnimeFromSource() },
                        async { fetchEpisodesFromSource() },
                    )
                    fetchFromSourceTasks.awaitAll()
                }
            }

            updateSuccessState { it.copy(isRefreshingData = false) }
            fetchSuggestions(initialAnime)
        }
    }

    fun fetchAllFromSource(manualFetch: Boolean = true) {
        screenModelScope.launch {
            updateSuccessState { it.copy(isRefreshingData = true) }
            val fetchFromSourceTasks = listOf(
                async { fetchAnimeFromSource(manualFetch) },
                async { fetchEpisodesFromSource(manualFetch) },
            )
            fetchFromSourceTasks.awaitAll()
            updateSuccessState { it.copy(isRefreshingData = false) }
            successState?.let { updateAiringTime(it.anime, it.trackItems, manualFetch) }
        }
    }

    private suspend fun fetchAnimeFromSource(manualFetch: Boolean = false) {
        val state = successState ?: return
        try {
            withIOContext {
                val networkAnime = state.source.getAnimeDetails(state.anime.toSAnime())
                updateAnime.awaitUpdateFromSource(state.anime, networkAnime, manualFetch)
            }
        } catch (e: Throwable) {
            if (e is HttpException && e.code == 103) return
            logcat(LogPriority.ERROR, e)
            screenModelScope.launch {
                snackbarHostState.showSnackbar(message = with(context) { e.formattedMessage })
            }
        }
    }

    private data class CachedSuggestions(
        val sections: ImmutableList<SuggestionSection>,
        val timestamp: Long,
    )

    private companion object {
        // Limit to 50 anime to prevent OOM, LruCache is thread-safe
        private val suggestionsCache = android.util.LruCache<Long, CachedSuggestions>(50)
        private const val CACHE_TTL = 60 * 60 * 1000L // 1 hour
    }

    private suspend fun fetchSuggestions(anime: Anime) {
        val now = System.currentTimeMillis()
        val cached = suggestionsCache.get(anime.id)
        if (cached != null && (now - cached.timestamp) < CACHE_TTL) {
            updateSuccessState { it.copy(suggestionSections = cached.sections) }
            return
        }

        screenModelScope.launchIO {
            // Update affinity vector in background if needed
            calculateUserAffinity.await()
            
            val source = sourceManager.get(anime.source) as? AnimeCatalogueSource ?: return@launchIO
            val library = getLibraryAnime.await()
            
            val affinityMap = try {
                val json = Json.parseToJsonElement(libraryPreferences.userAffinityMap().get()).jsonObject
                json.mapValues { it.value.jsonPrimitive.float }
            } catch (e: Exception) { emptyMap<String, Float>() }

            // Only deduplicate against the current anime itself to keep density high as requested
            val initialSections = SuggestionSection.Type.entries.map { type ->
                SuggestionSection(
                    title = when (type) {
                        SuggestionSection.Type.Franchise -> "Series & Sequels"
                        SuggestionSection.Type.Similarity -> "Similar Media"
                        SuggestionSection.Type.Author -> "More by Studio"
                        SuggestionSection.Type.Source -> "Recommended"
                        SuggestionSection.Type.Tag -> "You Might Like"
                    },
                    items = persistentListOf(),
                    type = type
                )
            }.toMutableList()

            fun rankAndSortItems(items: List<Anime>, currentAnime: Anime, type: SuggestionSection.Type): List<Anime> {
                val currentClean = eu.kanade.tachiyomi.util.lang.StringSimilarity.cleanTitle(currentAnime.title)
                return items.distinctBy { it.id }
                    .filter { it.id != currentAnime.id }
                    .map { candidate ->
                        val candClean = eu.kanade.tachiyomi.util.lang.StringSimilarity.cleanTitle(candidate.title)
                        
                        // 1. Metadata Similarity (Order independent)
                        val titleSim = eu.kanade.tachiyomi.util.lang.StringSimilarity.tokenSortRatio(currentClean, candClean)
                        
                        // 2. User Affinity Score
                        var affinityScore = 0f
                        candidate.genre?.forEach { tag ->
                            affinityScore += affinityMap[tag.trim().lowercase()] ?: 0f
                        }
                        
                        // Author/Studio Match Boost
                        val candAuthor = candidate.author?.lowercase() ?: ""
                        val curAuthor = currentAnime.author?.lowercase() ?: ""
                        if (candAuthor.isNotEmpty() && candAuthor == curAuthor) {
                            affinityScore += 2.0f
                        }

                        // 3. Franchise Context
                        val isFranchise = library.any { lib ->
                            eu.kanade.tachiyomi.util.lang.StringSimilarity.tokenSortRatio(candClean, eu.kanade.tachiyomi.util.lang.StringSimilarity.cleanTitle(lib.anime.title)) > 85
                        }
                        
                        val franchiseWeight = if (type == SuggestionSection.Type.Franchise) 3.0f else if (isFranchise) 0.4f else 1.0f
                        val baseScore = 1.0f

                        candidate to ((baseScore + affinityScore) * (0.3f + titleSim.toFloat()) * franchiseWeight)
                    }
                    .sortedByDescending { it.second }
                    .map { it.first }
            }

            fun updateSection(type: SuggestionSection.Type, items: List<Anime>) {
                updateSuccessState { state ->
                    val index = initialSections.indexOfFirst { it.type == type }
                    if (index != -1) {
                        val rankedItems = rankAndSortItems(items, state.anime, type)
                        initialSections[index] = initialSections[index].copy(items = rankedItems.toImmutableList())
                        
                        // Fallback: If Recommended (Source) is empty but Franchise has items, mirror them to Recommended
                        if (type == SuggestionSection.Type.Franchise && initialSections.find { it.type == SuggestionSection.Type.Source }?.items.isNullOrEmpty()) {
                            val sourceIndex = initialSections.indexOfFirst { it.type == SuggestionSection.Type.Source }
                            if (sourceIndex != -1) {
                                initialSections[sourceIndex] = initialSections[sourceIndex].copy(items = rankedItems.take(10).toImmutableList())
                            }
                        }
                    }
                    val finalSections = initialSections
                        .filter { it.items.isNotEmpty() }
                        .sortedBy { it.type }
                        .toImmutableList()
                    suggestionsCache.put(anime.id, CachedSuggestions(finalSections, System.currentTimeMillis()))
                    state.copy(suggestionSections = finalSections)
                }
            }

            // 0. Franchise & Sequels (Strict Verification)
            launchIO {
                kotlinx.coroutines.coroutineScope {
                    try {
                        val rawVirtualSeasons = discoverSeasons.await(anime)
                        if (rawVirtualSeasons.isNotEmpty()) {
                            val validSeasons = rawVirtualSeasons
                                .map { async { networkToLocalAnime.await(it) } }
                                .awaitAll()
                                .mapNotNull { getAnime.await(it.id) }
                            
                            if (validSeasons.isNotEmpty()) {
                                updateSection(SuggestionSection.Type.Franchise, validSeasons)
                            }
                        }
                    } catch (_: Exception) {}
                }
            }

            // 1. Similar Media (Broad Search Probe)
            launchIO {
                kotlinx.coroutines.coroutineScope {
                    val keywords = eu.kanade.tachiyomi.util.lang.StringSimilarity.getSearchKeywords(anime.title)
                    try {
                        val searchResult = source.getSearchAnime(1, keywords, source.getFilterList())
                        val domainAnimes = searchResult.animes
                            .map { async { networkToLocalAnime.await(it.toDomainAnime(anime.source)) } }
                            .awaitAll()
                            .mapNotNull { getAnime.await(it.id) }
                        if (domainAnimes.isNotEmpty()) updateSection(SuggestionSection.Type.Similarity, domainAnimes)
                    } catch (_: Exception) {}
                }
            }

            // 2. Author/Studio (Parallel Split Search)
            launchIO {
                kotlinx.coroutines.coroutineScope {
                    val authors = anime.author?.split(",")?.map { it.trim() }?.filter { it.length > 2 && it != "Unknown" } ?: emptyList()
                    val results = authors.take(2).map { author ->
                        async {
                            try {
                                val searchResult = source.getSearchAnime(1, author, source.getFilterList())
                                searchResult.animes
                                    .map { async { networkToLocalAnime.await(it.toDomainAnime(anime.source)) } }
                                    .awaitAll()
                                    .mapNotNull { getAnime.await(it.id) }
                            } catch (_: Exception) {
                                emptyList()
                            }
                        }
                    }.awaitAll().flatten()
                    
                    if (results.isNotEmpty()) updateSection(SuggestionSection.Type.Author, results)
                }
            }

            // 3. Official Related (Source Provided)
            launchIO {
                getRelatedAnime.subscribe(anime).collect { (_, animes) ->
                    if (animes.isNotEmpty()) {
                        kotlinx.coroutines.coroutineScope {
                            val domainAnimes = animes
                                .map { async { networkToLocalAnime.await(it.toDomainAnime(anime.source)) } }
                                .awaitAll()
                                .mapNotNull { getAnime.await(it.id) }
                            updateSection(SuggestionSection.Type.Source, domainAnimes)
                        }
                    }
                }
            }

            // 4. Smart Recommendations (Parallel Tag Search)
            launchIO {
                kotlinx.coroutines.coroutineScope {
                    val tags = anime.genre?.take(3) ?: emptyList()
                    val results = tags.map { tag ->
                        async {
                            try {
                                val searchResult = source.getSearchAnime(1, tag, source.getFilterList())
                                searchResult.animes
                                    .map { async { networkToLocalAnime.await(it.toDomainAnime(anime.source)) } }
                                    .awaitAll()
                                    .mapNotNull { getAnime.await(it.id) }
                            } catch (_: Exception) {
                                emptyList()
                            }
                        }
                    }.awaitAll().flatten()
                    
                    if (results.isNotEmpty()) updateSection(SuggestionSection.Type.Tag, results)
                }
            }
        }
    }

    fun updateAnimeInfo(
        title: String?,
        author: String?,
        artist: String?,
        thumbnailUrl: String?,
        description: String?,
        tags: List<String>?,
        status: Long?,
        score: Double?,
    ) {
        val state = successState ?: return
        var anime = state.anime
        
        if (status != null || score != null) {
            screenModelScope.launchIO {
                val dbTrack = eu.kanade.tachiyomi.data.database.models.Track.create(TrackerManager.LOCAL).apply {
                    anime_id = anime.id
                    this.title = anime.title.ifBlank { anime.ogTitle }
                    this.status = status ?: anime.ogStatus
                    this.score = score ?: (anime.score ?: 0.0)
                }
                insertTrack.await(dbTrack.toDomainTrack(idRequired = false)!!)
            }
        }

        if (state.anime.isLocal()) {
            val newTitle = if (title.isNullOrBlank()) anime.url else title.trim()
            val newAuthor = author?.trimOrNull()
            val newArtist = artist?.trimOrNull()
            val newDesc = description?.trimOrNull()
            anime = anime.copy(
                ogTitle = newTitle,
                ogAuthor = author?.trimOrNull(),
                ogArtist = artist?.trimOrNull(),
                ogDescription = description?.trimOrNull(),
                ogGenre = tags?.nullIfEmpty(),
                ogStatus = status ?: 0,
                lastUpdate = anime.lastUpdate + 1,
            )
            (sourceManager.get(LocalSource.ID) as LocalSource).updateAnimeInfo(anime.toSAnime())
            screenModelScope.launchNonCancellable {
                updateAnime.await(
                    AnimeUpdate(
                        anime.id,
                        title = newTitle,
                        author = newAuthor,
                        artist = newArtist,
                        description = newDesc,
                        genre = tags,
                        status = status,
                    ),
                )
            }
        } else {
            val genre = if (!tags.isNullOrEmpty() && tags != state.anime.ogGenre) tags else null
            setCustomAnimeInfo.set(
                CustomAnimeInfo(
                    state.anime.id,
                    title?.trimOrNull(),
                    author?.trimOrNull(),
                    artist?.trimOrNull(),
                    thumbnailUrl?.trimOrNull(),
                    description?.trimOrNull(),
                    genre,
                    status.takeUnless { it == state.anime.ogStatus },
                    score,
                ),
            )
            anime = anime.copy(lastUpdate = anime.lastUpdate + 1)
        }

        updateSuccessState { it.copy(anime = anime) }
    }

    fun toggleFavorite() {
        toggleFavorite(
            onRemoved = {
                screenModelScope.launch {
                    if (!hasDownloads()) return@launch
                    val result = snackbarHostState.showSnackbar(
                        message = context.stringResource(MR.strings.delete_downloads_for_anime),
                        actionLabel = context.stringResource(MR.strings.action_delete),
                        withDismissAction = true,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        deleteDownloads()
                    }
                }
            },
        )
    }

    fun toggleFavorite(onRemoved: () -> Unit, checkDuplicate: Boolean = true) {
        val state = successState ?: return
        screenModelScope.launchIO {
            val anime = state.anime
            if (isFavorited) {
                if (updateAnime.awaitUpdateFavorite(anime.id, false)) {
                    if (trackPreferences.autoTrackWhenWatching().get()) {
                        val tracks = getTracks.await(anime.id)
                        val localTrack = tracks.find { it.trackerId == TrackerManager.LOCAL }
                        if (localTrack != null) {
                            when {
                                // If never started, delete track to keep history clean
                                localTrack.lastEpisodeSeen == 0.0 -> {
                                    deleteTrack.await(anime.id, TrackerManager.LOCAL)
                                }
                                // If already completed, leave it as completed
                                localTrack.status == eu.kanade.tachiyomi.data.track.local.LocalTracker.COMPLETED -> {}
                                // Otherwise, mark as dropped (including movies with progress)
                                else -> insertTrack.await(localTrack.copy(status = eu.kanade.tachiyomi.data.track.local.LocalTracker.DROPPED))
                            }
                        }
                    }
                    if (anime.removeCovers() != anime) {
                        updateAnime.awaitUpdateCoverLastModified(anime.id)
                    }
                    withUIContext { onRemoved() }
                }
            } else {
                if (checkDuplicate) {
                    val duplicate = getDuplicateLibraryAnime.await(anime).getOrNull(0)
                    if (duplicate != null) {
                        updateSuccessState { it.copy(dialog = Dialog.DuplicateAnime(anime, duplicate)) }
                        return@launchIO
                    }
                }
                val categories = getCategories()
                val defaultCategoryId = libraryPreferences.defaultCategory().get().toLong()
                val defaultCategory = categories.find { it.id == defaultCategoryId }
                when {
                    defaultCategory != null -> {
                        if (updateAnime.awaitUpdateFavorite(anime.id, true)) moveAnimeToCategory(defaultCategory)
                    }
                    defaultCategoryId == 0L || categories.isEmpty() -> {
                        if (updateAnime.awaitUpdateFavorite(anime.id, true)) moveAnimeToCategory(null)
                    }
                    else -> {
                        isFromChangeCategory = true
                        showChangeCategoryDialog()
                    }
                }

                if (trackPreferences.autoAddTrack().get()) {
                    addTracks.bindEnhancedTrackers(anime, state.source)

                    val tracks = getTracks.await(anime.id)
                    var localTrack = tracks.find { it.trackerId == TrackerManager.LOCAL }
                    if (localTrack == null) {
                        val episodes = getAnimeAndEpisodes.awaitChapters(anime.id)
                        val seenCount = episodes.count { it.seen }
                        val dbTrack = eu.kanade.tachiyomi.data.database.models.Track.create(TrackerManager.LOCAL).apply {
                            this.anime_id = anime.id
                            this.title = anime.title
                            this.last_episode_seen = seenCount.toDouble()
                            this.total_episodes = episodes.size.toLong()
                            this.status = when {
                                episodes.isNotEmpty() && (seenCount == episodes.size) -> eu.kanade.tachiyomi.data.track.local.LocalTracker.COMPLETED
                                seenCount > 0 -> eu.kanade.tachiyomi.data.track.local.LocalTracker.WATCHING
                                else -> eu.kanade.tachiyomi.data.track.local.LocalTracker.PLAN_TO_WATCH
                            }
                        }
                        localTrack = dbTrack.toDomainTrack(idRequired = false)
                    } else if (localTrack.status == eu.kanade.tachiyomi.data.track.local.LocalTracker.DROPPED) {
                        localTrack = localTrack.copy(status = eu.kanade.tachiyomi.data.track.local.LocalTracker.PLAN_TO_WATCH)
                    }
                    localTrack?.let { insertTrack.await(it) }
                }
            }
        }
    }

    fun showChangeCategoryDialog() {
        val anime = successState?.anime ?: return
        screenModelScope.launch {
            val categories = getCategories()
            val selection = getAnimeCategoryIds(anime)
            updateSuccessState { it.copy(
                dialog = Dialog.ChangeCategory(
                    anime = anime,
                    initialSelection = categories.mapAsCheckboxState { it.id in selection }.toImmutableList(),
                ),
            )}
        }
    }

    fun showSetAnimeFetchIntervalDialog() {
        val anime = successState?.anime ?: return
        updateSuccessState { it.copy(dialog = Dialog.SetAnimeFetchInterval(anime)) }
    }

    fun setFetchInterval(anime: Anime, interval: Int) {
        screenModelScope.launchIO {
            if (updateAnime.awaitUpdateFetchInterval(anime.copy(fetchInterval = -interval))) {
                val updatedAnime = animeRepository.getAnimeById(anime.id)
                updateSuccessState { it.copy(anime = updatedAnime) }
            }
        }
    }

    private fun hasDownloads(): Boolean {
        val anime = successState?.anime ?: return false
        return downloadManager.getDownloadCount(anime) > 0
    }

    private fun deleteDownloads() {
        val state = successState ?: return
        downloadManager.deleteAnime(state.anime, state.source)
    }

    suspend fun getCategories(): List<Category> {
        return getCategories.await().filterNot { it.isSystemCategory }
    }

    private suspend fun getAnimeCategoryIds(anime: Anime): List<Long> {
        return getCategories.await(anime.id).map { it.id }
    }

    fun moveAnimeToCategoriesAndAddToLibrary(anime: Anime, categories: List<Long>) {
        moveAnimeToCategory(categories)
        if (anime.favorite) return
        screenModelScope.launchIO { updateAnime.awaitUpdateFavorite(anime.id, true) }
    }

    private fun moveAnimeToCategory(categoryIds: List<Long>) {
        screenModelScope.launchIO { setAnimeCategories.await(animeId, categoryIds) }
    }

    private fun moveAnimeToCategory(category: Category?) {
        moveAnimeToCategory(listOfNotNull(category?.id))
    }

    private fun observeDownloads() {
        screenModelScope.launchIO {
            downloadManager.statusFlow()
                .filter { it.anime.id == successState?.anime?.id }
                .catch { error -> logcat(LogPriority.ERROR, error) }
                .flowWithLifecycle(lifecycle)
                .collect { withUIContext { updateDownloadState(it) } }
        }
        screenModelScope.launchIO {
            downloadManager.progressFlow()
                .filter { it.anime.id == successState?.anime?.id }
                .catch { error -> logcat(LogPriority.ERROR, error) }
                .flowWithLifecycle(lifecycle)
                .collect { withUIContext { updateDownloadState(it) } }
        }
    }

    private fun updateDownloadState(download: Download) {
        updateSuccessState { successState ->
            val modifiedIndex = successState.episodes.indexOfFirst { it.id == download.episode.id }
            if (modifiedIndex < 0) return@updateSuccessState successState
            val newEpisodes = successState.episodes.toMutableList().apply {
                val item = removeAt(modifiedIndex).copy(downloadState = download.status, downloadProgress = download.progress)
                add(modifiedIndex, item)
            }
            successState.copy(episodes = newEpisodes)
        }
    }

    private fun List<Episode>.toEpisodeListItems(anime: Anime): List<EpisodeList.Item> {
        val isLocal = anime.isLocal()
        val downloadedEpisodeDirs = if (isLocal) emptySet() else downloadManager.getDownloadedEpisodeDirs(anime)
        return map { episode ->
            val activeDownload = if (isLocal) null else downloadManager.getQueuedDownloadOrNull(episode.id)
            val downloaded = if (isLocal) true else if (downloadedEpisodeDirs.isNotEmpty()) {
                downloadProvider.getValidEpisodeDirNames(episode.name, episode.scanlator).any { it in downloadedEpisodeDirs }
            } else false
            val downloadState = when {
                activeDownload != null -> activeDownload.status
                downloaded -> Download.State.DOWNLOADED
                else -> Download.State.NOT_DOWNLOADED
            }
            EpisodeList.Item(episode = episode, downloadState = downloadState, downloadProgress = activeDownload?.progress ?: 0, selected = episode.id in selectedEpisodeIds)
        }
    }

    private suspend fun fetchEpisodesFromSource(manualFetch: Boolean = false) {
        val state = successState ?: return
        try {
            withIOContext {
                val episodes = state.source.getEpisodeList(state.anime.toSAnime())
                val newEpisodes = syncEpisodesWithSource.await(episodes, state.anime, state.source, manualFetch)
                if (manualFetch) downloadNewEpisodes(newEpisodes)
            }
        } catch (e: Throwable) {
            val message = if (e is NoResultsException) context.stringResource(MR.strings.no_episodes_error) else {
                logcat(LogPriority.ERROR, e)
                with(context) { e.formattedMessage }
            }
            screenModelScope.launch { snackbarHostState.showSnackbar(message = message) }
            val newAnime = animeRepository.getAnimeById(animeId)
            updateSuccessState { it.copy(anime = newAnime, isRefreshingData = false) }
        }
    }

    fun episodeSwipe(episodeItem: EpisodeList.Item, swipeAction: LibraryPreferences.EpisodeSwipeAction) {
        screenModelScope.launch { executeEpisodeSwipeAction(episodeItem, swipeAction) }
    }

    private fun executeEpisodeSwipeAction(episodeItem: EpisodeList.Item, swipeAction: LibraryPreferences.EpisodeSwipeAction) {
        val episode = episodeItem.episode
        when (swipeAction) {
            LibraryPreferences.EpisodeSwipeAction.ToggleSeen -> markEpisodesSeen(listOf(episode), !episode.seen)
            LibraryPreferences.EpisodeSwipeAction.ToggleBookmark -> bookmarkEpisodes(listOf(episode), !episode.bookmark)
            LibraryPreferences.EpisodeSwipeAction.ToggleFillermark -> fillermarkEpisodes(listOf(episode), !episode.fillermark)
            LibraryPreferences.EpisodeSwipeAction.Download -> {
                val downloadAction: EpisodeDownloadAction = when (episodeItem.downloadState) {
                    Download.State.ERROR, Download.State.NOT_DOWNLOADED, Download.State.PAUSED -> EpisodeDownloadAction.START_NOW
                    Download.State.QUEUE, Download.State.DOWNLOADING, Download.State.MERGING -> EpisodeDownloadAction.CANCEL
                    Download.State.DOWNLOADED -> EpisodeDownloadAction.DELETE
                }
                runEpisodeDownloadActions(items = listOf(episodeItem), action = downloadAction)
            }
            LibraryPreferences.EpisodeSwipeAction.Disabled -> throw IllegalStateException()
        }
    }

    fun getNextUnseenEpisode(): Episode? {
        val successState = successState ?: return null
        return successState.episodes.getNextUnseen(successState.anime)
    }

    private fun getUnseenEpisodes(): List<Episode> {
        return successState?.processedEpisodes?.filter { (episode, dlStatus) -> !episode.seen && dlStatus == Download.State.NOT_DOWNLOADED }?.map { it.episode }?.toList() ?: emptyList()
    }

    private fun getUnseenEpisodesSorted(): List<Episode> {
        val anime = successState?.anime ?: return emptyList()
        val episodes = getUnseenEpisodes().sortedWith(getEpisodeSort(anime))
        return if (anime.sortDescending()) episodes.reversed() else episodes
    }

    private fun startDownload(episodes: List<Episode>, startNow: Boolean, video: Video? = null) {
        val successState = successState ?: return
        screenModelScope.launchNonCancellable {
            if (startNow) {
                val episodeId = episodes.singleOrNull()?.id ?: return@launchNonCancellable
                downloadManager.startDownloadNow(episodeId)
            } else downloadEpisodes(episodes, useExternalDownloader, video)
            if (!isFavorited && !successState.hasPromptedToAddBefore) {
                updateSuccessState { it.copy(hasPromptedToAddBefore = true) }
                val result = snackbarHostState.showSnackbar(message = context.stringResource(MR.strings.snack_add_to_anime_library), actionLabel = context.stringResource(MR.strings.action_add), withDismissAction = true)
                if (result == SnackbarResult.ActionPerformed && !isFavorited) toggleFavorite()
            }
        }
    }

    fun runEpisodeDownloadActions(items: List<EpisodeList.Item>, action: EpisodeDownloadAction) {
        when (action) {
            EpisodeDownloadAction.START -> {
                startDownload(items.map { it.episode }, false)
                if (items.any { it.downloadState == Download.State.ERROR }) downloadManager.startDownloads()
            }
            EpisodeDownloadAction.START_NOW -> startDownload(listOf(items.singleOrNull()?.episode ?: return), true)
            EpisodeDownloadAction.CANCEL -> cancelDownload(items.singleOrNull()?.id ?: return)
            EpisodeDownloadAction.DELETE -> deleteEpisodes(items.map { it.episode })
            EpisodeDownloadAction.SHOW_QUALITIES -> showQualitiesDialog(items.singleOrNull()?.episode ?: return)
        }
    }

    fun runDownloadAction(action: DownloadAction) {
        val episodesToDownload = when (action) {
            DownloadAction.NEXT_1_EPISODE -> getUnseenEpisodesSorted().take(1)
            DownloadAction.NEXT_5_EPISODES -> getUnseenEpisodesSorted().take(5)
            DownloadAction.NEXT_10_EPISODES -> getUnseenEpisodesSorted().take(10)
            DownloadAction.NEXT_25_EPISODES -> getUnseenEpisodesSorted().take(25)
            DownloadAction.UNSEEN_EPISODES -> getUnseenEpisodes()
        }
        if (episodesToDownload.isNotEmpty()) startDownload(episodesToDownload, false)
    }

    private fun cancelDownload(episodeId: Long) {
        val activeDownload = downloadManager.getQueuedDownloadOrNull(episodeId) ?: return
        downloadManager.cancelQueuedDownloads(listOf(activeDownload))
        updateDownloadState(activeDownload.apply { status = Download.State.NOT_DOWNLOADED })
    }

    fun markPreviousEpisodeSeen(pointer: Episode) {
        val anime = successState?.anime ?: return
        val episodes = processedEpisodes.orEmpty().map { it.episode }.toList()
        val prevEpisodes = if (anime.sortDescending()) episodes.asReversed() else episodes
        val pointerPos = prevEpisodes.indexOf(pointer)
        if (pointerPos != -1) markEpisodesSeen(prevEpisodes.take(pointerPos), true)
    }

    fun markEpisodesSeen(episodes: List<Episode>, seen: Boolean) {
        toggleAllSelection(false)
        screenModelScope.launchIO {
            setSeenStatus.await(seen = seen, episodes = episodes.toTypedArray())
            if (!seen || successState?.hasLoggedInTrackers == false || autoTrackState == AutoTrackState.NEVER) return@launchIO
            val tracks = getTracks.await(animeId)
            val maxEpisodeNumber = episodes.maxOf { it.episodeNumber }
            if (tracks.none { it.lastEpisodeSeen < maxEpisodeNumber }) return@launchIO
            if (autoTrackState == AutoTrackState.ALWAYS) {
                trackEpisode.await(context, animeId, maxEpisodeNumber)
                withUIContext { context.toast(context.stringResource(MR.strings.trackers_updated_summary_anime, maxEpisodeNumber.toInt())) }
                return@launchIO
            }
            val result = snackbarHostState.showSnackbar(message = context.stringResource(MR.strings.confirm_tracker_update_anime, maxEpisodeNumber.toInt()), actionLabel = context.stringResource(MR.strings.action_ok), duration = SnackbarDuration.Short, withDismissAction = true)
            if (result == SnackbarResult.ActionPerformed) trackEpisode.await(context, animeId, maxEpisodeNumber)
        }
    }

    private fun downloadEpisodes(episodes: List<Episode>, alt: Boolean = false, video: Video? = null) {
        val anime = successState?.anime ?: return
        downloadManager.downloadEpisodes(anime, episodes, true, alt, video)
        toggleAllSelection(false)
    }

    fun bookmarkEpisodes(episodes: List<Episode>, bookmarked: Boolean) {
        screenModelScope.launchIO {
            episodes.filterNot { it.bookmark == bookmarked }.map { EpisodeUpdate(id = it.id, bookmark = bookmarked) }.let { updateEpisode.awaitAll(it) }
        }
        toggleAllSelection(false)
    }

    fun fillermarkEpisodes(episodes: List<Episode>, fillermarked: Boolean) {
        screenModelScope.launchIO {
            episodes.filterNot { it.fillermark == fillermarked }.map { EpisodeUpdate(id = it.id, fillermark = fillermarked) }.let { updateEpisode.awaitAll(it) }
        }
        toggleAllSelection(false)
    }

    fun deleteEpisodes(episodes: List<Episode>) {
        screenModelScope.launchNonCancellable {
            try {
                successState?.let { state -> downloadManager.deleteEpisodes(episodes, state.anime, state.source) }
            } catch (e: Throwable) { logcat(LogPriority.ERROR, e) }
        }
    }

    private fun downloadNewEpisodes(episodes: List<Episode>) {
        screenModelScope.launchNonCancellable {
            val anime = successState?.anime ?: return@launchNonCancellable
            val episodesToDownload = filterEpisodesForDownload.await(anime, episodes)
            if (episodesToDownload.isNotEmpty()) downloadEpisodes(episodesToDownload)
        }
    }

    fun setUnseenFilter(state: TriState) {
        val anime = successState?.anime ?: return
        val flag = when (state) {
            TriState.DISABLED -> Anime.SHOW_ALL
            TriState.ENABLED_IS -> Anime.EPISODE_SHOW_UNSEEN
            TriState.ENABLED_NOT -> Anime.EPISODE_SHOW_SEEN
        }
        screenModelScope.launchNonCancellable { setAnimeEpisodeFlags.awaitSetUnreadFilter(anime, flag) }
    }

    fun setDownloadedFilter(state: TriState) {
        val anime = successState?.anime ?: return
        val flag = when (state) {
            TriState.DISABLED -> Anime.SHOW_ALL
            TriState.ENABLED_IS -> Anime.EPISODE_SHOW_DOWNLOADED
            TriState.ENABLED_NOT -> Anime.EPISODE_SHOW_NOT_DOWNLOADED
        }
        screenModelScope.launchNonCancellable { setAnimeEpisodeFlags.awaitSetDownloadedFilter(anime, flag) }
    }

    fun setBookmarkedFilter(state: TriState) {
        val anime = successState?.anime ?: return
        val flag = when (state) {
            TriState.DISABLED -> Anime.SHOW_ALL
            TriState.ENABLED_IS -> Anime.EPISODE_SHOW_BOOKMARKED
            TriState.ENABLED_NOT -> Anime.EPISODE_SHOW_NOT_BOOKMARKED
        }
        screenModelScope.launchNonCancellable { setAnimeEpisodeFlags.awaitSetBookmarkFilter(anime, flag) }
    }

    fun setFillermarkedFilter(state: TriState) {
        val anime = successState?.anime ?: return
        val flag = when (state) {
            TriState.DISABLED -> Anime.SHOW_ALL
            TriState.ENABLED_IS -> Anime.EPISODE_SHOW_FILLERMARKED
            TriState.ENABLED_NOT -> Anime.EPISODE_SHOW_NOT_FILLERMARKED
        }
        screenModelScope.launchNonCancellable { setAnimeEpisodeFlags.awaitSetFillermarkFilter(anime, flag) }
    }

    fun setDisplayMode(mode: Long) {
        val anime = successState?.anime ?: return
        screenModelScope.launchNonCancellable { setAnimeEpisodeFlags.awaitSetDisplayMode(anime, mode) }
    }

    fun setSorting(sort: Long) {
        val anime = successState?.anime ?: return
        screenModelScope.launchNonCancellable { setAnimeEpisodeFlags.awaitSetSortingModeOrFlipOrder(anime, sort) }
    }

    fun setCurrentSettingsAsDefault(applyToExisting: Boolean) {
        val anime = successState?.anime ?: return
        screenModelScope.launchNonCancellable {
            libraryPreferences.setEpisodeSettingsDefault(anime)
            if (applyToExisting) setAnimeDefaultEpisodeFlags.awaitAll()
            snackbarHostState.showSnackbar(message = context.stringResource(MR.strings.episode_settings_updated))
        }
    }

    fun toggleSelection(item: EpisodeList.Item, selected: Boolean, userSelected: Boolean = false, fromLongPress: Boolean = false) {
        updateSuccessState { successState ->
            val newEpisodes = successState.processedEpisodes.toMutableList().apply {
                val selectedIndex = successState.processedEpisodes.indexOfFirst { it.id == item.episode.id }
                if (selectedIndex < 0) return@apply
                val selectedItem = get(selectedIndex)
                if ((selectedItem.selected && selected) || (!selectedItem.selected && !selected)) return@apply
                val firstSelection = none { it.selected }
                set(selectedIndex, selectedItem.copy(selected = selected))
                selectedEpisodeIds.addOrRemove(item.id, selected)
                if (selected && userSelected && fromLongPress) {
                    if (firstSelection) {
                        selectedPositions[0] = selectedIndex
                        selectedPositions[1] = selectedIndex
                    } else {
                        val range = if (selectedIndex < selectedPositions[0]) {
                            val r = selectedIndex + 1..<selectedPositions[0]
                            selectedPositions[0] = selectedIndex
                            r
                        } else if (selectedIndex > selectedPositions[1]) {
                            val r = (selectedPositions[1] + 1)..<selectedIndex
                            selectedPositions[1] = selectedIndex
                            r
                        } else IntRange.EMPTY
                        range.forEach {
                            val inbetweenItem = get(it)
                            if (!inbetweenItem.selected) {
                                selectedEpisodeIds.add(inbetweenItem.id)
                                set(it, inbetweenItem.copy(selected = true))
                            }
                        }
                    }
                } else if (userSelected && !fromLongPress) {
                    if (!selected) {
                        if (selectedIndex == selectedPositions[0]) selectedPositions[0] = indexOfFirst { it.selected }
                        else if (selectedIndex == selectedPositions[1]) selectedPositions[1] = indexOfLast { it.selected }
                    } else {
                        if (selectedIndex < selectedPositions[0]) selectedPositions[0] = selectedIndex
                        else if (selectedIndex > selectedPositions[1]) selectedPositions[1] = selectedIndex
                    }
                }
            }
            successState.copy(episodes = newEpisodes)
        }
    }

    fun toggleAllSelection(selected: Boolean) {
        updateSuccessState { successState ->
            val newEpisodes = successState.episodes.map {
                selectedEpisodeIds.addOrRemove(it.id, selected)
                it.copy(selected = selected)
            }
            selectedPositions[0] = -1
            selectedPositions[1] = -1
            successState.copy(episodes = newEpisodes)
        }
    }

    fun invertSelection() {
        updateSuccessState { successState ->
            val newEpisodes = successState.episodes.map {
                selectedEpisodeIds.addOrRemove(it.id, !it.selected)
                it.copy(selected = !it.selected)
            }
            selectedPositions[0] = -1
            selectedPositions[1] = -1
            successState.copy(episodes = newEpisodes)
        }
    }

    private fun observeTrackers() {
        val anime = successState?.anime ?: return
        screenModelScope.launchIO {
            combine(getTracks.subscribe(anime.id).catch { logcat(LogPriority.ERROR, it) }, trackerManager.loggedInTrackersFlow()) { animeTracks, loggedInTrackers ->
                val supportedTrackers = loggedInTrackers.filter { (it as? EnhancedTracker)?.accept(source!!) ?: true }
                val supportedTrackerIds = supportedTrackers.map { it.id }.toHashSet()
                val supportedTrackerTracks = animeTracks.filter { it.trackerId in supportedTrackerIds }
                supportedTrackerTracks.size to supportedTrackers.isNotEmpty()
            }.flowWithLifecycle(lifecycle).distinctUntilChanged().collectLatest { (trackingCount, hasLoggedInTrackers) ->
                updateSuccessState { it.copy(trackingCount = trackingCount, hasLoggedInTrackers = hasLoggedInTrackers) }
            }
        }
        screenModelScope.launchIO {
            combine(getTracks.subscribe(anime.id).catch { logcat(LogPriority.ERROR, it) }, trackerManager.loggedInTrackersFlow()) { animeTracks, loggedInTrackers ->
                loggedInTrackers.map { service -> TrackItem(animeTracks.find { it.trackerId == service.id }, service) }
            }.distinctUntilChanged().collectLatest { trackItems -> 
                updateSuccessState { it.copy(trackItems = trackItems) }
                updateAiringTime(anime, trackItems, manualFetch = false) 
            }
        }
    }

    private fun observeSeasons() {
        val virtualSeasonsFlow = state.map { successState ->
            (successState as? State.Success)?.suggestionSections
                ?.find { it.type == SuggestionSection.Type.Franchise }
                ?.items.orEmpty()
        }.distinctUntilChanged()

        getSeasonsByAnimeId.subscribe(animeId, virtualSeasonsFlow)
            .onEach { seasons ->
                updateSuccessState { it.copy(seasons = seasons.toImmutableList()) }
            }
            .launchIn(screenModelScope)
    }

    private suspend fun updateAiringTime(anime: Anime, trackItems: List<TrackItem>, manualFetch: Boolean) {
        val airingEpisodeData = AniChartApi().loadAiringTime(anime, trackItems, manualFetch)
        setAnimeViewerFlags.awaitSetNextEpisodeAiring(anime.id, airingEpisodeData)
        updateSuccessState { it.copy(nextAiringEpisode = airingEpisodeData) }
    }

    sealed interface Dialog {
        data class ChangeCategory(val anime: Anime, val initialSelection: ImmutableList<CheckboxState<Category>>) : Dialog
        data class DeleteEpisodes(val episodes: List<Episode>) : Dialog
        data class DuplicateAnime(val anime: Anime, val duplicate: Anime) : Dialog
        data class Migrate(val newAnime: Anime, val oldAnime: Anime) : Dialog
        data class SetAnimeFetchInterval(val anime: Anime) : Dialog
        data class ShowQualities(val episode: Episode, val anime: Anime, val source: Source) : Dialog
        data class EditAnimeInfo(val anime: Anime) : Dialog
        data class LocalScorePicker(val anime: Anime) : Dialog
        data object ChangeAnimeSkipIntro : Dialog
        data object SettingsSheet : Dialog
        data object TrackSheet : Dialog
        data object FullCover : Dialog
    }

    fun toggleDiscoveryExpansion() {
        updateSuccessState { it.copy(discoveryExpanded = !it.discoveryExpanded) }
    }

    fun dismissDialog() = updateSuccessState { it.copy(dialog = null) }
    fun showDeleteEpisodeDialog(episodes: List<Episode>) = updateSuccessState { it.copy(dialog = Dialog.DeleteEpisodes(episodes)) }
    fun showSettingsDialog() = updateSuccessState { it.copy(dialog = Dialog.SettingsSheet) }
    fun showTrackDialog() = updateSuccessState { it.copy(dialog = Dialog.TrackSheet) }
    fun showCoverDialog() = updateSuccessState { it.copy(dialog = Dialog.FullCover) }
    fun showEditAnimeInfoDialog() = updateSuccessState { it.copy(dialog = Dialog.EditAnimeInfo(it.anime)) }
    fun showLocalScoreDialog() = updateSuccessState { it.copy(dialog = Dialog.LocalScorePicker(it.anime)) }
    fun showMigrateDialog(duplicate: Anime) = updateSuccessState { it.copy(dialog = Dialog.Migrate(newAnime = it.anime, oldAnime = duplicate)) }
    fun showAnimeSkipIntroDialog() = updateSuccessState { it.copy(dialog = Dialog.ChangeAnimeSkipIntro) }
    private fun showQualitiesDialog(episode: Episode) = updateSuccessState { it.copy(dialog = Dialog.ShowQualities(episode, it.anime, it.source)) }

    sealed interface State {
        @Immutable data object Loading : State
        @Immutable data class Success(
            val anime: Anime,
            val source: Source,
            val isFromSource: Boolean,
            val episodes: List<EpisodeList.Item>,
            val trackingCount: Int = 0,
            val hasLoggedInTrackers: Boolean = false,
            val isRefreshingData: Boolean = false,
            val dialog: Dialog? = null,
            val hasPromptedToAddBefore: Boolean = false,
            val trackItems: List<TrackItem> = emptyList(),
            val nextAiringEpisode: Pair<Int, Long> = Pair(anime.nextEpisodeToAir, anime.nextEpisodeAiringAt),
            val suggestions: ImmutableList<Anime> = persistentListOf(),
            val suggestionSections: ImmutableList<SuggestionSection> = persistentListOf(),
            val seasons: ImmutableList<Season> = persistentListOf(),
            val discoveryExpanded: Boolean = false,
        ) : State {
            val totalScore: Double? by lazy {
                val localTrackScore = trackItems.find { it.tracker.id == 999L }?.track?.score?.takeIf { it > 0 }
                localTrackScore ?: anime.score ?: trackItems.mapNotNull { item ->
                    item.track?.let { item.tracker.animeService.get10PointScore(it) }
                }.filter { it > 0 }.average().takeIf { !it.isNaN() }
            }

            val processedEpisodes by lazy { episodes.applyFilters(anime).toList() }
            val episodeListItems by lazy {
                processedEpisodes.insertSeparators { before, after ->
                    val (lowerEpisode, higherEpisode) = if (anime.sortDescending()) {
                        after to before
                    } else {
                        before to after
                    }
                    if (higherEpisode == null) return@insertSeparators null

                    if (lowerEpisode == null) {
                        floor(higherEpisode.episode.episodeNumber)
                            .toInt()
                            .minus(1)
                            .coerceAtLeast(0)
                    } else {
                        calculateChapterGap(higherEpisode.episode, lowerEpisode.episode)
                    }
                        .takeIf { it > 0 }
                        ?.let { missingCount ->
                            EpisodeList.MissingCount(
                                id = "${lowerEpisode?.id}-${higherEpisode.id}",
                                count = missingCount,
                            )
                        }
                }
            }
            val trackingAvailable: Boolean get() = trackItems.isNotEmpty()
            val airingEpisodeNumber: Double get() = nextAiringEpisode.first.toDouble()
            val airingTime: Long get() = nextAiringEpisode.second.times(1000L).minus(Calendar.getInstance().timeInMillis)
            val filterActive: Boolean get() = anime.episodesFiltered()
        }
    }
}