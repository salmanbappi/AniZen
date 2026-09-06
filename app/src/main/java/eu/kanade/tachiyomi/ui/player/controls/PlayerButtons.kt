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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.BorderStroke
import eu.kanade.tachiyomi.ui.player.CastManager
import eu.kanade.tachiyomi.ui.player.Panels
import eu.kanade.tachiyomi.ui.player.PlayerActivity
import eu.kanade.tachiyomi.ui.player.PlayerButton
import eu.kanade.tachiyomi.ui.player.PlayerViewModel
import eu.kanade.tachiyomi.ui.player.Sheets
import eu.kanade.tachiyomi.ui.player.VideoAspect
import eu.kanade.tachiyomi.ui.player.cast.components.CastButton
import eu.kanade.tachiyomi.ui.player.controls.components.AutoPlaySwitch
import eu.kanade.tachiyomi.ui.player.controls.components.ControlsButton
import eu.kanade.tachiyomi.ui.player.controls.components.CurrentChapter
import eu.kanade.tachiyomi.ui.player.controls.components.FilledControlsButton
import eu.kanade.tachiyomi.ui.player.execute
import eu.kanade.tachiyomi.ui.player.executeLongPress
import eu.kanade.tachiyomi.ui.player.getIcon
import `is`.xyz.mpv.MPVLib
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

