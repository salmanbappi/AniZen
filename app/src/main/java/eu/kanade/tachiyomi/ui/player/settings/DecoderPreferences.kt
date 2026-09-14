package eu.kanade.tachiyomi.ui.player.settings

import eu.kanade.tachiyomi.ui.player.Debanding
import eu.kanade.tachiyomi.ui.player.PlayerEfficiency
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum

class DecoderPreferences(
    private val preferenceStore: PreferenceStore,
) {
    fun performanceProfile() = preferenceStore.getEnum("pref_performance_profile", PlayerEfficiency.Automatic)
    fun tryHWDecoding() = preferenceStore.getBoolean("pref_try_hwdec", true)
    fun gpuNext() = preferenceStore.getBoolean("pref_gpu_next", false)
    fun videoDebanding() = preferenceStore.getEnum("pref_video_debanding", Debanding.None)
    fun useYUV420P() = preferenceStore.getBoolean("use_yuv420p", true)
    fun highQualityScaling() = preferenceStore.getBoolean("pref_high_quality_scaling", false)
    fun smoothMotion() = preferenceStore.getBoolean("pref_smooth_motion", false)
    fun interpolationMode() = preferenceStore.getEnum("pref_interpolation_mode", InterpolationMode.Oversample)
    fun interpolationFPSLimit() = preferenceStore.getInt("pref_interpolation_fps_limit_int", 0)
    fun adaptiveShaderScaling() = preferenceStore.getBoolean("pref_adaptive_shader_scaling", false)

    fun enableAnime4K() = preferenceStore.getBoolean("pref_enable_anime4k", false)
    fun anime4kMode() = preferenceStore.getString("pref_anime4k_mode", "OFF")
    fun anime4kQuality() = preferenceStore.getString("pref_anime4k_quality", "BALANCED")

    // Non-preferences

    fun brightnessFilter() = preferenceStore.getInt("pref_player_filter_brightness")
    fun saturationFilter() = preferenceStore.getInt("pref_player_filter_saturation")
    fun contrastFilter() = preferenceStore.getInt("pref_player_filter_contrast")
    fun gammaFilter() = preferenceStore.getInt("pref_player_filter_gamma")
    fun hueFilter() = preferenceStore.getInt("pref_player_filter_hue")
    fun sharpenFilter() = preferenceStore.getInt("pref_player_filter_sharpen")
    fun debandFilter() = preferenceStore.getInt("pref_player_filter_deband", 1)
    fun grainFilter() = preferenceStore.getInt("pref_player_filter_grain", 32)
    fun debandThreshold() = preferenceStore.getInt("pref_player_filter_deband_threshold", 48)
    fun debandRange() = preferenceStore.getInt("pref_player_filter_deband_range", 16)

    fun videoFilterTheme() = preferenceStore.getInt("pref_video_filter_theme", 0)
}

enum class InterpolationMode(val title: String, val value: String) {
    Linear("Fast (Low Power)", "linear"),
    Oversample("Balanced (Anime)", "oversample"),
    Mitchell("Smooth (Cinematic)", "mitchell"),
    CatmullRom("High Quality (Sharp)", "catmull_rom"),
}
