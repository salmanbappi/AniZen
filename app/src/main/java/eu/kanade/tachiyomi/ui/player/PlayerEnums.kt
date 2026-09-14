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

package eu.kanade.tachiyomi.ui.player

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.Title
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.ui.graphics.vector.ImageVector
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.ui.player.settings.DecoderPreferences
import tachiyomi.core.common.preference.Preference
import tachiyomi.i18n.MR

/**
 * Results of the set as cover feature.
 */
enum class SetAsCover {
    Success,
    AddToLibraryFirst,
    Error,
}

enum class PlayerOrientation(val titleRes: StringResource) {
    Free(MR.strings.rotation_free),
    Video(MR.strings.rotation_video),
    Portrait(MR.strings.rotation_portrait),
    ReversePortrait(MR.strings.rotation_reverse_portrait),
    SensorPortrait(MR.strings.rotation_sensor_portrait),
    Landscape(MR.strings.rotation_landscape),
    ReverseLandscape(MR.strings.rotation_reverse_landscape),
    SensorLandscape(MR.strings.rotation_sensor_landscape),
}

enum class VideoAspect(val titleRes: StringResource) {
    Crop(MR.strings.video_crop_screen),
    Fit(MR.strings.video_fit_screen),
    Stretch(MR.strings.video_stretch_screen),
}

enum class PlayerEfficiency(val titleRes: StringResource) {
    Automatic(MR.strings.pref_performance_profile_automatic),
    MaxPerformance(MR.strings.pref_performance_profile_high),
    Balanced(MR.strings.pref_performance_profile_mid),
    PowerSaver(MR.strings.pref_performance_profile_low_power),
}

enum class PreloadState {
    None,
    MetadataLoading,
    MetadataReady,
    PreloadingBuffer,
    BufferReady,
    Failed,
    Unavailable,
}

enum class PreloadMode(val titleRes: StringResource) {
    Off(MR.strings.pref_preload_off),
    WifiOnly(MR.strings.pref_preload_wifi),
    Always(MR.strings.pref_preload_always),
}

/**
 * Action performed by a button, like double tap or media controls
 */
enum class SingleActionGesture(val stringRes: StringResource) {
    None(stringRes = MR.strings.single_action_none),
    Seek(stringRes = MR.strings.single_action_seek),
    PlayPause(stringRes = MR.strings.single_action_playpause),
    Switch(stringRes = MR.strings.single_action_switch),
    Custom(stringRes = MR.strings.single_action_custom),
}

enum class LongPressAction(val stringRes: StringResource) {
    None(stringRes = MR.strings.single_action_none),
    Speed(stringRes = MR.strings.player_sheets_speed_slider_label),
    Screenshot(stringRes = MR.strings.screenshot_header),
}

enum class PausedLongPressAction(val stringRes: StringResource) {
    DoNothing(stringRes = MR.strings.single_action_none),
    Play2x(stringRes = MR.strings.player_sheets_speed_slider_label),
    Screenshot(stringRes = MR.strings.screenshot_header),
}

/**
 * Key codes sent through the `Custom` option in gestures
 */
enum class CustomKeyCodes(val keyCode: String) {
    DoubleTapLeft("0x10001"),
    DoubleTapCenter("0x10002"),
    DoubleTapRight("0x10003"),
    MediaPrevious("0x10004"),
    MediaPlay("0x10005"),
    MediaNext("0x10006"),
}

enum class Decoder(val title: String, val value: String) {
    AutoCopy("Auto", "auto-copy"),
    Auto("Auto", "auto"),
    SW("SW", "no"),
    HW("HW", "mediacodec-copy"),
    HWPlus("HW+", "mediacodec"),
}

fun getDecoderFromValue(value: String?): Decoder {
    if (value == null) return Decoder.Auto
    return Decoder.entries.firstOrNull { it.value == value } ?: Decoder.Auto
}

enum class Debanding(
    val titleRes: StringResource,
) {
    None(MR.strings.pref_debanding_none),
    CPU(MR.strings.pref_debanding_cpu),
    GPU(MR.strings.pref_debanding_gpu),
}

