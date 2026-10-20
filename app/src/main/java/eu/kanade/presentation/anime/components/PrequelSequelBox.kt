package eu.kanade.presentation.anime.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.data.track.anilist.dto.ALRelationEdge
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.tvFocusHighlight
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

// ANZ -->
private data class RelationEntry(
    val label: String,
    val edge: ALRelationEdge,
    val isPrequel: Boolean,
)

/*
 * "Relacionados": prequel / sequel as wide cards in a horizontal carousel (a single one simply
 * takes the full width). Cards wrap their content, so big system fonts never clip the title.
 */
@Composable
fun PrequelSequelBox(
    anime: Anime,
    relations: List<ALRelationEdge>,
    onRelationClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (relations.isEmpty()) return

    val sourceManager = remember { Injekt.get<SourceManager>() }
    val source = remember(anime.source) { sourceManager.get(anime.source) as? ConfigurableSource }
    val preferredLanguage = remember(source) {
        val prefs = source?.getSourcePreferences()
        val rawLang = prefs?.getString("preferred_title_language", null)
            ?: prefs?.getString("preferred_title_lang", null)
            ?: prefs?.getString("pref_title_language", null)
            ?: prefs?.getString("title_language", null)
            ?: prefs?.getString("preferred_title", null)
            ?: prefs?.getString("pref_title", null)
            ?: prefs?.getString("preferred_title_style", null)
            ?: prefs?.getString("title_format", null)
            ?: "romaji"
        rawLang.lowercase()
    }

    val prequel = relations.find { it.relationType == "PREQUEL" }
    val sequel = relations.find { it.relationType == "SEQUEL" }
    if (prequel == null && sequel == null) return

    val entries = listOfNotNull(
        prequel?.let { RelationEntry(stringResource(MR.strings.relation_prequel), it, true) },
        sequel?.let { RelationEntry(stringResource(MR.strings.relation_sequel), it, false) },
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Relacionados",
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.3).sp,
            ),
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp),
        )
        if (entries.size == 1) {
            val entry = entries.first()
            RelationBanner(
                relationName = entry.label,
                edge = entry.edge,
                isPrequel = entry.isPrequel,
                preferredLanguage = preferredLanguage,
                onRelationClick = onRelationClick,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .fillMaxWidth(),
            )
        } else {
            val cardWidth = (LocalConfiguration.current.screenWidthDp * 0.8f).coerceAtMost(340f).dp
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(entries, key = { it.label }) { entry ->
                    RelationBanner(
                        relationName = entry.label,
                        edge = entry.edge,
                        isPrequel = entry.isPrequel,
                        preferredLanguage = preferredLanguage,
                        onRelationClick = onRelationClick,
                        modifier = Modifier.width(cardWidth),
                    )
                }
            }
        }
    }
}

@Composable
private fun RelationBanner(
    relationName: String,
    edge: ALRelationEdge,
    isPrequel: Boolean,
    preferredLanguage: String,
    onRelationClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(20.dp)

    val title = when (preferredLanguage) {
        "english" -> edge.node.title.english ?: edge.node.title.romaji ?: edge.node.title.userPreferred
        "native", "japanese" -> edge.node.title.native ?: edge.node.title.romaji ?: edge.node.title.userPreferred
        else -> edge.node.title.romaji ?: edge.node.title.userPreferred
    }

    Box(
        modifier = modifier
            .tvFocusHighlight(
                shape = shape,
                focusedScale = 1.04f,
                borderWidth = 3.dp,
                focusedBackgroundAlpha = 0.25f,
            )
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable { onRelationClick(title) },
    ) {
        // Soft blurred cover as the card atmosphere
        AsyncImage(
            model = edge.node.coverImage?.large,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alpha = 0.30f,
            modifier = Modifier
                .matchParentSize()
                .blur(14.dp),
        )

        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AsyncImage(
                model = edge.node.coverImage?.large,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(60.dp)
                    .height(88.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = if (isPrequel) Icons.AutoMirrored.Filled.ArrowBack else Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.width(14.dp).height(14.dp),
                        tint = accent,
                    )
                    Text(
                        text = relationName.uppercase(),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp,
                        ),
                        color = accent,
                        maxLines = 1,
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 19.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
// ANZ <--

@androidx.compose.ui.tooling.preview.Preview(showBackground = true)
@Composable
private fun PrequelSequelBoxPreview() {
    val dummyRelations = listOf(
        eu.kanade.tachiyomi.data.track.anilist.dto.ALRelationEdge(
            relationType = "PREQUEL",
            node = eu.kanade.tachiyomi.data.track.anilist.dto.ALRelationNode(
                id = 1,
                title = eu.kanade.tachiyomi.data.track.anilist.dto.ALRelationTitle(userPreferred = "Dr. STONE"),
                coverImage = null
            )
        ),
        eu.kanade.tachiyomi.data.track.anilist.dto.ALRelationEdge(
            relationType = "SEQUEL",
            node = eu.kanade.tachiyomi.data.track.anilist.dto.ALRelationNode(
                id = 2,
                title = eu.kanade.tachiyomi.data.track.anilist.dto.ALRelationTitle(userPreferred = "Dr. STONE: New World"),
                coverImage = null
            )
        )
    )

    MaterialTheme {
        PrequelSequelBox(
            anime = Anime.create(),
            relations = dummyRelations,
            onRelationClick = {}
        )
    }
}
