package eu.kanade.tachiyomi.ui.player.controls.components.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.player.components.PlayerSheet
import eu.kanade.tachiyomi.ui.player.PlayerViewModel
import eu.kanade.tachiyomi.ui.player.settings.GesturePreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState as collectAsStatePref
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
fun VideoZoomSheet(
    viewModel: PlayerViewModel,
    onDismissRequest: () -> Unit,
) {
    val gesturePreferences = remember { Injekt.get<GesturePreferences>() }
    val videoZoomGesture by gesturePreferences.gestureVideoZoom().collectAsStatePref()
    val zoom by viewModel.videoZoom.collectAsState()

    PlayerSheet(
        onDismissRequest = onDismissRequest,
    ) {
        Column(modifier = Modifier.padding(MaterialTheme.padding.medium)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Video Zoom",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )

                IconButton(onClick = { viewModel.resetVideoZoomAndPan() }) {
                    Icon(Icons.Default.SettingsBackupRestore, "Reset Zoom")
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = MaterialTheme.padding.small),
            ) {
                IconButton(onClick = { viewModel.setVideoZoom((zoom - 0.1f).coerceAtLeast(-1f)) }) {
                    Icon(Icons.Default.Remove, "Zoom Out")
                }

                Slider(
                    value = zoom,
                    onValueChange = { viewModel.setVideoZoom(it) },
                    valueRange = -1f..3f,
                    modifier = Modifier.weight(1f),
                )

                IconButton(onClick = { viewModel.setVideoZoom((zoom + 0.1f).coerceAtMost(3f)) }) {
                    Icon(Icons.Default.Add, "Zoom In")
                }

                Text("${(zoom * 100).toInt()}%", modifier = Modifier.width(48.dp))
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = MaterialTheme.padding.small),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = MaterialTheme.padding.small),
                ) {
                    Text(
                        text = stringResource(MR.strings.pref_video_zoom_gesture),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(MR.strings.pref_video_zoom_gesture_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Switch(
                    checked = videoZoomGesture,
                    onCheckedChange = { gesturePreferences.gestureVideoZoom().set(it) },
                )
            }
        }
    }
}