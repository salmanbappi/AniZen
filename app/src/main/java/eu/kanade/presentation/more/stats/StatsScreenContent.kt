package eu.kanade.presentation.more.stats

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import eu.kanade.domain.ai.AiPreferences
import eu.kanade.presentation.anime.components.MarkdownRender
import eu.kanade.presentation.more.stats.data.StatsData
import eu.kanade.presentation.util.toDurationString
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.util.system.copyToClipboard
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.util.collectAsState
import tachiyomi.presentation.core.util.secondaryItemAlpha
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.cos
import kotlin.math.sin
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@Composable
fun StatsScreenContent(
    state: StatsScreenState.SuccessAnime,
    paddingValues: PaddingValues,
    onGenerateAiAnalysis: () -> Unit,
    onRegenerateAiAnalysis: () -> Unit,
    onClickExtensionReport: () -> Unit,
) {
    val statListState = rememberLazyListState()
    val aiPreferences = remember { Injekt.get<AiPreferences>() }
    val enableAi by aiPreferences.enableAi().collectAsState()
    val enableAiStatistics by aiPreferences.enableAiStatistics().collectAsState()

    LazyColumn(
        state = statListState,
        contentPadding = paddingValues,
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium),
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background) // 60% Dominant
            .padding(horizontal = MaterialTheme.padding.medium),
    ) {
        item {
            ProfileHeaderSection(state)
        }

        if (enableAi && enableAiStatistics) {
            item {
                AiIntelligenceSection(
                    analysis = state.aiAnalysis ?: state.streamingAnalysis,
                    isLoading = state.isAiLoading,
                    onGenerate = onGenerateAiAnalysis,
                    onRegenerate = onRegenerateAiAnalysis,
                )
            }
        }

        item {
            GenreAffinitySection(state.genreAffinity)
        }

        item {
            OverviewGridSection(state)
        }

        item {
            StatusBreakdownSection(state.statuses)
        }

        item {
            ScoreDistributionSection(state.scores)
        }

        item {
            ExtensionUsageSection(state.extensions)
        }

        if (state.feedActivity != null) {
            item {
                FeedActivitySection(state.feedActivity)
            }
        }

        if (state.infrastructure != null) {
            item {
                InfrastructureSection(state.infrastructure, onClickExtensionReport)
            }
        }

        item {
            WatchHabitsSection(state.watchHabits)
        }
    }
}

