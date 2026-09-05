package eu.kanade.presentation.anime.components

import android.content.Context
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import eu.kanade.tachiyomi.R

/**
 * The "broken cover" painter, decoded once for the whole process.
 *
 * Every cover in a grid or list passes this to `AsyncImage` as its error painter. Decoding the
 * drawable per composable (as `rememberResourceBitmapPainter` does) would allocate a bitmap
 * per item; the image is a small static vector, so one shared instance is enough.
 */
internal object CoverErrorPainter {

    @Volatile
    private var painter: BitmapPainter? = null

    fun get(context: Context): BitmapPainter? {
        painter?.let { return it }
        return synchronized(this) {
            painter ?: runCatching {
                val drawable = ContextCompat.getDrawable(context.applicationContext, R.drawable.cover_error)
                    ?: return@runCatching null
                BitmapPainter(drawable.toBitmap().asImageBitmap())
            }.getOrNull().also { painter = it }
        }
    }
}
