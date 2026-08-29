package eu.kanade.tachiyomi.util.system

import android.app.ActivityManager
import android.content.Context
import android.os.Build

object DeviceTierManager {
    enum class Tier { LOW, MID, HIGH }

    fun getTier(context: Context): Tier {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val performanceClass = Build.VERSION.MEDIA_PERFORMANCE_CLASS
            if (performanceClass >= Build.VERSION_CODES.TIRAMISU) return Tier.HIGH
            if (performanceClass >= Build.VERSION_CODES.S) return Tier.MID
        }

        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (actManager != null) {
            val memoryInfo = ActivityManager.MemoryInfo()
            actManager.getMemoryInfo(memoryInfo)
            val totalRamGb = memoryInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
            return when {
                totalRamGb >= 7.0 -> Tier.HIGH
                totalRamGb >= 3.5 -> Tier.MID
                else -> Tier.LOW
            }
        }

        return Tier.MID
    }
}
