package tachiyomi.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Benchmark to measure the frame timing (smoothness) of the Browse Source screen.
 *
 * Rationale: Scrolling through potentially thousands of sources or media items
 * is the ultimate stress test for image loading and UI thread performance.
 */
@RunWith(AndroidJUnit4::class)
class BrowseScrollingBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun scrollBrowseSource() = benchmarkRule.measureRepeated(
        packageName = "eu.kanade.tachiyomi",
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        iterations = 5,
        startupMode = StartupMode.COLD,
        setupBlock = {
            pressHome()
            startActivityAndWait()

            // Navigate to Browse
            device.findObject(By.text("Browse")).click()
            device.waitForIdle()

            // Navigate to first source (e.g. Gogoanime)
            device.findObject(By.res("sources_list"))?.children?.firstOrNull()?.click()

            // Wait for media items to load
            device.wait(Until.hasObject(By.res("browse_grid")), 15_000)
        },
    ) {
        val browseGrid = device.findObject(By.res("browse_grid"))
        browseGrid.setGestureMargin(device.displayWidth / 4)

        // Measure sustained fling performance
        repeat(5) {
            browseGrid.fling(Direction.DOWN)
            device.waitForIdle()
        }
    }
}
