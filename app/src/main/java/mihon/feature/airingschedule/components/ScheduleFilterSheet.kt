package mihon.feature.airingschedule.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

private val ALL_FORMATS = listOf("TV", "TV_SHORT", "MOVIE", "SPECIAL", "OVA", "ONA")

private fun formatLabel(raw: String): String = when (raw) {
    "TV" -> "TV"
    "TV_SHORT" -> "TV Short"
    "MOVIE" -> "Movie"
    "SPECIAL" -> "Special"
    "OVA" -> "OVA"
    "ONA" -> "ONA"
    else -> raw
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScheduleFilterSheet(
    onDismissRequest: () -> Unit,
    onlyLibrary: Boolean,
    onToggleOnlyLibrary: (Boolean) -> Unit,
    onlyFavorites: Boolean,
    onToggleOnlyFavorites: (Boolean) -> Unit,
    hideAired: Boolean,
    onToggleHideAired: (Boolean) -> Unit,
    showAdult: Boolean,
    onToggleShowAdult: (Boolean) -> Unit,
    selectedFormats: Set<String>,
    onToggleFormat: (String) -> Unit,
    onResetFilters: () -> Unit,
) {
    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .verticalScroll(scrollState),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(MR.strings.action_filter_schedule),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                TextButton(onClick = onResetFilters) {
                    Text(text = stringResource(MR.strings.action_reset))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Library filter
            FilterSwitchRow(
                title = stringResource(MR.strings.cd_schedule_filter_library),
                subtitle = "Show only anime in your personal library",
                checked = onlyLibrary,
                onCheckedChange = onToggleOnlyLibrary,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // Favorite sources filter
            FilterSwitchRow(
                title = stringResource(MR.strings.pref_schedule_show_only_favorites),
                subtitle = stringResource(MR.strings.pref_schedule_show_only_favorites_summary),
                checked = onlyFavorites,
                onCheckedChange = onToggleOnlyFavorites,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // Hide already aired
            FilterSwitchRow(
                title = stringResource(MR.strings.pref_schedule_filter_hide_aired),
                subtitle = "Only display upcoming episodes that haven't aired yet",
                checked = hideAired,
                onCheckedChange = onToggleHideAired,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // 18+ Adult content
            FilterSwitchRow(
                title = stringResource(MR.strings.pref_schedule_display_group) + " 18+",
                subtitle = "Include adult and 18+ titles in the schedule",
                checked = showAdult,
                onCheckedChange = onToggleShowAdult,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // Formats
            Text(
                text = stringResource(MR.strings.pref_schedule_filter_formats),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                ALL_FORMATS.forEach { format ->
                    val isSelected = format in selectedFormats
                    FilterChip(
                        selected = isSelected,
                        onClick = { onToggleFormat(format) },
                        label = { Text(text = formatLabel(format)) },
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun FilterSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}
