package mihon.feature.announcements

import android.content.Intent
import android.net.Uri
import android.os.Parcelable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import kotlinx.parcelize.Parcelize
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Parcelize
data class AnnouncementDetailScreen(private val entry: AnnouncementEntry) : Screen, Parcelable {

    @Composable
    override fun Content() {
        val repository = remember { AnnouncementsRepository() }
        val extensionManager = remember { Injekt.get<ExtensionManager>() }
        val installedExtensions by extensionManager.installedExtensionsFlow.collectAsState()
        val context = LocalContext.current
        var isWatchlisted by remember { mutableStateOf(repository.isWatchlisted(entry.mediaId)) }
        var showSourceChooser by remember { mutableStateOf(false) }
        val navigator = LocalNavigator.currentOrThrow
        val relatedQuery = entry.relatedTitle ?: entry.title

        Scaffold { contentPadding ->
            Column(
                modifier = Modifier
                    .padding(contentPadding)
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth(),
            ) {
                HeroHeader(entry)
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp)) {
                    CategoryBadge(entry.category)
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    Text(
                        text = entry.description,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 10.dp),
                    )

                    Row(
                        modifier = Modifier.padding(top = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        entry.expectedYear?.let { year ->
                            LabeledValue(label = "Expected", value = year.toString())
                        }
                        LabeledValue(label = "Type", value = entry.category.displayName())
                        LabeledValue(
                            label = "Source",
                            value = if (entry.fetchedFrom.startsWith("MAL")) "MAL" else "AniList",
                        )
                    }

                    if (entry.relatedTitle != null) {
                        Text(
                            text = "Related anime",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 28.dp, bottom = 8.dp),
                        )
                        RelatedAnimeCard(
                            title = entry.relatedTitle,
                            year = entry.relatedYear,
                            onClick = { showSourceChooser = true },
                        )
                        TextButton(
                            onClick = {
                                context.startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("https://anilist.co/anime/${entry.relatedAniListMediaId ?: entry.mediaId}"),
                                    ),
                                )
                            },
                            modifier = Modifier.padding(top = 4.dp),
                        ) {
                            Icon(Icons.Outlined.OpenInNew, contentDescription = null)
                            Text("Open related anime on AniList", modifier = Modifier.padding(start = 8.dp))
                        }
                    }

                    Button(
                        onClick = {
                            repository.toggleWatchlist(entry.mediaId)
                            isWatchlisted = !isWatchlisted
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 20.dp),
                    ) {
                        Icon(
                            imageVector = if (isWatchlisted) {
                                Icons.Filled.NotificationsActive
                            } else {
                                Icons.Filled.Notifications
                            },
                            contentDescription = null,
                        )
                        Text(
                            text = if (isWatchlisted) {
                                "Watching for release date"
                            } else {
                                "Add to watchlist"
                            },
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        }

        if (showSourceChooser) {
            SourceChooserDialog(
                query = relatedQuery,
                extensions = installedExtensions,
                onDismiss = { showSourceChooser = false },
                onSelectExtension = { extension ->
                    showSourceChooser = false
                    navigator.push(GlobalSearchScreen(relatedQuery, extension.pkgName))
                },
                onSearchAllSources = {
                    showSourceChooser = false
                    navigator.push(GlobalSearchScreen(relatedQuery))
                },
            )
        }
    }

    @Composable
    private fun HeroHeader(entry: AnnouncementEntry) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(250.dp),
        ) {
            AsyncImage(
                model = entry.bannerImageUrl ?: entry.coverImageUrl,
                contentDescription = entry.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth(),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(250.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, MaterialTheme.colorScheme.background),
                        ),
                    ),
            )
        }
    }

    @Composable
    private fun SourceChooserDialog(
        query: String,
        extensions: List<Extension.Installed>,
        onDismiss: () -> Unit,
        onSelectExtension: (Extension.Installed) -> Unit,
        onSearchAllSources: () -> Unit,
    ) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Search related anime") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Choose an installed source for “$query”. If it is not available there, search all sources or use AniList.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    extensions
                        .sortedBy { it.name.lowercase() }
                        .forEach { extension ->
                            OutlinedButton(
                                onClick = { onSelectExtension(extension) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(extension.name)
                            }
                        }
                    if (extensions.isEmpty()) {
                        Text(
                            text = "No installed anime extensions were found.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(
                        onClick = onSearchAllSources,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Search all installed sources")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            },
        )
    }

    @Composable
    private fun CategoryBadge(category: AnnouncementCategory) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(50),
        ) {
            Text(
                text = category.displayName(),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }

    @Composable
    private fun LabeledValue(label: String, value: String) {
        Column {
            Text(text = label, style = MaterialTheme.typography.labelSmall)
            Text(text = value, style = MaterialTheme.typography.bodyMedium)
        }
    }

    @Composable
    private fun RelatedAnimeCard(title: String, year: Int?, onClick: () -> Unit) {
        Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = title, style = MaterialTheme.typography.bodyLarge)
                    if (year != null) {
                        Text(text = year.toString(), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(
                    text = "Search",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }

    private fun AnnouncementCategory.displayName(): String = when (this) {
        AnnouncementCategory.NEW_SEASON -> "New Season"
        AnnouncementCategory.ADAPTATION -> "Adaptation"
        AnnouncementCategory.SPIN_OFF -> "Spin-off"
        AnnouncementCategory.REMAKE -> "Remake"
        AnnouncementCategory.ORIGINAL -> "Original"
        AnnouncementCategory.MOVIE -> "Movie"
        AnnouncementCategory.SPECIAL -> "Special / OVA / ONA"
    }
}