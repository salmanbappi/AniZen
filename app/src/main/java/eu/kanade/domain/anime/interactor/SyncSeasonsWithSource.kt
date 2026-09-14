// AY -->
package eu.kanade.domain.anime.interactor

import eu.kanade.domain.anime.interactor.UpdateAnime
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.SAnime
import tachiyomi.data.anime.toDomainAnime
import tachiyomi.domain.anime.interactor.NetworkToLocalAnime
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.model.NoSeasonsException
import tachiyomi.domain.anime.model.toAnimeUpdate
import tachiyomi.domain.anime.repository.AnimeRepository
import tachiyomi.domain.season.interactor.GetAnimeSeasonsById
import tachiyomi.domain.season.interactor.ShouldUpdateDbSeason
import tachiyomi.domain.season.service.SeasonRecognition
import java.time.ZonedDateTime

class SyncSeasonsWithSource(
    private val updateAnime: UpdateAnime,
    private val animeRepository: AnimeRepository,
    private val networkToLocalAnime: NetworkToLocalAnime,
    private val shouldUpdateDbSeason: ShouldUpdateDbSeason,
    private val getAnimeSeasonsById: GetAnimeSeasonsById,
) {
    suspend fun await(
        rawSourceSeasons: List<SAnime>,
        anime: Anime,
        source: AnimeSource,
        manualFetch: Boolean = false,
        fetchWindow: Pair<Long, Long> = Pair(0, 0),
    ): List<Anime> {
        if (rawSourceSeasons.isEmpty() && source.id != 0L) {
            throw NoSeasonsException()
        }

        val now = ZonedDateTime.now()

        val rootParentId = anime.parentId ?: anime.id
        val sourceSeasons = rawSourceSeasons
            .distinctBy { it.url }
            .mapIndexed { i, sAnime ->
                networkToLocalAnime.await(sAnime.toDomainAnime(source.id))
                    .copy(
                        parentId = rootParentId,
                        seasonOrder = i.toLong(),
                        fetchType = eu.kanade.tachiyomi.animesource.model.FetchType.Episodes,
                    )
            }

        val dbSeasons = getAnimeSeasonsById.await(rootParentId)

        val newSeasons = mutableListOf<Anime>()
        val updatedSeasons = mutableListOf<Anime>()
        val removedSeasons = dbSeasons.filterNot { dbSeasonItem ->
            sourceSeasons.any { sourceSeason ->
                dbSeasonItem.anime.url == sourceSeason.url
            }
        }

        for (sourceSeason in sourceSeasons) {
            var season = sourceSeason

            // Recognize season number for the season
            val seasonNumber = SeasonRecognition.parseSeasonNumber(
                anime.title,
                season.title,
                season.seasonNumber,
            )
            season = season.copy(seasonNumber = seasonNumber)

            val dbSeason = dbSeasons.find { it.anime.url == season.url }?.anime
            if (dbSeason == null) {
                newSeasons.add(season)
            } else {
                if (shouldUpdateDbSeason.await(dbSeason, season)) {
                    val toChangeSeason = dbSeason.copy(
                        ogTitle = season.title,
                        seasonNumber = season.seasonNumber,
                        seasonOrder = season.seasonOrder,
                        fetchType = eu.kanade.tachiyomi.animesource.model.FetchType.Episodes,
                    )
                    updatedSeasons.add(toChangeSeason)
                }
            }
        }

        // Return if there's nothing to add, delete, or update to avoid unnecessary db transactions.
        if (newSeasons.isEmpty() && removedSeasons.isEmpty() && updatedSeasons.isEmpty()) {
            if (manualFetch || anime.fetchInterval == 0 || anime.nextUpdate < fetchWindow.first) {
                updateAnime.awaitUpdateFetchInterval(
                    anime,
                    now,
                    fetchWindow,
                )
            }
            return sourceSeasons
        }

        if (removedSeasons.isNotEmpty()) {
            val toDeleteIds = removedSeasons.map { it.anime.id }
            animeRepository.removeParentIdByIds(toDeleteIds)
        }

        val toUpdate = newSeasons.map { it.toAnimeUpdate() } +
            updatedSeasons.map { it.toAnimeUpdate() }

        if (toUpdate.isNotEmpty()) {
            updateAnime.awaitAll(toUpdate)
        }

        updateAnime.awaitUpdateLastUpdate(anime.id)

        return sourceSeasons
    }
}
// <-- AY
