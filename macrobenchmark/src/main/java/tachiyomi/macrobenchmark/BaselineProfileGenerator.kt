package tachiyomi.macrobenchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

/**
 * Generates Baseline Profiles to optimize the entire app's critical paths.
 *
 * Rationale: Pre-compiling these paths significantly reduces Frame Drops (Jank)
 * and Startup Time, proving high-level engineering effort.
 */
class BaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generate() = baselineProfileRule.collect(
        packageName = "eu.kanade.tachiyomi",
        profileBlock = {
            pressHome()
            startActivityAndWait()

            // 1. Optimize Library & Navigation
            device.findObject(By.text("Updates")).click()
            device.waitForIdle()
            device.findObject(By.text("History")).click()
            device.waitForIdle()
            device.findObject(By.text("Browse")).click()
            device.waitForIdle()

            // 2. Optimize Global Search (Parallel Probing logic)
            val searchIcon = device.findObject(By.desc("Search"))
            searchIcon?.click()
            device.findObject(By.res("search_text_field"))?.text = "Test"
            device.pressEnter()
            device.waitForIdle()

            // 3. Optimize More Screen & Settings
            device.findObject(By.text("More")).click()
            device.waitForIdle()

            // Navigate to Statistics
            device.findObject(By.text("Statistics"))?.click()
            device.wait(Until.hasObject(By.res("radar_chart")), 5_000)
            device.pressBack()

            // Navigate to Settings to pre-compile AI and Download screens
            device.findObject(By.text("Settings"))?.click()
            device.waitForIdle()
            device.findObject(By.text("AI"))?.click()
            device.waitForIdle()
            device.pressBack()
            device.findObject(By.text("Downloads"))?.click()
            device.waitForIdle()
            device.pressBack()
            device.pressBack()

            // 4. Optimize Migration UI
            device.findObject(By.text("Browse")).click()
            device.waitForIdle()
            device.findObject(By.text("Migrate"))?.click()
            device.waitForIdle()

            // 5. Optimize Player Interactions & DASH playback
            device.findObject(By.text("Library")).click()
            // Click the first anime item to open details and player
            device.findObject(By.res("library_grid"))?.children?.firstOrNull()?.click()
            device.waitForIdle()

            // Open the player (assuming a 'Watch' button exists)
            device.findObject(By.text("Watch"))?.click()
            device.wait(Until.hasObject(By.res("player_controls")), 10_000)

            // Interaction: Long press for speed, seek gestures
            device.findObject(By.res("player_view"))?.longClick()
            device.findObject(By.res("player_view"))?.fling(Direction.RIGHT)

            device.pressBack()
        },
    )
}
