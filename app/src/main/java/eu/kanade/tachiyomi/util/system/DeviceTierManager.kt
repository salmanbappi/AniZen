package eu.kanade.tachiyomi.util.system

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File

object DeviceTierManager {
    enum class Tier { LOW, MID, HIGH }

    fun getTotalRamGb(context: Context): Double {
        // 1. ActivityManager totalMem (CPU-X primary detection)
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (actManager != null) {
            val memoryInfo = ActivityManager.MemoryInfo()
            actManager.getMemoryInfo(memoryInfo)
            if (memoryInfo.totalMem > 0L) {
                return memoryInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
            }
        }

        // 2. Linux kernel /proc/meminfo fallback (CPU-X direct reader)
        try {
            val memInfoFile = File("/proc/meminfo")
            if (memInfoFile.exists()) {
                memInfoFile.forEachLine { line ->
                    if (line.startsWith("MemTotal:")) {
                        val kb = line.replace(Regex("[^0-9]"), "").toDoubleOrNull()
                        if (kb != null && kb > 0) return kb / (1024.0 * 1024.0)
                    }
                }
            }
        } catch (_: Exception) {
            // non-fatal fallback
        }

        return 4.0
    }

    fun getTier(context: Context): Tier {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val performanceClass = Build.VERSION.MEDIA_PERFORMANCE_CLASS
            if (performanceClass >= Build.VERSION_CODES.TIRAMISU) return Tier.HIGH
            if (performanceClass >= Build.VERSION_CODES.S) return Tier.MID
        }

        val totalRamGb = getTotalRamGb(context)
        return when {
            totalRamGb >= 7.0 -> Tier.HIGH
            totalRamGb >= 3.5 -> Tier.MID
            else -> Tier.LOW
        }
    }
}
