package eu.kanade.tachiyomi.ui.anime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import eu.kanade.tachiyomi.data.track.local.LocalTracker
import kotlinx.collections.immutable.toImmutableList
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.WheelTextPicker
import tachiyomi.presentation.core.i18n.stringResource

// ANZ -->
@Composable
fun LocalScoreDialog( // ANZ
    score: Double,
    status: Long,
    onDismissRequest: () -> Unit,
    onConfirm: (Double, Long) -> Unit,
) {
    val scores = List(11) { it.toString() }.toImmutableList()
    val statuses = listOf(
        stringResource(MR.strings.watching),
        stringResource(MR.strings.completed),
        stringResource(MR.strings.on_hold),
        stringResource(MR.strings.dropped),
        stringResource(MR.strings.plan_to_watch),
    ).toImmutableList()

    val statusValues = listOf(
        LocalTracker.WATCHING,
        LocalTracker.COMPLETED,
        LocalTracker.ON_HOLD,
        LocalTracker.DROPPED,
        LocalTracker.PLAN_TO_WATCH,
    )

    var newScore by remember { mutableStateOf(score.toInt().toString()) }
    var newStatusIndex by remember { mutableStateOf(statusValues.indexOf(status).coerceAtLeast(0)) }

    var isInitialScoreComposition by remember { mutableStateOf(true) }
    var isInitialStatusComposition by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = {
                onConfirm(newScore.toDouble(), statusValues[newStatusIndex])
                onDismissRequest()
            }) {
                Text(stringResource(MR.strings.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
        title = {
            Text(text = "Status & Score Tracking")
        },
        text = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(MR.strings.score), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    WheelTextPicker(
                        items = scores,
                        startIndex = scores.indexOf(newScore).coerceAtLeast(0),
                        onSelectionChanged = {
                            if (!isInitialScoreComposition) {
                                newScore = scores[it]
                            }
                            isInitialScoreComposition = false
                        },
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(MR.strings.status), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    WheelTextPicker(
                        items = statuses,
                        startIndex = newStatusIndex,
                        onSelectionChanged = {
                            if (!isInitialStatusComposition) {
                                newStatusIndex = it
                            }
                            isInitialStatusComposition = false
                        },
                    )
                }
            }
        },
    )
}
// ANZ <--