enum class Sheets {
    None,
    PlaybackSpeed,
    SubtitleTracks,
    AudioTracks,
    QualityTracks,
    Chapters,
    VideoZoom,
    AspectRatios,
    More,
    Screenshot,
}

enum class Panels {
    None,
    SubtitleSettings,
    SubtitleDelay,
    AudioDelay,
    VideoFilters,
}

sealed class Dialogs {
    data object None : Dialogs()
    data object EpisodeList : Dialogs()
    data class IntegerPicker(
        val defaultValue: Int,
        val minValue: Int,
        val maxValue: Int,
        val step: Int,
        val nameFormat: String,
        val title: String,
        val onChange: (Int) -> Unit,
        val onDismissRequest: () -> Unit,
    ) : Dialogs()
}

sealed class PlayerUpdates {
    data object None : PlayerUpdates()
    data class DoubleSpeed(val speed: Float, val isDragging: Boolean = false) : PlayerUpdates()
    data object AspectRatio : PlayerUpdates()
    data class ShowText(val value: String) : PlayerUpdates()
    data class ShowTextResource(val textResource: StringResource) : PlayerUpdates()
    data class VideoZoom(val zoom: Float) : PlayerUpdates()
}

enum class DebandSettings(
    val titleRes: StringResource,
    val preference: (DecoderPreferences) -> Preference<Int>,
    val mpvProperty: String,
    val start: Int,
    val end: Int,
) {
    ITERATIONS(
        MR.strings.pref_debanding_title,
        { it.debandFilter() },
        "deband-iterations",
        start = 0,
        end = 16,
    ),
    THRESHOLD(
        MR.strings.player_sheets_deband_threshold,
        { it.debandThreshold() },
        "deband-threshold",
        start = 0,
        end = 200,
    ),
    RANGE(
        MR.strings.player_sheets_deband_range,
        { it.debandRange() },
        "deband-range",
        start = 1,
        end = 64,
    ),
    GRAIN(
        MR.strings.player_sheets_filters_grain,
        { it.grainFilter() },
        "deband-grain",
        start = 0,
        end = 200,
    ),
}

enum class VideoFilters(
    val titleRes: StringResource,
    val preference: (DecoderPreferences) -> Preference<Int>,
    val mpvProperty: String,
    val min: Int = -100,
    val max: Int = 100,
) {
    BRIGHTNESS(
        MR.strings.player_sheets_filters_brightness,
        { it.brightnessFilter() },
        "brightness",
    ),
    SATURATION(
        MR.strings.player_sheets_filters_Saturation,
        { it.saturationFilter() },
        "saturation",
    ),
    CONTRAST(
        MR.strings.player_sheets_filters_contrast,
        { it.contrastFilter() },
        "contrast",
    ),
    GAMMA(
        MR.strings.player_sheets_filters_gamma,
        { it.gammaFilter() },
        "gamma",
    ),
    HUE(
        MR.strings.player_sheets_filters_hue,
        { it.hueFilter() },
        "hue",
    ),
    SHARPEN(
        MR.strings.player_sheets_filters_sharpen,
        { it.sharpenFilter() },
        "sharpen",
        min = -5,
        max = 5,
    ),
}

enum class VideoFilterTheme(
    val titleRes: StringResource,
    val description: String = "",
    val brightness: Int = 0,
    val contrast: Int = 0,
    val saturation: Int = 0,
    val gamma: Int = 0,
    val hue: Int = 0,
    val sharpen: Int = 0,
) {
    Default(
        MR.strings.player_sheets_filters_theme_default,
        description = "No filters applied.",
    ),
    Anime(
        MR.strings.player_sheets_filters_theme_anime,
        description = "Vivid colors and sharper edges, best for modern anime.",
        contrast = 5,
        saturation = 20,
        sharpen = 1,
    ),
    Cinema(
        MR.strings.player_sheets_filters_theme_cinema,
        description = "Movie-like experience with higher contrast and lower saturation.",
        brightness = -5,
        contrast = 15,
        saturation = -10,
        gamma = -5,
    ),
    Warm(
        MR.strings.player_sheets_filters_theme_warm,
        description = "Warmer color temperature for a cozy feel.",
        hue = -5,
        saturation = 5,
    ),
    Cold(
        MR.strings.player_sheets_filters_theme_cold,
        description = "Cooler color temperature with slightly reduced saturation.",
        hue = 5,
        saturation = -5,
    ),
    Night(
        MR.strings.player_sheets_filters_theme_night,
        description = "Comfortable night viewing by reducing brightness and contrast.",
        brightness = -20,
        contrast = -10,
        gamma = -10,
    ),
    Grayscale(
        MR.strings.player_sheets_filters_theme_grayscale,
        description = "Classic black and white mode.",
        saturation = -100,
    ),
    Vibrant(
        MR.strings.player_sheets_filters_theme_vibrant,
        description = "Boosts colors for a more lively image.",
        contrast = 10,
        saturation = 30,
    ),
    Vintage(
        MR.strings.player_sheets_filters_theme_vintage,
        description = "A nostalgic look with faded colors.",
        contrast = 10,
        saturation = -30,
        gamma = -10,
        hue = -5,
    ),
    HighContrast(
        MR.strings.player_sheets_filters_theme_high_contrast,
        description = "Sharper difference between light and dark areas.",
        brightness = -10,
        contrast = 30,
    ),
}

