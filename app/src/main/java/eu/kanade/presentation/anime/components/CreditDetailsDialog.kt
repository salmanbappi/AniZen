package eu.kanade.presentation.anime.components

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import eu.kanade.tachiyomi.animesource.model.Credit
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.presentation.core.util.clickableNoIndication
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.absoluteValue

private val biographyCache = ConcurrentHashMap<String, String>()

@Composable
fun CreditDetailsDialog(
    cast: List<Credit>,
    initialIndex: Int,
    onDismissRequest: () -> Unit,
    onSearch: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var isVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        isVisible = true
    }

    fun dismissWithAnimation() {
        isVisible = false
        scope.launch {
            delay(250)
            onDismissRequest()
        }
    }

    val pagerState = rememberPagerState(
        initialPage = initialIndex,
        pageCount = { cast.size },
    )

    Dialog(
        onDismissRequest = { dismissWithAnimation() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickableNoIndication { dismissWithAnimation() },
            contentAlignment = Alignment.Center,
        ) {
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(300)) + scaleIn(tween(300), initialScale = 0.8f),
                exit = fadeOut(tween(250)) + scaleOut(tween(250), targetScale = 0.8f),
            ) {
                HorizontalPager(
                    state = pagerState,
                    contentPadding = PaddingValues(horizontal = 32.dp),
                    pageSpacing = 16.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) { page ->
                    val credit = cast[page]
                    val pageOffset = ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction).absoluteValue
                    val scale = 0.85f + (1f - 0.85f) * (1f - pageOffset.coerceIn(0f, 1f))
                    val alpha = 0.5f + (1f - 0.5f) * (1f - pageOffset.coerceIn(0f, 1f))

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                this.alpha = alpha
                            }
                            .clickableNoIndication {} // Prevent click propagation to background
                            .border(
                                width = 1.5.dp,
                                brush = Brush.sweepGradient(
                                    colors = listOf(
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                        MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f),
                                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f),
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                    ),
                                ),
                                shape = RoundedCornerShape(24.dp),
                            ),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
                    ) {
                        CreditPageContent(
                            credit = credit,
                            dismissWithAnimation = { dismissWithAnimation() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CreditPageContent(
    credit: Credit,
    dismissWithAnimation: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    var biography by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    val parsedDetails = remember(credit.url) {
        val url = credit.url
        if (url == null) return@remember null
        when {
            url.contains("anilist.co/character/") -> {
                val id = url.substringAfter("anilist.co/character/").substringBefore("/").toLongOrNull()
                id?.let { Pair("anilist-character", it) }
            }
            url.contains("anilist.co/staff/") -> {
                val id = url.substringAfter("anilist.co/staff/").substringBefore("/").toLongOrNull()
                id?.let { Pair("anilist-staff", it) }
            }
            url.contains("themoviedb.org/person/") -> {
                val id = url.substringAfter("themoviedb.org/person/").substringBefore("/").toLongOrNull()
                id?.let { Pair("tmdb-person", it) }
            }
            else -> null
        }
    }

    LaunchedEffect(parsedDetails) {
        if (parsedDetails == null) return@LaunchedEffect
        val cacheKey = credit.url ?: ""
        if (cacheKey.isNotEmpty() && biographyCache.containsKey(cacheKey)) {
            biography = biographyCache[cacheKey]
            return@LaunchedEffect
        }

        isLoading = true
        biography = null
        try {
            val trackerManager = Injekt.get<eu.kanade.tachiyomi.data.track.TrackerManager>()
            val result = withIOContext {
                when (parsedDetails.first) {
                    "anilist-character" -> trackerManager.aniList.api.getCharacterDescription(parsedDetails.second)
                    "anilist-staff" -> trackerManager.aniList.api.getStaffDescription(parsedDetails.second)
                    "tmdb-person" -> trackerManager.tmdb.api.getPersonBiography(parsedDetails.second)
                    else -> null
                }
            }
            biography = result
            if (!result.isNullOrBlank() && cacheKey.isNotEmpty()) {
                biographyCache[cacheKey] = result
            }
        } catch (_: Exception) {
            // Log/ignore
        } finally {
            isLoading = false
        }
    }

    val processedBio = remember(biography) {
        biography?.let {
            it.replace(Regex("<br\\s*/?>"), "\n")
                .trim()
        }
    }

    Column(
        modifier = Modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Share and Close buttons at top
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {
                try {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, credit.name)
                        putExtra(Intent.EXTRA_TEXT, "${credit.name} - ${credit.url ?: ""}")
                    }
                    context.startActivity(Intent.createChooser(intent, "Share Character"))
                } catch (_: Exception) {}
            }) {
                Icon(
                    imageVector = Icons.Outlined.Share,
                    contentDescription = "Share",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }

            IconButton(onClick = { dismissWithAnimation() }) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Close",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }

        // Profile Image
        val ctx = LocalContext.current
        val imageModifier = Modifier
            .size(120.dp)
            .clip(CircleShape)
            .border(
                width = 3.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                shape = CircleShape,
            )

        if (!credit.image_url.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(ctx)
                    .data(credit.image_url)
                    .crossfade(true)
                    .build(),
                contentDescription = credit.name,
                modifier = imageModifier,
            )
        } else {
            Box(
                modifier = imageModifier.background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Name
        Text(
            text = credit.name,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Role / Character Info
        val isCharacter = credit.character != null
        val hasVA = !credit.role.isNullOrBlank()

        var isCharacterExpanded by remember { mutableStateOf(false) }
        var isVAExpanded by remember { mutableStateOf(false) }
        var isRoleExpanded by remember { mutableStateOf(false) }

        if (isCharacter && hasVA && credit.role != credit.character) {
            Text(
                text = "Character",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = credit.character ?: "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = if (isCharacterExpanded) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickableNoIndication { isCharacterExpanded = !isCharacterExpanded },
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Played by / VA",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
            Text(
                text = credit.role ?: "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = if (isVAExpanded) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickableNoIndication { isVAExpanded = !isVAExpanded },
            )
        } else if (!credit.role.isNullOrBlank()) {
            Text(
                text = "Role",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = credit.role ?: "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = if (isRoleExpanded) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickableNoIndication { isRoleExpanded = !isRoleExpanded },
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Biography / Description scrollable area with Markdown
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 180.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(12.dp),
                )
                .padding(12.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            } else {
                val bioMarkdown = processedBio
                if (!bioMarkdown.isNullOrBlank()) {
                    MarkdownRender(
                        content = bioMarkdown,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(
                        text = "No description available.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Buttons
        val creditUrl = credit.url
        if (!creditUrl.isNullOrBlank()) {
            val buttonText = remember(creditUrl) {
                when {
                    creditUrl.contains("anilist.co") -> "Open on AniList"
                    creditUrl.contains("themoviedb.org") -> "Open on TMDB"
                    else -> "Open Source Page"
                }
            }

            Button(
                onClick = {
                    uriHandler.openUri(creditUrl)
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Language,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = buttonText)
            }

            Spacer(modifier = Modifier.height(8.dp))
        }

        OutlinedButton(
            onClick = {
                val escapedName = URLEncoder.encode(credit.name, "UTF-8")
                uriHandler.openUri("https://www.google.com/search?q=$escapedName")
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Language,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "Search on Web (Google)")
        }
    }
}
