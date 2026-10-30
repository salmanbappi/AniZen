package exh.log

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.tachiyomi.ui.player.PlayerActivity
import eu.kanade.tachiyomi.ui.player.PlayerStats
import eu.kanade.tachiyomi.ui.player.settings.DecoderPreferences
import eu.kanade.tachiyomi.util.system.findActivity
import eu.kanade.tachiyomi.util.system.getDisplayRefreshRate
import kotlinx.coroutines.delay
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

@Composable
fun InterpolationStatsOverlay() {
    // ANZ -->
    val activity = LocalContext.current.findActivity() as? PlayerActivity
    val mpv = activity?.viewModel?.mpv
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        while (true) {
            PlayerStats.estimatedVfFps.value = mpv?.getPropertyDouble("estimated-vf-fps") ?: 0.0
            PlayerStats.videoParamsFps.value = mpv?.getPropertyDouble("video-params/fps") ?: 0.0
            PlayerStats.containerFps.value = mpv?.getPropertyDouble("container-fps") ?: 0.0
            val detectedRefreshRate = context.getDisplayRefreshRate().toDouble()
            val mpvDisplayFps = mpv?.getPropertyDouble("display-fps")?.takeIf { it > 0.0 }
            PlayerStats.displayFps.value = mpvDisplayFps ?: detectedRefreshRate
            PlayerStats.estimatedDisplayFps.value = mpv?.getPropertyDouble("estimated-display-fps") ?: 0.0

            PlayerStats.isInterpolating.value = mpv?.getPropertyBoolean("interpolation") ?: false
            PlayerStats.videoSync.value = mpv?.getPropertyString("video-sync") ?: ""
            PlayerStats.tscale.value = mpv?.getPropertyString("tscale") ?: ""
            PlayerStats.delayedFrames.value = mpv?.getPropertyInt("vo-delayed-frame-count")?.toLong() ?: 0L
            PlayerStats.mistime.value = mpv?.getPropertyDouble("mistime") ?: 0.0
            PlayerStats.voPasses.value = (mpv?.getPropertyString("vo-passes")?.toLongOrNull() ?: 0L)

            PlayerStats.hwdec.value = mpv?.getPropertyString("hwdec-current") ?: ""
            PlayerStats.videoW.value = mpv?.getPropertyInt("video-params/w")?.toLong() ?: 0L
            PlayerStats.videoH.value = mpv?.getPropertyInt("video-params/h")?.toLong() ?: 0L
            PlayerStats.videoOutW.value = mpv?.getPropertyInt("video-out-params/w")?.toLong() ?: 0L
            PlayerStats.videoOutH.value = mpv?.getPropertyInt("video-out-params/h")?.toLong() ?: 0L
            PlayerStats.dwidth.value = mpv?.getPropertyInt("dwidth")?.toLong() ?: 0L
            PlayerStats.dheight.value = mpv?.getPropertyInt("dheight")?.toLong() ?: 0L
            PlayerStats.currentVo.value = mpv?.getPropertyString("current-vo") ?: mpv?.getPropertyString("vo") ?: ""
            PlayerStats.gpuDumbMode.value = mpv?.getPropertyBoolean("gpu-dumb-mode") ?: false

            delay(1000)
        }
    }
    // ANZ <--
    val vfFps by PlayerStats.estimatedVfFps.collectAsState(0.0)
    val sourceFps by PlayerStats.videoParamsFps.collectAsState(0.0)
    val containerFps by PlayerStats.containerFps.collectAsState(0.0)
    val displayFps by PlayerStats.displayFps.collectAsState(0.0)
    val actualFps by PlayerStats.estimatedDisplayFps.collectAsState(0.0)

    val isInterpolating by PlayerStats.isInterpolating.collectAsState(false)
    val videoSync by PlayerStats.videoSync.collectAsState("")
    val tscale by PlayerStats.tscale.collectAsState("")
    val delayedFrames by PlayerStats.delayedFrames.collectAsState(0L)
    val mistime by PlayerStats.mistime.collectAsState(0.0)
    val voPasses by PlayerStats.voPasses.collectAsState(0L)

    val hwdec by PlayerStats.hwdec.collectAsState("")
    // ANZ -->
    val currentVo by PlayerStats.currentVo.collectAsState("")
    val gpuDumbMode by PlayerStats.gpuDumbMode.collectAsState(false)
    // ANZ <--
    val videoW by PlayerStats.videoW.collectAsState(0L)
    val videoH by PlayerStats.videoH.collectAsState(0L)
    val videoOutW by PlayerStats.videoOutW.collectAsState(0L)
    val videoOutH by PlayerStats.videoOutH.collectAsState(0L)
    val dwidth by PlayerStats.dwidth.collectAsState(0L)
    val dheight by PlayerStats.dheight.collectAsState(0L)

    val format = remember {
        DecimalFormat(
            "0.0",
            DecimalFormatSymbols.getInstance(Locale.ENGLISH),
        )
    }

    val shadow = Shadow(color = Color.Black, offset = androidx.compose.ui.geometry.Offset(2f, 2f), blurRadius = 2f)
    val baseStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        color = Color.White,
        shadow = shadow,
        lineHeight = 18.sp,
    )

    Column(
        Modifier.padding(16.dp),
    ) {
        Text(text = "SMOOTH MOTION DEBUG (PAGE 6)", style = baseStyle.copy(color = Color(0xFF33BBFF)))
        Spacer(Modifier.height(8.dp))

        // ANZ -->
        // Pipeline Logic: Frame interpolation works with MediaCodec on vo=gpu/gpu-next.
        // Only direct surface rendering (vo=mediacodec_embed) bypasses mpv's GPU renderer.
        val isDirectSurface = currentVo == "mediacodec_embed"
        val isDumbMode = gpuDumbMode
        val isWorking = isInterpolating &&
            !isDirectSurface &&
            !isDumbMode &&
            (voPasses > 1 || actualFps > (vfFps + 5) || (tscale.isNotEmpty() && tscale != "none"))

        val statusText = when {
            isDirectSurface -> "BYPASSED (Direct Surface)"
            isDumbMode -> "DISABLED (gpu-dumb-mode=yes)"
            isWorking -> "ACTIVE"
            isInterpolating && !isWorking -> "WAITING (Preparing frames)"
            else -> "OFF"
        }
        val statusColor = when {
            isDirectSurface || isDumbMode -> Color.Red
            isWorking -> Color.Green
            isInterpolating -> Color(0xFFFFAA00)
            else -> Color.Unspecified
        }
        StatLine("Status", statusText, baseStyle.copy(color = statusColor))
        StatLine("Sync Mode", videoSync, baseStyle)
        StatLine("Scaler", tscale.ifEmpty { "none" }, baseStyle)

        val pipelineMode = when {
            isDirectSurface -> "Direct Surface (Bypass)"
            hwdec == "mediacodec" -> "GPU Zero-Copy (HW+)"
            hwdec == "mediacodec-copy" -> "GPU Copy-Back (HW)"
            hwdec.isNotEmpty() && hwdec != "no" -> "GPU HW ($hwdec)"
            else -> "GPU Software (SW)"
        }
        StatLine("Pipeline", pipelineMode, baseStyle)
        // ANZ <--

        Spacer(Modifier.height(12.dp))

        // FPS Details with fallbacks
        val finalSourceFps = listOf(sourceFps, containerFps, vfFps).firstOrNull { it > 0.0 } ?: 0.0
        val finalActualFps = if (actualFps > 0) actualFps else vfFps

        StatLine("Source Rate", "${format.format(finalSourceFps)} fps", baseStyle)
        // ANZ -->
        val fpsLimitInt = Injekt.get<DecoderPreferences>().interpolationFPSLimit().get()
        val targetLabel = when {
            !isWorking -> "Off"
            fpsLimitInt == 0 -> "Auto"
            else -> "$fpsLimitInt fps"
        }
        StatLine("Target FPS", targetLabel, baseStyle)
        StatLine("Actual Display", "${format.format(finalActualFps)} fps", baseStyle.copy(color = if (finalActualFps >= 24) Color.Green else Color.Unspecified))
        StatLine("Display Rate", "${format.format(displayFps)} Hz", baseStyle)
        // ANZ <--

        Spacer(Modifier.height(8.dp))

        StatLine("Mistime", "${(mistime * 1000).toInt()} ms", baseStyle)
        StatLine("Dropped", "$delayedFrames frames", baseStyle.copy(color = if (delayedFrames > 0) Color.Red else Color.Unspecified))

        Spacer(Modifier.height(12.dp))

        // Hardware details
        val finalW = listOf(dwidth, videoW, videoOutW).firstOrNull { it > 0L } ?: 0L
        val finalH = listOf(dheight, videoH, videoOutH).firstOrNull { it > 0L } ?: 0L
        Row {
            StatLine("Res", "${finalW}x$finalH", baseStyle)
            Text(" | ", style = baseStyle)
            StatLine("HW", hwdec.ifEmpty { "no" }, baseStyle)
        }
    }
}

@Composable
private fun StatLine(label: String, value: String, style: TextStyle) {
    Text(
        text = String.format(Locale.ENGLISH, "%-15s: %s", label, value),
        style = style,
    )
}
