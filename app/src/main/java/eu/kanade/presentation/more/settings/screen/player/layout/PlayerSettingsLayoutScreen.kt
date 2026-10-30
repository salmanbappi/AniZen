package eu.kanade.presentation.more.settings.screen.player.layout

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.player.LayoutRegion
import eu.kanade.tachiyomi.ui.player.PlayerButton
import eu.kanade.tachiyomi.ui.player.allPlayerButtons
import eu.kanade.tachiyomi.ui.player.getIcon
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

class PlayerSettingsLayoutScreen(val initialRegion: LayoutRegion) : Screen() {

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { PlayerSettingsLayoutScreenModel() }
        val state by screenModel.state.collectAsState()

        var showResetDialog by remember { mutableStateOf(false) }

        androidx.compose.runtime.LaunchedEffect(initialRegion) {
            screenModel.selectRegion(initialRegion)
        }

        val selectedButtons = state.buttons
        val disabledButtons = state.disabledButtons

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(state.selectedRegion.titleRes),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                    actions = {
                        IconButton(onClick = { showResetDialog = true }) {
                            Icon(
                                imageVector = Icons.Outlined.Restore,
                                contentDescription = "Reset to default",
                            )
                        }
                    },
                )
            },
        ) { paddingValues ->
            Column(modifier = Modifier.padding(paddingValues)) {
                val gridState = rememberLazyGridState()
                val reorderableState = rememberReorderableLazyGridState(gridState) { from, to ->
                    val fromKey = from.key as? PlayerButton
                    val toKey = to.key as? PlayerButton

                    val fromIndex = selectedButtons.indexOf(fromKey)
                    val toIndex = selectedButtons.indexOf(toKey)

                    if (fromIndex in selectedButtons.indices && toIndex in selectedButtons.indices && fromIndex != toIndex) {
                        val newList = selectedButtons.toMutableList().apply {
                            add(toIndex, removeAt(fromIndex))
                        }
                        screenModel.updateButtons(newList)
                    }
                }

                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(minSize = 72.dp),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            text = "Long press to reorder items. Tap '-' icon to remove.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 12.dp, start = 4.dp),
                        )
                    }

                    if (selectedButtons.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(120.dp),
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                                border = BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.outlineVariant,
                                ),
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.AddCircle,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(32.dp)
                                            .padding(bottom = 8.dp),
                                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                    )
                                    Text(
                                        text = "Drop zone is empty",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    } else {
                        items(
                            count = selectedButtons.size,
                            key = { selectedButtons[it] },
                            span = { index ->
                                val button = selectedButtons[index]
                                if (button == PlayerButton.CurrentChapter || button == PlayerButton.VideoTitle) {
                                    GridItemSpan(maxLineSpan)
                                } else {
                                    GridItemSpan(1)
                                }
                            },
                        ) { index ->
                            val button = selectedButtons[index]
                            ReorderableItem(reorderableState, key = button) { isDragging ->
                                val elevation by animateFloatAsState(
                                    targetValue = if (isDragging) 8f else 0f,
                                    label = "drag_elevation",
                                )

                                Surface(
                                    modifier = Modifier
                                        .draggableHandle()
                                        .then(
                                            if (button == PlayerButton.CurrentChapter || button == PlayerButton.VideoTitle) {
                                                Modifier.wrapContentWidth(Alignment.Start)
                                            } else {
                                                Modifier
                                            },
                                        ),
                                    shape = RoundedCornerShape(24.dp),
                                    shadowElevation = elevation.dp,
                                    color = Color.Transparent,
                                ) {
                                    PlayerButtonChip(
                                        button = button,
                                        enabled = true,
                                        onClick = {
                                            screenModel.updateButtons(selectedButtons - button)
                                        },
                                        badgeIcon = Icons.Default.RemoveCircle,
                                        badgeColor = Color(0xFFEF5350),
                                    )
                                }
                            }
                        }
                    }

                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Spacer(modifier = Modifier.height(40.dp))
                    }

                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            ),
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "Available Palette",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(bottom = 12.dp),
                                )
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    val isCastEnabled = screenModel.isCastEnabled()
                                    val availableButtons = allPlayerButtons.filter {
                                        it !in selectedButtons && (it != PlayerButton.Cast || isCastEnabled)
                                    }
                                    availableButtons.forEach { button ->
                                        val isEnabled = button !in disabledButtons
                                        PlayerButtonChip(
                                            button = button,
                                            enabled = isEnabled,
                                            onClick = {
                                                screenModel.updateButtons(selectedButtons + button)
                                            },
                                            badgeIcon = Icons.Default.AddCircle,
                                            badgeColor = if (isEnabled) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    item(span = { GridItemSpan(maxLineSpan) }) {
                        IconsLegend(isCastEnabled = screenModel.isCastEnabled())
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }

            if (showResetDialog) {
                AlertDialog(
                    onDismissRequest = { showResetDialog = false },
                    title = { Text(text = "Reset to default?") },
                    text = { Text(text = "This will reset the controls in this region to their default configuration.") },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                screenModel.resetToDefault()
                                showResetDialog = false
                            },
                        ) {
                            Text(stringResource(MR.strings.action_ok))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showResetDialog = false }) {
                            Text(stringResource(MR.strings.action_cancel))
                        }
                    },
                )
            }
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun IconsLegend(isCastEnabled: Boolean) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Icons Legend",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    allPlayerButtons.filter { it != PlayerButton.Cast || isCastEnabled }.forEach { button ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                imageVector = button.getIcon(),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = stringResource(button.titleRes),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
