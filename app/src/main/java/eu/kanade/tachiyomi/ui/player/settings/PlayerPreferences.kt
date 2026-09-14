package eu.kanade.tachiyomi.ui.player.settings

import eu.kanade.tachiyomi.ui.player.PlayerOrientation
import eu.kanade.tachiyomi.ui.player.PreloadMode
import eu.kanade.tachiyomi.ui.player.VideoAspect
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum

class PlayerPreferences(
    private val preferenceStore: PreferenceStore,
) {
    fun preserveWatchingPosition() = preferenceStore.getBoolean(
        "pref_preserve_watching_position",
        false,
    )
    fun progressPreference() = preferenceStore.getFloat("pref_progress_preference", 0.85F)
    fun defaultPlayerOrientationType() = preferenceStore.getEnum(
        "pref_default_player_orientation_type_key",
        PlayerOrientation.SensorLandscape,
    )

    fun preferredQuality() = preferenceStore.getString("pref_preferred_quality", "1080")
    fun defaultStreamSelector() = preferenceStore.getString("pref_default_stream_selector", "")

    /** Master switch: save/load default stream per anime (tap in Qualities). */
    fun perAnimeDefaultStream() = preferenceStore.getBoolean("pref_per_anime_default_stream", true)

    /** Scroll the Qualities list to the saved default when the sheet opens. */
    fun autoScrollDefaultStream() = preferenceStore.getBoolean("pref_auto_scroll_default_stream", true)

    /** Show bordered highlight on the saved default row in Qualities. */
    fun showDefaultStreamHighlight() = preferenceStore.getBoolean("pref_show_default_stream_highlight", true)

    /** Encoded map of anime id → stream fingerprint. */
    fun perAnimeDefaultStreamData() = preferenceStore.getString("pref_per_anime_default_stream_data", "")

    // Controls

    fun allowGestures() = preferenceStore.getBoolean("pref_allow_gestures_in_panels", false)
    fun showLoadingCircle() = preferenceStore.getBoolean("pref_show_loading", true)
    fun showCurrentChapter() = preferenceStore.getBoolean("pref_show_current_chapter", true)
    fun rememberPlayerBrightness() = preferenceStore.getBoolean("pref_remember_brightness", false)
    fun playerBrightnessValue() = preferenceStore.getFloat("player_brightness_value", -1.0F)
    fun rememberPlayerVolume() = preferenceStore.getBoolean("pref_remember_volume", false)
    fun playerVolumeValue() = preferenceStore.getFloat("player_volume_value", -1.0F)

    // Hoster

    fun showFailedHosters() = preferenceStore.getBoolean("pref_show_failed_hosters", false)
    fun showEmptyHosters() = preferenceStore.getBoolean("pref_show_empty_hosters", false)

    // Display

    fun playerFullscreen() = preferenceStore.getBoolean("player_fullscreen", true)
    fun hideControls() = preferenceStore.getBoolean("player_hide_controls", false)
    fun displayVolPer() = preferenceStore.getBoolean("pref_display_vol_as_per", true)
    fun showSystemStatusBar() = preferenceStore.getBoolean("pref_show_system_status_bar", false)
    fun reduceMotion() = preferenceStore.getBoolean("pref_reduce_motion", false)
    fun playerTimeToDisappear() = preferenceStore.getInt("pref_player_time_to_disappear", 4000)
    fun panelOpacity() = preferenceStore.getInt("pref_panel_opacity", 60)

    fun showDoubleTapOvals() = preferenceStore.getBoolean("pref_show_double_tap_ovals", true)
    fun showSeekIcon() = preferenceStore.getBoolean("pref_show_seek_icon", true)
    fun showSeekTimeWhileSeeking() = preferenceStore.getBoolean("pref_show_seek_time_while_seeking", true)

    // Skip intro button

    fun enableSkipIntro() = preferenceStore.getBoolean("pref_enable_skip_intro", true)
    fun autoSkipIntro() = preferenceStore.getBoolean("pref_enable_auto_skip_ani_skip", false)
    fun enableNetflixStyleIntroSkip() = preferenceStore.getBoolean(
        "pref_enable_netflixStyle_aniskip",
        false,
    )
    fun waitingTimeIntroSkip() = preferenceStore.getInt("pref_waiting_time_aniskip", 5)
    fun aniSkipEnabled() = preferenceStore.getBoolean("pref_enable_ani_skip", false)
    fun disableAniSkipOnChapters() = preferenceStore.getBoolean("pref_disabled_ani_skip_chapters", true)

    // Filler skip

    fun skipFillerEpisodes() = preferenceStore.getBoolean("pref_skip_filler_episodes", false)

    // PiP

    fun enablePip() = preferenceStore.getBoolean("pref_enable_pip", true)
    fun pipEpisodeToasts() = preferenceStore.getBoolean("pref_pip_episode_toasts", true)
    fun pipOnExit() = preferenceStore.getBoolean("pref_pip_on_exit", false)
    fun pipReplaceWithPrevious() = preferenceStore.getBoolean("pip_replace_with_previous", false)

    // External player

    fun enableCast() = preferenceStore.getBoolean("pref_enable_cast", false)

    fun alwaysUseExternalPlayer() = preferenceStore.getBoolean(
        "pref_always_use_external_player",
        false,
    )
    fun externalPlayerPreference() = preferenceStore.getString("external_player_preference", "")

    fun useExternalDownloader() = preferenceStore.getBoolean("pref_use_external_downloader", false)

    // Non-preferences

    fun playerSpeed() = preferenceStore.getFloat("pref_player_speed", 1f)
    fun playerSpeedLongPress() = preferenceStore.getFloat("pref_player_speed_long_press", 2f)
    fun speedPresets() = preferenceStore.getStringSet(
        "default_speed_presets",
        setOf("0.25", "0.5", "0.75", "1.0", "1.25", "1.5", "1.75", "2.0", "2.5", "3.0", "3.5", "4.0"),
    )
    fun longPressSpeedPresets() = preferenceStore.getStringSet(
        "default_long_press_speed_presets",
        setOf("0.5", "1.0", "1.5", "2.0", "2.5", "3.0", "3.5", "4.0"),
    )
    fun invertDuration() = preferenceStore.getBoolean("invert_duration", false)
    fun aspectState() = preferenceStore.getEnum("pref_player_aspect_state", VideoAspect.Fit)

    fun rememberAspectRatio() = preferenceStore.getBoolean("pref_remember_aspect_ratio", false)

    fun lastAspectRatio() = preferenceStore.getFloat("pref_player_last_aspect_ratio", -1f)

    fun lastAspectRatioAnimeId() = preferenceStore.getLong("pref_last_aspect_ratio_anime_id", -1L)

    fun customAspectRatios() = preferenceStore.getStringSet("pref_player_custom_aspect_ratios", emptySet())

    // Old

    fun autoplayEnabled() = preferenceStore.getBoolean("pref_auto_play_enabled", false)

    fun switchOnFailure() = preferenceStore.getBoolean("pref_switch_on_failure", true)

    fun preloadMode() = preferenceStore.getEnum("pref_preload_mode_key", PreloadMode.WifiOnly)
    fun selfHealingLinks() = preferenceStore.getBoolean("pref_self_healing_links", true)
    fun intelligentBufferHandoff() = preferenceStore.getBoolean("pref_intelligent_buffer_handoff", false)
    fun networkAwareThrottling() = preferenceStore.getBoolean("pref_network_aware_throttling", true)

    // Layout

    fun topLeftControls() = preferenceStore.getString(
        "pref_top_left_controls",
        "BackArrow,VideoTitle",
    )
    fun topRightControls() = preferenceStore.getString(
        "pref_top_right_controls",
        "AutoPlay,Cast,SubtitleTracks,AudioTracks,QualityTracks,MoreOptions",
    )
    fun bottomLeftControls() = preferenceStore.getString(
        "pref_bottom_left_controls",
        "LockControls,ScreenRotation,PlaybackSpeed,CurrentChapter",
    )
    fun bottomRightControls() = preferenceStore.getString(
        "pref_bottom_right_controls",
        "SkipIntro,CustomButton,PictureInPicture,VideoZoom,AspectRatio",
    )
    fun portraitBottomControls() = preferenceStore.getString(
        "pref_portrait_bottom_controls",
        "ScreenRotation,AudioTracks,SubtitleTracks,CurrentChapter,PlaybackSpeed,VideoZoom,SkipIntro,AspectRatio,PictureInPicture,LockControls,MoreOptions",
    )
}
