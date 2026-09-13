package eu.kanade.presentation.browse.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.library.components.AnimeComfortableGridItem
import eu.kanade.presentation.library.components.CommonAnimeItemDefaults
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.model.AnimeCover
import tachiyomi.presentation.core.util.collectAsState as collectAsStatePref
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import tachiyomi.domain.anime.model.asAnimeCover
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun GlobalSearchCardRow(
    titles: List<Anime>,
    getAnime: @Composable (Anime) -> State<Anime>,
    onClick: (Anime) -> Unit,
    onLongClick: (Anime) -> Unit,
    selection: List<Anime> = emptyList(),
) {
    if (titles.isEmpty()) {
        EmptyResultItem()
        return
    }

    val uiPreferences = remember { Injekt.get<UiPreferences>() }
    val globalPanorama by uiPreferences.panoramaCover().collectAsStatePref() as State<Boolean>

    LazyRow(
        contentPadding = PaddingValues(MaterialTheme.padding.small),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
    ) {
        items(
            items = titles,
            key = { it.id },
        ) { it: tachiyomi.domain.anime.model.Anime ->
            val animeState = getAnime(it)
            val title by animeState
            val (entry, _) = eu.kanade.presentation.anime.components.AnimeCover.getEntry(title.id, usePanoramaOverride = globalPanorama)
            AnimeItem(
                title = title.title,
                cover = title.asAnimeCover(),
                isFavorite = title.favorite,
                isSelected = selection.any { it.id == title.id },
                onClick = { onClick(title) },
                onLongClick = { onLongClick(title) },
                width = if (entry == eu.kanade.presentation.anime.components.AnimeCover.Panorama) 160.dp else 96.dp,
            )
        }
    }
}

@Composable
internal fun AnimeItem(
    title: String,
    cover: AnimeCover,
    isFavorite: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    width: Dp = 96.dp,
) {
    Box(modifier = Modifier.width(width)) {
        AnimeComfortableGridItem(
            title = title,
            titleMaxLines = 3,
            coverData = cover,
            coverBadgeStart = {
                InLibraryBadge(enabled = isFavorite)
            },
            coverBadgeEnd = {
                if (isSelected) {
                    Checkbox(
                        checked = true,
                        onCheckedChange = { onClick() },
                    )
                }
            },
            coverAlpha = if (isFavorite) CommonAnimeItemDefaults.BrowseFavoriteCoverAlpha else 1f,
            onClick = onClick,
            onLongClick = onLongClick,
        )
    }
}

@Composable
internal fun EmptyResultItem() {
    Text(
        text = stringResource(MR.strings.no_results_found),
        modifier = Modifier
            .padding(
                horizontal = MaterialTheme.padding.medium,
                vertical = MaterialTheme.padding.small,
            ),
    )
}
