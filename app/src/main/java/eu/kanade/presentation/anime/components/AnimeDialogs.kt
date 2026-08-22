package eu.kanade.presentation.anime.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.StringResource
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.domain.anime.interactor.FetchInterval
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.LabeledCheckbox
import tachiyomi.presentation.core.components.WheelTextPicker
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.absoluteValue

@Composable
fun DeleteEpisodesDialog(
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismissRequest()
                    onConfirm()
                },
            ) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
        title = {
            Text(text = stringResource(MR.strings.are_you_sure))
        },
        text = {
            Text(text = stringResource(MR.strings.confirm_delete_episodes))
        },
    )
}

@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun SetIntervalDialog(
    interval: Int,
    nextUpdate: Instant?,
    onDismissRequest: () -> Unit,
    onValueChanged: ((Int) -> Unit)? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var isScheduledMode by rememberSaveable { mutableStateOf(interval < -100) }

    // Standard Interval State
    // Index mapping: 0 -> Disabled (-1), 1 -> Default (0), 2+ -> Days (1+)
    var selectedIntervalIndex by rememberSaveable {
        mutableIntStateOf(
            when {
                interval == FetchInterval.MANUAL_DISABLE -> 0
                interval == 0 -> 1
                interval < 0 -> interval.absoluteValue + 1
                else -> 1
            },
        )
    }

    // Scheduled State
    // fetchInterval = -(10000 + D*2000 + H*60 + M)
    val initialEncoded = if (interval < -100) -interval - 10000 else 0
    var selectedDayIndex by rememberSaveable {
        mutableIntStateOf(
            if (initialEncoded > 0) {
                val d = initialEncoded / 2000 // 1-7
                // Map 1-7 (Mon-Sun) to 0-6 (Sat-Fri)
                // Sat=6, Sun=7, Mon=1, Tue=2, Wed=3, Thu=4, Fri=5
                when (d) {
                    6 -> 0 // Sat
                    7 -> 1 // Sun
                    1 -> 2 // Mon
                    2 -> 3 // Tue
                    3 -> 4 // Wed
                    4 -> 5 // Thu
                    5 -> 6 // Fri
                    else -> 0
                }
            } else {
                0
            },
        )
    }

    val initialHour24 = if (initialEncoded > 0) (initialEncoded % 2000) / 60 else 0
    val initialMinute = if (initialEncoded > 0) (initialEncoded % 2000) % 60 else 0

    var selectedHour12 by rememberSaveable { mutableIntStateOf(if (initialHour24 % 12 == 0) 12 else initialHour24 % 12) }
    var selectedMinute by rememberSaveable { mutableIntStateOf(initialMinute) }
    var selectedAmPm by rememberSaveable { mutableIntStateOf(if (initialHour24 < 12) 0 else 1) }

    val nextUpdateDays = remember(nextUpdate) {
        return@remember if (nextUpdate != null) {
            val now = Instant.now()
            now.until(nextUpdate, ChronoUnit.DAYS).toInt().coerceAtLeast(0)
        } else {
            null
        }
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(MR.strings.pref_library_update_smart_update)) },
        text = {
            Column {
                if (interval == FetchInterval.MANUAL_DISABLE) {
                    Text(stringResource(MR.strings.disabled))
                } else if (nextUpdateDays != null && nextUpdateDays >= 0) {
                    Text(
                        stringResource(
                            MR.strings.anime_interval_expected_update,
                            pluralStringResource(
                                MR.plurals.day,
                                count = nextUpdateDays,
                                nextUpdateDays,
                            ),
                            if (isScheduledMode) {
                                "weekly"
                            } else {
                                val days = if (selectedIntervalIndex > 1) selectedIntervalIndex - 1 else 0
                                if (days == 0) {
                                    stringResource(MR.strings.label_default)
                                } else {
                                    pluralStringResource(
                                        MR.plurals.day,
                                        count = days,
                                        days,
                                    )
                                }
                            },
                        ),
                    )
                } else {
                    Text(
                        stringResource(MR.strings.anime_interval_expected_update_null),
                    )
                }
                Spacer(Modifier.height(MaterialTheme.padding.medium))

                MultiChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        checked = !isScheduledMode,
                        onCheckedChange = { isScheduledMode = false },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) {
                        Text("Interval")
                    }
                    SegmentedButton(
                        checked = isScheduledMode,
                        onCheckedChange = { isScheduledMode = true },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) {
                        Text("Scheduled")
                    }
                }

                Spacer(Modifier.height(MaterialTheme.padding.medium))

                if (onValueChanged != null) {
                    if (!isScheduledMode) {
                        Text(stringResource(MR.strings.manga_interval_custom_amount), style = MaterialTheme.typography.labelMedium)
                        BoxWithConstraints(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            val size = DpSize(width = maxWidth / 2, height = 128.dp)
                            val items = remember {
                                buildList {
                                    add("Disabled")
                                    add(context.stringResource(MR.strings.label_default))
                                    addAll((1..FetchInterval.MAX_INTERVAL).map { it.toString() })
                                }.toImmutableList()
                            }
                            WheelTextPicker(
                                items = items,
                                size = size,
                                startIndex = selectedIntervalIndex,
                                onSelectionChanged = { selectedIntervalIndex = it },
                            )
                        }
                    } else {
                        val dayOptions = persistentListOf("Saturday", "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday")
                        val hourOptions = (1..12).map { it.toString() }.toImmutableList()
                        val minuteOptions = (0..59).map { it.toString().padStart(2, '0') }.toImmutableList()
                        val amPmOptions = persistentListOf("AM", "PM")

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Select Day and Time (12h)", style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.height(8.dp))

                            WheelTextPicker(
                                items = dayOptions,
                                size = DpSize(width = 150.dp, height = 90.dp),
                                startIndex = selectedDayIndex,
                                onSelectionChanged = { selectedDayIndex = it },
                            )

                            Spacer(Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                WheelTextPicker(
                                    items = hourOptions,
                                    size = DpSize(width = 60.dp, height = 90.dp),
                                    startIndex = selectedHour12 - 1,
                                    onSelectionChanged = { selectedHour12 = it + 1 },
                                )
                                Text(":", style = MaterialTheme.typography.titleLarge)
                                WheelTextPicker(
                                    items = minuteOptions,
                                    size = DpSize(width = 60.dp, height = 90.dp),
                                    startIndex = selectedMinute,
                                    onSelectionChanged = { selectedMinute = it },
                                )
                                Spacer(Modifier.width(8.dp))
                                WheelTextPicker(
                                    items = amPmOptions,
                                    size = DpSize(width = 60.dp, height = 90.dp),
                                    startIndex = selectedAmPm,
                                    onSelectionChanged = { selectedAmPm = it },
                                )
                            }
                        }
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val newValue = if (!isScheduledMode) {
                        when (selectedIntervalIndex) {
                            0 -> FetchInterval.MANUAL_DISABLE
                            1 -> 0
                            else -> -(selectedIntervalIndex - 1)
                        }
                    } else {
                        // Map 0-6 (Sat-Fri) back to 1-7 (Mon-Sun)
                        val d = when (selectedDayIndex) {
                            0 -> 6 // Sat
                            1 -> 7 // Sun
                            2 -> 1 // Mon
                            3 -> 2 // Tue
                            4 -> 3 // Wed
                            5 -> 4 // Thu
                            6 -> 5 // Fri
                            else -> 1
                        }
                        val h24 = when {
                            selectedAmPm == 0 && selectedHour12 == 12 -> 0
                            selectedAmPm == 0 -> selectedHour12
                            selectedAmPm == 1 && selectedHour12 == 12 -> 12
                            else -> selectedHour12 + 12
                        }
                        -(10000 + d * 2000 + h24 * 60 + selectedMinute)
                    }
                    onValueChanged?.invoke(newValue)
                    onDismissRequest()
                },
            ) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
    )
}

@Composable
fun ClearAnimeDialog(
    onDismissRequest: () -> Unit,
    onConfirm: (Boolean, Boolean) -> Unit,
) {
    var list by remember {
        mutableStateOf(
            buildList<CheckboxState.State<StringResource>> {
                add(CheckboxState.State.None(MR.strings.downloaded_data))
                add(CheckboxState.State.None(MR.strings.episodes_from_database))
            },
        )
    }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        confirmButton = {
            TextButton(
                enabled = list.any { it.isChecked },
                onClick = {
                    onDismissRequest()
                    onConfirm(
                        list[0].isChecked,
                        list[1].isChecked,
                    )
                },
            ) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
        title = {
            Text(text = stringResource(MR.strings.action_remove))
        },
        text = {
            Column {
                list.forEachIndexed { index, state ->
                    LabeledCheckbox(
                        label = stringResource(state.value),
                        checked = state.isChecked,
                        onCheckedChange = {
                            val mutableList = list.toMutableList()
                            mutableList[index] = state.next() as CheckboxState.State<StringResource>
                            list = mutableList.toList()
                        },
                    )
                }
            }
        },
    )
}
