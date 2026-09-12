package eu.kanade.tachiyomi.data.sync

import android.content.Context
import android.net.Uri
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.tachiyomi.data.backup.create.BackupCreator
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupAnime
import eu.kanade.tachiyomi.data.backup.models.BackupEpisode
import eu.kanade.tachiyomi.data.backup.restore.BackupRestoreJob
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import eu.kanade.tachiyomi.data.backup.restore.restorers.AnimeRestorer
import eu.kanade.tachiyomi.data.sync.service.GoogleDriveSyncService
import eu.kanade.tachiyomi.data.sync.service.SyncData
import eu.kanade.tachiyomi.data.sync.service.SyncYomiSyncService
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import logcat.LogPriority
import logcat.logcat
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.Episodes
import tachiyomi.data.anime.AnimeMapper.mapAnime
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.category.interactor.GetCategories
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.IOException
import java.util.Date
import kotlin.system.measureTimeMillis

import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.model.Track
import eu.kanade.tachiyomi.data.backup.models.BackupTracking

/**
 * A manager to handle synchronization tasks in the app, such as updating
 * sync preferences and performing synchronization with a remote server.
 *
 * @property context The application context.
 */
class SyncManager(
    private val context: Context,
    private val handler: DatabaseHandler = Injekt.get(),
    private val syncPreferences: SyncPreferences = Injekt.get(),
    private var json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    },
    private val getCategories: GetCategories = Injekt.get(),
    private val getTracks: GetTracks = Injekt.get(),
) {
    private val backupCreator: BackupCreator = BackupCreator(context, false)
    private val notifier: SyncNotifier = SyncNotifier(context)
    private val animeRestorer: AnimeRestorer = AnimeRestorer()

    enum class SyncService(val value: Int) {
        NONE(0),
        SYNCYOMI(1),
        GOOGLE_DRIVE(2),
        ;

        companion object {
            fun fromInt(value: Int) = entries.firstOrNull { it.value == value } ?: NONE
        }
    }

    /**
     * Syncs data with a sync service.
     *
     * This function retrieves local data (favorites, anime, extensions, and categories)
     * from the database using the BackupManager, then synchronizes the data with a sync service.
     */
    suspend fun syncData() {
        // Reset isSyncing in case it was left over or failed syncing during restore.
        handler.await(inTransaction = true) {
            animesQueries.resetIsSyncing()
            episodesQueries.resetIsSyncing()
        }

        val syncOptions = syncPreferences.getSyncSettings()
        val databaseAnime = getAllAnimeThatNeedsSync()

        val backupOptions = BackupOptions(
            libraryEntries = syncOptions.libraryEntries,
            categories = syncOptions.categories,
            chapters = syncOptions.chapters,
            tracking = syncOptions.tracking,
            history = syncOptions.history,
            appSettings = syncOptions.appSettings,
            sourceSettings = syncOptions.sourceSettings,
            privateSettings = syncOptions.privateSettings,
        )

        logcat(LogPriority.DEBUG) { "Begin create backup" }
        val backupAnime = backupCreator.backupAnimes(databaseAnime, backupOptions)
        val backup = Backup(
            backupAnime = backupAnime,
            backupCategories = backupCreator.backupAnimeCategories(backupOptions),
            backupSources = backupCreator.backupAnimeSources(backupAnime),
            backupPreferences = backupCreator.backupAppPreferences(backupOptions),
            backupSourcePreferences = backupCreator.backupSourcePreferences(backupOptions),
            backupExtensionRepo = backupCreator.backupAnimeExtensionRepos(backupOptions),
            backupExtensions = backupCreator.backupExtensions(backupOptions),
            backupCustomButton = backupCreator.backupCustomButtons(backupOptions),
        )
        logcat(LogPriority.DEBUG) { "End create backup" }

        // Create the SyncData object
        val syncData = SyncData(
            deviceId = syncPreferences.uniqueDeviceID(),
            backup = backup,
        )

        // Handle sync based on the selected service
        val syncService = when (val syncService = SyncService.fromInt(syncPreferences.syncService().get())) {
            SyncService.SYNCYOMI -> {
                SyncYomiSyncService(
                    context,
                    json,
                    syncPreferences,
                    notifier,
                )
            }

            SyncService.GOOGLE_DRIVE -> {
                GoogleDriveSyncService(context, json, syncPreferences)
            }

            else -> {
                logcat(LogPriority.ERROR) { "Invalid sync service type: $syncService" }
                null
            }
        }

        val remoteBackup = syncService?.doSync(syncData)

        if (remoteBackup == null) {
            logcat(LogPriority.DEBUG) { "Skip restore due to network issues" }
            // should we call showSyncError?
            return
        }

        if (remoteBackup === syncData.backup) {
            // nothing changed
            syncPreferences.lastSyncTimestamp().set(Date().time)
            notifier.showSyncSuccess("Sync completed successfully")
            return
        }

        // Stop the sync early if the remote backup is null or empty
        if (remoteBackup.backupAnime.isEmpty()) {
            notifier.showSyncError("No data found on remote server.")
            return
        }

        // Check if it's first sync based on lastSyncTimestamp
        if (syncPreferences.lastSyncTimestamp().get() == 0L &&
            databaseAnime.isNotEmpty()
        ) {
            // It's first sync no need to restore data. (just update remote data)
            syncPreferences.lastSyncTimestamp().set(Date().time)
            notifier.showSyncSuccess("Updated remote data successfully")
            return
        }

        val (animeFilteredFavorites, animeNonFavorites) = aniFilterFavoritesAndNonFavorites(remoteBackup)
        animeUpdateNonFavorites(animeNonFavorites)

        val newSyncData = backup.copy(
            backupAnime = animeFilteredFavorites,
            backupCategories = remoteBackup.backupCategories,
            backupSources = remoteBackup.backupSources,
            backupPreferences = remoteBackup.backupPreferences,
            backupSourcePreferences = remoteBackup.backupSourcePreferences,
            backupExtensionRepo = remoteBackup.backupExtensionRepo,
            backupExtensions = remoteBackup.backupExtensions,
            backupCustomButton = remoteBackup.backupCustomButton,
        )

        // It's local sync no need to restore data. (just update remote data)
        if (animeFilteredFavorites.isEmpty()) {
            // update the sync timestamp

            syncPreferences.lastSyncTimestamp().set(Date().time)
            notifier.showSyncSuccess("Sync completed successfully")
            return
        }

        val backupUri = writeSyncDataToCache(context, newSyncData)
        logcat(LogPriority.DEBUG) { "Got Backup Uri: $backupUri" }
        if (backupUri != null) {
            BackupRestoreJob.start(
                context,
                backupUri,
                sync = true,
                options = RestoreOptions(
                    appSettings = true,
                    sourceSettings = true,
                    libraryEntries = true, // Correct parameter name
                    extensionRepoSettings = true,
                    extensions = true,
                    customButtons = true,
                ),
            )

            // update the sync timestamp
            syncPreferences.lastSyncTimestamp().set(Date().time)
        } else {
            logcat(LogPriority.ERROR) { "Failed to write sync data to file" }
        }
    }

    private fun writeSyncDataToCache(context: Context, backup: Backup): Uri? {
        val cacheFile = File(context.cacheDir, "anizen_sync_data.proto.gz")
        return try {
            cacheFile.outputStream().use { output ->
                output.write(ProtoBuf.encodeToByteArray(Backup.serializer(), backup))
                Uri.fromFile(cacheFile)
            }
        } catch (e: IOException) {
            logcat(LogPriority.ERROR, throwable = e) { "Failed to write sync data to cache" }
            null
        }
    }

    /**
     * Retrieves all manga from the local database.
     *
     * @return a list of all manga stored in the database
     */
    private suspend fun getAllAnimeFromDB(): List<Anime> {
        return handler.awaitList { animesQueries.getAllAnime(::mapAnime) }
    }

    private suspend fun getAllAnimeThatNeedsSync(): List<Anime> {
        return handler.awaitList { animesQueries.getAnimesWithFavoriteTimestamp(::mapAnime) }
    }

    private suspend fun isAnimeDifferent(localAnime: Anime, remoteAnime: BackupAnime): Boolean {
        // Fast in-memory check first: when the version already differs there is
        // nothing to gain from the category/track/episode queries below.
        if (localAnime.version != remoteAnime.version) {
            return true
        }

        val localCategories = getCategories.await(localAnime.id).map { it.order }
        if (localCategories.toSet() != remoteAnime.categories.toSet()) {
            return true
        }

        val localTracks = getTracks.await(localAnime.id)
        if (areTracksDifferent(localTracks, remoteAnime.tracking)) {
            return true
        }

        val localEpisodes = handler.await { episodesQueries.getEpisodesByAnimeId(localAnime.id, 0).executeAsList() }
        return areEpisodesDifferent(localEpisodes, remoteAnime.episodes)
    }

    private fun areTracksDifferent(localTracks: List<Track>, remoteTracks: List<BackupTracking>): Boolean {
        val localTracksMap = localTracks.associateBy { it.trackerId }
        val remoteTracksMap = remoteTracks.map { it.getTrackImpl() }.associateBy { it.trackerId }

        if (localTracksMap.size != remoteTracksMap.size) {
            return true
        }

        for ((trackerId, localTrack) in localTracksMap) {
            val remoteTrack = remoteTracksMap[trackerId]

            if (remoteTrack == null || localTrack.forComparison() != remoteTrack.forComparison()) {
                return true
            }
        }

        return false
    }

    private fun Track.forComparison() = this.copy(id = 0L, animeId = 0L)

    @Suppress("ReturnCount")
    private fun areEpisodesDifferent(localEpisodes: List<Episodes>, remoteEpisodes: List<BackupEpisode>): Boolean {
        val localEpisodeMap = localEpisodes.associateBy { it.url }
        val remoteEpisodeMap = remoteEpisodes.associateBy { it.url }

        if (localEpisodeMap.size != remoteEpisodeMap.size) {
            return true
        }

        for ((url, localEpisode) in localEpisodeMap) {
            val remoteEpisode = remoteEpisodeMap[url]

            // If a matching remote Episode doesn't exist, or the version numbers are different, consider them different
            if (remoteEpisode == null || localEpisode.version != remoteEpisode.version) {
                return true
            }
        }

        return false
    }

    /**
     * Filters the favorite and non-favorite manga from the backup and checks
     * if the favorite manga is different from the local database.
     * @param backup the Backup object containing the backup data.
     * @return a Pair of lists, where the first list contains different favorite manga
     * and the second list contains non-favorite manga.
     */
    @Suppress("MagicNumber")
    private suspend fun aniFilterFavoritesAndNonFavorites(backup: Backup): Pair<List<BackupAnime>, List<BackupAnime>> {
        val favorites = mutableListOf<BackupAnime>()
        val nonFavorites = mutableListOf<BackupAnime>()
        val logTag = "filterFavoritesAndNonFavorites"

        val elapsedTimeMillis = measureTimeMillis {
            val databaseAnime = getAllAnimeFromDB()
            val localAnimeMap = databaseAnime.associateBy {
                Triple(it.source, it.url, it.title)
            }

            logcat(LogPriority.DEBUG, logTag) { "Starting to filter favorites and non-favorites from backup data." }

            backup.backupAnime.forEach { remoteAnime ->
                val compositeKey = Triple(remoteAnime.source, remoteAnime.url, remoteAnime.title)
                val localAnime = localAnimeMap[compositeKey]
                when {
                    // Checks if the anime is in favorites and needs updating or adding
                    remoteAnime.favorite -> {
                        if (localAnime == null || isAnimeDifferent(localAnime, remoteAnime)) {
                            logcat(LogPriority.DEBUG, logTag) { "Adding to favorites: ${remoteAnime.title}" }
                            favorites.add(remoteAnime)
                        } else {
                            logcat(LogPriority.DEBUG, logTag) { "Already up-to-date favorite: ${remoteAnime.title}" }
                        }
                    }
                    // Handle non-favorites
                    !remoteAnime.favorite -> {
                        logcat(LogPriority.DEBUG, logTag) { "Adding to non-favorites: ${remoteAnime.title}" }
                        nonFavorites.add(remoteAnime)
                    }
                }
            }
        }

        val minutes = elapsedTimeMillis / 60000
        val seconds = (elapsedTimeMillis % 60000) / 1000
        logcat(LogPriority.DEBUG, logTag) {
            "Filtering completed in ${minutes}m ${seconds}s. Favorites found: ${favorites.size}, " +
                "Non-favorites found: ${nonFavorites.size}"
        }

        return Pair(favorites, nonFavorites)
    }

    /**
     * Updates the non-favorite manga in the local database with their favorite status from the backup.
     * @param nonFavorites the list of non-favorite BackupManga objects from the backup.
     */
    private suspend fun animeUpdateNonFavorites(nonFavorites: List<BackupAnime>) {
        val localAnimeList = getAllAnimeFromDB()

        val localAnimeMap = localAnimeList.associateBy { Triple(it.source, it.url, it.title) }

        nonFavorites.forEach { nonFavorite ->
            val key = Triple(nonFavorite.source, nonFavorite.url, nonFavorite.title)
            localAnimeMap[key]?.let { localAnime ->
                if (localAnime.favorite != nonFavorite.favorite) {
                    val updatedAnime = localAnime.copy(favorite = nonFavorite.favorite)
                    animeRestorer.updateAnime(updatedAnime)
                }
            }
        }
    }
}
