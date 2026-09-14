package eu.kanade.tachiyomi.data.backup.create.creators

import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.models.BackupAnime
import eu.kanade.tachiyomi.data.backup.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.models.backupEpisodeMapper
import eu.kanade.tachiyomi.data.backup.models.backupTrackMapper
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.history.interactor.GetHistory
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AnimeBackupCreator(
    private val handler: DatabaseHandler = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val getHistory: GetHistory = Injekt.get(),
) {

    suspend operator fun invoke(animes: List<Anime>, options: BackupOptions): List<BackupAnime> {
        return animes.map {
            backupAnime(it, options)
        }
    }

    private suspend fun backupAnime(anime: Anime, options: BackupOptions): BackupAnime {
        // Entry for this anime
        val animeObject = anime.toBackupAnime()

        if (options.chapters) {
            // Backup all the episodes
            handler.awaitList {
                episodesQueries.getEpisodesByAnimeId(
                    animeId = anime.id,
                    applyScanlatorFilter = 0, // false
                    mapper = backupEpisodeMapper,
                )
            }
                .takeUnless { it.isEmpty() }
                ?.let { animeObject.episodes = it }
        }

        if (options.categories) {
            // Backup categories for this anime
            val categoriesForAnime = getCategories.await(anime.id)
            if (categoriesForAnime.isNotEmpty()) {
                animeObject.categories = categoriesForAnime.map { it.order }
            }
        }

        if (options.tracking) {
            val tracks = handler.awaitList { anime_syncQueries.getTracksByAnimeId(anime.id, backupTrackMapper) }
            if (tracks.isNotEmpty()) {
                animeObject.tracking = tracks
            }
        }

        if (options.history) {
            val historyByAnimeId = getHistory.await(anime.id)
            if (historyByAnimeId.isNotEmpty()) {
                val history = historyByAnimeId.map { history ->
                    val episode = handler.awaitOne { episodesQueries.getEpisodeById(history.episodeId) }
                    BackupHistory(url = episode.url, lastSeen = history.seenAt?.time ?: 0L)
                }
                if (history.isNotEmpty()) {
                    animeObject.history = history
                }
            }
        }

        return animeObject
    }
}

private fun Anime.toBackupAnime() =
    BackupAnime(
        source = this.source,
        url = this.url,
    ).apply {
        title = this@toBackupAnime.title
        artist = this@toBackupAnime.artist
        author = this@toBackupAnime.author
        description = this@toBackupAnime.description
        genre = this@toBackupAnime.genre.orEmpty()
        status = this@toBackupAnime.status.toInt()
        thumbnailUrl = this@toBackupAnime.thumbnailUrl
        favorite = this@toBackupAnime.favorite
        dateAdded = this@toBackupAnime.dateAdded
        viewer_flags = this@toBackupAnime.viewerFlags.toInt()
        episodeFlags = this@toBackupAnime.episodeFlags.toInt()
        updateStrategy = this@toBackupAnime.updateStrategy
        lastModifiedAt = this@toBackupAnime.lastModifiedAt
        favoriteModifiedAt = this@toBackupAnime.favoriteModifiedAt
        version = this@toBackupAnime.version

        // AY -->
        parentId = this@toBackupAnime.parentId
        seasonNumber = this@toBackupAnime.seasonNumber ?: -1.0
        seasonSourceOrder = this@toBackupAnime.seasonOrder ?: 0L
        fetchType = FetchType.Episodes
        // <-- AY
    }