enum class PlayerButton(
    val titleRes: StringResource,
) {
    BackArrow(MR.strings.player_button_back_arrow),
    VideoTitle(MR.strings.player_button_video_title),
    AutoPlay(MR.strings.player_button_autoplay),
    Cast(MR.strings.player_button_cast),
    SubtitleTracks(MR.strings.player_button_subtitle_tracks),
    AudioTracks(MR.strings.player_button_audio_tracks),
    QualityTracks(MR.strings.player_button_quality_tracks),
    MoreOptions(MR.strings.player_button_more_options),
    PlaybackSpeed(MR.strings.player_button_playback_speed),
    CurrentChapter(MR.strings.player_button_current_chapter),
    LockControls(MR.strings.player_button_lock_controls),
    ScreenRotation(MR.strings.player_button_screen_rotation),
    PictureInPicture(MR.strings.player_button_picture_in_picture),
    AspectRatio(MR.strings.player_button_aspect_ratio),
    VideoZoom(MR.strings.player_button_video_zoom),
    SkipIntro(MR.strings.player_button_skip_intro),
    CustomButton(MR.strings.player_button_custom_button),
}

fun PlayerButton.getIcon(): ImageVector = when (this) {
    PlayerButton.BackArrow -> Icons.AutoMirrored.Outlined.ArrowBack
    PlayerButton.VideoTitle -> Icons.Outlined.Title
    PlayerButton.AutoPlay -> Icons.Outlined.PlayCircle
    PlayerButton.Cast -> Icons.Outlined.Cast
    PlayerButton.SubtitleTracks -> Icons.Outlined.Subtitles
    PlayerButton.AudioTracks -> Icons.Outlined.Audiotrack
    PlayerButton.QualityTracks -> Icons.Outlined.HighQuality
    PlayerButton.MoreOptions -> Icons.Outlined.MoreVert
    PlayerButton.PlaybackSpeed -> Icons.Outlined.Speed
    PlayerButton.CurrentChapter -> Icons.Outlined.Bookmarks
    PlayerButton.LockControls -> Icons.Outlined.LockOpen
    PlayerButton.ScreenRotation -> Icons.Outlined.ScreenRotation
    PlayerButton.PictureInPicture -> Icons.Outlined.PictureInPictureAlt
    PlayerButton.AspectRatio -> Icons.Outlined.AspectRatio
    PlayerButton.VideoZoom -> Icons.Outlined.ZoomIn
    PlayerButton.SkipIntro -> Icons.Outlined.FastForward
    PlayerButton.CustomButton -> Icons.Outlined.TouchApp
}

val allPlayerButtons = PlayerButton.entries.filter {
    it != PlayerButton.BackArrow && it != PlayerButton.VideoTitle
}

enum class LayoutRegion(val titleRes: StringResource) {
    TopRight(MR.strings.pref_player_layout_landscape_top_right),
    BottomLeft(MR.strings.pref_player_layout_landscape_bottom_left),
    BottomRight(MR.strings.pref_player_layout_landscape_bottom_right),
    Portrait(MR.strings.pref_player_layout_portrait_bottom),
}
