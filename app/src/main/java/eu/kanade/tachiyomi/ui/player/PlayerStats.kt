package eu.kanade.tachiyomi.ui.player

import kotlinx.coroutines.flow.MutableStateFlow

object PlayerStats {
    val estimatedVfFps = MutableStateFlow(0.0)
    val videoParamsFps = MutableStateFlow(0.0)
    val videoOutParamsFps = MutableStateFlow(0.0)
    val containerFps = MutableStateFlow(0.0)
    val displayFps = MutableStateFlow(0.0)
    val estimatedDisplayFps = MutableStateFlow(0.0)

    val videoW = MutableStateFlow(0L)
    val videoH = MutableStateFlow(0L)
    val dwidth = MutableStateFlow(0L)
    val dheight = MutableStateFlow(0L)
    val videoOutW = MutableStateFlow(0L)
    val videoOutH = MutableStateFlow(0L)

    val videoCodec = MutableStateFlow("")
    val videoBitrate = MutableStateFlow(0L)
    val videoPixFmt = MutableStateFlow("")
    val videoLevels = MutableStateFlow("")
    val videoPrimaries = MutableStateFlow("")
    val hwdec = MutableStateFlow("")

    val videoSync = MutableStateFlow("")
    val tscale = MutableStateFlow("")
    val isInterpolating = MutableStateFlow(false)
    val voPasses = MutableStateFlow(0L)
    val isAdaptiveDowngraded = MutableStateFlow(false)

    val delayedFrames = MutableStateFlow(0L)
    val mistime = MutableStateFlow(0.0)

    fun reset() {
        estimatedVfFps.value = 0.0
        videoParamsFps.value = 0.0
        videoOutParamsFps.value = 0.0
        containerFps.value = 0.0
        displayFps.value = 0.0
        estimatedDisplayFps.value = 0.0
        videoW.value = 0L
        videoH.value = 0L
        dwidth.value = 0L
        dheight.value = 0L
        videoOutW.value = 0L
        videoOutH.value = 0L
        videoCodec.value = ""
        videoBitrate.value = 0L
        videoPixFmt.value = ""
        videoLevels.value = ""
        videoPrimaries.value = ""
        videoSync.value = ""
        tscale.value = ""
        isInterpolating.value = false
        voPasses.value = 0L
        isAdaptiveDowngraded.value = false
        delayedFrames.value = 0L
        mistime.value = 0.0
    }
}
