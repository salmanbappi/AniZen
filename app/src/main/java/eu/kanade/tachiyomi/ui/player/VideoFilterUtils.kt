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
import eu.kanade.tachiyomi.ui.player.settings.DecoderPreferences
import eu.kanade.tachiyomi.ui.player.utils.Anime4KManager
import eu.kanade.tachiyomi.util.system.findActivity
import eu.kanade.tachiyomi.util.system.getDisplayRefreshRate
import eu.kanade.tachiyomi.util.system.resetRefreshRate
import eu.kanade.tachiyomi.util.system.setTargetRefreshRate
import `is`.xyz.mpv.MPV
import logcat.LogPriority
import logcat.logcat

// ANZ -->
fun applyFilter(mpv: MPV?, filter: VideoFilters, value: Int, prefs: DecoderPreferences) {
    val property = filter.mpvProperty

    mpv?.setPropertyInt(property, value)
}

fun applyDebandMode(mpv: MPV?, mode: Debanding, prefs: DecoderPreferences) {
    when (mode) {
        Debanding.None -> {
            mpv?.setOptionString("deband", "no")
            mpv?.command("vf", "remove", "@deband")
        }
        Debanding.CPU -> {
            mpv?.setOptionString("deband", "no")
            mpv?.command("vf", "add", "@deband:gradfun=radius=12")
        }
        Debanding.GPU -> {
            mpv?.setOptionString("deband", "yes")
            mpv?.command("vf", "remove", "@deband")
            // Apply current GPU settings
            DebandSettings.entries.forEach {
                mpv?.setPropertyInt(it.mpvProperty, it.preference(prefs).get())
            }
        }
    }
}

fun applyDebandSetting(mpv: MPV?, setting: DebandSettings, value: Int) {
    mpv?.setPropertyInt(setting.mpvProperty, value)
}
// ANZ <--

fun buildVFChain(decoderPreferences: DecoderPreferences): String {
    // ANZ -->
    val filters = mutableListOf<String>()
    if (decoderPreferences.useYUV420P().get()) {
        filters.add("format=yuv420p")
    }
    if (decoderPreferences.videoDebanding().get() == Debanding.CPU) {
        filters.add("@deband:gradfun=radius=12")
    }
    return filters.joinToString(",")
    // ANZ <--
}

// ANZ -->
fun applyTheme(mpv: MPV?, theme: VideoFilterTheme, prefs: DecoderPreferences) {
    prefs.brightnessFilter().set(theme.brightness)
    prefs.contrastFilter().set(theme.contrast)
    prefs.saturationFilter().set(theme.saturation)
    prefs.gammaFilter().set(theme.gamma)
    prefs.hueFilter().set(theme.hue)
    prefs.sharpenFilter().set(theme.sharpen)

    // Reset deband
    prefs.debandFilter().set(0)
    prefs.grainFilter().set(0)
    prefs.debandThreshold().set(32)
    prefs.debandRange().set(16)

    // Apply direct properties
    mpv?.setPropertyInt("brightness", theme.brightness)
    mpv?.setPropertyInt("contrast", theme.contrast)
    mpv?.setPropertyInt("saturation", theme.saturation)
    mpv?.setPropertyInt("gamma", theme.gamma)
    mpv?.setPropertyInt("hue", theme.hue)
    mpv?.setPropertyInt("sharpen", theme.sharpen)

    // Apply VF chain once
    mpv?.setPropertyString("vf", buildVFChain(prefs))

    // Reset deband engine properties
    mpv?.setPropertyBoolean("deband", false)
    mpv?.setPropertyInt("deband-iterations", 1)
    mpv?.setPropertyInt("deband-threshold", 32)
    mpv?.setPropertyInt("deband-range", 16)
    mpv?.setPropertyInt("deband-grain", 48)
}

