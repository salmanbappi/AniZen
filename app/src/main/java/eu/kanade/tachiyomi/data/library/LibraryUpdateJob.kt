package eu.kanade.tachiyomi.data.library

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkQuery
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import eu.kanade.domain.anime.interactor.UpdateAnime
import eu.kanade.domain.anime.interactor.SyncSeasonsWithSource
import eu.kanade.tachiyomi.animesource.model.FetchType
import tachiyomi.domain.season.interactor.GetAnimeSeasonsById
import tachiyomi.domain.anime.model.toSAnime
import eu.kanade.domain.episode.interactor.SyncEpisodesWithSource
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.sync.SyncDataJob
import eu.kanade.tachiyomi.data.track.TrackStatus
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.model.SAnime
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.createFileInCacheDir
import eu.kanade.tachiyomi.util.system.isConnectedToWifi
import eu.kanade.tachiyomi.util.system.isRunning
import eu.kanade.tachiyomi.util.system.workManager
import exh.util.nullIfBlank
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import logcat.LogPriority
import mihon.domain.episode.interactor.FilterEpisodesForDownload
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.getAndSet
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.source.NoResultsException
import tachiyomi.domain.anime.interactor.FetchInterval
import tachiyomi.domain.anime.interactor.GetAnime
import tachiyomi.domain.anime.interactor.GetLibraryAnime
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.episode.model.Episode
import tachiyomi.domain.history.interactor.LogActivity
import tachiyomi.domain.history.model.ActivityLog
import tachiyomi.domain.library.model.GroupLibraryMode
import tachiyomi.domain.library.model.LibraryAnime
import tachiyomi.domain.library.model.LibraryGroup
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.library.service.LibraryPreferences.Companion.ANIME_HAS_UNSEEN
import tachiyomi.domain.library.service.LibraryPreferences.Companion.ANIME_NON_COMPLETED
import tachiyomi.domain.library.service.LibraryPreferences.Companion.ANIME_NON_SEEN
import tachiyomi.domain.library.service.LibraryPreferences.Companion.ANIME_OUTSIDE_RELEASE_PERIOD
import tachiyomi.domain.library.service.LibraryPreferences.Companion.DEVICE_CHARGING
import tachiyomi.domain.library.service.LibraryPreferences.Companion.DEVICE_NETWORK_NOT_METERED
import tachiyomi.domain.library.service.LibraryPreferences.Companion.DEVICE_ONLY_ON_WIFI
import tachiyomi.domain.source.model.SourceNotInstalledException
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.libraryUpdateError.interactor.DeleteLibraryUpdateErrors
import tachiyomi.domain.libraryUpdateError.interactor.InsertLibraryUpdateErrors
import tachiyomi.domain.libraryUpdateError.model.LibraryUpdateError
import tachiyomi.domain.libraryUpdateErrorMessage.interactor.InsertLibraryUpdateErrorMessages
import tachiyomi.domain.libraryUpdateErrorMessage.model.LibraryUpdateErrorMessage
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.time.Instant
import java.time.ZonedDateTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class LibraryUpdateJob(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val sourceManager: SourceManager = Injekt.get()
    private val libraryPreferences: LibraryPreferences = Injekt.get()
    private val downloadManager: DownloadManager = Injekt.get()
    private val coverCache: CoverCache = Injekt.get()
    private val getLibraryAnime: GetLibraryAnime = Injekt.get()
    private val getAnime: GetAnime = Injekt.get()
    private val updateAnime: UpdateAnime = Injekt.get()
    private val syncEpisodesWithSource: SyncEpisodesWithSource = Injekt.get()
    private val syncSeasonsWithSource: SyncSeasonsWithSource = Injekt.get()
    private val getAnimeSeasonsById: GetAnimeSeasonsById = Injekt.get()
    private val deleteLibraryUpdateErrors: DeleteLibraryUpdateErrors = Injekt.get<DeleteLibraryUpdateErrors>()
    private val insertLibraryUpdateErrors: InsertLibraryUpdateErrors = Injekt.get<InsertLibraryUpdateErrors>()
    private val insertLibraryUpdateErrorMessages: InsertLibraryUpdateErrorMessages = Injekt.get<InsertLibraryUpdateErrorMessages>()
    private val getTracks: GetTracks = Injekt.get()
    private val fetchInterval: FetchInterval = Injekt.get()
    private val filterEpisodesForDownload: FilterEpisodesForDownload = Injekt.get()
    private val logActivity: LogActivity = Injekt.get()

    private val notifier = LibraryUpdateNotifier(context)

    private var animeToUpdate: List<LibraryAnime> = mutableListOf()

    override suspend fun doWork(): Result {
        if (tags.contains(WORK_NAME_AUTO)) {
            val preferences = Injekt.get<LibraryPreferences>()
            val restrictions = preferences.autoUpdateDeviceRestrictions.get()
            if ((DEVICE_ONLY_ON_WIFI in restrictions) && !context.isConnectedToWifi()) {
                return Result.failure()
            }

            // Find a running manual worker. If exists, try again later
            if (context.workManager.isRunning(WORK_NAME_MANUAL)) {
                return Result.retry()
            }
        }

        deleteLibraryUpdateErrors.cleanUnrelevantMangaErrors()

        try {
            setForeground(getForegroundInfo())
        } catch (e: IllegalStateException) {
            logcat(LogPriority.ERROR, e) { "Not allowed to set foreground job" }
        }

        libraryPreferences.lastUpdatedTimestamp().set(Instant.now().toEpochMilli())

        val showBanner = libraryPreferences.showUpdatingProgressBanner().get()

        val categoryId = inputData.getLong(KEY_CATEGORY, -1L)
        // SY -->
        val group = inputData.getInt(KEY_GROUP, LibraryGroup.BY_DEFAULT)
        val groupExtra = inputData.getString(KEY_GROUP_EXTRA)
        // SY <--
        addAnimeToQueue(categoryId, group, groupExtra)

        return withIOContext {
            try {
                if (showBanner) {
                    setProgress(workDataOf("progress" to 0, "title" to context.stringResource(MR.strings.updating_library)))
                }
                updateEpisodeList(showBanner)
                Result.success()
            } catch (e: Exception) {
                if (e is CancellationException) {
                    // Assume success although cancelled
                    Result.success()
                } else {
                    logcat(LogPriority.ERROR, e)
                    Result.failure()
                }
            } finally {
                notifier.cancelProgressNotification()
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notifier = LibraryUpdateNotifier(context)
        return ForegroundInfo(
            Notifications.ID_LIBRARY_PROGRESS,
            notifier.progressNotificationBuilder.build(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },

        )
    }

    /**
     * Adds list of anime to be updated.
     *
     * @param categoryId the ID of the category to update, or -1 if no category specified.
     */
    @Suppress("MagicNumber", "LongMethod", "CyclomaticComplexMethod", "ComplexCondition")
    private suspend fun addAnimeToQueue(categoryId: Long, group: Int, groupExtra: String?) {
        val libraryAnime = getLibraryAnime.await()

        // SY -->
        val groupAnimeLibraryUpdateType = libraryPreferences.groupLibraryUpdateType().get()
        // SY <--

        val listToUpdate = if (categoryId != -1L) {
            libraryAnime.filter { it.category == categoryId }
        } else if (
            group == LibraryGroup.BY_DEFAULT ||
            groupAnimeLibraryUpdateType == GroupLibraryMode.GLOBAL ||
            (
                groupAnimeLibraryUpdateType == GroupLibraryMode.ALL_BUT_UNGROUPED &&
                    group == LibraryGroup.UNGROUPED
                )
        ) {
            val categoriesToUpdate = libraryPreferences.updateCategories().get().map { it.toLong() }
            val includedAnime = if (categoriesToUpdate.isNotEmpty()) {
                libraryAnime.filter { it.category in categoriesToUpdate }
            } else {
                libraryAnime
            }

            val categoriesToExclude = libraryPreferences.updateCategoriesExclude().get().map { it.toLong() }
            val excludedAnimeIds = if (categoriesToExclude.isNotEmpty()) {
                libraryAnime.filter { it.category in categoriesToExclude }.map { it.anime.id }
            } else {
                emptyList()
            }

            includedAnime
                .filterNot { it.anime.id in excludedAnimeIds }
        } else {
            when (group) {
                LibraryGroup.BY_TRACK_STATUS -> {
                    val trackingExtra = groupExtra?.toIntOrNull() ?: -1
                    val tracks = runBlocking { getTracks.await() }.groupBy { it.animeId }

                    libraryAnime.filter { (anime) ->
                        val status = tracks[anime.id]?.firstNotNullOfOrNull { track ->
                            TrackStatus.parseTrackerStatus(track.trackerId, track.status)
                        } ?: TrackStatus.OTHER
                        status.int == trackingExtra.toLong()
                    }
                }
                LibraryGroup.BY_SOURCE -> {
                    val sourceExtra = groupExtra?.nullIfBlank()?.toIntOrNull()
                    val source = libraryAnime.map { it.anime.source }
                        .distinct()
                        .sorted()
                        .getOrNull(sourceExtra ?: -1)

                    if (source != null) libraryAnime.filter { it.anime.source == source } else emptyList()
                }
                LibraryGroup.BY_TAG -> {
                    val tagExtra = groupExtra?.nullIfBlank()?.toIntOrNull()
                    val tag = libraryAnime.map { it.anime.genre }
                        .distinct()
                        .getOrNull(tagExtra ?: -1)

                    if (tag != null) libraryAnime.filter { it.anime.genre == tag } else emptyList()
                }
                LibraryGroup.BY_STATUS -> {
                    val statusExtra = groupExtra?.toLongOrNull() ?: -1
                    libraryAnime.filter {
                        it.anime.status == statusExtra
                    }
                }
                LibraryGroup.UNGROUPED -> libraryAnime
                else -> libraryAnime
            }
            // SY <--
        }

        val includeSeasonsGlobal = libraryPreferences.useHierarchicalSeasons().get()
        
        val lastToUpdateWithSeasons = listToUpdate.flatMap { libAnime ->
            when (libAnime.anime.fetchType) {
                FetchType.Seasons -> {
                    val list = mutableListOf(libAnime)
                    
                    if (includeSeasonsGlobal) {
                        val seasons = getAnimeSeasonsById.await(libAnime.anime.id)
                        list.addAll(
                            seasons
                                .filter { s ->
                                    s.anime.fetchType == FetchType.Episodes && !s.anime.favorite
                                }
                                .map { it.toLibraryAnime() }
                        )
                    }
                    list
                }
                FetchType.Episodes -> listOf(libAnime)
            }
        }

        val restrictions = libraryPreferences.autoUpdateAnimeRestrictions.get()
        val skippedUpdates = mutableListOf<Pair<Anime, String?>>()
        val (_, fetchWindowUpperBound) = fetchInterval.getWindow(ZonedDateTime.now())

        animeToUpdate = lastToUpdateWithSeasons
            // SY -->
            .distinctBy { it.anime.id }
            // SY <--
            .filter {
                when {
                    it.anime.updateStrategy != UpdateStrategy.ALWAYS_UPDATE -> {
                        skippedUpdates.add(
                            it.anime to context.stringResource(MR.strings.skipped_reason_not_always_update),
                        )
                        false
                    }

                    ANIME_NON_COMPLETED in restrictions && it.anime.status.toInt() == SAnime.COMPLETED -> {
                        skippedUpdates.add(
                            it.anime to context.stringResource(MR.strings.skipped_reason_completed),
                        )
                        false
                    }

                    ANIME_HAS_UNSEEN in restrictions && it.unseenCount != 0L -> {
                        skippedUpdates.add(
                            it.anime to context.stringResource(MR.strings.skipped_reason_not_caught_up),
                        )
                        false
                    }

                    ANIME_NON_SEEN in restrictions && it.totalEpisodes > 0L && !it.hasStarted -> {
                        skippedUpdates.add(
                            it.anime to context.stringResource(MR.strings.skipped_reason_not_started),
                        )
                        false
                    }

                    ANIME_OUTSIDE_RELEASE_PERIOD in restrictions && it.anime.nextUpdate > fetchWindowUpperBound -> {
                        skippedUpdates.add(
                            it.anime to context.stringResource(MR.strings.skipped_reason_not_in_release_period),
                        )
                        false
                    }
                    else -> true
                }
            }
            .sortedBy { it.anime.title }
        // Warn when excessively checking a single source
        val maxUpdatesFromSource = animeToUpdate
            .groupBy { it.anime.source + (0..4).random() }
            .filterKeys { sourceManager.get(it) !is UnmeteredSource }
            .maxOfOrNull { it.value.size } ?: 0
        if (maxUpdatesFromSource > ANIME_PER_SOURCE_QUEUE_WARNING_THRESHOLD) {
            notifier.showQueueSizeWarningNotificationIfNeeded(animeToUpdate)
        }

        if (skippedUpdates.isNotEmpty()) {
            // TODO: surface skipped reasons to user?
            logcat {
                skippedUpdates
                    .groupBy { it.second }
                    .map { (reason, entries) -> "$reason: [${entries.map { it.first.title }.sorted().joinToString()}]" }
                    .joinToString()
            }
        }
    }

    /**
     * Method that updates anime in [animeToUpdate]. It's called in a background thread, so it's safe
     * to do heavy operations or network calls here.
     * For each anime it calls [updateAnime] and updates the notification showing the current
     * progress.
     *
     * @return an observable delivering the progress of each update.
     */
    @Suppress("MagicNumber", "LongMethod")
    private suspend fun updateEpisodeList(showBanner: Boolean) {
        val semaphore = Semaphore(5)
        val progressCount = AtomicInteger(0)
        val currentlyUpdatingAnime = CopyOnWriteArrayList<Anime>()
        val newUpdates = CopyOnWriteArrayList<Pair<Anime, Array<Episode>>>()
        val failedUpdates = CopyOnWriteArrayList<Pair<Anime, String?>>()
        val hasDownloads = AtomicBoolean(false)
        val fetchWindow = fetchInterval.getWindow(ZonedDateTime.now())

        coroutineScope {
            animeToUpdate.groupBy { it.anime.source + (0..4).random() }.values
                .map { animeInSource ->
                    async {
                        semaphore.withPermit {
                            animeInSource.forEach { libraryAnime ->
                                val anime = libraryAnime.anime
                                ensureActive()

                                // Don't continue to update if anime is not in library
                                if (getAnime.await(anime.id)?.favorite != true) {
                                    return@forEach
                                }

                                withUpdateNotification(
                                    currentlyUpdatingAnime,
                                    progressCount,
                                    anime,
                                    showBanner,
                                ) {
                                    try {
                                        val newEpisodes = updateAnime(anime, fetchWindow)
                                            .sortedByDescending { it.sourceOrder }

                                        if (newEpisodes.isNotEmpty()) {
                                            logActivity.await(anime.source, ActivityLog.TYPE_FETCH, count = newEpisodes.size.toLong())
                                            val episodesToDownload = filterEpisodesForDownload.await(anime, newEpisodes)

                                            if (episodesToDownload.isNotEmpty()) {
                                                downloadEpisodes(anime, episodesToDownload)
                                                hasDownloads.set(true)
                                            }

                                            libraryPreferences.newUpdatesCount()
                                                .getAndSet { it + newEpisodes.size }

                                            // Convert to the anime that contains new episodes
                                            newUpdates.add(anime to newEpisodes.toTypedArray())
                                        }
                                        clearErrorFromDB(anime.id)
                                    } catch (e: Throwable) {
                                        val errorMessage = when (e) {
                                            is NoResultsException -> context.stringResource(
                                                MR.strings.no_episodes_error,
                                            )
                                            // failedUpdates will already have the source, don't need to copy it into the message
                                            is SourceNotInstalledException -> context.stringResource(
                                                MR.strings.loader_not_implemented_error,
                                            )
                                            else -> e.message
                                        }
                                        writeErrorToDB(anime to errorMessage)
                                        failedUpdates.add(anime to errorMessage)
                                    }
                                }
                            }
                        }
                    }
                }
                .awaitAll()
        }

        notifier.cancelProgressNotification()

        if (newUpdates.isNotEmpty()) {
            notifier.showUpdateNotifications(newUpdates)
            
            // Pre-fetch covers for new updates to make them load instantly in the library
            coroutineScope {
                val imageLoader = coil3.SingletonImageLoader.get(context)
                newUpdates.forEach { (anime, _) ->
                    val request = coil3.request.ImageRequest.Builder(context)
                        .data(anime)
                        .precision(coil3.size.Precision.INEXACT)
                        .build()
                    imageLoader.enqueue(request)
                }
            }

            if (hasDownloads.get()) {
                downloadManager.startDownloads()
            }
        }

        if (failedUpdates.isNotEmpty()) {
            val errorFile = writeErrorFile(failedUpdates)
            notifier.showUpdateErrorNotification(
                failedUpdates.size,
                errorFile.getUriCompat(context),
            )
        }
    }

    private fun downloadEpisodes(anime: Anime, episodes: List<Episode>) {
        // We don't want to start downloading while the library is updating, because websites
        // may don't like it and they could ban the user.
        downloadManager.downloadEpisodes(anime, episodes, false)
    }

    /**
     * Updates the episodes for the given anime and adds them to the database.
     *
     * @param anime the anime to update.
     * @return a pair of the inserted and removed episodes.
     */
    private suspend fun updateAnime(anime: Anime, fetchWindow: Pair<Long, Long>): List<Episode> {
        val source = sourceManager.getOrStub(anime.source)

        // Update anime metadata if needed
        val fetchDetails = libraryPreferences.autoUpdateMetadata.get()

        val dbAnime = getAnime.await(anime.id)?.takeIf { it.favorite } ?: return emptyList()

        if (dbAnime.fetchType == FetchType.Seasons) {
            val seasonUpdate = source.getAnimeSeasonUpdate(
                anime = dbAnime.toSAnime(),
                seasons = emptyList(),
                fetchDetails = fetchDetails,
                fetchSeasons = true,
            )
            if (fetchDetails) {
                updateAnime.awaitUpdateFromSource(dbAnime, seasonUpdate.anime, manualFetch = false, coverCache)
            }
            syncSeasonsWithSource.await(seasonUpdate.seasons, dbAnime, source, false, fetchWindow)
            return emptyList()
        }

        val episodeUpdate = source.getAnimeEpisodeUpdate(
            anime = anime.toSAnime(),
            episodes = emptyList(),
            fetchDetails = fetchDetails,
            fetchEpisodes = true,
        )
        if (fetchDetails) {
            updateAnime.awaitUpdateFromSource(anime, episodeUpdate.anime, manualFetch = false, coverCache)
        }

        return syncEpisodesWithSource.await(episodeUpdate.episodes, dbAnime, source, false, fetchWindow)
    }

    private suspend fun withUpdateNotification(
        updatingAnime: CopyOnWriteArrayList<Anime>,
        completed: AtomicInteger,
        anime: Anime,
        showBanner: Boolean,
        block: suspend () -> Unit,
    ) = coroutineScope {
        ensureActive()

        updatingAnime.add(anime)
        notifier.showProgressNotification(
            updatingAnime,
            completed.get(),
            animeToUpdate.size,
        )
        
        if (showBanner && animeToUpdate.isNotEmpty()) {
            setProgress(workDataOf("progress" to (completed.get() * 100 / animeToUpdate.size)))
        }

        block()

        ensureActive()

        updatingAnime.remove(anime)
        completed.getAndIncrement()
        notifier.showProgressNotification(
            updatingAnime,
            completed.get(),
            animeToUpdate.size,
        )
        
        if (showBanner && animeToUpdate.isNotEmpty()) {
            setProgress(workDataOf("progress" to (completed.get() * 100 / animeToUpdate.size)))
        }
    }

    /**
     * Writes basic file of update errors to cache dir.
     */
    private fun writeErrorFile(errors: List<Pair<Anime, String?>>): File {
        try {
            if (errors.isNotEmpty()) {
                val file = context.createFileInCacheDir("anizen_update_errors.txt")
                file.bufferedWriter().use { out ->
                    out.write(
                        context.stringResource(MR.strings.library_errors_help, ERROR_LOG_HELP_URL) + "\n\n",
                    )
                    // Error file format:
                    // ! Error
                    //   # Source
                    //     - Anime
                    errors.groupBy({ it.second }, { it.first }).forEach { (error, animes) ->
                        out.write("\n! ${error}\n")
                        animes.groupBy { it.source }.forEach { (srcId, animes) ->
                            val source = sourceManager.getOrStub(srcId)
                            out.write("  # $source\n")
                            animes.forEach {
                                out.write("    - ${it.title}\n")
                            }
                        }
                    }
                }
                return file
            }
        } catch (_: Exception) {}
        return File("")
    }

    private suspend fun clearErrorFromDB(animeId: Long) {
        deleteLibraryUpdateErrors.deleteMangaError(animeId)
    }

    private suspend fun writeErrorToDB(error: Pair<Anime, String?>) {
        val errorMessage = error.second ?: context.stringResource(MR.strings.unknown_error)
        val errorMessageId = insertLibraryUpdateErrorMessages.insert(
            LibraryUpdateErrorMessage(-1L, errorMessage),
        )

        insertLibraryUpdateErrors.upsert(
            LibraryUpdateError(id = -1L, animeId = error.first.id, messageId = errorMessageId, lastUpdate = 0L),
        )
    }

    private suspend fun writeErrorsToDB(errors: List<Pair<Anime, String?>>) {
        val libraryErrors = errors.groupBy({ it.second }, { it.first })
        val errorMessages = insertLibraryUpdateErrorMessages.insertAll(
            libraryUpdateErrorMessages = libraryErrors.keys.map { errorMessage ->
                LibraryUpdateErrorMessage(-1L, errorMessage.orEmpty())
            },
        )
        val errorList = mutableListOf<LibraryUpdateError>()
        errorMessages.forEach { (messageId, message) ->
            libraryErrors[message]?.forEach { anime ->
                errorList.add(LibraryUpdateError(id = -1L, animeId = anime.id, messageId = messageId, lastUpdate = 0L))
            }
        }
        insertLibraryUpdateErrors.insertAll(errorList)
    }

    companion object {
        private const val TAG = "AnimeLibraryUpdate"
        private const val WORK_NAME_AUTO = "AnimeLibraryUpdate-auto"
        private const val WORK_NAME_MANUAL = "AnimeLibraryUpdate-manual"

        private const val ERROR_LOG_HELP_URL = "https://aniyomi.org/docs/guides/troubleshooting/"

        private const val ANIME_PER_SOURCE_QUEUE_WARNING_THRESHOLD = 60

        /**
         * Key for category to update.
         */
        private const val KEY_CATEGORY = "animeCategory"

        // SY -->
        /**
         * Key for group to update.
         */
        const val KEY_GROUP = "group"
        const val KEY_GROUP_EXTRA = "group_extra"
        // SY <--

        fun cancelAllWorks(context: Context) {
            context.workManager.cancelAllWorkByTag(TAG)
        }

        fun setupTask(
            context: Context,
            prefInterval: Int? = null,
        ) {
            val preferences = Injekt.get<LibraryPreferences>()
            val interval = prefInterval ?: preferences.autoUpdateInterval().get()
            if (interval > 0) {
                val restrictions = preferences.autoUpdateDeviceRestrictions.get()
                val constraints = Constraints(
                    requiredNetworkType = if (DEVICE_NETWORK_NOT_METERED in restrictions) {
                        NetworkType.UNMETERED
                    } else {
                        NetworkType.CONNECTED
                    },
                    requiresCharging = DEVICE_CHARGING in restrictions,
                    requiresBatteryNotLow = true,
                )

                val request = PeriodicWorkRequestBuilder<LibraryUpdateJob>(
                    interval.toLong(),
                    TimeUnit.HOURS,
                    10,
                    TimeUnit.MINUTES,
                )
                    .addTag(TAG)
                    .addTag(WORK_NAME_AUTO)
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.MINUTES)
                    .build()

                context.workManager.enqueueUniquePeriodicWork(
                    WORK_NAME_AUTO,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request,
                )
            } else {
                context.workManager.cancelUniqueWork(WORK_NAME_AUTO)
            }
        }

        @Suppress("ReturnCount")
        fun startNow(
            context: Context,
            category: Category? = null,
            // SY -->
            group: Int = LibraryGroup.BY_DEFAULT,
            groupExtra: String? = null,
            // SY <--
        ): Boolean {
            val wm = context.workManager
            // Check if the LibraryUpdateJob is already running
            if (wm.isRunning(TAG)) {
                // Already running either as a scheduled or manual job
                return false
            }

            val inputData = workDataOf(
                KEY_CATEGORY to category?.id,
                // SY -->
                KEY_GROUP to group,
                KEY_GROUP_EXTRA to groupExtra,
                // SY <--
            )

            val syncPreferences: SyncPreferences = Injekt.get()
            val syncTriggerOpt = syncPreferences.getSyncTriggerOptions()

            // Always sync the data before library update if syncing is enabled.
            if (syncPreferences.isSyncEnabled() && syncTriggerOpt.syncOnLibraryUpdate) {
                // Check if SyncDataJob is already running
                if (SyncDataJob.isRunning(context)) {
                    // SyncDataJob is already running
                    return false
                }

                // Define the SyncDataJob
                val syncDataJob = OneTimeWorkRequestBuilder<SyncDataJob>()
                    .addTag(SyncDataJob.TAG_MANUAL)
                    .build()

                // Chain SyncDataJob to run before LibraryUpdateJob
                val libraryUpdateJob = OneTimeWorkRequestBuilder<LibraryUpdateJob>()
                    .addTag(TAG)
                    .addTag(WORK_NAME_MANUAL)
                    .setInputData(inputData)
                    .build()

                wm.beginUniqueWork(WORK_NAME_MANUAL, ExistingWorkPolicy.KEEP, syncDataJob)
                    .then(libraryUpdateJob)
                    .enqueue()
            } else {
                val request = OneTimeWorkRequestBuilder<LibraryUpdateJob>()
                    .addTag(TAG)
                    .addTag(WORK_NAME_MANUAL)
                    .setInputData(inputData)
                    .build()

                wm.enqueueUniqueWork(WORK_NAME_MANUAL, ExistingWorkPolicy.KEEP, request)
            }

            return true
        }

        fun stop(context: Context) {
            val wm = context.workManager
            val workQuery = WorkQuery.Builder.fromTags(listOf(TAG))
                .addStates(listOf(WorkInfo.State.RUNNING))
                .build()
            wm.getWorkInfos(workQuery).get()
                // Should only return one work but just in case
                .forEach {
                    wm.cancelWorkById(it.id)

                    // Re-enqueue cancelled scheduled work
                    if (it.tags.contains(WORK_NAME_AUTO)) {
                        setupTask(context)
                    }
                }
        }
    }
}
