package tachiyomi.domain.episode.interactor

import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.domain.anime.interactor.GetFavorites
import tachiyomi.domain.anime.interactor.SetAnimeEpisodeFlags
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.library.service.LibraryPreferences

class SetAnimeDefaultEpisodeFlags(
    private val libraryPreferences: LibraryPreferences,
    private val setAnimeEpisodeFlags: SetAnimeEpisodeFlags,
    private val getFavorites: GetFavorites,
) {

    suspend fun await(anime: Anime) {
        withNonCancellableContext {
            with(libraryPreferences) {
                setAnimeEpisodeFlags.awaitSetAllFlags(
                    animeId = anime.id,
                    unseenFilter = filterEpisodeBySeen().get(),
                    downloadedFilter = filterEpisodeByDownloaded().get(),
                    bookmarkedFilter = filterEpisodeByBookmarked().get(),
                    // AM (FILLERMARK) -->
                    fillermarkedFilter = filterEpisodeByFillermarked().get(),
                    // <-- AM (FILLERMARK)
                    sortingMode = sortEpisodeBySourceOrNumber().get(),
                    sortingDirection = sortEpisodeByAscendingOrDescending().get(),
                    displayMode = displayEpisodeByNameOrNumber().get(),
                    seasonGrouping = Anime.EPISODE_SEASON_GROUP_DEFAULT,
                    showPreviews = if (showEpisodeThumbnail().get()) Anime.EPISODE_SHOW_PREVIEWS else Anime.EPISODE_SHOW_NOT_PREVIEWS,
                    showSummaries = if (showEpisodeSummary().get()) Anime.EPISODE_SHOW_SUMMARIES else Anime.EPISODE_SHOW_NOT_SUMMARIES,
                    // ANZ -->
                    thumbnailSize = episodeThumbnailSize().get(),
                    // ANZ <--
                )
            }
        }
    }

    suspend fun awaitAll() {
        withNonCancellableContext {
            getFavorites.await().forEach { await(it) }
        }
    }
}
