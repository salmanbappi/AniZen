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

import android.content.Context
import android.os.Build
import android.os.Environment
import android.view.Surface
import android.util.AttributeSet
import android.view.KeyCharacterMap
import android.view.KeyEvent
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.ui.player.controls.components.panels.toColorHexString
import eu.kanade.tachiyomi.ui.player.settings.AdvancedPlayerPreferences
import eu.kanade.tachiyomi.ui.player.settings.AudioPreferences
import eu.kanade.tachiyomi.ui.player.settings.DecoderPreferences
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import eu.kanade.tachiyomi.ui.player.settings.SubtitlePreferences
import eu.kanade.tachiyomi.ui.player.applyAnime4K
import eu.kanade.tachiyomi.ui.player.buildVFChain
import eu.kanade.tachiyomi.ui.player.utils.Anime4KManager
import eu.kanade.tachiyomi.util.system.DeviceTierManager
import eu.kanade.tachiyomi.util.system.findActivity
import `is`.xyz.mpv.BaseMPVView
import `is`.xyz.mpv.KeyMapping
import `is`.xyz.mpv.MPVLib
import logcat.LogPriority
import logcat.logcat
import uy.kohesive.injekt.injectLazy
import kotlin.reflect.KProperty

class AniyomiMPVView(context: Context, attributes: AttributeSet) : BaseMPVView(context, attributes) {

    private val playerPreferences: PlayerPreferences by injectLazy()
    private val decoderPreferences: DecoderPreferences by injectLazy()
    private val subtitlePreferences: SubtitlePreferences by injectLazy()
    private val audioPreferences: AudioPreferences by injectLazy()
    private val advancedPreferences: AdvancedPlayerPreferences by injectLazy()
    private val networkPreferences: NetworkPreferences by injectLazy()
    private val networkHelper: NetworkHelper by injectLazy()
    private val anime4kManager: Anime4KManager by injectLazy()

    var isExiting = false
    var initialized = false
    private var lastAdaptiveCheckTime = 0L

    private fun getPropertyInt(property: String): Int? {
        if (!initialized) return null
        return MPVLib.getPropertyInt(property) as Int?
    }

    private fun getPropertyBoolean(property: String): Boolean? {
        if (!initialized) return null
        return MPVLib.getPropertyBoolean(property) as Boolean?
    }

    private fun getPropertyDouble(property: String): Double? {
        if (!initialized) return null
        return MPVLib.getPropertyDouble(property) as Double?
    }

    private fun getPropertyString(property: String): String? {
        if (!initialized) return null
        return MPVLib.getPropertyString(property) as String?
    }

    val duration: Int?
        get() = getPropertyInt("duration")

    var timePos: Int?
        get() = getPropertyInt("time-pos")
        set(position) {
            if (initialized) MPVLib.setPropertyInt("time-pos", position!!)
        }

    var paused: Boolean?
        get() = getPropertyBoolean("pause")
        set(paused) {
            if (initialized) MPVLib.setPropertyBoolean("pause", paused!!)
        }

    val hwdecActive: String
        get() = getPropertyString("hwdec-current") ?: "no"

    val coreIdle: Boolean?
        get() = getPropertyBoolean("core-idle")

    val pausedForCache: Boolean?
        get() = getPropertyBoolean("paused-for-cache")

    val videoH: Int?
        get() = getPropertyInt("video-params/h")

    fun getVideoOutAspect(): Double? {
        return getPropertyDouble("video-params/aspect")?.let {
            if (it < 0.001) return 0.0
            if ((getPropertyInt("video-params/rotate") ?: 0) % 180 == 90) 1.0 / it else it
        }
    }

