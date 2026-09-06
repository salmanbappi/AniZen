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
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.constraintlayout.compose.Dimension
import eu.kanade.presentation.more.settings.screen.player.custombutton.getButtons
import eu.kanade.presentation.theme.playerRippleConfiguration
import eu.kanade.tachiyomi.ui.player.CastManager
import eu.kanade.tachiyomi.ui.player.Dialogs
import eu.kanade.tachiyomi.ui.player.Panels
import eu.kanade.tachiyomi.ui.player.PlayerActivity
import eu.kanade.tachiyomi.ui.player.PlayerUpdates
import eu.kanade.tachiyomi.ui.player.PlayerViewModel
import eu.kanade.tachiyomi.ui.player.execute
import eu.kanade.tachiyomi.ui.player.executeLongPress
import eu.kanade.tachiyomi.ui.player.controls.components.DoubleTapToSeekOvals
import eu.kanade.tachiyomi.ui.player.Sheets
import eu.kanade.tachiyomi.ui.player.VideoAspect
import eu.kanade.tachiyomi.ui.player.cast.components.CastSheet
import eu.kanade.tachiyomi.ui.player.controls.components.BrightnessOverlay
import eu.kanade.tachiyomi.ui.player.controls.components.BrightnessSlider
import eu.kanade.tachiyomi.ui.player.controls.components.ControlsButton
import eu.kanade.tachiyomi.ui.player.controls.components.DoubleSpeedPlayerUpdate
import eu.kanade.tachiyomi.ui.player.controls.components.FilledControlsButton
import eu.kanade.tachiyomi.ui.player.controls.components.SeekbarWithTimers
import eu.kanade.tachiyomi.ui.player.controls.components.ThumbnailPreview
import eu.kanade.tachiyomi.ui.player.controls.components.TextPlayerUpdate
import eu.kanade.tachiyomi.ui.player.controls.components.VolumeSlider
import eu.kanade.tachiyomi.ui.player.controls.components.sheets.toFixed
import eu.kanade.tachiyomi.ui.player.settings.AdvancedPlayerPreferences
import eu.kanade.tachiyomi.ui.player.settings.AudioPreferences
import eu.kanade.tachiyomi.ui.player.settings.GesturePreferences
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import eu.kanade.tachiyomi.ui.player.settings.SubtitlePreferences
import exh.log.InterpolationStatsOverlay
import `is`.xyz.mpv.MPVLib
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import eu.kanade.tachiyomi.ui.player.PlayerButton
import eu.kanade.tachiyomi.ui.player.parseButtons
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Suppress("CompositionLocalAllowlist")
val LocalPlayerButtonsClickEvent = staticCompositionLocalOf { {} }