fun applyAnime4K(mpv: MPV?, prefs: DecoderPreferences, manager: Anime4KManager, isInit: Boolean = false) {
    val enabled = prefs.enableAnime4K().get()

    // DEFENSIVE: Anime4K is incompatible with gpu-next in current builds
    val gpuNext = prefs.gpuNext().get()
    if (enabled && gpuNext) {
        logcat("Anime4K", LogPriority.WARN) { "Anime4K is incompatible with gpu-next. Skipping." }
        if (!isInit) mpv?.setPropertyString("glsl-shaders", "")
        return
    }

    val mode = try {
        Anime4KManager.Mode.valueOf(prefs.anime4kMode().get())
    } catch (e: Exception) {
        Anime4KManager.Mode.OFF
    }
    val quality = try {
        Anime4KManager.Quality.valueOf(prefs.anime4kQuality().get())
    } catch (e: Exception) {
        Anime4KManager.Quality.BALANCED
    }

    // Ensure initialization happened
    manager.initialize()

    val chain = if (enabled) manager.getShaderChain(mode, quality) else ""
    logcat("Anime4K", LogPriority.DEBUG) { "Applying Anime4K chain (enabled=$enabled): $chain" }

    if (chain.isNotEmpty()) {
        if (isInit) {
            mpv?.setOptionString("glsl-shaders", chain)
        } else {
            mpv?.setPropertyString("glsl-shaders", chain)
        }
    } else {
        if (isInit) {
            mpv?.setOptionString("glsl-shaders", "")
        } else {
            mpv?.setPropertyString("glsl-shaders", "")
        }
    }
}

// ANZ -->
fun resolveInterpolationHwdec(prefs: DecoderPreferences): String {
    if (!prefs.tryHWDecoding().get()) return "no"
    // vo=gpu renders MediaCodec frames into an internal OpenGL FBO before tscale blending.
    // vo=gpu-next consumes SurfaceTexture buffers via libplacebo, where copy-back avoids stalls.
    return if (prefs.smoothMotion().get() && prefs.gpuNext().get()) {
        "mediacodec-copy"
    } else {
        "mediacodec"
    }
}

fun syncInterpolationDisplayFps(mpv: MPV?, prefs: DecoderPreferences, context: Context?) {
    if (mpv == null) return
    if (prefs.smoothMotion().get()) {
        val activeDisplayFps = context?.getDisplayRefreshRate()?.toDouble() ?: return
        if (mpv.getPropertyDouble("display-fps-override") != activeDisplayFps) {
            mpv.setPropertyDouble("display-fps-override", activeDisplayFps)
        }
    } else {
        mpv.setPropertyDouble("display-fps-override", 0.0)
    }
}

fun applyInterpolation(
    mpv: MPV?,
    prefs: DecoderPreferences,
    context: Context?,
    playerView: AniyomiMPVView? = (context?.findActivity() as? PlayerActivity)?.player,
) {
    val enabled = prefs.smoothMotion().get()
    val activity = context?.findActivity()

    if (enabled) {
        val fpsLimit = prefs.interpolationFPSLimit().get()
        val targetHz = activity?.setTargetRefreshRate(fpsLimit) ?: 0f
        playerView?.applySurfaceFrameRate(targetHz)

        if (prefs.tryHWDecoding().get() && mpv?.getPropertyString("hwdec") != "no") {
            mpv?.setPropertyString("hwdec", resolveInterpolationHwdec(prefs))
        }

        mpv?.setPropertyString("vf", buildVFChain(prefs))
        mpv?.setPropertyString("video-sync", "display-resample")
        mpv?.setPropertyString("video-sync-max-audio-change", "0.125")
        mpv?.setPropertyString("video-sync-max-video-change", "1")
        mpv?.setPropertyString("tscale", prefs.interpolationMode().get().value)
        mpv?.setPropertyString("interpolation-threshold", "-1")
        mpv?.setPropertyString("gpu-dumb-mode", "no")
        mpv?.setPropertyString("correct-pts", "yes")
        mpv?.setPropertyString("interpolation", "yes")
        syncInterpolationDisplayFps(mpv, prefs, context)
        (activity as? PlayerActivity)?.scheduleDisplayFpsResync()
    } else {
        (activity as? PlayerActivity)?.cancelDisplayFpsResync()
        if (prefs.tryHWDecoding().get() && mpv?.getPropertyString("hwdec") != "no") {
            mpv?.setPropertyString("hwdec", "mediacodec")
        }
        mpv?.setPropertyString("vf", buildVFChain(prefs))
        mpv?.setPropertyString("interpolation", "no")
        mpv?.setPropertyString("video-sync", "audio")
        mpv?.setPropertyString("video-sync-max-audio-change", "0.125")
        mpv?.setPropertyString("video-sync-max-video-change", "1")
        mpv?.setPropertyString("gpu-dumb-mode", "auto")
        mpv?.setPropertyDouble("display-fps-override", 0.0)
        playerView?.applySurfaceFrameRate(0f)
        activity?.resetRefreshRate()
    }
}
// ANZ <--
