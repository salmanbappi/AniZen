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

import eu.kanade.tachiyomi.ui.player.settings.DecoderPreferences
import eu.kanade.tachiyomi.ui.player.utils.Anime4KManager
import `is`.xyz.mpv.MPVLib
import logcat.LogPriority
import logcat.logcat

fun applyFilter(filter: VideoFilters, value: Int, prefs: DecoderPreferences) {
    val property = filter.mpvProperty
    
    MPVLib.setPropertyInt(property, value)
}

fun applyDebandMode(mode: Debanding, prefs: DecoderPreferences) {
    when (mode) {
        Debanding.None -> {
            MPVLib.setOptionString("deband", "no")
            MPVLib.command("vf", "remove", "@deband")
        }
        Debanding.CPU -> {
            MPVLib.setOptionString("deband", "no")
            MPVLib.command("vf", "add", "@deband:gradfun=radius=12")
        }
        Debanding.GPU -> {
            MPVLib.setOptionString("deband", "yes")
            MPVLib.command("vf", "remove", "@deband")
            // Apply current GPU settings
            DebandSettings.entries.forEach {
                MPVLib.setPropertyInt(it.mpvProperty, it.preference(prefs).get())
            }
        }
    }
}

fun applyDebandSetting(setting: DebandSettings, value: Int) {
    MPVLib.setPropertyInt(setting.mpvProperty, value)
}

fun buildVFChain(decoderPreferences: DecoderPreferences): String {
    val useYuv420p = decoderPreferences.useYUV420P().get()

    return if (useYuv420p) {
        "format=yuv420p"
    } else {
        ""
    }
}

fun applyTheme(theme: VideoFilterTheme, prefs: DecoderPreferences) {
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
    MPVLib.setPropertyInt("brightness", theme.brightness)
    MPVLib.setPropertyInt("contrast", theme.contrast)
    MPVLib.setPropertyInt("saturation", theme.saturation)
    MPVLib.setPropertyInt("gamma", theme.gamma)
    MPVLib.setPropertyInt("hue", theme.hue)
    MPVLib.setPropertyInt("sharpen", theme.sharpen)
    
    // Apply VF chain once
    MPVLib.setPropertyString("vf", buildVFChain(prefs))
    
    // Reset deband engine properties
    MPVLib.setPropertyBoolean("deband", false)
    MPVLib.setPropertyInt("deband-iterations", 1)
    MPVLib.setPropertyInt("deband-threshold", 32)
    MPVLib.setPropertyInt("deband-range", 16)
    MPVLib.setPropertyInt("deband-grain", 48)
}

fun applyAnime4K(prefs: DecoderPreferences, manager: Anime4KManager, isInit: Boolean = false) {
    val enabled = prefs.enableAnime4K().get()
    
    // DEFENSIVE: Anime4K is incompatible with gpu-next in current builds
    val gpuNext = prefs.gpuNext().get()
    if (enabled && gpuNext) {
        logcat("Anime4K", LogPriority.WARN) { "Anime4K is incompatible with gpu-next. Skipping." }
        if (!isInit) MPVLib.setPropertyString("glsl-shaders", "")
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
            MPVLib.setOptionString("glsl-shaders", chain)
        } else {
            MPVLib.setPropertyString("glsl-shaders", chain)
        }
    } else {
        if (isInit) {
            MPVLib.setOptionString("glsl-shaders", "")
        } else {
            MPVLib.setPropertyString("glsl-shaders", "")
        }
    }
}