    inner class TrackDelegate(private val name: String) {
        operator fun getValue(thisRef: Any?, property: KProperty<*>): Int {
            val v = getPropertyString(name)
            if (v == "no" || v == null) return -1
            return v.toIntOrNull() ?: -1
        }
        operator fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) {
            if (value == -1) {
                MPVLib.setPropertyString(name, "no")
            } else {
                MPVLib.setPropertyInt(name, value)
            }
        }
    }

    var sid: Int by TrackDelegate("sid")
    var secondarySid: Int by TrackDelegate("secondary-sid")
    var aid: Int by TrackDelegate("aid")

    private var currentMaxBytes = 192 * 1024 * 1024L
    private var currentMaxBackBytes = 64 * 1024 * 1024L

    fun applyPlaybackStrategy() {
        val performanceProfile = decoderPreferences.performanceProfile().get()
        val tier = when (performanceProfile) {
            PlayerEfficiency.MaxPerformance -> DeviceTierManager.Tier.HIGH
            PlayerEfficiency.Balanced -> DeviceTierManager.Tier.MID
            PlayerEfficiency.PowerSaver -> DeviceTierManager.Tier.LOW
            else -> DeviceTierManager.getTier(context)
        }

        val (maxMb, maxBackMb, readahead) = when (tier) {
            DeviceTierManager.Tier.LOW -> Triple(64, 32, 60)
            DeviceTierManager.Tier.MID -> Triple(128, 64, 120)
            DeviceTierManager.Tier.HIGH -> {
                MPVLib.setOptionString("hwdec-extra-frames", "24")
                Triple(192, 128, 180)
            }
        }

        currentMaxBytes = maxMb * 1024 * 1024L
        currentMaxBackBytes = maxBackMb * 1024 * 1024L

        MPVLib.setOptionString("demuxer-readahead-secs", "$readahead")
        MPVLib.setOptionString("demuxer-max-bytes", "$currentMaxBytes")
        MPVLib.setOptionString("demuxer-max-back-bytes", "$currentMaxBackBytes")
    }

    fun restoreCache() {
        MPVLib.setPropertyString("demuxer-max-bytes", "$currentMaxBytes")
        MPVLib.setPropertyString("demuxer-max-back-bytes", "$currentMaxBackBytes")
    }

    fun shrinkCache() {
        val shrinkBytes = 64 * 1024 * 1024L
        MPVLib.setPropertyString("demuxer-max-bytes", "$shrinkBytes")
        MPVLib.setPropertyString("demuxer-max-back-bytes", "$shrinkBytes")
    }

    private var pendingVideoToPlay: Pair<eu.kanade.tachiyomi.animesource.model.Video, Long?>? = null

    fun queueOrPlayVideo(video: eu.kanade.tachiyomi.animesource.model.Video, position: Long?, playBlock: (eu.kanade.tachiyomi.animesource.model.Video, Long?) -> Unit) {
        if (!initialized) {
            pendingVideoToPlay = video to position
        } else {
            playBlock(video, position)
        }
    }

    override fun initOptions(vo: String) {
        initialized = true
        setVo(if (decoderPreferences.gpuNext().get()) "gpu-next" else "gpu")
        
        MPVLib.setPropertyBoolean("pause", true)
        MPVLib.setOptionString("profile", "fast")
        MPVLib.setOptionString("hwdec", if (decoderPreferences.tryHWDecoding().get()) "mediacodec,mediacodec-copy" else "no")
        
        // Gated Defaults with HQ toggle
        val isHighQuality = decoderPreferences.highQualityScaling().get()
        val scaler = if (isHighQuality) "spline36" else "bilinear"
        MPVLib.setOptionString("scale", scaler)
        MPVLib.setOptionString("cscale", scaler)
        MPVLib.setOptionString("dscale", scaler)
        MPVLib.setOptionString("dither", if (isHighQuality) "fruit" else "no")

        when (decoderPreferences.videoDebanding().get()) {
            Debanding.None -> {}
            Debanding.CPU -> MPVLib.setOptionString("vf", "gradfun=radius=12")
            Debanding.GPU -> MPVLib.setOptionString("deband", "yes")
        }

        MPVLib.setOptionString("video-sync", "audio")

        if (decoderPreferences.useYUV420P().get()) {
            MPVLib.setOptionString("vf", "format=yuv420p")
        }

        if (decoderPreferences.enableAnime4K().get()) {
            anime4kManager.initialize()
            applyAnime4K(decoderPreferences, anime4kManager, isInit = true)
        }

        MPVLib.setOptionString("msg-level", "all=" + if (networkPreferences.verboseLogging().get()) "v" else "warn")
        MPVLib.setPropertyBoolean("input-default-bindings", true)
        MPVLib.setOptionString("keep-open", "yes")
        MPVLib.setOptionString("ytdl", "no")
        MPVLib.setOptionString("cookies", "yes")
        MPVLib.setOptionString("cache", "yes")
        MPVLib.setOptionString("demuxer-thread", "yes")
        MPVLib.setOptionString("demuxer-lavf-o", "reconnect=1,reconnect_streamed=1,reconnect_delay_max=5")

        val cacheMegs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) 64 else 32
        MPVLib.setOptionString("demuxer-max-bytes", "${cacheMegs * 1024 * 1024}")
        MPVLib.setOptionString("demuxer-max-back-bytes", "${cacheMegs * 1024 * 1024}")

        applyPlaybackStrategy()
        
        MPVLib.setOptionString("hr-seek", "default")
        MPVLib.setOptionString("sub-auto", "fuzzy")
        
        val screenshotDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        screenshotDir.mkdirs()
        MPVLib.setOptionString("screenshot-directory", screenshotDir.path)

        // Only apply non-zero filters
        VideoFilters.entries.forEach {
            val value = it.preference(decoderPreferences).get()
            if (value != 0 && !it.mpvProperty.startsWith("vf_")) {
                MPVLib.setOptionString(it.mpvProperty, value.toString())
            }
        }

        MPVLib.setOptionString("speed", playerPreferences.playerSpeed().get().toString())
        MPVLib.setOptionString("vd-lavc-film-grain", "cpu")
        
        setupSubtitlesOptions()
        setupAudioOptions()
    }

    override fun observeProperties() {
        for ((name, format) in observedProps) MPVLib.observeProperty(name, format)
    }

    var onPlayerReady: (() -> Unit)? = null

    override fun postInitOptions() {
        onPlayerReady?.invoke()
        pendingVideoToPlay?.let { (vid, pos) ->
            pendingVideoToPlay = null
        }
        advancedPreferences.playerStatisticsPage().get().let {
            if (it in 1..5) {
                MPVLib.command(arrayOf("script-binding", "stats/display-stats-toggle"))
                MPVLib.command(arrayOf("script-binding", "stats/display-page-$it"))
            } else if (it == 6 || it == 0) {
                MPVLib.setPropertyString("user-data/stats/display-page", "0")
            }
        }
    }

    fun onKey(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_MULTIPLE || KeyEvent.isModifierKey(event.keyCode)) return false
        var mapped = KeyMapping.map.get(event.keyCode)
        if (mapped == null) {
            if (!event.isPrintingKey) return false
            val ch = event.unicodeChar
            if (ch.and(KeyCharacterMap.COMBINING_ACCENT) != 0) return false
            mapped = ch.toChar().toString()
        }
        if (event.repeatCount > 0) return true
        val mod: MutableList<String> = mutableListOf()
        event.isShiftPressed && mod.add("shift")
        event.isCtrlPressed && mod.add("ctrl")
        event.isAltPressed && mod.add("alt")
        event.isMetaPressed && mod.add("meta")
        val action = if (event.action == KeyEvent.ACTION_DOWN) "keydown" else "keyup"
        mod.add(mapped)
        MPVLib.command(arrayOf(action, mod.joinToString("+")))
        return true
    }

    private val observedProps = mapOf(
        "chapter-list" to MPVLib.mpvFormat.MPV_FORMAT_NONE,
        "track-list" to MPVLib.mpvFormat.MPV_FORMAT_NONE,
        "time-pos" to MPVLib.mpvFormat.MPV_FORMAT_INT64,
        "demuxer-cache-time" to MPVLib.mpvFormat.MPV_FORMAT_INT64,
        "duration" to MPVLib.mpvFormat.MPV_FORMAT_INT64,
        "volume" to MPVLib.mpvFormat.MPV_FORMAT_INT64,
        "volume-max" to MPVLib.mpvFormat.MPV_FORMAT_INT64,
        "sid" to MPVLib.mpvFormat.MPV_FORMAT_STRING,
        "secondary-sid" to MPVLib.mpvFormat.MPV_FORMAT_STRING,
        "aid" to MPVLib.mpvFormat.MPV_FORMAT_STRING,
        "speed" to MPVLib.mpvFormat.MPV_FORMAT_DOUBLE,
        "video-zoom" to MPVLib.mpvFormat.MPV_FORMAT_DOUBLE,
        "video-pan-x" to MPVLib.mpvFormat.MPV_FORMAT_DOUBLE,
        "video-pan-y" to MPVLib.mpvFormat.MPV_FORMAT_DOUBLE,
        "video-params/aspect" to MPVLib.mpvFormat.MPV_FORMAT_DOUBLE,
        "pause" to MPVLib.mpvFormat.MPV_FORMAT_FLAG,
        "paused-for-cache" to MPVLib.mpvFormat.MPV_FORMAT_FLAG,
        "core-idle" to MPVLib.mpvFormat.MPV_FORMAT_FLAG,
        "seeking" to MPVLib.mpvFormat.MPV_FORMAT_FLAG,
        "eof-reached" to MPVLib.mpvFormat.MPV_FORMAT_FLAG,
        "hwdec-current" to MPVLib.mpvFormat.MPV_FORMAT_STRING,
        "hwdec" to MPVLib.mpvFormat.MPV_FORMAT_STRING,
        "user-data/current-anime/intro-length" to MPVLib.mpvFormat.MPV_FORMAT_INT64,
        "user-data/aniyomi/show_text" to MPVLib.mpvFormat.MPV_FORMAT_STRING,
        "user-data/aniyomi/show_seek_text" to MPVLib.mpvFormat.MPV_FORMAT_STRING,
        "user-data/aniyomi/toggle_ui" to MPVLib.mpvFormat.MPV_FORMAT_STRING,
        "user-data/aniyomi/show_panel" to MPVLib.mpvFormat.MPV_FORMAT_STRING,
        "user-data/aniyomi/software_keyboard" to MPVLib.mpvFormat.MPV_FORMAT_STRING,
        "user-data/aniyomi/set_button_title" to MPVLib.mpvFormat.MPV_FORMAT_STRING,
        "user-data/aniyomi/reset_button_title" to MPVLib.mpvFormat.MPV_FORMAT_STRING,
        "user-data/aniyomi/toggle_button" to MPVLib.mpvFormat.MPV_FORMAT_STRING,
        "user-data/aniyomi/switch_episode" to MPVLib.mpvFormat.MPV_FORMAT_STRING,
        "user-data/aniyomi/pause" to MPVLib.mpvFormat.MPV_FORMAT_STRING,
        "user-data/aniyomi/seek_by" to MPVLib.mpvFormat.MPV_FORMAT_STRING,
        "user-data/aniyomi/seek_to" to MPVLib.mpvFormat.MPV_FORMAT_STRING,
        "user-data/aniyomi/seek_by_with_text" to MPVLib.mpvFormat.MPV_FORMAT_STRING,
        "user-data/aniyomi/seek_to_with_text" to MPVLib.mpvFormat.MPV_FORMAT_STRING,
        "user-data/aniyomi/launch_int_picker" to MPVLib.mpvFormat.MPV_FORMAT_STRING,
    )

    private fun setupAudioOptions() {
        MPVLib.setOptionString("alang", audioPreferences.preferredAudioLanguages().get())
        MPVLib.setOptionString("audio-delay", (audioPreferences.audioDelay().get() / 1000.0).toString())
        MPVLib.setOptionString("audio-pitch-correction", audioPreferences.enablePitchCorrection().get().toString())
        MPVLib.setOptionString("volume-max", (audioPreferences.volumeBoostCap().get() + 100).toString())
    }

    private fun setupSubtitlesOptions() {
        MPVLib.setOptionString("slang", subtitlePreferences.preferredSubLanguages().get())
        MPVLib.setOptionString("sub-delay", (subtitlePreferences.subtitlesDelay().get() / 1000.0).toString())
        MPVLib.setOptionString("sub-speed", subtitlePreferences.subtitlesSpeed().get().toString())
        MPVLib.setOptionString("secondary-sub-delay", (subtitlePreferences.subtitlesSecondaryDelay().get() / 1000.0).toString())
        MPVLib.setOptionString("sub-font", subtitlePreferences.subtitleFont().get())
        if (subtitlePreferences.overrideSubsASS().get()) {
            MPVLib.setOptionString("sub-ass-override", "force")
            MPVLib.setOptionString("sub-ass-justify", "yes")
        }
        MPVLib.setOptionString("sub-font-size", subtitlePreferences.subtitleFontSize().get().toString())
        MPVLib.setOptionString("sub-bold", if (subtitlePreferences.boldSubtitles().get()) "yes" else "no")
        MPVLib.setOptionString("sub-italic", if (subtitlePreferences.italicSubtitles().get()) "yes" else "no")
        MPVLib.setOptionString("sub-justify", subtitlePreferences.subtitleJustification().get().value)
        MPVLib.setOptionString("sub-color", subtitlePreferences.textColorSubtitles().get().toColorHexString())
        MPVLib.setOptionString("sub-back-color", subtitlePreferences.backgroundColorSubtitles().get().toColorHexString())
        MPVLib.setOptionString("sub-border-color", subtitlePreferences.borderColorSubtitles().get().toColorHexString())
        MPVLib.setOptionString("sub-border-size", subtitlePreferences.subtitleBorderSize().get().toString())
        MPVLib.setOptionString("sub-border-style", subtitlePreferences.borderStyleSubtitles().get().value)
        MPVLib.setOptionString("sub-shadow-offset", subtitlePreferences.shadowOffsetSubtitles().get().toString())
        MPVLib.setOptionString("sub-pos", subtitlePreferences.subtitlePos().get().toString())
        MPVLib.setOptionString("sub-scale", subtitlePreferences.subtitleFontScale().get().toString())
    }

    fun checkAdaptiveScaling(delayedFrames: Long) {
        if (!decoderPreferences.adaptiveShaderScaling().get() || !decoderPreferences.enableAnime4K().get() || PlayerStats.isAdaptiveDowngraded.value) return
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastAdaptiveCheckTime < 5000) return
        lastAdaptiveCheckTime = currentTime
        if (delayedFrames > 10 && decoderPreferences.anime4kQuality().get() == "HIGH") {
            decoderPreferences.anime4kQuality().set("BALANCED")
            applyAnime4K(decoderPreferences, anime4kManager)
            PlayerStats.isAdaptiveDowngraded.value = true
            (context as? PlayerActivity)?.runOnUiThread {
                (context as? PlayerActivity)?.showToast("Performance: Anime4K downgraded to Balanced")
            }
        }
    }
}
