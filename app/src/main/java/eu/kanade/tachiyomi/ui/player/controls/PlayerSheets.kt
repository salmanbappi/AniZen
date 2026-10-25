/*
 * Copyright 2024 Abdallah Mehiz
 * https://github.com/abdallahmehiz/mpvKt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.kanade.tachiyomi.ui.player.controls

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.vivvvek.seeker.Segment
import eu.kanade.tachiyomi.ui.player.Decoder
import eu.kanade.tachiyomi.ui.player.Panels
import eu.kanade.tachiyomi.ui.player.PlayerViewModel
import eu.kanade.tachiyomi.ui.player.Sheets
import eu.kanade.tachiyomi.ui.player.VideoTrack
import eu.kanade.tachiyomi.ui.player.controls.components.sheets.AspectRatioItem
import eu.kanade.tachiyomi.ui.player.controls.components.sheets.AspectRatioSheet
import eu.kanade.tachiyomi.ui.player.controls.components.sheets.AudioTracksSheet
import eu.kanade.tachiyomi.ui.player.controls.components.sheets.ChaptersSheet
import eu.kanade.tachiyomi.ui.player.controls.components.sheets.HosterState
import eu.kanade.tachiyomi.ui.player.controls.components.sheets.MoreSheet
import eu.kanade.tachiyomi.ui.player.controls.components.sheets.PlaybackSpeedSheet
import eu.kanade.tachiyomi.ui.player.controls.components.sheets.QualitySheet
import eu.kanade.tachiyomi.ui.player.controls.components.sheets.ScreenshotSheet
import eu.kanade.tachiyomi.ui.player.controls.components.sheets.SubtitlesSheet
import eu.kanade.tachiyomi.ui.player.controls.components.sheets.VideoZoomSheet
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import tachiyomi.domain.custombuttons.model.CustomButton
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.InputStream
import androidx.compose.runtime.collectAsState as composeCollectAsState

@Composable
fun PlayerSheets(
    sheetShown: Sheets,
    viewModel: PlayerViewModel,

    // subtitles sheet
    subtitles: List<VideoTrack>,
    selectedSubtitles: List<Int>,
    onAddSubtitle: (Uri) -> Unit,
    onSelectSubtitle: (VideoTrack) -> Unit,

    // audio sheet
    audioTracks: List<VideoTrack>,
    selectedAudio: Int,
    onAddAudio: (Uri) -> Unit,
    onSelectAudio: (VideoTrack) -> Unit,

    // video sheet
    isLoadingHosters: Boolean,
    hosterState: List<HosterState>,
    expandedState: List<Boolean>,
    selectedVideoIndex: Pair<Int, Int>,
    onClickHoster: (Int) -> Unit,
    onClickVideo: (Int, Int) -> Unit,
    defaultStreamSelector: String,
    highlightDefaultStream: Boolean = true,
    autoScrollToDefault: Boolean,
    displayHosters: Pair<Boolean, Boolean>,

    // chapters sheet
    chapter: Segment?,
    chapters: ImmutableList<Segment>,
    onSeekToChapter: (Int) -> Unit,

    // Decoders sheet
    decoder: Decoder,
    onUpdateDecoder: (Decoder) -> Unit,

    // Speed sheet
    // ANZ -->
    pitchCorrection: Boolean,
    onPitchCorrectionChange: (Boolean) -> Unit,
    speed: Float,
    speedPresets: ImmutableList<Float>,
    onSpeedChange: (Float) -> Unit,
    onAddSpeedPreset: (Float) -> Unit,
    onRemoveSpeedPreset: (Float) -> Unit,
    onResetSpeedPresets: () -> Unit,
    longPressSpeed: Float,
    longPressSpeedPresets: ImmutableList<Float>,
    onLongPressSpeedChange: (Float) -> Unit,
    onAddLongPressSpeedPreset: (Float) -> Unit,
    onRemoveLongPressSpeedPreset: (Float) -> Unit,
    onResetLongPressSpeedPresets: () -> Unit,
    onMakeDefaultSpeed: (Float) -> Unit,
    onResetDefaultSpeed: () -> Unit,
    // ANZ <--

    // More sheet
    sleepTimerTimeRemaining: Int,
    onStartSleepTimer: (Int) -> Unit,
    buttons: List<CustomButton>,

    // Screenshot sheet
    showSubtitles: Boolean,
    onToggleShowSubtitles: (Boolean) -> Unit,
    cachePath: String,
    onSetAsCover: (() -> InputStream) -> Unit,
    onShare: (() -> InputStream) -> Unit,
    onSave: (() -> InputStream) -> Unit,
    takeScreenshot: (String, Boolean) -> InputStream?,
    onDismissScreenshot: () -> Unit,

    onOpenPanel: (Panels) -> Unit,
    onDismissRequest: () -> Unit,
    dismissSheet: Boolean,
) {
    when (sheetShown) {
        Sheets.None -> {}
        Sheets.SubtitleTracks -> {
            val subtitlesPicker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument(),
            ) {
                if (it == null) return@rememberLauncherForActivityResult
                onAddSubtitle(it)
            }
            SubtitlesSheet(
                tracks = subtitles.toImmutableList(),
                onSelect = onSelectSubtitle,
                onAddSubtitle = { subtitlesPicker.launch(arrayOf("*/*")) },
                onOpenSubtitleSettings = { onOpenPanel(Panels.SubtitleSettings) },
                onOpenSubtitleDelay = { onOpenPanel(Panels.SubtitleDelay) },
                onDismissRequest = onDismissRequest,
            )
        }

        Sheets.AudioTracks -> {
            val audioPicker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument(),
            ) {
                if (it == null) return@rememberLauncherForActivityResult
                onAddAudio(it)
            }
            // ANZ -->
            AudioTracksSheet(
                tracks = audioTracks.toImmutableList(),
                selectedAudio = selectedAudio,
                onSelect = onSelectAudio,
                onAddAudioTrack = { audioPicker.launch(arrayOf("*/*")) },
                onOpenDelayPanel = { onOpenPanel(Panels.AudioDelay) },
                onDismissRequest = onDismissRequest,
            )
            // ANZ <--
        }

        Sheets.QualityTracks -> {
            QualitySheet(
                isLoadingHosters = isLoadingHosters,
                hosterState = hosterState,
                expandedState = expandedState,
                selectedVideoIndex = selectedVideoIndex,
                onClickHoster = onClickHoster,
                onClickVideo = onClickVideo,
                onEnsureHosterExpanded = viewModel::ensureHosterExpanded,
                defaultStreamSelector = defaultStreamSelector,
                highlightDefaultStream = highlightDefaultStream,
                autoScrollToDefault = autoScrollToDefault,
                sheetActive = sheetShown == Sheets.QualityTracks,
                displayHosters = displayHosters,
                onDismissRequest = onDismissRequest,
                dismissSheet = dismissSheet,
            )
        }

        Sheets.Chapters -> {
            if (chapter == null) return
            ChaptersSheet(
                chapters = chapters,
                currentChapter = chapter,
                onClick = { onSeekToChapter(chapters.indexOf(it)) },
                onDismissRequest = onDismissRequest,
                dismissSheet = dismissSheet,
            )
        }

        Sheets.AspectRatios -> {
            val playerPreferences = remember { Injekt.get<PlayerPreferences>() }
            val customRatiosSet by playerPreferences.customAspectRatios().collectAsState()
            val customRatios = customRatiosSet.mapNotNull {
                val parts = it.split("|")
                if (parts.size == 2) {
                    val ratio = parts[1].toDoubleOrNull()
                    if (ratio != null) AspectRatioItem(parts[0], ratio, true) else null
                } else {
                    null
                }
            }

            val currentRatio = viewModel.videoAspectOverride.composeCollectAsState().value

            AspectRatioSheet(
                currentRatio = currentRatio,
                customRatios = customRatios,
                onSelectRatio = { ratio ->
                    viewModel.setCustomVideoAspect(ratio.ratio, ratio.label)
                },
                onAddCustomRatio = { label, ratio ->
                    playerPreferences.customAspectRatios().set(customRatiosSet + "$label|$ratio")
                },
                onDeleteCustomRatio = { item ->
                    val toRemove = customRatiosSet.firstOrNull { it.startsWith("${item.label}|") }
                    if (toRemove != null) {
                        playerPreferences.customAspectRatios().set(customRatiosSet - toRemove)
                    }
                },
                onDismissRequest = onDismissRequest,
            )
        }

        Sheets.VideoZoom -> {
            VideoZoomSheet(
                viewModel = viewModel,
                onDismissRequest = onDismissRequest,
            )
        }

        Sheets.More -> {
            MoreSheet(
                selectedDecoder = decoder,
                onSelectDecoder = onUpdateDecoder,
                remainingTime = sleepTimerTimeRemaining,
                onStartTimer = onStartSleepTimer,
                onDismissRequest = onDismissRequest,
                onEnterFiltersPanel = { onOpenPanel(Panels.VideoFilters) },
                customButtons = buttons,
            )
        }

        Sheets.PlaybackSpeed -> {
            // ANZ -->
            PlaybackSpeedSheet(
                pitchCorrection = pitchCorrection,
                onPitchCorrectionChange = onPitchCorrectionChange,
                speed = speed,
                speedPresets = speedPresets,
                onSpeedChange = onSpeedChange,
                onAddSpeedPreset = onAddSpeedPreset,
                onRemoveSpeedPreset = onRemoveSpeedPreset,
                onResetPresets = onResetSpeedPresets,
                longPressSpeed = longPressSpeed,
                longPressSpeedPresets = longPressSpeedPresets,
                onLongPressSpeedChange = onLongPressSpeedChange,
                onAddLongPressSpeedPreset = onAddLongPressSpeedPreset,
                onRemoveLongPressSpeedPreset = onRemoveLongPressSpeedPreset,
                onResetLongPressPresets = onResetLongPressSpeedPresets,
                onMakeDefault = onMakeDefaultSpeed,
                onResetDefault = onResetDefaultSpeed,
                onDismissRequest = onDismissRequest,
            )
            // ANZ <--
        }

        Sheets.Screenshot -> {
            ScreenshotSheet(
                hasSubTracks = subtitles.isNotEmpty(),
                showSubtitles = showSubtitles,
                onToggleShowSubtitles = onToggleShowSubtitles,
                cachePath = cachePath,
                onSetAsCover = onSetAsCover,
                onShare = onShare,
                onSave = onSave,
                takeScreenshot = takeScreenshot,
                onDismissRequest = onDismissScreenshot,
            )
        }
    }
}
