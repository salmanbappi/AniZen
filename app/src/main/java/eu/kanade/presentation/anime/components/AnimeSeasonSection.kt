package eu.kanade.presentation.anime.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LibraryBooks
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.domain.ui.UiPreferences
import kotlinx.collections.immutable.ImmutableList
import tachiyomi.domain.anime.model.Season
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.Badge
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.roundToInt

@Composable
fun AnimeSeasonSection(
    seasons: ImmutableList<Season>,
    onSeasonClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (seasons.size <= 1) return

    val uiPreferences = remember { Injekt.get<UiPreferences>() }
    val containerStyles by uiPreferences.containerStyles().collectAsState()
    val useContainer = true

    // Intuitive Sorting: Seasons/Movies first (positive/0/-2), then OVAs/ONAs/Specials
    val sortedSeasons = remember(seasons) {
        seasons.sortedWith(
            compareBy<Season> {
                when {
                    it.seasonNumber >= 0 -> 0 // Normal seasons
                    it.seasonNumber == -2.0 -> 1 // Movies
                    it.seasonNumber == -3.0 -> 2 // OVA
                    it.seasonNumber == -4.0 -> 3 // ONA
                    else -> 4 // Specials
                }
            }.thenBy { it.seasonNumber },
        )
    }

    val content = @Composable {
        Column(modifier = if (useContainer) Modifier.padding(vertical = 12.dp) else Modifier) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                androidx.compose.foundation.layout.Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.LibraryBooks,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "Series Seasons",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    text = "Quickly switch between seasons of this series",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }

            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(
                    items = sortedSeasons,
                    key = { _, it -> "anime-season-${it.anime.id}" },
                ) { _, season ->
                    SeasonItem(
                        season = season,
                        onClick = { onSeasonClick(season.anime.id) },
                    )
                }
            }
        }
    }

    if (useContainer) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            tonalElevation = 2.dp,
        ) {
            content()
        }
    } else {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun SeasonItem(
    season: Season,
    onClick: () -> Unit,
) {
    val seasonNum = season.seasonNumber
    val seasonLabel = if (seasonNum > 0.0) {
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
        // MOVIES, OVAS, SPECIALS -> Show the Unique Name
        val fullTitle = season.anime.title
        val subtitle = if (fullTitle.contains(":")) {
            fullTitle.substringAfter(":").trim()
        } else {
            fullTitle
        }

        when (seasonNum) {
            -2.0 -> if (subtitle.contains("Movie", ignoreCase = true)) subtitle else "Movie: $subtitle"
            -3.0 -> if (subtitle.contains("OVA", ignoreCase = true)) subtitle else "OVA: $subtitle"
            -4.0 -> if (subtitle.contains("ONA", ignoreCase = true)) subtitle else "ONA: $subtitle"
            -5.0 -> if (subtitle.contains("Special", ignoreCase = true)) subtitle else "Special: $subtitle"
            else -> subtitle
        }
    }

    val (entry, ratio) = AnimeCover.getEntry(season.anime.id)
    val width = if (entry == AnimeCover.Panorama) 200.dp else 104.dp

    Column(
        modifier = Modifier
            .width(width)
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        androidx.compose.foundation.layout.Box {
            entry(
                data = season.anime,
                onClick = onClick,
                modifier = Modifier.fillMaxWidth(),
                ratio = ratio,
            )

            if (season.isPrimary) {
                tachiyomi.presentation.core.components.BadgeGroup(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp),
                ) {
                    Badge(
                        text = stringResource(MR.strings.selected),
                        color = MaterialTheme.colorScheme.primary,
                        textColor = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
        Text(
            text = seasonLabel,
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = if (season.isPrimary) FontWeight.Bold else FontWeight.Normal,
            ),
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            color = if (season.isPrimary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}
