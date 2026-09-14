package tachiyomi.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Benchmark to measure the frame timing (smoothness) of the Library screen.
 */
@RunWith(AndroidJUnit4ClassRunner::class)
class LibraryScrollingBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun scrollLibrary() = benchmarkRule.measureRepeated(
        packageName = "eu.kanade.tachiyomi", // Replace with your package name if different
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        iterations = 5,
        startupMode = StartupMode.COLD,
        setupBlock = {
            pressHome()
            startActivityAndWait()

            // Wait for the library content to load
            device.wait(Until.hasObject(By.res("library_grid")), 10_000)
        },
    ) {
        val libraryList = device.findObject(By.res("library_grid"))
        libraryList.setGestureMargin(device.displayWidth / 5)

        // Fling down several times to measure frame drops
        repeat(3) {
            libraryList.fling(Direction.DOWN)
            device.waitForIdle()
        }
    }
}
