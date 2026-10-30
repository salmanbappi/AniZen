package tachiyomi.domain.anime.interactor

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.model.Season
import tachiyomi.domain.anime.repository.AnimeMergeRepository
import tachiyomi.domain.anime.repository.AnimeRepository
import tachiyomi.domain.anime.service.SeasonRecognition

class GetSeasonsByAnimeId(
    private val animeRepository: AnimeRepository,
    private val animeMergeRepository: AnimeMergeRepository,
) {

    suspend fun await(
        animeId: Long,
        virtualSeasons: List<Anime> = emptyList(),
        useHierarchicalSeasons: Boolean = true,
    ): List<Season> {
        val anime = animeRepository.getAnimeById(animeId) ?: return emptyList()

        // 1. Get real hierarchical seasons from DB
        val parentId = if (useHierarchicalSeasons) (anime.parentId ?: anime.id) else anime.id
        val dbSeasons = animeRepository.getAnimeSeasonsById(parentId).map { it.anime }
        if (dbSeasons.isNotEmpty()) {
            return dbSeasons.map {
                Season(
                    anime = it,
                    seasonNumber = it.seasonNumber ?: SeasonRecognition.parseSeasonNumber(anime.title, it.title),
                    isPrimary = it.id == anime.id,
                )
            }.sortedBy { it.seasonNumber }
        }

        // 2. Get merged seasons (legacy/compatibility)
        val references = animeMergeRepository.getReferencesById(animeId)
        if (references.isNotEmpty()) {
            val mergedAnimes = animeMergeRepository.getMergedAnimeById(animeId)
            return mergedAnimes.map { mergedAnime ->
                val ref = references.find { it.animeId == mergedAnime.id }
                Season(
                    anime = mergedAnime,
                    seasonNumber = mergedAnime.seasonNumber ?: SeasonRecognition.parseSeasonNumber(anime.title, mergedAnime.title),
                    isPrimary = ref?.isInfoAnime ?: false,
                )
            }.sortedBy { it.seasonNumber }
        }

        // 3. Fallback: Virtual Discovery Seasons
        if (virtualSeasons.isNotEmpty()) {
            val all = (listOf(anime) + virtualSeasons).distinctBy { it.url.trimEnd('/') }
            return all.map {
                Season(
                    anime = it,
                    seasonNumber = it.seasonNumber ?: SeasonRecognition.parseSeasonNumber(anime.title, it.title),
                    isPrimary = it.id == anime.id,
                )
            }.sortedBy { it.seasonNumber }
        }

        // 4. Just the anime itself
        return listOf(Season(anime, SeasonRecognition.parseSeasonNumber(anime.title, anime.title), true))
    }

    fun subscribe(
        animeId: Long,
        virtualSeasonsFlow: Flow<List<Anime>>? = null,
        useHierarchicalSeasons: Boolean = true,
    ): Flow<List<Season>> = flow {
        val anime = animeRepository.getAnimeById(animeId) ?: return@flow
        val parentId = if (useHierarchicalSeasons) (anime.parentId ?: anime.id) else anime.id

        val animeFlow = animeRepository.getAnimeByIdAsFlow(animeId)
        val dbSeasonsFlow = animeRepository.getAnimeSeasonsByIdAsFlow(parentId)
        val mergedAnimesFlow = animeMergeRepository.subscribeMergedAnimeById(animeId)
        val referencesFlow = animeMergeRepository.subscribeReferencesById(animeId)

        val flow = if (virtualSeasonsFlow != null) {
            combine(animeFlow, dbSeasonsFlow, mergedAnimesFlow, referencesFlow, virtualSeasonsFlow) { a, db, merged, refs, virtual ->
                mapToSeasons(a, db.map { it.anime }, merged, refs, virtual)
            }
        } else {
            combine(animeFlow, dbSeasonsFlow, mergedAnimesFlow, referencesFlow) { a, db, merged, refs ->
                mapToSeasons(a, db.map { it.anime }, merged, refs, emptyList())
            }
        }
        emitAll(flow)
    }

    private fun mapToSeasons(
        anime: Anime?,
        dbSeasons: List<Anime>,
        mergedAnimes: List<Anime>,
        references: List<tachiyomi.domain.anime.model.MergedAnimeReference>,
        virtualSeasons: List<Anime>,
    ): List<Season> {
        if (anime == null) return emptyList()

        // 1. Prioritize Hierarchical Seasons
        if (dbSeasons.isNotEmpty()) {
            return dbSeasons.map {
                Season(
                    anime = it,
                    seasonNumber = it.seasonNumber ?: SeasonRecognition.parseSeasonNumber(anime.title, it.title),
                    isPrimary = it.id == anime.id,
                )
            }.sortedBy { it.seasonNumber }
        }

        // 2. Secondary: Merged Seasons
        if (references.isNotEmpty()) {
            return mergedAnimes.map { mergedAnime ->
                val ref = references.find { it.animeId == mergedAnime.id }
                Season(
                    anime = mergedAnime,
                    seasonNumber = mergedAnime.seasonNumber ?: SeasonRecognition.parseSeasonNumber(anime.title, mergedAnime.title),
                    isPrimary = ref?.isInfoAnime ?: false,
                )
            }.sortedBy { it.seasonNumber }
        }

        // 3. Fallback: Virtual Discovery Seasons
        if (virtualSeasons.isNotEmpty()) {
            val all = (listOf(anime) + virtualSeasons).distinctBy { it.url.trimEnd('/') }
            return all.map {
                Season(
                    it,
                    seasonNumber = it.seasonNumber ?: SeasonRecognition.parseSeasonNumber(anime.title, it.title),
                    isPrimary = it.id == anime.id,
                )
            }.sortedBy { it.seasonNumber }
        }

        return listOf(Season(anime, SeasonRecognition.parseSeasonNumber(anime.title, anime.title), true))
    }
}