@Composable
fun PlayerControls(
    viewModel: PlayerViewModel,
    castManager: CastManager,
    onBackPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showCastSheet by remember { mutableStateOf(false) }
    val castState by castManager.castState.collectAsState()

    val spacing = MaterialTheme.padding
    val playerPreferences = remember { Injekt.get<PlayerPreferences>() }
    val gesturePreferences = remember { Injekt.get<GesturePreferences>() }
    val audioPreferences = remember { Injekt.get<AudioPreferences>() }
    val subtitlePreferences = remember { Injekt.get<SubtitlePreferences>() }
    val interactionSource = remember { MutableInteractionSource() }
    val controlsShown by viewModel.controlsShown.collectAsState()
    val areControlsLocked by viewModel.areControlsLocked.collectAsState()
    val seekBarShown by viewModel.seekBarShown.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val pausedForCache by viewModel.pausedForCache.collectAsState()
    val isLoadingEpisode by viewModel.isLoadingEpisode.collectAsState()
    val isStopped by viewModel.isStopped.collectAsState()
    val duration by viewModel.duration.collectAsState()
    val position by viewModel.pos.collectAsState()
    val paused by viewModel.paused.collectAsState()
    val gestureSeekAmount by viewModel.gestureSeekAmount.collectAsState()
    val doubleTapSeekAmount by viewModel.doubleTapSeekAmount.collectAsState()
    val showDoubleTapOvals by playerPreferences.showDoubleTapOvals().collectAsState()
    val showSeekIcon by playerPreferences.showSeekIcon().collectAsState()
    val showSeekTime by playerPreferences.showSeekTimeWhileSeeking().collectAsState()
    val seekText by viewModel.seekText.collectAsState()
    val currentChapter by viewModel.currentChapter.collectAsState()
    val chapters by viewModel.chapters.collectAsState()
    val currentBrightness by viewModel.currentBrightness.collectAsState()

    val playerTimeToDisappear by playerPreferences.playerTimeToDisappear().collectAsState()
    var resetControls by remember { mutableStateOf(true) }
    val isSeekingUI by viewModel.isSeekingUI.collectAsState()
    val seekPosition by viewModel.seekPosition.collectAsState()
    val chaptersList = remember(chapters) {
        chapters.map { it.toSegment() }
    }

    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    val topRightButtons by playerPreferences.topRightControls().collectAsState()
    val bottomLeftButtons by playerPreferences.bottomLeftControls().collectAsState()
    val bottomRightButtons by playerPreferences.bottomRightControls().collectAsState()
    val portraitBottomButtons by playerPreferences.portraitBottomControls().collectAsState()

    val topRightButtonsList = remember(topRightButtons) { parseButtons(topRightButtons) }
    val bottomLeftButtonsList = remember(bottomLeftButtons) { parseButtons(bottomLeftButtons) }
    val bottomRightButtonsList = remember(bottomRightButtons) { parseButtons(bottomRightButtons) }
    val portraitBottomButtonsList = remember(portraitBottomButtons) { parseButtons(portraitBottomButtons) }

    val customButtons by viewModel.customButtons.collectAsState()
    val customButton by viewModel.primaryButton.collectAsState()

    LaunchedEffect(
        controlsShown,
        paused,
        isSeekingUI,
        resetControls,
    ) {
        if (controlsShown && !paused && !isSeekingUI) {
            delay(playerTimeToDisappear.toLong())
            viewModel.hideControls()
        }
    }

    val transparentOverlay by animateFloatAsState(
        if (controlsShown && !areControlsLocked) .8f else 0f,
        animationSpec = playerControlsExitAnimationSpec(),
        label = "controls_transparent_overlay",
    )
    GestureHandler(
        viewModel = viewModel,
        interactionSource = interactionSource,
    )
    DoubleTapToSeekOvals(
        amount = doubleTapSeekAmount,
        text = seekText,
        showOvals = showDoubleTapOvals,
        showSeekIcon = showSeekIcon,
        showSeekTime = showSeekTime,
        interactionSource = interactionSource,
    )
    CompositionLocalProvider(
        LocalRippleConfiguration provides playerRippleConfiguration,
        LocalPlayerButtonsClickEvent provides { resetControls = !resetControls },
        LocalContentColor provides Color.White,
    ) {
        CompositionLocalProvider(
            LocalLayoutDirection provides LayoutDirection.Ltr,
        ) {
            ConstraintLayout(
                modifier = modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            Pair(0f, Color.Black),
                            Pair(.2f, Color.Transparent),
                            Pair(.7f, Color.Transparent),
                            Pair(1f, Color.Black),
                        ),
                        alpha = transparentOverlay,
                    )
                    .padding(horizontal = MaterialTheme.padding.medium),
            ) {
                val (
                    topLeftControls, topRightControls, castButton,
                    volumeSlider, brightnessSlider,
                    unlockControlsButton,
                    bottomRightControls, bottomLeftControls,
                    centerControls, seekbar, playerUpdates,
                    portraitBottomBar, thumbnail, playerPrompt,
                ) = createRefs()

                val hasPreviousEpisode by viewModel.hasPreviousEpisode.collectAsState()
                val hasNextEpisode by viewModel.hasNextEpisode.collectAsState()
                val isBrightnessSliderShown by viewModel.isBrightnessSliderShown.collectAsState()
                val isVolumeSliderShown by viewModel.isVolumeSliderShown.collectAsState()
                val brightness by viewModel.currentBrightness.collectAsState()
                val volume by viewModel.currentVolume.collectAsState()
                val mpvVolume by viewModel.currentMPVVolume.collectAsState()
                val swapVolumeAndBrightness by gesturePreferences.swapVolumeBrightness().collectAsState()
                val reduceMotion by playerPreferences.reduceMotion().collectAsState()

                LaunchedEffect(volume, mpvVolume, isVolumeSliderShown) {
                    delay(2000)
                    if (isVolumeSliderShown) viewModel.isVolumeSliderShown.update { false }
                }
                LaunchedEffect(brightness, isBrightnessSliderShown) {
                    delay(2000)
                    if (isBrightnessSliderShown) viewModel.isBrightnessSliderShown.update { false }
                }
                AnimatedVisibility(
                    isBrightnessSliderShown,
                    enter =
                    if (!reduceMotion) {
                        slideInHorizontally(playerControlsEnterAnimationSpec()) {
                            if (swapVolumeAndBrightness) -it else it
                        } +
                            fadeIn(
                                playerControlsEnterAnimationSpec(),
                            )
                    } else {
                        fadeIn(playerControlsEnterAnimationSpec())
                    },
                    exit =
                    if (!reduceMotion) {
                        slideOutHorizontally(playerControlsExitAnimationSpec()) {
                            if (swapVolumeAndBrightness) -it else it
                        } +
                            fadeOut(
                                playerControlsExitAnimationSpec(),
                            )
                    } else {
                        fadeOut(playerControlsExitAnimationSpec())
                    },
                    modifier = Modifier.constrainAs(brightnessSlider) {
                        if (swapVolumeAndBrightness) {
                            start.linkTo(parent.start, spacing.medium)
                        } else {
                            end.linkTo(parent.end, spacing.medium)
                        }
                        top.linkTo(parent.top)
                        bottom.linkTo(parent.bottom)
                    },
                ) {
                    BrightnessSlider(
                        brightness = brightness,
                        positiveRange = 0f..1f,
                        negativeRange = 0f..0.75f,
                    )
                }

                AnimatedVisibility(
                    isVolumeSliderShown,
                    enter =
                    if (!reduceMotion) {
                        slideInHorizontally(playerControlsEnterAnimationSpec()) {
                            if (swapVolumeAndBrightness) it else -it
                        } +
                            fadeIn(
                                playerControlsEnterAnimationSpec(),
                            )
                    } else {
                        fadeIn(playerControlsEnterAnimationSpec())
                    },
                    exit =
                    if (!reduceMotion) {
                        slideOutHorizontally(playerControlsExitAnimationSpec()) {
                            if (swapVolumeAndBrightness) it else -it
                        } +
                            fadeOut(
                                playerControlsExitAnimationSpec(),
                            )
                    } else {
                        fadeOut(playerControlsExitAnimationSpec())
                    },
                    modifier = Modifier.constrainAs(volumeSlider) {
                        if (swapVolumeAndBrightness) {
                            end.linkTo(parent.end, spacing.medium)
                        } else {
                            start.linkTo(parent.start, spacing.medium)
                        }
                        top.linkTo(parent.top)
                        bottom.linkTo(parent.bottom)
                    },
                ) {
                    val boostCap by audioPreferences.volumeBoostCap().collectAsState()
                    val displayVolumeAsPercentage by playerPreferences.displayVolPer().collectAsState()
                    VolumeSlider(
                        volume = volume,
                        mpvVolume = mpvVolume,
                        range = 0..viewModel.maxVolume,
                        boostRange = if (boostCap > 0) 0..audioPreferences.volumeBoostCap().get() else null,
                        displayAsPercentage = displayVolumeAsPercentage,
                    )
                }

                val currentPlayerUpdate by viewModel.playerUpdate.collectAsState()
                val aspectRatio by playerPreferences.aspectState().collectAsState()
                LaunchedEffect(currentPlayerUpdate, aspectRatio) {
                    if (currentPlayerUpdate is PlayerUpdates.DoubleSpeed || currentPlayerUpdate is PlayerUpdates.None) {
                        return@LaunchedEffect
                    }
                    delay(2000)
                    viewModel.playerUpdate.update { PlayerUpdates.None }
                }
                AnimatedVisibility(
                    currentPlayerUpdate !is PlayerUpdates.None,
                    enter = fadeIn(playerControlsEnterAnimationSpec()),
                    exit = fadeOut(playerControlsExitAnimationSpec()),
                    modifier = Modifier.constrainAs(playerUpdates) {
                        linkTo(parent.start, parent.end)
                        linkTo(parent.top, parent.bottom, bias = 0.2f)
                    },
                ) {
                    when (currentPlayerUpdate) {
                        is PlayerUpdates.DoubleSpeed -> eu.kanade.tachiyomi.ui.player.controls.components.DoubleSpeedIndicator(
                            speed = (currentPlayerUpdate as PlayerUpdates.DoubleSpeed).speed,
                            isDragging = (currentPlayerUpdate as PlayerUpdates.DoubleSpeed).isDragging,
                        )
                        is PlayerUpdates.AspectRatio -> TextPlayerUpdate(stringResource(aspectRatio.titleRes))
                        is PlayerUpdates.ShowText -> TextPlayerUpdate(
                            (currentPlayerUpdate as PlayerUpdates.ShowText).value,
                        )
                        is PlayerUpdates.ShowTextResource -> TextPlayerUpdate(
                            stringResource((currentPlayerUpdate as PlayerUpdates.ShowTextResource).textResource),
                        )
                        is PlayerUpdates.VideoZoom -> TextPlayerUpdate(
                            "Zoom: ${((currentPlayerUpdate as PlayerUpdates.VideoZoom).zoom * 100).toInt()}%"
                        )
                        else -> {}
                    }
                }

                AnimatedVisibility(
                    controlsShown && areControlsLocked,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.constrainAs(unlockControlsButton) {
                        bottom.linkTo(parent.bottom, spacing.extraLarge)
                        start.linkTo(parent.start)
                        end.linkTo(parent.end)
                    },
                ) {
                    eu.kanade.tachiyomi.ui.player.controls.components.SlideToUnlock(
                        onUnlock = { viewModel.unlockControls() },
                    )
                }
                val isLongPressing by viewModel.isLongPressing.collectAsState()
                 AnimatedVisibility(
                    visible = (
                        (controlsShown && !areControlsLocked || gestureSeekAmount != null) ||
                            ((isLoading || pausedForCache) && !isStopped) ||
                            isLoadingEpisode
                        ) && !isLongPressing,
                    enter = fadeIn(playerControlsEnterAnimationSpec()),
                    exit = fadeOut(playerControlsExitAnimationSpec()),
                    modifier = Modifier.constrainAs(centerControls) {
                        end.linkTo(parent.absoluteRight)
                        start.linkTo(parent.absoluteLeft)
                        top.linkTo(parent.top)
                        bottom.linkTo(parent.bottom)
                    },
                ) {
                    val showLoadingCircle by playerPreferences.showLoadingCircle().collectAsState()
                    MiddlePlayerControls(
                        hasPrevious = hasPreviousEpisode,
                        onSkipPrevious = { viewModel.changeEpisode(true) },
                        hasNext = hasNextEpisode,
                        onSkipNext = { viewModel.changeEpisode(false) },
                        isStopped = isStopped,
                        isLoading = isLoading || pausedForCache,
                        isLoadingEpisode = isLoadingEpisode,
                        controlsShown = controlsShown,
                        areControlsLocked = areControlsLocked,
                        showLoadingCircle = showLoadingCircle,
                        paused = paused,
                        gestureSeekAmount = gestureSeekAmount,
                        onPlayPauseClick = viewModel::pauseUnpause,
                        enter = fadeIn(playerControlsEnterAnimationSpec()),
                        exit = fadeOut(playerControlsExitAnimationSpec()),
                    )
                }
                AnimatedVisibility(
                    visible = (controlsShown || seekBarShown) && !areControlsLocked && !isLongPressing,
                    enter = if (!reduceMotion) {
                        slideInVertically(playerControlsEnterAnimationSpec()) { it } +
                            fadeIn(playerControlsEnterAnimationSpec())
                    } else {
                        fadeIn(playerControlsEnterAnimationSpec())
                    },
                    exit = if (!reduceMotion) {
                        slideOutVertically(playerControlsExitAnimationSpec()) { it } +
                            fadeOut(playerControlsExitAnimationSpec())
                    } else {
                        fadeOut(playerControlsExitAnimationSpec())
                    },
                    modifier = Modifier.constrainAs(seekbar) {
                        if (isLandscape) {
                            bottom.linkTo(bottomLeftControls.top)
                        } else {
                            bottom.linkTo(portraitBottomBar.top)
                        }
                    }.offset(y = spacing.medium),
                ) {
                    val invertDuration by playerPreferences.invertDuration().collectAsState()
                    val readAhead by viewModel.readAhead.collectAsState()
                    val preciseSeeking by gesturePreferences.playerSmoothSeek().collectAsState()

                    var wasPlayerAlreadyPause by remember { mutableStateOf(false) }
                    var sliderPosition by remember { androidx.compose.runtime.mutableFloatStateOf(position) }
                    var lastTargetSeekPos by remember { mutableStateOf<Float?>(null) }

                    LaunchedEffect(position, seekPosition, isSeekingUI) {
                        if (isSeekingUI) {
                            sliderPosition = seekPosition
                        } else {
                            val target = lastTargetSeekPos
                            if (target != null) {
                                if (kotlin.math.abs(position - target) > 1.5f) {
                                    sliderPosition = target
                                } else {
                                    sliderPosition = position
                                    lastTargetSeekPos = null
                                }
                            } else {
                                sliderPosition = position
                            }
                        }
                    }

                    LaunchedEffect(isSeekingUI) {
                        if (!isSeekingUI && lastTargetSeekPos != null) {
                            kotlinx.coroutines.delay(1000)
                            lastTargetSeekPos = null
                        }
                    }

                    SeekbarWithTimers(
                        position = sliderPosition,
                        duration = duration,
                        readAheadValue = readAhead,
                        onValueChange = {
                            if (!viewModel.isSeekingUI.value) {
                                wasPlayerAlreadyPause = viewModel.paused.value
                                viewModel.pause()
                                viewModel.updateIsSeeking(true)
                            }
                            sliderPosition = it
                            lastTargetSeekPos = it
                            viewModel.updateSeekPos(it)
                            viewModel.scrubSeekTo(it.toInt(), false)
                        },
                        onValueChangeFinished = {
                            val target = sliderPosition
                            lastTargetSeekPos = target
                            viewModel.updateSeekPos(target)
                            viewModel.updateIsSeeking(false)
                            viewModel.seekTo(target.toInt(), preciseSeeking)
                            if (!wasPlayerAlreadyPause) {
                                viewModel.unpause()
                            }
                        },
                        timersInverted = Pair(false, invertDuration),
                        durationTimerOnCLick = { playerPreferences.invertDuration().set(!invertDuration) },
                        positionTimerOnClick = {},
                        chapters = chaptersList,
                    )
                }

                // Skip intro prompt / custom action button, right-aligned above the seekbar
                val skipIntroButton by viewModel.skipIntroText.collectAsState()
                val customButtonTitle by viewModel.primaryButtonTitle.collectAsState()
                AnimatedVisibility(
                    visible = controlsShown && !areControlsLocked && !isLongPressing && !isSeekingUI &&
                        (skipIntroButton != null || (customButton != null && customButtonTitle.isNotEmpty())),
                    enter = fadeIn(playerControlsEnterAnimationSpec()),
                    exit = fadeOut(playerControlsExitAnimationSpec()),
                    modifier = Modifier.constrainAs(playerPrompt) {
                        bottom.linkTo(seekbar.top, spacing.small)
                        end.linkTo(seekbar.end)
                    }.offset(y = spacing.medium),
                ) {
                    val skipIntroLabel = skipIntroButton
                    if (skipIntroLabel != null) {
                        FilledControlsButton(
                            text = skipIntroLabel,
                            onClick = viewModel::onSkipIntro,
                            onLongClick = viewModel::onSkipIntro,
                        )
                    } else {
                        val actionButton = customButton
                        if (actionButton != null && customButtonTitle.isNotEmpty()) {
                            FilledControlsButton(
                                text = customButtonTitle,
                                onClick = { actionButton.execute() },
                                onLongClick = { actionButton.executeLongPress() },
                            )
                        }
                    }
                }
                val mediaTitle by viewModel.mediaTitle.collectAsState()
                val animeTitle by viewModel.animeTitle.collectAsState()
                AnimatedVisibility(
                    controlsShown && !areControlsLocked && !isLongPressing,
                    enter = if (!reduceMotion) {
                        slideInHorizontally(playerControlsEnterAnimationSpec()) { -it } +
                            fadeIn(playerControlsEnterAnimationSpec())
                    } else {
                        fadeIn(playerControlsEnterAnimationSpec())
                    },
                    exit = if (!reduceMotion) {
                        slideOutHorizontally(playerControlsExitAnimationSpec()) { -it } +
                            fadeOut(playerControlsExitAnimationSpec())
                    } else {
                        fadeOut(playerControlsExitAnimationSpec())
                    },
                    modifier = Modifier.constrainAs(topLeftControls) {
                        top.linkTo(parent.top, spacing.medium)
                        start.linkTo(parent.start)
                        width = Dimension.fillToConstraints
                        end.linkTo(topRightControls.start)
                    },
                ) {
                    val animeTitle by viewModel.animeTitle.collectAsState()
                    val mediaTitle by viewModel.mediaTitle.collectAsState()
                    TopLeftPlayerControls(
                        animeTitle = animeTitle,
                        mediaTitle = mediaTitle,
                        onTitleClick = { viewModel.showEpisodeListDialog() },
                        onBackClick = onBackPress,
                    )
                }
                // Top right controls
                AnimatedVisibility(
                    controlsShown && !areControlsLocked && !isLongPressing,
                    enter = if (!reduceMotion) {
                        slideInHorizontally(playerControlsEnterAnimationSpec()) { it } +
                            fadeIn(playerControlsEnterAnimationSpec())
                    } else {
                        fadeIn(playerControlsEnterAnimationSpec())
                    },
                    exit = if (!reduceMotion) {
                        slideOutHorizontally(playerControlsExitAnimationSpec()) { it } +
                            fadeOut(playerControlsExitAnimationSpec())
                    } else {
                        fadeOut(playerControlsExitAnimationSpec())
                    },
                    modifier = Modifier.constrainAs(topRightControls) {
                        top.linkTo(parent.top, spacing.medium)
                        end.linkTo(parent.end)
                    },
                ) {
                    TopRightPlayerControls(
                        buttons = topRightButtonsList,
                        viewModel = viewModel,
                        castManager = castManager,
                        onBackPress = onBackPress,
                        onCastClick = { showCastSheet = true },
                    )
                }

                // Portrait bottom bar
                AnimatedVisibility(
                    visible = controlsShown && !areControlsLocked && !isLongPressing && !isLandscape,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.constrainAs(portraitBottomBar) {
                        bottom.linkTo(parent.bottom, spacing.medium)
                        start.linkTo(parent.start)
                        end.linkTo(parent.end)
                        width = Dimension.fillToConstraints
                    },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        portraitBottomButtonsList.forEach { button ->
                            RenderPlayerButton(
                                button = button,
                                viewModel = viewModel,
                                castManager = castManager,
                                onBackPress = onBackPress,
                                onCastClick = { showCastSheet = true },
                            )
                        }
                    }
                }

                // Bottom right controls
                AnimatedVisibility(
                    controlsShown && !areControlsLocked && !isLongPressing && isLandscape,
                    enter = if (!reduceMotion) {
                        slideInHorizontally(playerControlsEnterAnimationSpec()) { it } +
                            fadeIn(playerControlsEnterAnimationSpec())
                    } else {
                        fadeIn(playerControlsEnterAnimationSpec())
                    },
                    exit = if (!reduceMotion) {
                        slideOutHorizontally(playerControlsExitAnimationSpec()) { it } +
                            fadeOut(playerControlsExitAnimationSpec())
                    } else {
                        fadeOut(playerControlsExitAnimationSpec())
                    },
                    modifier = Modifier.constrainAs(bottomRightControls) {
                        bottom.linkTo(parent.bottom, spacing.medium)
                        end.linkTo(seekbar.end)
                    },
                ) {
                    BottomRightPlayerControls(
                        buttons = bottomRightButtonsList,
                        viewModel = viewModel,
                        castManager = castManager,
                        onBackPress = onBackPress,
                        onCastClick = { showCastSheet = true },
                    )
                }
                // Bottom left controls
                AnimatedVisibility(
                    controlsShown && !areControlsLocked && !isLongPressing && isLandscape,
                    enter = if (!reduceMotion) {
                        slideInHorizontally(playerControlsEnterAnimationSpec()) { -it } +
                            fadeIn(playerControlsEnterAnimationSpec())
                    } else {
                        fadeIn(playerControlsEnterAnimationSpec())
                    },
                    exit = if (!reduceMotion) {
                        slideOutHorizontally(playerControlsExitAnimationSpec()) { -it } +
                            fadeOut(playerControlsExitAnimationSpec())
                    } else {
                        fadeOut(playerControlsExitAnimationSpec())
                    },
                    modifier = Modifier.constrainAs(bottomLeftControls) {
                        bottom.linkTo(parent.bottom, spacing.medium)
                        start.linkTo(seekbar.start)
                        width = Dimension.fillToConstraints
                        end.linkTo(bottomRightControls.start)
                    },
                ) {
                    BottomLeftPlayerControls(
                        buttons = bottomLeftButtonsList,
                        viewModel = viewModel,
                        castManager = castManager,
                        onBackPress = onBackPress,
                        onCastClick = { showCastSheet = true },
                    )
                }

                val thumbnailImage by viewModel.thumbnailImage.collectAsState()
                ThumbnailPreview(
                    visible = isSeekingUI,
                    image = thumbnailImage,
                    positionSProvider = run {
                        val currentSeekPosition = androidx.compose.runtime.rememberUpdatedState(seekPosition.toLong())
                        remember { { currentSeekPosition.value } }
                    },
                    durationS = duration.toLong(),
                    chapters = chaptersList,
                    modifier = Modifier.fillMaxWidth().constrainAs(thumbnail) {
                        bottom.linkTo(seekbar.top, spacing.medium)
                    },
                )
            }
        }

        val sheetShown by viewModel.sheetShown.collectAsState()
        val dismissSheet by viewModel.dismissSheet.collectAsState()
        val subtitles by viewModel.subtitleTracks.collectAsState()
        val selectedSubtitles by viewModel.selectedSubtitles.collectAsState()
        val audioTracks by viewModel.audioTracks.collectAsState()
        val selectedAudio by viewModel.selectedAudio.collectAsState()
        val isLoadingHosters by viewModel.isLoadingHosters.collectAsState()
        val hosterState by viewModel.hosterState.collectAsState()
        val expandedState by viewModel.hosterExpandedList.collectAsState()
        val selectedHosterVideoIndex by viewModel.selectedHosterVideoIndex.collectAsState()
        val currentAnime by viewModel.currentAnime.collectAsState()
        val perAnimeDefaultStream by playerPreferences.perAnimeDefaultStream().collectAsState()
        val perAnimeDefaultStreamData by playerPreferences.perAnimeDefaultStreamData().collectAsState()
        val showDefaultStreamHighlight by playerPreferences.showDefaultStreamHighlight().collectAsState()
        val autoScrollDefaultStream by playerPreferences.autoScrollDefaultStream().collectAsState()
        val defaultStreamSelector = remember(
            currentAnime?.id,
            perAnimeDefaultStream,
            perAnimeDefaultStreamData,
        ) {
            if (!perAnimeDefaultStream) "" else viewModel.getEffectiveDefaultStreamSelector()
        }
        val highlightDefaultStream = perAnimeDefaultStream && showDefaultStreamHighlight
        val autoScrollToDefault = perAnimeDefaultStream && autoScrollDefaultStream
        val decoder by viewModel.currentDecoder.collectAsState()
        val speed by viewModel.playbackSpeed.collectAsState()
        val sleepTimerTimeRemaining by viewModel.remainingTime.collectAsState()
        val showSubtitles by subtitlePreferences.screenshotSubtitles().collectAsState()
        val showFailedHosters by playerPreferences.showFailedHosters().collectAsState()
        val emptyHosters by playerPreferences.showEmptyHosters().collectAsState()

        PlayerSheets(
            sheetShown = sheetShown,
            viewModel = viewModel,
            subtitles = subtitles,
            selectedSubtitles = selectedSubtitles.toList(),
            onAddSubtitle = viewModel::addSubtitle,
            onSelectSubtitle = { viewModel.selectSub(it) },
            audioTracks = audioTracks,
            selectedAudio = selectedAudio,
            onAddAudio = viewModel::addAudio,
            onSelectAudio = viewModel::selectAudio,

            isLoadingHosters = isLoadingHosters,

            hosterState = hosterState,
            expandedState = expandedState,
            selectedVideoIndex = selectedHosterVideoIndex,
            onClickHoster = viewModel::onHosterClicked,
            onClickVideo = viewModel::onVideoClicked,
            defaultStreamSelector = defaultStreamSelector,
            highlightDefaultStream = highlightDefaultStream,
            autoScrollToDefault = autoScrollToDefault,
            displayHosters = Pair(showFailedHosters, emptyHosters),

            chapter = currentChapter?.toSegment(),
            chapters = chaptersList,
            onSeekToChapter = {
                viewModel.selectChapter(it)
                viewModel.dismissSheet()
                viewModel.unpause()
            },
            decoder = decoder,
            onUpdateDecoder = viewModel::updateDecoder,
            speed = speed,
            onSpeedChange = { MPVLib.setPropertyDouble("speed", it.toFixed(2).toDouble()) },
            sleepTimerTimeRemaining = sleepTimerTimeRemaining,
            onStartSleepTimer = viewModel::startTimer,
            buttons = customButtons.getButtons(),

            showSubtitles = showSubtitles,
            onToggleShowSubtitles = { subtitlePreferences.screenshotSubtitles().set(it) },
            cachePath = viewModel.cachePath,
            onSetAsCover = viewModel::setAsCover,
            onShare = { viewModel.shareImage(it, viewModel.pos.value.toInt()) },
            onSave = { viewModel.saveImage(it, viewModel.pos.value.toInt()) },
            takeScreenshot = viewModel::takeScreenshot,
            onDismissScreenshot = {
                viewModel.showSheet(Sheets.None)
                viewModel.unpause()
            },
            onOpenPanel = viewModel::showPanel,
            onDismissRequest = { viewModel.showSheet(Sheets.None) },
            dismissSheet = dismissSheet,
        )
        val panel by viewModel.panelShown.collectAsState()
        key("player-panels") {
            PlayerPanels(
                panelShown = panel,
                onDismissRequest = { viewModel.showPanel(Panels.None) },
            )
        }

        val activity = LocalContext.current as PlayerActivity
        val dialog by viewModel.dialogShown.collectAsState()
        val anime by viewModel.currentAnime.collectAsState()
        val playlist by viewModel.currentPlaylist.collectAsState()

        PlayerDialogs(
            dialogShown = dialog,
            episodeDisplayMode = anime?.displayMode,
            episodeList = playlist,
            currentEpisodeIndex = viewModel.getCurrentEpisodeIndex(),
            dateRelativeTime = viewModel.relativeTime,
            dateFormat = viewModel.dateFormat,
            onBookmarkClicked = viewModel::bookmarkEpisode,
            onEpisodeClicked = {
                viewModel.showDialog(Dialogs.None)
                activity.changeEpisode(it)
            },
            onDismissRequest = { viewModel.showDialog(Dialogs.None) },
        )

        BrightnessOverlay(
            brightness = currentBrightness,
        )

        val advancedPlayerPreferences = remember { Injekt.get<AdvancedPlayerPreferences>() }
        val statsPage by advancedPlayerPreferences.playerStatisticsPage().collectAsState()
        if (statsPage == 6) {
            InterpolationStatsOverlay()
        }
    }

    if (showCastSheet) {
        CastSheet(
            castManager = castManager,
            viewModel = viewModel,
            onDismissRequest = { showCastSheet = false },
        )
    }
}

fun <T> playerControlsExitAnimationSpec(): FiniteAnimationSpec<T> = tween(
    durationMillis = 300,
    easing = FastOutSlowInEasing,
)

fun <T> playerControlsEnterAnimationSpec(): FiniteAnimationSpec<T> = tween(
    durationMillis = 100,
    easing = LinearOutSlowInEasing,
)