@Composable
private fun ProfileHeaderSection(state: StatsScreenState.SuccessAnime) {
    val aiPreferences = remember { Injekt.get<AiPreferences>() }
    val displayName by aiPreferences.displayName().collectAsState()
    val profilePhotoUri by aiPreferences.profilePhotoUri().collectAsState()
    val context = LocalContext.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

    val pickImage = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (e: Exception) {
                // Ignore if not supported
            }
            aiPreferences.profilePhotoUri().set(uri.toString())
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow, // 30% Secondary
        tonalElevation = 2.dp,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer) // Accent highlight
                    .clickable { pickImage.launch("image/*") },
                contentAlignment = Alignment.Center,
            ) {
                if (profilePhotoUri.isNotEmpty()) {
                    AsyncImage(
                        model = profilePhotoUri,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                } else {
                    AsyncImage(
                        model = R.drawable.ic_splash_logo,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            var editText by remember(displayName) { mutableStateOf(displayName) }

            BasicTextField(
                value = editText,
                onValueChange = {
                    editText = it
                    aiPreferences.displayName().set(it)
                },
                textStyle = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                ),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = { focusManager.clearFocus() },
                ),
                decorationBox = { innerTextField ->
                    val lineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .padding(bottom = 4.dp)
                            .drawBehind {
                                val strokeWidth = 1.dp.toPx()
                                val y = size.height - strokeWidth / 2
                                drawLine(
                                    color = lineColor,
                                    start = Offset(0f, y),
                                    end = Offset(size.width, y),
                                    strokeWidth = strokeWidth,
                                )
                            },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (editText.isEmpty()) {
                                Text(
                                    text = "Your Name",
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                )
                            }
                            innerTextField()
                        }
                    }
                },
            )

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "${state.overview.libraryAnimeCount} Titles in Collection",
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                ),
                modifier = Modifier.secondaryItemAlpha(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AiIntelligenceSection(
    analysis: String?,
    isLoading: Boolean,
    onGenerate: () -> Unit,
    onRegenerate: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current

    StatsSectionCard(
        title = "Behavioral Analytics",
        modifier = Modifier.clickable(enabled = analysis != null) { expanded = !expanded },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(MaterialTheme.padding.medium),
            ) {
                Icon(
                    imageVector = Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when {
                            isLoading && analysis.isNullOrBlank() -> "Processing system data..."
                            analysis != null -> "Analytical Summary"
                            else -> "Generate behavioral insight"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = if (analysis != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                    if (analysis == null && !isLoading) {
                        Text(
                            text = "AI will analyze your watch patterns",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.secondaryItemAlpha(),
                        )
                    }
                }

                if (analysis == null && isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else if (analysis == null) {
                    TextButton(
                        onClick = onGenerate,
                        modifier = Modifier.padding(start = 8.dp),
                    ) {
                        Text("Generate")
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(
                            onClick = {
                                context.copyToClipboard("AniZen AI Analysis", analysis)
                            },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ContentCopy,
                                contentDescription = "Copy",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        IconButton(
                            onClick = onRegenerate,
                            enabled = !isLoading,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = "Regenerate",
                                modifier = Modifier.size(18.dp),
                                tint = if (isLoading) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f) else MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
            if (expanded && analysis != null) {
                HorizontalDivider(modifier = Modifier.alpha(0.2f))
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    SelectionContainer {
                        MarkdownRender(
                            content = analysis,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OverviewGridSection(state: StatsScreenState.SuccessAnime) {
    val context = LocalContext.current
    val watchTime = state.overview.totalSeenDuration
        .toDuration(DurationUnit.MILLISECONDS)
        .toDurationString(context, fallback = "0m")

    StatsSectionCard(title = "Core Metrics") {
        Column(modifier = Modifier.padding(MaterialTheme.padding.medium)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MetricItem(Icons.Outlined.Schedule, "Watch Time", watchTime)
                MetricItem(Icons.Outlined.Star, "Mean Score", "%.2f".format(state.trackers.meanScore))
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp).alpha(0.5f))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MetricItem(Icons.Outlined.History, "Episodes", state.episodes.readEpisodeCount.toString())
                MetricItem(Icons.Outlined.Extension, "Sources", state.trackers.sourceCount.toString())
            }
        }
    }
}

@Composable
private fun MetricItem(icon: ImageVector, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary, // 10% Accent
            )
            Text(text = label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.secondaryItemAlpha())
        }
    }
}

@Composable
private fun GenreAffinitySection(genreAffinity: StatsData.GenreAffinity) {
    StatsSectionCard(title = "Genre Distribution") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MaterialTheme.padding.medium),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val genres = genreAffinity.genreScores.take(6)
            if (genres.size >= 3) {
                RadarChart(
                    data = genres.map { it.second.toFloat() },
                    labels = genres.map { it.first },
                    modifier = Modifier
                        .size(240.dp)
                        .padding(MaterialTheme.padding.large),
                )
            } else {
                genres.forEach { pair ->
                    GenreBar(pair.first, pair.second, genres.firstOrNull()?.second ?: 1)
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun RadarChart(
    data: List<Float>,
    labels: List<String>,
    modifier: Modifier = Modifier,
) {
    val maxValue = data.maxOrNull() ?: 1f
    val color = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val density = LocalDensity.current

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = size.minDimension / 2
        val angleStep = (2 * Math.PI / data.size).toFloat()

        // Draw grid
        for (i in 1..4) {
            val gridRadius = radius * (i / 4f)
            val path = Path()
            for (j in data.indices) {
                val angle = j * angleStep - Math.PI.toFloat() / 2
                val x = center.x + gridRadius * cos(angle)
                val y = center.y + gridRadius * sin(angle)
                if (j == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            drawPath(path, gridColor, style = Stroke(width = 1.dp.toPx()))
        }

        // Draw data path
        val dataPath = Path()
        for (i in data.indices) {
            val angle = i * angleStep - Math.PI.toFloat() / 2
            val dataRadius = radius * (data[i] / maxValue)
            val x = center.x + dataRadius * cos(angle)
            val y = center.y + dataRadius * sin(angle)
            if (i == 0) dataPath.moveTo(x, y) else dataPath.lineTo(x, y)
        }
        dataPath.close()
        drawPath(dataPath, color.copy(alpha = 0.3f))
        drawPath(dataPath, color, style = Stroke(width = 2.dp.toPx()))

        // Draw labels
        for (i in labels.indices) {
            val angle = i * angleStep - Math.PI.toFloat() / 2
            val labelRadius = radius + 20.dp.toPx()
            val x = center.x + labelRadius * cos(angle)
            val y = center.y + labelRadius * sin(angle)

            drawContext.canvas.nativeCanvas.drawText(
                labels[i].take(8),
                x,
                y,
                android.graphics.Paint().apply {
                    this.color = textColor
                    this.textSize = with(density) { 10.sp.toPx() }
                    this.textAlign = android.graphics.Paint.Align.CENTER
                },
            )
        }
    }
}

@Composable
private fun GenreBar(genre: String, count: Int, maxCount: Int) {
    val progress = count.toFloat() / maxCount
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = genre, style = MaterialTheme.typography.bodySmall)
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.secondaryItemAlpha(),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh), // 30% Structural
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary), // 10% Accent
            )
        }
    }
}

@Composable
private fun FeedActivitySection(feedActivity: StatsData.FeedActivity) {
    StatsSectionCard(title = "Feed Statistics") {
        Column(modifier = Modifier.padding(MaterialTheme.padding.medium)) {
            if (feedActivity.activity.isEmpty()) {
                Text(
                    text = "No updates recorded yet.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.secondaryItemAlpha(),
                )
            } else {
                val maxActivity = feedActivity.activity.maxOf { it.fetchCount }.coerceAtLeast(1)

                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    feedActivity.activity.take(10).forEach { activity ->
                        SourceActivityBar(activity, maxActivity)
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceActivityBar(activity: eu.kanade.presentation.more.stats.data.SourceActivity, maxActivity: Int) {
    val totalEngagement = activity.openCount + activity.playCount + activity.completeCount
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(text = activity.sourceName, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
            Text(
                text = "$totalEngagement Actions",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.secondaryItemAlpha(),
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            val totalF = totalEngagement.toFloat().coerceAtLeast(1f)
            val openWeight = activity.openCount.toFloat() / totalF
            val playWeight = activity.playCount.toFloat() / totalF
            val completeWeight = activity.completeCount.toFloat() / totalF

            if (openWeight > 0) Box(modifier = Modifier.weight(openWeight).fillMaxHeight().background(MaterialTheme.colorScheme.secondary))
            if (playWeight > 0) Box(modifier = Modifier.weight(playWeight).fillMaxHeight().background(MaterialTheme.colorScheme.tertiary))
            if (completeWeight > 0) Box(modifier = Modifier.weight(completeWeight).fillMaxHeight().background(Color(0xFF4CAF50)))
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ActivityLegendItem(MaterialTheme.colorScheme.secondary, "Opened", activity.openCount)
            ActivityLegendItem(MaterialTheme.colorScheme.tertiary, "Started", activity.playCount)
            ActivityLegendItem(Color(0xFF4CAF50), "Finished", activity.completeCount)
        }
    }
}

@Composable
private fun ActivityLegendItem(color: Color, label: String, count: Int, forceShow: Boolean = false) {
    if (count > 0 || forceShow) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(color))
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "$label: $count",
                style = MaterialTheme.typography.labelSmall,
                fontSize = 8.sp,
                modifier = Modifier.secondaryItemAlpha(),
            )
        }
    }
}

@Composable
private fun ExtensionUsageSection(extensions: StatsData.ExtensionUsage) {
    StatsSectionCard(title = "Source Distribution") {
        Column(modifier = Modifier.padding(MaterialTheme.padding.medium)) {
            extensions.topExtensions.forEachIndexed { index, info ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    Text(
                        text = "${index + 1}.",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(24.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = info.name, style = MaterialTheme.typography.bodyMedium)
                        if (info.repo != null) {
                            Text(
                                text = info.repo,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.alpha(0.7f),
                            )
                        }
                    }
                    Text(
                        text = "${info.count} titles",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.secondaryItemAlpha(),
                    )
                }
            }
        }
    }
}

@Composable
private fun InfrastructureSection(infra: StatsData.InfrastructureAnalytics, onClickReport: () -> Unit) {
    StatsSectionCard(
        title = "Extension Infrastructure Analytics",
        modifier = Modifier.clickable { onClickReport() },
    ) {
        Column(modifier = Modifier.padding(MaterialTheme.padding.medium), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // Topology
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                infra.topologyBreakdown.forEach { (type, count) ->
                    if (count > 0) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.weight(1f),
                        ) {
                            Column(
                                modifier = Modifier.padding(8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(text = type, style = MaterialTheme.typography.labelSmall)
                                Text(
                                    text = count.toString(),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }
                }
            }

            // Latency Matrix
            Column {
                Text(
                    text = "Latency Matrix (ms)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(8.dp))
                infra.latencyMatrix.forEach { (name, ms) ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                        Text(text = name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        Text(
                            text = "${ms}ms",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = if (ms < 100) {
                                Color(0xFF4CAF50)
                            } else if (ms < 300) {
                                Color(0xFFFFC107)
                            } else {
                                Color(0xFFF44336)
                            },
                        )
                    }
                }
            }

            // Reliability Index
            Column {
                Text(
                    text = "Endpoint Reliability Index",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(8.dp))
                infra.reliabilityIndex.forEach { (name, rate) ->
                    val percentage = (rate * 100).toInt()
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                        Text(text = name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        Text(
                            text = "$percentage% SR",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WatchHabitsSection(habits: StatsData.WatchHabits) {
    StatsSectionCard(title = "Temporal Patterns") {
        Column(modifier = Modifier.padding(MaterialTheme.padding.medium), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                HabitItem("Preferred Cycle", habits.preferredWatchTime)
                HabitItem("Intensity", "%.1f sessions/week".format(habits.avgSessionsPerWeek))
            }
            HorizontalDivider(modifier = Modifier.alpha(0.3f))
            if (habits.topDayAnime != null) {
                HabitItem("Peak Focus (24h)", habits.topDayAnime)
            }
            if (habits.topMonthAnime != null) {
                HabitItem("Dominant Series (30d)", habits.topMonthAnime)
            }
        }
    }
}

@Composable
private fun HabitItem(label: String, value: String) {
    Column {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary, // 10% Accent
        )
    }
}

@Composable
private fun StatusBreakdownSection(statuses: StatsData.StatusBreakdown) {
    StatsSectionCard(title = "Collection Status") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MaterialTheme.padding.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val data = listOf(
                statuses.completedCount.toFloat(),
                statuses.ongoingCount.toFloat(),
                statuses.droppedCount.toFloat(),
                statuses.onHoldCount.toFloat(),
                statuses.planToWatchCount.toFloat(),
            )
            val colors = listOf(
                MaterialTheme.colorScheme.primary,
                MaterialTheme.colorScheme.tertiary,
                MaterialTheme.colorScheme.error,
                MaterialTheme.colorScheme.secondary,
                MaterialTheme.colorScheme.outline,
            )

            PieChart(
                data = data,
                colors = colors,
                modifier = Modifier.size(120.dp),
            )

            Spacer(modifier = Modifier.width(24.dp))

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StatusLegendItem(MaterialTheme.colorScheme.primary, "Completed", statuses.completedCount)
                StatusLegendItem(MaterialTheme.colorScheme.tertiary, "Ongoing", statuses.ongoingCount)
                StatusLegendItem(MaterialTheme.colorScheme.secondary, "On Hold", statuses.onHoldCount)
                StatusLegendItem(MaterialTheme.colorScheme.error, "Dropped", statuses.droppedCount)
                StatusLegendItem(MaterialTheme.colorScheme.outline, "Planned", statuses.planToWatchCount)
            }
        }
    }
}

@Composable
private fun StatusLegendItem(color: Color, label: String, count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun ScoreDistributionSection(scores: StatsData.ScoreDistribution) {
    StatsSectionCard(title = "Score Distribution") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MaterialTheme.padding.medium),
        ) {
            val maxCount = scores.distribution.values.maxOrNull() ?: 1
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                for (i in 1..10) {
                    val count = scores.distribution[i] ?: 0
                    val weight = count.toFloat() / maxCount
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (count > 0) {
                            Text(
                                text = count.toString(),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                ),
                                fontSize = 9.sp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(weight.coerceAtLeast(0.02f))
                                .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            MaterialTheme.colorScheme.primary,
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                                        ),
                                    ),
                                ),
                        )
                        HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        Text(
                            text = i.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (count > 0) FontWeight.ExtraBold else FontWeight.Normal,
                            color = if (count > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Based on ${scores.scoredAnimeCount} rated titles",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.secondaryItemAlpha().align(Alignment.CenterHorizontally),
            )
        }
    }
}

@Composable
private fun PieChart(
    data: List<Float>,
    colors: List<Color>,
    modifier: Modifier = Modifier,
) {
    val total = data.sum()
    Canvas(modifier = modifier) {
        var startAngle = -90f
        data.forEachIndexed { index, value ->
            val sweepAngle = if (total > 0) (value / total) * 360f else 0f
            drawArc(
                color = colors[index % colors.size],
                startAngle = startAngle,
                sweepAngle = sweepAngle,
                useCenter = true,
            )
            startAngle += sweepAngle
        }
    }
}

@Composable
private fun StatsSectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
        )
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = androidx.compose.material3.CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow, // 30% Secondary
            ),
            elevation = androidx.compose.material3.CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
        ) {
            content()
        }
    }
}
