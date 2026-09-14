package eu.kanade.presentation.more.settings.screen.player.layout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.player.LayoutRegion
import eu.kanade.tachiyomi.ui.player.PlayerButton
import eu.kanade.tachiyomi.ui.player.parseButtons
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object PlayerSettingsLayoutMainScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val playerPreferences = remember { Injekt.get<PlayerPreferences>() }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.pref_player_layout),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { paddingValues ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            ) {
                item {
                    PreferenceSectionHeader(title = "Landscape Controls")
                }

                item {
                    androidx.compose.material3.Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        colors = androidx.compose.material3.CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                    ) {
                        RegionSummaryItem(
                            region = LayoutRegion.TopRight,
                            playerPreferences = playerPreferences,
                            onClick = { navigator.push(PlayerSettingsLayoutScreen(LayoutRegion.TopRight)) },
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        RegionSummaryItem(
                            region = LayoutRegion.BottomLeft,
                            playerPreferences = playerPreferences,
                            onClick = { navigator.push(PlayerSettingsLayoutScreen(LayoutRegion.BottomLeft)) },
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        RegionSummaryItem(
                            region = LayoutRegion.BottomRight,
                            playerPreferences = playerPreferences,
                            onClick = { navigator.push(PlayerSettingsLayoutScreen(LayoutRegion.BottomRight)) },
                        )
                    }
                }

                item {
                    PreferenceSectionHeader(title = "Portrait Controls")
                }

                item {
                    androidx.compose.material3.Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        colors = androidx.compose.material3.CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                    ) {
                        RegionSummaryItem(
                            region = LayoutRegion.Portrait,
                            playerPreferences = playerPreferences,
                            onClick = { navigator.push(PlayerSettingsLayoutScreen(LayoutRegion.Portrait)) },
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun PreferenceSectionHeader(title: String) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        )
    }

    @Composable
    private fun RegionSummaryItem(
        region: LayoutRegion,
        playerPreferences: PlayerPreferences,
        onClick: () -> Unit,
    ) {
        val buttonsPref = remember(region) {
            when (region) {
                LayoutRegion.TopRight -> playerPreferences.topRightControls()
                LayoutRegion.BottomLeft -> playerPreferences.bottomLeftControls()
                LayoutRegion.BottomRight -> playerPreferences.bottomRightControls()
                LayoutRegion.Portrait -> playerPreferences.portraitBottomControls()
            }
        }
        val buttonsCsv by buttonsPref.collectAsState()
        val buttons = remember(buttonsCsv) { parseButtons(buttonsCsv) }
        val isCastEnabled by playerPreferences.enableCast().collectAsState()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(region.titleRes),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClick) {
                    Icon(
                        imageVector = Icons.Outlined.Edit,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            ) {
                if (buttons.isEmpty()) {
                    Text(
                        text = "None",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                } else {
                    buttons.filter { it != PlayerButton.Cast || isCastEnabled }.forEach { button ->
                        PlayerButtonChip(
                            button = button,
                            enabled = true,
                            onClick = null,
                            badgeIcon = null,
                            badgeColor = null,
                        )
                    }
                }
            }
        }
    }
}
