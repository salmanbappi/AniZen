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

import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeGestures
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.util.fastAll
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastForEach
import eu.kanade.presentation.player.components.LeftSideOvalShape
import eu.kanade.presentation.player.components.RightSideOvalShape
import eu.kanade.presentation.theme.playerRippleConfiguration
import eu.kanade.tachiyomi.ui.player.LongPressAction
import eu.kanade.tachiyomi.ui.player.Panels
import eu.kanade.tachiyomi.ui.player.PausedLongPressAction
import eu.kanade.tachiyomi.ui.player.PlayerUpdates
import eu.kanade.tachiyomi.ui.player.PlayerViewModel
import eu.kanade.tachiyomi.ui.player.Sheets
import eu.kanade.tachiyomi.ui.player.videoDisplaySize
import eu.kanade.tachiyomi.ui.player.settings.AudioPreferences
import eu.kanade.tachiyomi.ui.player.settings.GesturePreferences
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import `is`.xyz.mpv.MPVLib
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.util.collectAsState as collectAsStatePref
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

@Composable
fun GestureHandler(
    viewModel: PlayerViewModel,
    interactionSource: MutableInteractionSource,
    modifier: Modifier = Modifier,
) {
    val playerPreferences = remember { Injekt.get<PlayerPreferences>() }
    val gesturePreferences = remember { Injekt.get<GesturePreferences>() }
    val audioPreferences = remember { Injekt.get<AudioPreferences>() }

    val panelShown by viewModel.panelShown.collectAsState()
    val allowGesturesInPanels by playerPreferences.allowGestures().collectAsStatePref()
    val duration by viewModel.duration.collectAsState()
    val position by viewModel.pos.collectAsState()
    val controlsShown by viewModel.controlsShown.collectAsState()
    val areControlsLocked by viewModel.areControlsLocked.collectAsState()
    val seekAmount by viewModel.doubleTapSeekAmount.collectAsState()
    val isSeekingForwards by viewModel.isSeekingForwards.collectAsState()
    var isDoubleTapSeeking by remember { mutableStateOf(false) }

    LaunchedEffect(seekAmount) {
        delay(800)
        isDoubleTapSeeking = false
        viewModel.updateSeekAmount(0)
        viewModel.updateSeekText(null)
        delay(100)
        viewModel.hideSeekBar()
    }

    val gestureVolumeBrightness = gesturePreferences.gestureVolumeBrightness().get()
    val swapVolumeBrightness by gesturePreferences.swapVolumeBrightness().collectAsStatePref()
    val seekGesture by gesturePreferences.gestureHorizontalSeek().collectAsStatePref()
    val videoZoomGesture by gesturePreferences.gestureVideoZoom().collectAsStatePref()
    val preciseSeeking by gesturePreferences.playerSmoothSeek().collectAsStatePref()
    val showSeekbar by gesturePreferences.showSeekBar().collectAsStatePref()
    
    val longPressAction by gesturePreferences.longPressAction().collectAsStatePref()
    val pausedLongPressAction by gesturePreferences.pausedLongPressAction().collectAsStatePref()
    val longPressSliding by gesturePreferences.gestureLongPressSpeedSliding().collectAsStatePref()

    val currentVolume by viewModel.currentVolume.collectAsState()
    val currentMPVVolume by viewModel.currentMPVVolume.collectAsState()
    val currentBrightness by viewModel.currentBrightness.collectAsState()
    val volumeBoostingCap = audioPreferences.volumeBoostCap().get()

    val context = LocalContext.current
    val isTv = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    
    var speedRampJob by remember { mutableStateOf<Job?>(null) }
    var longPressJob by remember { mutableStateOf<Job?>(null) }
    var originalSpeed by remember { mutableFloatStateOf(1f) }
    var wasPaused by remember { mutableStateOf(false) }
    var isSpeedLongPress by remember { mutableStateOf(false) }

    fun rampSpeed(targetSpeed: Float, onComplete: () -> Unit = {}) {
        speedRampJob?.cancel()
        speedRampJob = scope.launch {
            var currentSpeed = (MPVLib.getPropertyDouble("speed") ?: 1.0).toFloat()
            val step = if (targetSpeed > currentSpeed) 0.1f else -0.1f
            
            while (if (step > 0) currentSpeed < targetSpeed else currentSpeed > targetSpeed) {
                currentSpeed += step
                if (step > 0 && currentSpeed > targetSpeed) currentSpeed = targetSpeed
                if (step < 0 && currentSpeed < targetSpeed) currentSpeed = targetSpeed
                
                MPVLib.setPropertyDouble("speed", currentSpeed.toDouble())
                viewModel.playerUpdate.update { PlayerUpdates.DoubleSpeed(currentSpeed, false) }
                delay(16)
            }
            MPVLib.setPropertyDouble("speed", targetSpeed.toDouble())
            viewModel.playerUpdate.update { PlayerUpdates.DoubleSpeed(targetSpeed, false) }
            onComplete()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeGestures)
            .pointerInput(areControlsLocked) {
                if (areControlsLocked || !videoZoomGesture) return@pointerInput
                awaitEachGesture {
                    var zoom = viewModel.videoZoom.value
                    var panX = viewModel.videoPanX.value
                    var panY = viewModel.videoPanY.value
                    var smoothPanX = panX
                    var smoothPanY = panY
                    var prevDist = 0f
                    var prevMidX = 0f
                    var prevMidY = 0f

                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.fastAny { it.isConsumed } || viewModel.isLongPressing.value) break

                        val currentZoom = viewModel.videoZoom.value
                        if (abs(zoom - currentZoom) > 0.001f && event.changes.size <= 1) {
                            zoom = currentZoom
                            panX = viewModel.videoPanX.value
                            panY = viewModel.videoPanY.value
                            smoothPanX = panX
                            smoothPanY = panY
                            prevDist = 0f
                        }

                        if (event.changes.size > 1) {
                            val zoomChanges = event.changes
                            val p1 = zoomChanges[0].position
                            val p2 = zoomChanges[1].position
                            val dx = p2.x - p1.x
                            val dy = p2.y - p1.y
                            val dist = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                            val midX = (p1.x + p2.x) / 2f
                            val midY = (p1.y + p2.y) / 2f

                            if (prevDist == 0f) {
                                prevDist = dist
                                prevMidX = midX
                                prevMidY = midY
                            } else {
                                if (prevDist > 0f && dist > 0f) {
                                    val zoomDelta = ln((dist / prevDist).toDouble()).toFloat() * 1.2f
                                    zoom = (zoom + zoomDelta).coerceIn(-1f, 3f)
                                    viewModel.setVideoZoom(zoom)
                                    viewModel.playerUpdate.update { PlayerUpdates.VideoZoom(zoom) }

                                    val scale = 2f.pow(zoom)
                                    val (bw, bh) = videoDisplaySize(size)
                                    val panDX = midX - prevMidX
                                    val panDY = midY - prevMidY
                                    val targetPanX = panX + panDX / (bw * scale)
                                    val targetPanY = panY + panDY / (bh * scale)
                                    smoothPanX += (targetPanX - smoothPanX) * 0.5f
                                    smoothPanY += (targetPanY - smoothPanY) * 0.5f
                                    val maxPan = ((scale - 1f) / (2f * scale)).coerceAtLeast(0f)
                                    panX = smoothPanX.coerceIn(-maxPan, maxPan)
                                    panY = smoothPanY.coerceIn(-maxPan, maxPan)
                                    viewModel.setVideoPan(panX, panY)
                                }
                                prevDist = dist
                                prevMidX = midX
                                prevMidY = midY
                            }
                            event.changes.fastForEach { it.consume() }
                        } else {
                            prevDist = 0f
                        }
                        if (event.changes.fastAll { it.changedToUp() }) break
                    }
                }
            }
            .pointerInput(areControlsLocked, longPressAction, pausedLongPressAction, longPressSliding) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = true)
                    val startPosition = down.position
                    originalSpeed = viewModel.playbackSpeed.value
                    wasPaused = false
                    isSpeedLongPress = false
                    
                    val press = PressInteraction.Press(
                        down.position.copy(x = if (down.position.x > size.width * 3 / 5) down.position.x - size.width * 0.6f else down.position.x),
                    )
                    scope.launch { interactionSource.emit(press) }

                    // Hyper-Lane Cumulative Seek
                    if (!areControlsLocked && isDoubleTapSeeking) {
                        if (down.position.x > size.width * 3 / 5) {
                            if (!isSeekingForwards) viewModel.updateSeekAmount(0)
                            viewModel.handleRightDoubleTap()
                        } else if (down.position.x < size.width * 2 / 5) {
                            if (isSeekingForwards) viewModel.updateSeekAmount(0)
                            viewModel.handleLeftDoubleTap()
                        } else {
                            viewModel.handleCenterDoubleTap()
                        }
                    }

                    try {
                        longPressJob?.cancel()
                        longPressJob = scope.launch {
                            delay(viewConfiguration.longPressTimeoutMillis)
                            val isPaused = viewModel.paused.value
                            wasPaused = isPaused
                            if (isPaused) {
                                when (pausedLongPressAction) {
                                    PausedLongPressAction.Screenshot -> viewModel.sheetShown.update { Sheets.Screenshot }
                                    PausedLongPressAction.Play2x -> {
                                        viewModel.isLongPressing.update { true }
                                        isSpeedLongPress = true
                                        viewModel.unpause()
                                        originalSpeed = (MPVLib.getPropertyDouble("speed") ?: 1.0).toFloat()
                                        rampSpeed(playerPreferences.playerSpeedLongPress().get())
                                    }
                                    else -> {}
                                }
                            } else {
                                if (longPressAction == LongPressAction.Speed) {
                                    viewModel.isLongPressing.update { true }
                                    isSpeedLongPress = true
                                    originalSpeed = (MPVLib.getPropertyDouble("speed") ?: 1.0).toFloat()
                                    rampSpeed(playerPreferences.playerSpeedLongPress().get())
                                } else if (longPressAction == LongPressAction.Screenshot) {
                                    viewModel.sheetShown.update { Sheets.Screenshot }
                                }
                            }
                        }

                        var up: androidx.compose.ui.input.pointer.PointerInputChange? = null
                        var lastX = down.position.x
                        var unsnappedCurrentSpeed = originalSpeed.toDouble()
                        var hasInitializedDragSpeed = false
                        
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.fastAny { it.isConsumed } && !viewModel.isLongPressing.value) {
                                longPressJob?.cancel()
                                break
                            }
                            if (event.changes.size > 1) {
                                longPressJob?.cancel()
                                break
                            }
                            val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (pointer.changedToUp()) {
                                up = pointer
                                break
                            }
                            
                            val distance = (pointer.position - startPosition).getDistance()
                            if (!viewModel.isLongPressing.value) {
                                if (distance > viewConfiguration.touchSlop * 1.5f) {
                                    longPressJob?.cancel()
                                    if (abs(pointer.position.y - startPosition.y) > abs(pointer.position.x - startPosition.x)) break
                                }
                            } else {
                                pointer.consume()
                                if (longPressSliding && isSpeedLongPress && !viewModel.paused.value) {
                                    if (!hasInitializedDragSpeed) {
                                        unsnappedCurrentSpeed = MPVLib.getPropertyDouble("speed") ?: 1.0
                                        hasInitializedDragSpeed = true
                                    }
                                    val diffX = pointer.position.x - lastX
                                    if (abs(diffX) > 1f) {
                                        unsnappedCurrentSpeed = (unsnappedCurrentSpeed + diffX * 0.0035).coerceIn(0.25, 4.0)                                        
                                        val snappedSpeed = (Math.round(unsnappedCurrentSpeed * 2.0) / 2.0).toFloat().coerceIn(0.5f, 4.0f)
                                        speedRampJob?.cancel() 
                                        MPVLib.setPropertyDouble("speed", snappedSpeed.toDouble())
                                        viewModel.playerUpdate.update { PlayerUpdates.DoubleSpeed(snappedSpeed, isDragging = true) }
                                        lastX = pointer.position.x
                                    }
                                }
                            }
                        }

                        longPressJob?.cancel()
                        if (viewModel.isLongPressing.value) {
                            val wasPausedOriginally = wasPaused
                            viewModel.isLongPressing.update { false }
                            isSpeedLongPress = false
                            rampSpeed(originalSpeed) {
                                if (wasPausedOriginally) viewModel.pause()
                                viewModel.playerUpdate.update { PlayerUpdates.None }
                            }
                        } else if (up != null) {
                            val secondDown = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
                                awaitFirstDown(requireUnconsumed = true)
                            }
                            if (secondDown == null) {
                                if (!isDoubleTapSeeking && viewModel.doubleTapSeekAmount.value == 0) {
                                    if (controlsShown) viewModel.hideControls() else viewModel.showControls()
                                }
                            } else {
                                if (secondDown.position.x > size.width * 3 / 5) {
                                    if (!isSeekingForwards) viewModel.updateSeekAmount(0)
                                    viewModel.handleRightDoubleTap()
                                    isDoubleTapSeeking = true
                                } else if (secondDown.position.x < size.width * 2 / 5) {
                                    if (isSeekingForwards) viewModel.updateSeekAmount(0)
                                    viewModel.handleLeftDoubleTap()
                                    isDoubleTapSeeking = true
                                } else {
                                    viewModel.handleCenterDoubleTap()
                                }
                            }
                        }
                        scope.launch { interactionSource.emit(PressInteraction.Release(press)) }
                    } catch (e: Exception) {
                        longPressJob?.cancel()
                        if (viewModel.isLongPressing.value) {
                            viewModel.isLongPressing.update { false }
                            isSpeedLongPress = false
                            rampSpeed(originalSpeed)
                        }
                        scope.launch { interactionSource.emit(PressInteraction.Cancel(press)) }
                    }
                }
            }
            .pointerInput(areControlsLocked, gestureVolumeBrightness, seekGesture) {
                if (areControlsLocked) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = true)
                    var startingPosition = position.toInt()
                    var startingX = down.position.x
                    var startingY = down.position.y
                    var wasPlayerAlreadyPause = false
                    var dragDirection: Int = 0
                    var initialVolumePercent = 0f
                    var originalBrightness = viewModel.currentBrightness.value
                    val brightnessGestureSens = 0.001f
                    val volumeGestureSens = 0.08f
                    
                    while (true) {
                        val event = awaitPointerEvent()
                        if ((event.changes.fastAny { it.isConsumed } || viewModel.isLongPressing.value) && dragDirection == 0) break
                        if (event.changes.size > 1) {
                            if (dragDirection == 1) {
                                viewModel.gestureSeekAmount.update { null }
                                viewModel.hideSeekBar()
                                if (!wasPlayerAlreadyPause) viewModel.unpause()
                            }
                            break
                        }
                        val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (pointer.changedToUp()) {
                            if (dragDirection == 1) {
                                viewModel.gestureSeekAmount.update { null }
                                viewModel.hideSeekBar()
                                if (!wasPlayerAlreadyPause) viewModel.unpause()
                            }
                            break
                        }
                        if (dragDirection == 0) {
                            val diffX = abs(pointer.position.x - down.position.x)
                            val diffY = abs(pointer.position.y - down.position.y)
                            if (diffX > viewConfiguration.touchSlop * 1.5f || diffY > viewConfiguration.touchSlop * 1.5f) {
                                if (diffX > diffY && seekGesture) {
                                    dragDirection = 1
                                    startingPosition = position.toInt()
                                    startingX = pointer.position.x
                                    wasPlayerAlreadyPause = viewModel.paused.value
                                    viewModel.pause()
                                } else if (diffY > diffX && gestureVolumeBrightness) {
                                    dragDirection = 2
                                    startingY = pointer.position.y
                                    initialVolumePercent = if (viewModel.currentMPVVolume.value > 100) viewModel.currentMPVVolume.value.toFloat()
                                    else viewModel.currentVolume.value.toFloat() / viewModel.maxVolume * 100f
                                    originalBrightness = viewModel.currentBrightness.value
                                } else break
                            }
                        } else if (dragDirection == 1) {
                            calculateNewHorizontalGestureValue(startingPosition.toFloat(), startingX, pointer.position.x, 0.15f).let {
                                viewModel.gestureSeekAmount.update { _ -> Pair(startingPosition, (it - startingPosition).toInt().coerceIn(0 - startingPosition, (duration - startingPosition).toInt())) }
                                viewModel.seekTo(it.toInt().coerceIn(0, duration.toInt()), preciseSeeking)
                            }
                            if (showSeekbar) viewModel.showSeekBar()
                            pointer.consume()
                        } else if (dragDirection == 2) {
                            if (swapVolumeBrightness) {
                                if (pointer.position.x > size.width / 2) {
                                    viewModel.changeBrightnessTo(calculateNewVerticalGestureValue(originalBrightness, startingY, pointer.position.y, brightnessGestureSens))
                                    viewModel.displayBrightnessSlider()
                                } else {
                                    viewModel.setVolume(initialVolumePercent + (startingY - pointer.position.y) * volumeGestureSens)
                                    viewModel.displayVolumeSlider()
                                }
                            } else {
                                if (pointer.position.x < size.width / 2) {
                                    viewModel.changeBrightnessTo(calculateNewVerticalGestureValue(originalBrightness, startingY, pointer.position.y, brightnessGestureSens))
                                    viewModel.displayBrightnessSlider()
                                } else {
                                    viewModel.setVolume(initialVolumePercent + (startingY - pointer.position.y) * volumeGestureSens)
                                    viewModel.displayVolumeSlider()
                                }
                            }
                            pointer.consume()
                        }
                    }
                }
            }
    ) {}
}

fun calculateNewVerticalGestureValue(originalValue: Int, startingY: Float, newY: Float, sensitivity: Float): Int {
    return originalValue + ((startingY - newY) * sensitivity).toInt()
}

fun calculateNewVerticalGestureValue(originalValue: Float, startingY: Float, newY: Float, sensitivity: Float): Float {
    return originalValue + ((startingY - newY) * sensitivity)
}

fun calculateNewHorizontalGestureValue(originalValue: Int, startingX: Float, newX: Float, sensitivity: Float): Int {
    return originalValue + ((newX - startingX) * sensitivity).toInt()
}

fun calculateNewHorizontalGestureValue(originalValue: Float, startingX: Float, newX: Float, sensitivity: Float): Float {
    return originalValue + ((newX - startingX) * sensitivity)
}
