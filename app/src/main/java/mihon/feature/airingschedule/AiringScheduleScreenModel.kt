package mihon.feature.airingschedule

import android.app.Application
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.source.service.SourcePreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.feature.airingschedule.components.BellNotifyState
import mihon.feature.airingschedule.notification.ScheduleNotifications
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.anime.interactor.GetLibraryAnime
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.TimeUnit

import tachiyomi.domain.anime.interactor.FetchInterval
import tachiyomi.domain.library.model.LibraryAnime
import kotlin.math.absoluteValue

class AiringScheduleScreenModel(
    private val repository: AiringScheduleRepository = AiringScheduleRepository(),
    private val schedulePrefs: SchedulePreferences = Injekt.get(),
    private val sourcePreferences: SourcePreferences = Injekt.get(),
    private val uploadDelayTracker: UploadDelayTracker = Injekt.get(),
    private val application: Application = Injekt.get(),
    private val getLibraryAnime: GetLibraryAnime = Injekt.get(),
    private val fetchInterval: FetchInterval = Injekt.get(),
) : StateScreenModel<AiringScheduleScreenModel.State>(State()) {

    private var allEntries: List<AiringScheduleEntry> = emptyList()
    private var remoteEntries: List<AiringScheduleEntry> = emptyList()
    private var libraryPredictedEntries: List<AiringScheduleEntry> = emptyList()
    private var hasLoaded = false
    private var fetchJob: Job? = null

    init {
        loadSchedule()
        observePreferences()
        observeLibrary()
    }

    private fun observeLibrary() {
        screenModelScope.launch {
            getLibraryAnime.subscribe().collectLatest { libraryAnime ->
                val (titles, sourcesByTitle, idByTitle, predicted) = withIOContext {
                    val titles = mutableSetOf<String>()
                    val sourcesByTitle = mutableMapOf<String, Set<String>>()
                    val idByTitle = mutableMapOf<String, Long>()

                    for (lib in libraryAnime) {
                        val animeTitle = lib.anime.title
                        val keys = mihon.feature.airingschedule.util.ScheduleTitleMatcher.normalizedKeys(animeTitle)
                        titles.addAll(keys)
                        val sourceStr = lib.anime.source.toString()
                        for (k in keys) {
                            val existingSources = sourcesByTitle[k].orEmpty()
                            sourcesByTitle[k] = existingSources + sourceStr
                            idByTitle[k] = lib.anime.id
                        }
                    }
                    val predicted = generateLibraryPredictedEntries(libraryAnime)
                    LibraryObservedData(titles, sourcesByTitle, idByTitle, predicted)
                }

                libraryPredictedEntries = predicted
                allEntries = mergeEntries(remoteEntries, libraryPredictedEntries)

                mutableState.update {
                    it.copy(
                        libraryAnimeTitles = titles,
                        librarySourcesByTitle = sourcesByTitle,
                        libraryAnimeIdByTitle = idByTitle,
                    )
                }
                if (allEntries.isNotEmpty()) {
                    applyFilters()
                }
            }
        }
    }

    private suspend fun generateLibraryPredictedEntries(
        libraryAnime: List<LibraryAnime>,
    ): List<AiringScheduleEntry> {
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        val nowMs = System.currentTimeMillis()
        val result = mutableListOf<AiringScheduleEntry>()

        for (lib in libraryAnime) {
            val anime = lib.anime
            if (anime.status == eu.kanade.tachiyomi.animesource.model.SAnime.COMPLETED.toLong()) continue
            if (anime.fetchInterval == FetchInterval.MANUAL_DISABLE) continue

            var nextUpdateMs = anime.nextUpdate
            var intervalDays = if (anime.fetchInterval in 1..FetchInterval.MAX_INTERVAL) {
                anime.fetchInterval
            } else 7

            if (nextUpdateMs <= 0L || nextUpdateMs < nowMs) {
                val update = runCatching {
                    fetchInterval.toAnimeUpdate(anime, now, Pair(0L, 0L))
                }.getOrNull()
                if (update != null) {
                    val updateNext = update.nextUpdate
                    if (updateNext != null && updateNext > 0L) {
                        nextUpdateMs = updateNext
                    }
                    val updateInterval = update.fetchInterval
                    if (updateInterval != null && updateInterval > 0) {
                        intervalDays = updateInterval
                    }
                }
            }

            if (nextUpdateMs <= 0L) continue

            val nextAirSec = nextUpdateMs / 1000L
            val intervalSec = intervalDays * 86400L
            val currentEpCount = lib.totalEpisodes.toInt()
            val startEp = if (currentEpCount > 0) currentEpCount + 1 else 1

            for (cycle in 0..4) {
                val epAir = nextAirSec + (cycle * intervalSec)
                val epNum = startEp + cycle
                result.add(
                    AiringScheduleEntry(
                        scheduleId = -((anime.id * 100 + cycle).toInt().absoluteValue),
                        airingAt = epAir,
                        episode = epNum,
                        mediaId = anime.id.toInt(),
                        titleUserPreferred = anime.title,
                        titleEnglish = null,
                        titleRomaji = null,
                        titleNative = null,
                        coverImageUrl = anime.thumbnailUrl.orEmpty(),
                        totalEpisodes = null,
                        averageScore = null,
                        format = "TV",
                        status = "RELEASING",
                        isAdult = false,
                        genres = anime.genre.orEmpty(),
                    ),
                )
            }
        }
        return result
    }

    private fun mergeEntries(
        remote: List<AiringScheduleEntry>,
        predicted: List<AiringScheduleEntry>,
    ): List<AiringScheduleEntry> {
        if (remote.isEmpty()) return predicted
        if (predicted.isEmpty()) return remote

        val nonDuplicatePredicted = predicted.filter { pred ->
            val isAlreadyInRemote = remote.any { rem ->
                val timeDiffSec = kotlin.math.abs(rem.airingAt - pred.airingAt)
                timeDiffSec < 3 * 86400L && mihon.feature.airingschedule.util.ScheduleTitleMatcher.matchesAny(
                    pred.titleUserPreferred,
                    mihon.feature.airingschedule.util.ScheduleTitleMatcher.candidateTitlesFromEntry(rem),
                )
            }
            !isAlreadyInRemote
        }

        return (remote + nonDuplicatePredicted).sortedBy { it.airingAt }
    }

    private data class LibraryObservedData(
        val titles: Set<String>,
        val sourcesByTitle: Map<String, Set<String>>,
        val idByTitle: Map<String, Long>,
        val predicted: List<AiringScheduleEntry>,
    )

    private fun observePreferences() {
        screenModelScope.launch {
            combine(
                schedulePrefs.showOnlyFavoriteSources().changes(),
                schedulePrefs.favoriteSourceIds().changes(),
                schedulePrefs.showAdultContent().changes(),
                schedulePrefs.titleLanguage().changes(),
                schedulePrefs.uploadDelayRefreshInterval().changes(),
                schedulePrefs.customUploadDelayMinutes().changes(),
                schedulePrefs.sourceUploadDelays().changes(),
                schedulePrefs.viewMode().changes(),
            ) { _ -> Unit }.collectLatest {
                if (allEntries.isNotEmpty()) {
                    applyFilters()
                }
            }
        }
    }

    fun loadSchedule(forceRefresh: Boolean = false) {
        // Cancel any still-running fetch (e.g. the user hit retry while a cold-open stream was
        // still filling in) so only the newest request drives the UI.
        fetchJob?.cancel()
        fetchJob = screenModelScope.launch {
            val zone = ZoneId.systemDefault()
            val now = ZonedDateTime.now(zone)
            val weekStart = now.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .toLocalDate().atStartOfDay(zone)
            val weekEnd = weekStart.plusDays(7).minusSeconds(1)

            // Optimize schedule window:
            // - Past: 1 week past release date (7 days ago, or start of current week, whichever is earlier)
            //   This avoids wasting network calls and memory on weeks of obsolete historical data.
            // - Future: Focus on upcoming release dates (rolling 30 days ahead from today)
            //   Ensures releases extending into next month are fetched and displayed ahead of time.
            val pastWeekStart = now.minusDays(7).toLocalDate().atStartOfDay(zone)
            val fetchStart = if (weekStart.isBefore(pastWeekStart)) weekStart else pastWeekStart
            val fetchEnd = now.plusDays(30).toLocalDate().atTime(23, 59, 59).atZone(zone).let {
                if (weekEnd.isAfter(it)) weekEnd else it
            }
            val currentFetchStart = fetchStart.toEpochSecond()

            // 1. Try reading disk cache first (Instant Offline Display, no blank screen)
            val cache = ScheduleDataRefreshWorker.readCache(application)
            val cachedEntries = if (cache != null && cache.entries.isNotEmpty()) {
                cache.entries
            } else null

            val cacheAge = System.currentTimeMillis() - (cache?.fetchedAt ?: 0L)
            val isCacheValid = cache != null && cache.entries.isNotEmpty() && cacheAge < TimeUnit.HOURS.toMillis(12)

            if (cachedEntries != null && !forceRefresh) {
                remoteEntries = cachedEntries
                allEntries = mergeEntries(remoteEntries, libraryPredictedEntries)
                hasLoaded = true
                applyFilters(
                    entries = allEntries,
                    delays = if (schedulePrefs.uploadDelayEnabled().get()) uploadDelayTracker.getDelays() else emptyMap(),
                    weekStart = weekStart.toLocalDate(),
                    weekEnd = weekEnd.toLocalDate(),
                )
                // If cache is fresh (< 12 hours old), skip network fetch
                if (isCacheValid) {
                    rescheduleSeriesAlarms()
                    return@launch
                }
            } else if (allEntries.isEmpty() && libraryPredictedEntries.isNotEmpty()) {
                allEntries = libraryPredictedEntries
                hasLoaded = true
                applyFilters(
                    entries = allEntries,
                    delays = if (schedulePrefs.uploadDelayEnabled().get()) uploadDelayTracker.getDelays() else emptyMap(),
                    weekStart = weekStart.toLocalDate(),
                    weekEnd = weekEnd.toLocalDate(),
                )
            }

            // 2. Fetch live data from AniList.
            val includeAdult = schedulePrefs.showAdultContent().get()
            val delays = if (schedulePrefs.uploadDelayEnabled().get()) {
                uploadDelayTracker.getDelays()
            } else {
                emptyMap()
            }
            val weekStartDate = weekStart.toLocalDate()
            val weekEndDate = weekEnd.toLocalDate()

            try {
                val fetched = if (allEntries.isEmpty()) {
                    // Nothing on screen yet (no usable cache and no library predictions) — the
                    // first-ever-open case. Stream every AniList page into the UI as it lands so
                    // rows appear within the first second and the schedule slowly fills in,
                    // instead of sitting behind a blank loader for the whole paginated fetch.
                    // A previously cancelled stream may have left partial pages behind; reset
                    // so the new stream can't double up with stale data.
                    remoteEntries = emptyList()
                    allEntries = emptyList()
                    mutableState.update { it.copy(isLoading = false, isRefreshing = true, error = null) }
                    repository.getScheduleIncremental(
                        start = fetchStart.toEpochSecond(),
                        end = fetchEnd.toEpochSecond(),
                        includeAdult = includeAdult,
                        onPage = { page, _ ->
                            appendPageAndPublish(page, delays, weekStartDate, weekEndDate)
                        },
                    )
                } else {
                    // Content is already on screen (stale cache or library predictions): fetch
                    // everything in the background and swap atomically at the end, avoiding
                    // visible churn in day counts and rows while it refreshes.
                    repository.getSchedule(
                        fetchStart.toEpochSecond(),
                        fetchEnd.toEpochSecond(),
                        includeAdult = includeAdult,
                    )
                }

                // Persist live fetch to disk cache
                ScheduleDataRefreshWorker.writeCache(application, currentFetchStart, fetched)

                remoteEntries = fetched
                allEntries = mergeEntries(remoteEntries, libraryPredictedEntries)
                hasLoaded = true

                rescheduleSeriesAlarms()

                publishScheduleView(
                    entries = allEntries,
                    delays = delays,
                    weekStart = weekStartDate,
                    weekEnd = weekEndDate,
                    refreshing = false,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (remoteEntries.isEmpty()) {
                    val fallback = cache?.takeIf { it.entries.isNotEmpty() }
                    if (fallback != null) {
                        remoteEntries = fallback.entries
                    }
                }
                allEntries = mergeEntries(remoteEntries, libraryPredictedEntries)
                if (allEntries.isNotEmpty()) {
                    hasLoaded = true
                    publishScheduleView(
                        entries = allEntries,
                        delays = delays,
                        weekStart = weekStartDate,
                        weekEnd = weekEndDate,
                        refreshing = false,
                    )
                } else {
                    mutableState.update { it.copy(isLoading = false, isRefreshing = false, error = e.message) }
                }
            }
        }
    }

    /**
     * Appends one freshly fetched AniList page and republishes the schedule view so the UI
     * fills in progressively during a cold (streamed) load. Invoked from inside the
     * repository's pagination loop on its IO dispatcher: shared model state is captured on
     * the main thread, the heavy merge/filter/grouping runs on a background dispatcher, and
     * only the resulting view lands back on the main thread.
     */
    private suspend fun appendPageAndPublish(
        page: List<AiringScheduleEntry>,
        delays: Map<String, Long>,
        weekStartDate: LocalDate,
        weekEndDate: LocalDate,
    ) {
        val (remote, predicted, config) = withContext(Dispatchers.Main) {
            remoteEntries = remoteEntries + page
            Triple(remoteEntries, libraryPredictedEntries, snapshotFilterConfig())
        }
        val (merged, view) = withContext(Dispatchers.Default) {
            // Merging is O(remote x predicted) with expensive title matching for large
            // libraries — keep it off the main thread so pages never stall composition.
            val merged = mergeEntries(remote, predicted)
            val view = computeScheduleView(merged, delays, weekStartDate, weekEndDate, config)
            merged to view
        }
        withContext(Dispatchers.Main) {
            allEntries = merged
            hasLoaded = true
            publishState(
                view = view,
                config = config,
                delays = delays,
                weekStart = weekStartDate,
                weekEnd = weekEndDate,
                refreshing = true,
            )
        }
    }

    /**
     * Recomputes the filtered/grouped schedule view with the heavy per-entry work on a
     * background dispatcher, then publishes the result on the main thread. Used by the fetch
     * completion paths of [loadSchedule]; interactive filter changes go through the
     * synchronous [applyFilters] instead so their update lands within the same frame.
     */
    private suspend fun publishScheduleView(
        entries: List<AiringScheduleEntry>,
        delays: Map<String, Long>,
        weekStart: LocalDate?,
        weekEnd: LocalDate?,
        refreshing: Boolean,
    ) {
        val config = snapshotFilterConfig()
        val view = withContext(Dispatchers.Default) {
            computeScheduleView(entries, delays, weekStart, weekEnd, config)
        }
        publishState(
            view = view,
            config = config,
            delays = delays,
            weekStart = weekStart,
            weekEnd = weekEnd,
            refreshing = refreshing,
        )
    }

    /**
     * Manual override: when the user has picked "Custom" for the upload-delay refresh interval,
     * they've supplied a fixed delay themselves — that takes priority over any auto-learned
     * per-source delay when computing expected upload time / countdown.
     */
    private fun computeManualDelayMinutes(): Long? {
        if (!schedulePrefs.uploadDelayEnabled().get()) return null
        if (schedulePrefs.uploadDelayRefreshInterval().get() != SchedulePreferences.UploadDelayInterval.CUSTOM) {
            return null
        }
        return SchedulePreferences.parseCustomDelayMinutes(schedulePrefs.customUploadDelayMinutes().get())
    }

    private fun isEntryInLibrary(
        entry: AiringScheduleEntry,
        libraryAnimeTitles: Set<String>,
        libraryAnimeIdByTitle: Map<String, Long>,
    ): Boolean {
        val titleCandidates = mihon.feature.airingschedule.util.ScheduleTitleMatcher.candidateTitlesFromEntry(entry)
        val candidateKeys = titleCandidates.flatMap { mihon.feature.airingschedule.util.ScheduleTitleMatcher.normalizedKeys(it) }
        return candidateKeys.any { it in libraryAnimeTitles || libraryAnimeIdByTitle.containsKey(it) }
    }

    private fun filterEntries(
        entries: List<AiringScheduleEntry>,
        showAdult: Boolean,
        showOnlyFavorites: Boolean,
        hideAired: Boolean,
        selectedFormats: Set<String>,
        configuredSources: Set<String>,
        libraryAnimeTitles: Set<String>,
        librarySourcesByTitle: Map<String, Set<String>>,
        libraryAnimeIdByTitle: Map<String, Long>,
    ): List<AiringScheduleEntry> = entries.filter { entry ->
        // Re-apply adult-content filter in case the preference changed since last fetch.
        if (!showAdult && entry.isAdult) return@filter false
        if (hideAired && entry.hasAired()) return@filter false
        if (selectedFormats.isNotEmpty() && (entry.format == null || entry.format !in selectedFormats)) return@filter false

        // When showOnlyFavorites is true:
        // Filter out everything except anime in the user's library.
        if (showOnlyFavorites) {
            val inLibrary = isEntryInLibrary(entry, libraryAnimeTitles, libraryAnimeIdByTitle)
            if (!inLibrary) return@filter false

            // If specific favorite/pinned sources are selected in settings, filter out
            // everything except those that are from those sources added to the library.
            if (configuredSources.isNotEmpty()) {
                val matchedSources = mihon.feature.airingschedule.util.UploadDelayResolver.matchedSourcesFor(
                    entry,
                    configuredSources,
                    librarySourcesByTitle,
                )
                if (matchedSources.isEmpty()) return@filter false
            }
        }
        true
    }

    private fun groupByDelayAdjustedDay(
        entries: List<AiringScheduleEntry>,
        librarySourcesByTitle: Map<String, Set<String>>,
        manualDelayMinutes: Long?,
        delays: Map<String, Long>,
        pinnedSources: Set<String>,
        favoriteIds: Set<String>,
        zone: ZoneId,
        weekStartDate: LocalDate? = null,
        weekEndDate: LocalDate? = null,
    ): Map<DayOfWeek, List<AiringScheduleEntry>> {
        // Single pass over the entries: the upload delay is resolved exactly once per entry
        // (title normalization against the user's library makes it the most expensive step of
        // the pipeline) instead of once for the week-window check and again for grouping.
        val grouped = LinkedHashMap<DayOfWeek, MutableList<AiringScheduleEntry>>()
        for (entry in entries) {
            val delay = mihon.feature.airingschedule.util.UploadDelayResolver.resolveDelay(
                entry = entry,
                delays = delays,
                manualDelayMinutes = manualDelayMinutes,
                librarySourcesByTitle = librarySourcesByTitle,
                pinnedSources = pinnedSources,
                favoriteSources = favoriteIds,
            )
            val airTime = mihon.feature.airingschedule.util.UploadDelayResolver.adjustedAirTime(entry, delay)
            val entryDate = ZonedDateTime.ofInstant(Instant.ofEpochSecond(airTime), zone).toLocalDate()
            if (weekStartDate != null && weekEndDate != null) {
                if (entryDate.isBefore(weekStartDate) || entryDate.isAfter(weekEndDate)) continue
            }
            grouped.getOrPut(entryDate.dayOfWeek) { mutableListOf() }.add(entry)
        }
        return grouped
    }

    /**
     * Snapshot of every preference/state value the filtering pipeline reads, taken on the main
     * thread so the entry-heavy computation can safely run on a background dispatcher.
     */
    private data class FilterConfig(
        val showOnlyFavorites: Boolean,
        val favoriteIds: Set<String>,
        val showAdult: Boolean,
        val pinnedSources: Set<String>,
        val hideAired: Boolean,
        val selectedFormats: Set<String>,
        val manualDelayMinutes: Long?,
        val libraryAnimeTitles: Set<String>,
        val librarySourcesByTitle: Map<String, Set<String>>,
        val libraryAnimeIdByTitle: Map<String, Long>,
        val configuredSources: Set<String>,
    )

    private fun snapshotFilterConfig(): FilterConfig {
        val favoriteIds = schedulePrefs.favoriteSourceIds().get()
        val pinnedSources = sourcePreferences.pinnedSources().get()
        return FilterConfig(
            showOnlyFavorites = schedulePrefs.showOnlyFavoriteSources().get(),
            favoriteIds = favoriteIds,
            showAdult = schedulePrefs.showAdultContent().get(),
            pinnedSources = pinnedSources,
            hideAired = mutableState.value.hideAired,
            selectedFormats = mutableState.value.selectedFormats,
            manualDelayMinutes = computeManualDelayMinutes(),
            libraryAnimeTitles = mutableState.value.libraryAnimeTitles,
            librarySourcesByTitle = mutableState.value.librarySourcesByTitle,
            libraryAnimeIdByTitle = mutableState.value.libraryAnimeIdByTitle,
            // Source filters should apply for either favourite or pinned sources — a user who
            // only pins sources from Browse (without also marking them "favourite" here) still
            // expects "show only my sources" to work.
            configuredSources = favoriteIds + pinnedSources,
        )
    }

    /** The computed schedule view: filtered entries plus their delay-adjusted day grouping. */
    private data class ScheduleView(
        val filtered: List<AiringScheduleEntry>,
        val grouped: Map<DayOfWeek, List<AiringScheduleEntry>>,
    )

    private fun computeScheduleView(
        entries: List<AiringScheduleEntry>,
        delays: Map<String, Long>,
        weekStart: LocalDate?,
        weekEnd: LocalDate?,
        config: FilterConfig,
    ): ScheduleView {
        val filtered = filterEntries(
            entries = entries,
            showAdult = config.showAdult,
            showOnlyFavorites = config.showOnlyFavorites,
            hideAired = config.hideAired,
            selectedFormats = config.selectedFormats,
            configuredSources = config.configuredSources,
            libraryAnimeTitles = config.libraryAnimeTitles,
            librarySourcesByTitle = config.librarySourcesByTitle,
            libraryAnimeIdByTitle = config.libraryAnimeIdByTitle,
        )
        val grouped = groupByDelayAdjustedDay(
            entries = filtered,
            librarySourcesByTitle = config.librarySourcesByTitle,
            manualDelayMinutes = config.manualDelayMinutes,
            delays = delays,
            pinnedSources = config.pinnedSources,
            favoriteIds = config.favoriteIds,
            zone = ZoneId.systemDefault(),
            weekStartDate = weekStart,
            weekEndDate = weekEnd,
        )
        return ScheduleView(filtered, grouped)
    }

    private fun publishState(
        view: ScheduleView,
        config: FilterConfig,
        delays: Map<String, Long>,
        weekStart: LocalDate?,
        weekEnd: LocalDate?,
        refreshing: Boolean,
    ) {
        val titleLang = schedulePrefs.titleLanguage().get()
        mutableState.update {
            it.copy(
                isLoading = false,
                isRefreshing = refreshing,
                scheduleByDay = view.grouped,
                allFilteredEntries = view.filtered,
                viewMode = schedulePrefs.viewMode().get(),
                weekStartDate = weekStart,
                weekEndDate = weekEnd,
                titleLanguage = titleLang,
                sourceDelays = delays,
                manualDelayMinutes = config.manualDelayMinutes,
                favoriteSourceIds = config.favoriteIds,
                pinnedSourceIds = config.pinnedSources,
                onlyFavorites = config.showOnlyFavorites,
                showAdult = config.showAdult,
                notifyOnceMediaIds = schedulePrefs.notifyOnceMediaIds().get(),
                notifySeriesMediaIds = schedulePrefs.notifySeriesMediaIds().get(),
            )
        }
    }

    private fun applyFilters(
        entries: List<AiringScheduleEntry> = allEntries,
        delays: Map<String, Long> = if (schedulePrefs.uploadDelayEnabled().get()) uploadDelayTracker.getDelays() else emptyMap(),
        weekStart: LocalDate? = mutableState.value.weekStartDate,
        weekEnd: LocalDate? = mutableState.value.weekEndDate,
    ) {
        val config = snapshotFilterConfig()
        val view = computeScheduleView(entries, delays, weekStart, weekEnd, config)
        publishState(
            view = view,
            config = config,
            delays = delays,
            weekStart = weekStart,
            weekEnd = weekEnd,
            // Preserve the streaming indicator: interactive filter changes can land while a
            // cold open is still filling in.
            refreshing = mutableState.value.isRefreshing,
        )
    }

    fun setFilterOnlyFavorites(value: Boolean) {
        schedulePrefs.showOnlyFavoriteSources().set(value)
    }

    fun setFilterHideAired(value: Boolean) {
        mutableState.update { it.copy(hideAired = value) }
        applyFilters()
    }

    fun setFilterShowAdult(value: Boolean) {
        schedulePrefs.showAdultContent().set(value)
    }

    fun toggleFilterFormat(format: String) {
        mutableState.update {
            val next = if (format in it.selectedFormats) it.selectedFormats - format else it.selectedFormats + format
            it.copy(selectedFormats = next)
        }
        applyFilters()
    }

    fun resetFilters() {
        schedulePrefs.showOnlyFavoriteSources().set(false)
        schedulePrefs.showAdultContent().set(false)
        mutableState.update {
            it.copy(
                onlyFavorites = false,
                hideAired = false,
                showAdult = false,
                selectedFormats = emptySet(),
            )
        }
        applyFilters()
    }

    /** Determines the current bell state for a given schedule entry. */
    fun notifyStateFor(mediaId: Int): BellNotifyState {
        val state = mutableState.value
        return when {
            mediaId.toString() in state.notifySeriesMediaIds -> BellNotifyState.SERIES
            mediaId.toString() in state.notifyOnceMediaIds -> BellNotifyState.ONCE
            else -> BellNotifyState.NONE
        }
    }

    /** Toggles a single alert for the next upcoming episode of this anime. */
    fun toggleNotifyOnce(entry: AiringScheduleEntry) {
        val key = entry.mediaId.toString()
        val current = schedulePrefs.notifyOnceMediaIds().get()
        val seriesCurrent = schedulePrefs.notifySeriesMediaIds().get()
        if (key in current) {
            schedulePrefs.notifyOnceMediaIds().set(current - key)
            ScheduleNotifications.cancel(application, entry)
        } else {
            // Only persist the "notify" preference if an alarm was actually scheduled —
            // an already-aired entry has nothing to back the bell state, so don't leave a
            // stuck ONCE indicator with no alarm behind it.
            if (ScheduleNotifications.ensureScheduled(application, entry)) {
                schedulePrefs.notifyOnceMediaIds().set(current + key)
                schedulePrefs.notifySeriesMediaIds().set(seriesCurrent - key)
            }
        }
        applyFilters()
    }

    /** Toggles recurring alerts for every future episode of this anime until it finishes airing. */
    fun toggleNotifySeries(entry: AiringScheduleEntry) {
        val key = entry.mediaId.toString()
        val seriesCurrent = schedulePrefs.notifySeriesMediaIds().get()
        val onceCurrent = schedulePrefs.notifyOnceMediaIds().get()
        if (key in seriesCurrent) {
            schedulePrefs.notifySeriesMediaIds().set(seriesCurrent - key)
            ScheduleNotifications.cancelAllForMedia(application, entry.mediaId, allEntries)
        } else {
            schedulePrefs.notifySeriesMediaIds().set(seriesCurrent + key)
            schedulePrefs.notifyOnceMediaIds().set(onceCurrent - key)
            rescheduleSeriesAlarms()
        }
        applyFilters()
    }

    private fun rescheduleSeriesAlarms() {
        val seriesIds = schedulePrefs.notifySeriesMediaIds().get()
        if (seriesIds.isEmpty()) return
        allEntries
            .filter { it.mediaId.toString() in seriesIds && !it.hasAired() }
            .forEach { ScheduleNotifications.ensureScheduled(application, it) }
    }

    fun selectDay(day: DayOfWeek) {
        mutableState.update { it.copy(selectedDay = day) }
    }

    fun clearLearnedDelays() {
        uploadDelayTracker.clearAllDelays()
        applyFilters(delays = emptyMap())
    }

    fun toggleViewMode() {
        val current = schedulePrefs.viewMode().get()
        val next = if (current == SchedulePreferences.ViewMode.WEEKLY) {
            SchedulePreferences.ViewMode.MONTHLY
        } else {
            SchedulePreferences.ViewMode.WEEKLY
        }
        schedulePrefs.viewMode().set(next)
        mutableState.update { it.copy(viewMode = next) }
    }

    fun setViewMode(mode: SchedulePreferences.ViewMode) {
        schedulePrefs.viewMode().set(mode)
        mutableState.update { it.copy(viewMode = mode) }
    }

    data class State(
        val isLoading: Boolean = true,
        /** True while a cold (first-ever) load is still streaming AniList pages into the UI. */
        val isRefreshing: Boolean = false,
        val scheduleByDay: Map<DayOfWeek, List<AiringScheduleEntry>> = emptyMap(),
        val allFilteredEntries: List<AiringScheduleEntry> = emptyList(),
        val viewMode: SchedulePreferences.ViewMode = SchedulePreferences.ViewMode.WEEKLY,
        val selectedDay: DayOfWeek = ZonedDateTime.now().dayOfWeek,
        val weekStartDate: LocalDate? = null,
        val weekEndDate: LocalDate? = null,
        val error: String? = null,
        val titleLanguage: SchedulePreferences.TitleLanguage = SchedulePreferences.TitleLanguage.USER_PREFERRED,
        val sourceDelays: Map<String, Long> = emptyMap(),
        val manualDelayMinutes: Long? = null,
        val favoriteSourceIds: Set<String> = emptySet(),
        val pinnedSourceIds: Set<String> = emptySet(),
        val notifyOnceMediaIds: Set<String> = emptySet(),
        val notifySeriesMediaIds: Set<String> = emptySet(),
        val libraryAnimeTitles: Set<String> = emptySet(),
        val librarySourcesByTitle: Map<String, Set<String>> = emptyMap(),
        val libraryAnimeIdByTitle: Map<String, Long> = emptyMap(),
        val onlyFavorites: Boolean = false,
        val hideAired: Boolean = false,
        val showAdult: Boolean = false,
        val selectedFormats: Set<String> = emptySet(),
    ) {
        val hasActiveFilters: Boolean
            get() = onlyFavorites || hideAired || showAdult || selectedFormats.isNotEmpty()
    }
}
