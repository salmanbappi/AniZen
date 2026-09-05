package eu.kanade.tachiyomi.util.system

import android.app.Application
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import coil3.BitmapImage
import coil3.asDrawable
import coil3.compose.AsyncImagePainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import tachiyomi.domain.anime.model.AnimeCover
import tachiyomi.presentation.core.util.ScrollActivity
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object CoverColorExtractor {

    /**
     * Fast-scrolling a large category can make many covers visible per frame; each
     * uncached cover would otherwise do a full-res bitmap copy + palette generation
     * concurrently on [Dispatchers.Default], saturating CPU and GC. The semaphore bounds
     * how many of those expensive extractions run at once (the rest queue, so coverage is
     * never reduced).
     */
    private val extractionPermits = Semaphore(3)

    suspend fun extract(
        cover: AnimeCover,
        state: AsyncImagePainter.State.Success,
        extractColor: Boolean = true,
    ) = withContext(Dispatchers.Default) {
        val context = Injekt.get<Application>()
        val image = state.result.image
        
        // Fast ratio extraction without bitmap conversion
        val ratio = image.width.toFloat() / image.height.toFloat()
        cover.ratio = ratio
        CoverColorObserver.updateRatio(cover.animeId, ratio, cover.lastModified)

        // Version-aware guard: skip extraction when a current-color cache entry exists.
        // The observer is the single source of truth (it is written together with
        // [AnimeCover.vibrantCoverColor] and is version-aware), so a cover whose
        // lastModified changed mid-session is re-extracted instead of being hidden behind
        // a stale session-only entry.
        if (!extractColor) return@withContext
        if (CoverColorObserver.get(cover.animeId, cover.lastModified) != null) return@withContext

        // Palette generation needs a *software* copy of the bitmap, and for a hardware
        // bitmap that copy is a GPU->CPU readback: the most expensive thing this function
        // does, and the one that stalls rendering mid-fling. Waiting for the scroll to
        // settle means a cover the user flings past gets disposed (cancelling this
        // coroutine) instead of paying that cost for something no longer on screen.
        ScrollActivity.awaitIdle()
        ensureActive()

        val color = extractionPermits.withPermit {
            ensureActive()
            val originalBitmap = when (image) {
                is BitmapImage -> image.bitmap
                else -> image.asDrawable(context.resources).toBitmap()
            }

            val softwareBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && originalBitmap.config == Bitmap.Config.HARDWARE) {
                originalBitmap.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                originalBitmap
            }

            val bitmap = softwareBitmap.let {
                // Downsample to a tiny size for negligible extraction cost (max 100x100)
                val scale = 100f / Math.max(it.width, it.height).coerceAtLeast(1)
                if (scale < 1f) {
                    Bitmap.createScaledBitmap(it, (it.width * scale).toInt(), (it.height * scale).toInt(), true)
                } else {
                    it
                }
            }

            val palette = Palette.from(bitmap).generate()
            palette.getVibrantColor(palette.getMutedColor(0))
        }
        if (color != 0) {
            cover.vibrantCoverColor = color
            CoverColorObserver.update(cover.animeId, color, cover.lastModified)
        }
    }
}
