package eu.kanade.tachiyomi.ui.player.settings

import eu.kanade.tachiyomi.ui.player.LongPressAction
import eu.kanade.tachiyomi.ui.player.PausedLongPressAction
import eu.kanade.tachiyomi.ui.player.SingleActionGesture
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum

class GesturePreferences(
    private val preferenceStore: PreferenceStore,
) {
    // Sliders
    fun gestureVolumeBrightness() = preferenceStore.getBoolean(
        "pref_gesture_volume_brightness",
        true,
    )
    fun swapVolumeBrightness() = preferenceStore.getBoolean("pref_swap_volume_and_brightness", false)

    // Seeking

    fun gestureHorizontalSeek() = preferenceStore.getBoolean("pref_gesture_horizontal_seek", true)
    fun gestureVideoZoom() = preferenceStore.getBoolean("pref_gesture_video_zoom", false)
    fun showSeekBar() = preferenceStore.getBoolean("pref_show_seekbar", false)
    fun defaultIntroLength() = preferenceStore.getInt("pref_default_intro_length", 85)
    fun skipLengthPreference() = preferenceStore.getInt("pref_skip_length_preference", 10)
    fun playerSmoothSeek() = preferenceStore.getBoolean("pref_player_smooth_seek", false)

    // Double tap

    fun leftDoubleTapGesture() = preferenceStore.getEnum("pref_left_double_tap", SingleActionGesture.Seek)
    fun centerDoubleTapGesture() = preferenceStore.getEnum("pref_center_double_tap", SingleActionGesture.PlayPause)
    fun rightDoubleTapGesture() = preferenceStore.getEnum("pref_right_double_tap", SingleActionGesture.Seek)

    // Long press

    fun longPressAction() = preferenceStore.getEnum("pref_long_press_action", LongPressAction.Speed)
    fun pausedLongPressAction() = preferenceStore.getEnum("pref_paused_long_press_action", PausedLongPressAction.DoNothing)
    fun gestureLongPressSpeedSliding() = preferenceStore.getBoolean("pref_gesture_long_press_speed_sliding", true)

    // Media controls

    fun mediaPreviousGesture() = preferenceStore.getEnum("pref_media_previous", SingleActionGesture.Switch)
    fun mediaPlayPauseGesture() = preferenceStore.getEnum("pref_media_playpause", SingleActionGesture.PlayPause)
    fun mediaNextGesture() = preferenceStore.getEnum("pref_media_next", SingleActionGesture.Switch)
}