@Composable
fun RenderPlayerButton(
    button: PlayerButton,
    viewModel: PlayerViewModel,
    castManager: CastManager,
    onBackPress: () -> Unit,
    onCastClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val autoPlayEnabled by viewModel.playerPreferences.autoplayEnabled().collectAsState()
    val isEpisodeOnline by viewModel.isEpisodeOnline.collectAsState()
    val castState by castManager.castState.collectAsState()
    val aspectRatio by viewModel.playerPreferences.aspectState().collectAsState()
    val videoZoom by viewModel.videoZoom.collectAsState()
    val playbackSpeed by viewModel.playbackSpeed.collectAsState()
    val currentChapter by viewModel.currentChapter.collectAsState()
    val customButtonTitle by viewModel.primaryButtonTitle.collectAsState()
    val customButton by viewModel.primaryButton.collectAsState()

    when (button) {
        PlayerButton.BackArrow -> {
            ControlsButton(
                icon = button.getIcon(),
                onClick = onBackPress,
            )
        }
        PlayerButton.VideoTitle -> {
            val animeTitle by viewModel.animeTitle.collectAsState()
            val mediaTitle by viewModel.mediaTitle.collectAsState()
            Column(
                verticalArrangement = Arrangement.spacedBy(-MaterialTheme.padding.extraSmall),
                modifier = Modifier
                    .clickable(onClick = { viewModel.showEpisodeListDialog() }),
            ) {
                Text(
                    animeTitle,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    mediaTitle,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = Color.White.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.bodyLarge,
                    fontStyle = FontStyle.Italic,
                )
            }
        }
        PlayerButton.AutoPlay -> {
            AutoPlaySwitch(
                isChecked = autoPlayEnabled,
                onToggleAutoPlay = { viewModel.setAutoPlay(it) },
                modifier = Modifier
                    .padding(vertical = MaterialTheme.padding.medium, horizontal = MaterialTheme.padding.mediumSmall)
                    .size(width = 48.dp, height = 24.dp),
            )
        }
        PlayerButton.Cast -> {
            if (viewModel.playerPreferences.enableCast().get()) {
                CastButton(
                    castState = castState,
                    onClick = onCastClick,
                    modifier = Modifier.padding(horizontal = MaterialTheme.padding.mediumSmall),
                )
            }
        }
        PlayerButton.SubtitleTracks -> {
            ControlsButton(
                icon = button.getIcon(),
                onClick = { viewModel.showSheet(Sheets.SubtitleTracks) },
                onLongClick = { viewModel.showPanel(Panels.SubtitleSettings) },
                horizontalSpacing = MaterialTheme.padding.mediumSmall,
            )
        }
        PlayerButton.AudioTracks -> {
            ControlsButton(
                icon = button.getIcon(),
                onClick = { viewModel.showSheet(Sheets.AudioTracks) },
                onLongClick = { viewModel.showPanel(Panels.AudioDelay) },
                horizontalSpacing = MaterialTheme.padding.mediumSmall,
            )
        }
        PlayerButton.QualityTracks -> {
            if (isEpisodeOnline == true) {
                ControlsButton(
                    icon = button.getIcon(),
                    onClick = { viewModel.showSheet(Sheets.QualityTracks) },
                    onLongClick = { viewModel.showSheet(Sheets.QualityTracks) },
                    horizontalSpacing = MaterialTheme.padding.mediumSmall,
                )
            }
        }
        PlayerButton.MoreOptions -> {
            ControlsButton(
                icon = button.getIcon(),
                onClick = { viewModel.showSheet(Sheets.More) },
                onLongClick = { viewModel.showPanel(Panels.VideoFilters) },
                horizontalSpacing = MaterialTheme.padding.mediumSmall,
            )
        }
        PlayerButton.PlaybackSpeed -> {
            ControlsButton(
                text = stringResource(MR.strings.player_speed, playbackSpeed),
                onClick = {
                    val newSpeed = if (playbackSpeed >= 2) 0.25f else playbackSpeed + 0.25f
                    MPVLib.setPropertyDouble("speed", newSpeed.toDouble())
                    viewModel.playerPreferences.playerSpeed().set(newSpeed)
                },
                onLongClick = { viewModel.showSheet(Sheets.PlaybackSpeed) },
            )
        }
        PlayerButton.CurrentChapter -> {
            AnimatedVisibility(
                currentChapter != null && viewModel.playerPreferences.showCurrentChapter().get(),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                CurrentChapter(
                    chapter = currentChapter!!.toSegment(),
                    onClick = { viewModel.showSheet(Sheets.Chapters) },
                )
            }
        }
        PlayerButton.LockControls -> {
            ControlsButton(
                icon = button.getIcon(),
                onClick = { viewModel.lockControls() },
            )
        }
        PlayerButton.ScreenRotation -> {
            ControlsButton(
                icon = button.getIcon(),
                onClick = { viewModel.cycleScreenRotations() },
            )
        }
        PlayerButton.PictureInPicture -> {
            val activity = LocalContext.current as? PlayerActivity
            if (activity?.isPipSupportedAndEnabled == true) {
                ControlsButton(
                    icon = button.getIcon(),
                    onClick = {
                        if (!viewModel.isLoadingEpisode.value) {
                            activity.enterPictureInPictureMode(activity.createPipParams())
                        }
                    },
                    horizontalSpacing = MaterialTheme.padding.mediumSmall,
                )
            }
        }
        PlayerButton.AspectRatio -> {
            ControlsButton(
                icon = when (aspectRatio) {
                    VideoAspect.Fit -> button.getIcon()
                    VideoAspect.Stretch -> Icons.Filled.ZoomOutMap
                    VideoAspect.Crop -> Icons.Filled.FitScreen
                },
                onClick = {
                    viewModel.changeVideoAspect(
                        when (aspectRatio) {
                            VideoAspect.Fit -> VideoAspect.Stretch
                            VideoAspect.Stretch -> VideoAspect.Crop
                            VideoAspect.Crop -> VideoAspect.Fit
                        },
                    )
                },
                onLongClick = { viewModel.showSheet(Sheets.AspectRatios) },
                horizontalSpacing = MaterialTheme.padding.mediumSmall,
            )
        }
        PlayerButton.VideoZoom -> {
            if (kotlin.math.abs(videoZoom) >= 0.005f) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.background.copy(alpha = 0.6f),
                    modifier = Modifier
                        .height(48.dp)
                        .clip(RoundedCornerShape(50))
                        .combinedClickable(
                            onClick = { viewModel.showSheet(Sheets.VideoZoom) },
                            onLongClick = { viewModel.resetVideoZoomAndPan() },
                        ),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
                        modifier = Modifier.padding(horizontal = MaterialTheme.padding.small),
                    ) {
                        Icon(
                            imageVector = button.getIcon(),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = String.format("%.0f%%", videoZoom * 100),
                            maxLines = 1,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            } else {
                ControlsButton(
                    icon = button.getIcon(),
                    onClick = { viewModel.showSheet(Sheets.VideoZoom) },
                    onLongClick = { viewModel.resetVideoZoomAndPan() },
                    horizontalSpacing = MaterialTheme.padding.mediumSmall,
                )
            }
        }
        // The skip intro prompt is rendered above the seekbar in PlayerControls
        PlayerButton.SkipIntro -> Unit
        PlayerButton.CustomButton -> {
            if (customButton != null && customButtonTitle != null) {
                FilledControlsButton(
                    text = customButtonTitle!!,
                    onClick = { customButton!!.execute() },
                    onLongClick = { customButton!!.executeLongPress() },
                )
            }
        }
    }
}
