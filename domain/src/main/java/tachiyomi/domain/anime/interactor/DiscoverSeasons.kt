package tachiyomi.domain.anime.interactor

import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import kotlinx.coroutines.flow.firstOrNull
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.model.toDomainAnime
import tachiyomi.domain.anime.repository.AnimeRepository
import tachiyomi.domain.anime.service.SeasonRecognition
import tachiyomi.domain.source.interactor.GetRelatedAnime
import tachiyomi.domain.source.service.SourceManager

class DiscoverSeasons(
    private val sourceManager: SourceManager,
    private val animeRepository: AnimeRepository,
    private val getRelatedAnime: GetRelatedAnime,
) {
    suspend fun await(anime: Anime): List<Anime> {
        val source = sourceManager.get(anime.source) as? AnimeCatalogueSource ?: return emptyList()

        val originalFullTitle = anime.title
        val rootTitle = SeasonRecognition.getRootTitle(originalFullTitle)
        val ogTitle = anime.ogTitle.takeIf { it.isNotBlank() } ?: originalFullTitle

        if (rootTitle.length < 3) return emptyList()

        return try {
            // 1. Get explicit relations from the extension (Highest Confidence)
            val explicitRelations = getRelatedAnime.subscribe(anime).firstOrNull()?.second.orEmpty()
                .map { it.toDomainAnime(anime.source) }

            // 2. Try searching for root title and original title for better coverage
            val searchQueries = listOf(rootTitle, ogTitle).distinctBy { SeasonRecognition.getAlphanumeric(it) }
            val searchResults = searchQueries.flatMap { query ->
                source.getSearchAnime(1, query, AnimeFilterList()).animes
            }.map { it.toDomainAnime(anime.source) }

            // 3. Pool and Filter
            val allResults = (explicitRelations + searchResults)
                .filter { it.url.trimEnd('/') != anime.url.trimEnd('/') } // Filter out the current anime
                .distinctBy { it.url.trimEnd('/') }

            val originalWordSet = SeasonRecognition.getWordSet(rootTitle)
            if (originalWordSet.isEmpty()) return emptyList()

            val candidates = allResults.filter { sAnime ->
                val candidateFullTitle = sAnime.title

                // If it came from explicit relations, we trust it more
                val isExplicit = explicitRelations.any { it.url == sAnime.url }
                if (isExplicit) return@filter true

                // NO-TOLERANCE FILTERS
                if (SeasonRecognition.isUnrelated(originalFullTitle, candidateFullTitle)) return@filter false

                // 1. Signature Word Coverage Lock (Alphanumeric normalization)
                val originalSignature = SeasonRecognition.getSignatureWords(rootTitle)
                val candidateAlpha = SeasonRecognition.getAlphanumeric(candidateFullTitle)

                if (originalSignature.isNotEmpty()) {
                    val allWordsContained = originalSignature.all { sigWord ->
                        candidateAlpha.contains(SeasonRecognition.getAlphanumeric(sigWord))
                    }
                    if (!allWordsContained) return@filter false
                }

                // 2. Multi-Layer Similarity Check
                val dice = SeasonRecognition.diceCoefficient(rootTitle, candidateFullTitle)
                val tokenSort = SeasonRecognition.tokenSortSimilarity(rootTitle, candidateFullTitle)
                val isAcronym = SeasonRecognition.isAcronymMatch(rootTitle, candidateFullTitle)

                // Final Decision: Must pass signature check AND one of the similarity checks
                if (dice < 0.6 && tokenSort < 0.6 && !isAcronym && !candidateAlpha.contains(SeasonRecognition.getAlphanumeric(rootTitle))) {
                    return@filter false
                }

                // 3. Main Season Lock: Allow seasons, movies, OVAs, and Specials.
                val seasonNum = SeasonRecognition.parseSeasonNumber(anime.title, candidateFullTitle)
                if (seasonNum < -5.0) return@filter false

                true
            }.take(20)

            candidates.sortedBy { sAnime ->
                SeasonRecognition.parseSeasonNumber(anime.title, sAnime.title)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
