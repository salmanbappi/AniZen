// AY -->
package eu.kanade.presentation.anime.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.kanade.presentation.library.components.AnimeComfortableGridItem
import eu.kanade.presentation.library.components.AnimeCompactGridItem
import eu.kanade.presentation.library.components.AnimeListItem
import eu.kanade.presentation.library.components.DownloadsBadge
import eu.kanade.presentation.library.components.UnviewedBadge
import eu.kanade.tachiyomi.ui.anime.AnimeSeasonItem
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.model.AnimeCover
import tachiyomi.domain.anime.model.SeasonAnime
import tachiyomi.domain.anime.model.SeasonDisplayMode
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.math.roundToInt

@Composable
fun AnimeSeasonListItem(
    anime: Anime,
    item: AnimeSeasonItem,
    containerHeight: Int,
    onSeasonClicked: (SeasonAnime) -> Unit,
    onClickContinueWatching: ((SeasonAnime) -> Unit)?,
    listItemModifier: Modifier = Modifier,
) {
    val itemAnime = item.seasonAnime.anime
    val seasonNum = itemAnime.seasonNumber ?: 0.0
    val title = if (anime.seasonDisplayMode == Anime.SEASON_DISPLAY_MODE_NUMBER || seasonNum > 0.0) {
        val major = seasonNum.toInt()
        val minor = ((seasonNum - major) * 100).roundToInt()
        if (minor > 0) {
            stringResource(
                MR.strings.display_mode_season_part,
                major.toString(),
                minor.toString(),
            )
        } else {
            stringResource(
                MR.strings.display_mode_season,
                major.toString(),
            )
        }
    } else {
        itemAnime.title
    }

    when (anime.seasonDisplayGridMode) {
        SeasonDisplayMode.ComfortableGrid -> {
            AnimeComfortableGridItem(
                title = title,
                coverData = AnimeCover(
                    animeId = itemAnime.id,
                    sourceId = itemAnime.source,
                    isAnimeFavorite = itemAnime.favorite,
                    ogUrl = itemAnime.thumbnailUrl,
                    lastModified = itemAnime.coverLastModified,
                ),
                coverBadgeStart = {
                    DownloadsBadge(count = item.downloadCount)
                    UnviewedBadge(count = item.unseenCount)
                },
                coverBadgeEnd = {},
                onLongClick = { onSeasonClicked(item.seasonAnime) },
                onClick = { onSeasonClicked(item.seasonAnime) },
                onClickContinueWatching = if (onClickContinueWatching != null && item.showContinueOverlay) {
                    { onClickContinueWatching(item.seasonAnime) }
                } else {
                    null
                },
            )
        }
        SeasonDisplayMode.CompactGrid, SeasonDisplayMode.CoverOnlyGrid -> {
            AnimeCompactGridItem(
                title = title.takeIf { anime.seasonDisplayGridMode is SeasonDisplayMode.CompactGrid },
                coverData = AnimeCover(
                    animeId = itemAnime.id,
                    sourceId = itemAnime.source,
                    isAnimeFavorite = itemAnime.favorite,
                    ogUrl = itemAnime.thumbnailUrl,
                    lastModified = itemAnime.coverLastModified,
                ),
                coverBadgeStart = {
                    DownloadsBadge(count = item.downloadCount)
                    UnviewedBadge(count = item.unseenCount)
                },
                coverBadgeEnd = {},
                onLongClick = { onSeasonClicked(item.seasonAnime) },
                onClick = { onSeasonClicked(item.seasonAnime) },
                onClickContinueWatching = if (onClickContinueWatching != null && item.showContinueOverlay) {
                    { onClickContinueWatching(item.seasonAnime) }
                } else {
                    null
                },
            )
        }
        SeasonDisplayMode.List -> {
            AnimeListItem(
                title = title,
                coverData = AnimeCover(
                    animeId = itemAnime.id,
                    sourceId = itemAnime.source,
                    isAnimeFavorite = itemAnime.favorite,
                    ogUrl = itemAnime.thumbnailUrl,
                    lastModified = itemAnime.coverLastModified,
                ),
                badge = {
                    DownloadsBadge(count = item.downloadCount)
                    UnviewedBadge(count = item.unseenCount)
                },
                onLongClick = { onSeasonClicked(item.seasonAnime) },
                onClick = { onSeasonClicked(item.seasonAnime) },
                onClickContinueWatching = if (onClickContinueWatching != null && item.showContinueOverlay) {
                    { onClickContinueWatching(item.seasonAnime) }
                } else {
                    null
                },
                entries = anime.seasonDisplayGridSize,
                containerHeight = containerHeight,
            )
        }
    }
}
// <-- AY
