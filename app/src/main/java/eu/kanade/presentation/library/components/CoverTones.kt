package eu.kanade.presentation.library.components

import android.app.Application
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import coil3.BitmapImage
import coil3.Image
import coil3.asDrawable
import coil3.compose.AsyncImagePainter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.anime.model.AnimeCover
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt

// ANZ -->
/**
 * The colours of one cover, used to paint the Home backdrop so it looks like the artwork.
 *
 * [lights] are four vivid colours taken from the cover itself (the most present first, then different
 * hues, then a bright tint of the main one); [top] / [mid] / [deep] are the dark base of the page in the
 * same hue family. An empty [lights] means the cover has no real colour (black and white art): the page
 * then falls back to the single vibrant colour it used before.
 *
 * [presence] is the share of the cover that really has colour (0..1): grey covers get calmer lights.
 *
 * [mosaic] is a tiny, smooth, vivid picture of the cover (24x36 px) that the backdrop stretches over the whole
 * screen: it is like a blurred copy of the artwork, so every part of the page takes the colour of the part of the
 * cover it sits next to.
 */
@Immutable
internal class CoverTones(
    val coverVersion: Long,
    val presence: Float,
    val top: Color,
    val mid: Color,
    val deep: Color,
    val lights: List<Color>,
    val mosaic: ImageBitmap? = null,
)

internal object CoverTonesCache {

    /** By anime id. Written on the main thread only, so the page recomposes as soon as a cover is read. */
    private val cache = mutableStateMapOf<Long, CoverTones>()

    /** Ids being read right now (main thread only). */
    private val inFlight = HashSet<Long>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun get(animeId: Long, coverVersion: Long): CoverTones? =
        cache[animeId]?.takeIf { it.coverVersion == coverVersion }

    /** Reads the colours of a cover that has just loaded, once per cover version. Call it on the main thread. */
    fun request(cover: AnimeCover, result: AsyncImagePainter.State.Success) {
        val id = cover.animeId
        val version = cover.lastModified
        if (get(id, version) != null || !inFlight.add(id)) return
        val image = result.result.image
        scope.launch {
            val tones = try {
                extract(image, version)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                null
            }
            withContext(Dispatchers.Main) {
                inFlight.remove(id)
                if (tones != null) {
                    if (cache.size > 64) cache.clear()
                    cache[id] = tones
                }
            }
        }
    }

    private fun extract(image: Image, version: Long): CoverTones? {
        val context = Injekt.get<Application>()
        val original = when (image) {
            is BitmapImage -> image.bitmap
            else -> image.asDrawable(context.resources).toBitmap()
        }
        val software = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && original.config == Bitmap.Config.HARDWARE) {
            original.copy(Bitmap.Config.ARGB_8888, false) ?: return null
        } else {
            original
        }
        // a tiny copy is plenty to find the main colours
        val scale = 100f / maxOf(software.width, software.height).coerceAtLeast(1)
        val small = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                software,
                (software.width * scale).toInt().coerceAtLeast(1),
                (software.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            software
        }
        val builder = Palette.from(small).maximumColorCount(24)
        if (small.width >= 10 && small.height >= 10) {
            // Only the middle of the artwork: poster frames, black bars at the sides and the title strip at the
            // bottom would otherwise decide the colours (a yellow border turned the whole page olive).
            builder.setRegion(
                (small.width * 0.12f).toInt(),
                (small.height * 0.06f).toInt(),
                (small.width * 0.88f).toInt(),
                (small.height * 0.78f).toInt(),
            )
        }
        val palette = builder.generate()
        val mosaic = try {
            buildMosaic(small)
        } catch (_: Throwable) {
            null
        }
        return tonesFrom(palette, version, mosaic)
    }
}

private class Candidate(val hue: Float, val sat: Float, val value: Float, val score: Float)

private fun tonesFrom(palette: Palette, version: Long, mosaic: ImageBitmap?): CoverTones {
    val swatches = palette.swatches
    val total = swatches.sumOf { it.population }.coerceAtLeast(1)
    val hsv = FloatArray(3)
    val candidates = ArrayList<Candidate>()
    var presence = 0f
    for (swatch in swatches) {
        android.graphics.Color.colorToHSV(swatch.rgb, hsv)
        // greys, blacks and whites carry no hue
        if (hsv[1] < 0.22f || hsv[2] < 0.12f) continue
        val share = swatch.population.toFloat() / total
        presence += share
        // area counts (its square root, so small bright details still get a say), and so does colourfulness
        val score = sqrt(share) * (0.35f + hsv[1]) * (0.70f + 0.30f * hsv[2])
        candidates.add(Candidate(hsv[0], hsv[1], hsv[2], score))
    }
    if (candidates.isEmpty()) {
        return CoverTones(version, 0f, Color.Black, Color.Black, Color.Black, emptyList())
    }
    candidates.sortByDescending { it.score }

    // up to three clearly different hues, the most present first
    val picked = ArrayList<Candidate>(3)
    for (c in candidates) {
        if (picked.size == 3) break
        if (picked.all { hueDistance(it.hue, c.hue) >= 28f }) picked.add(c)
    }
    val main = picked[0]
    // a cover with a single hue: neighbouring hues of it instead
    while (picked.size < 3) {
        val offset = if (picked.size == 1) 24f else -24f
        picked.add(Candidate(wrapHue(main.hue + offset), main.sat, main.value, 0f))
    }

    val lights = listOf(
        vividColor(main.hue, main.sat, main.value),
        vividColor(picked[1].hue, picked[1].sat, picked[1].value),
        vividColor(picked[2].hue, picked[2].sat, picked[2].value),
        vividColor(wrapHue(main.hue - 14f), maxOf(main.sat, 0.75f), 1f),
    )

    // the dark base stays in the main hue, leaning a little towards the second one
    val delta = (((picked[1].hue - main.hue + 540f) % 360f) - 180f).coerceIn(-60f, 60f)
    val sat = (main.sat * 1.1f).coerceIn(0.60f, 0.90f)
    return CoverTones(
        coverVersion = version,
        presence = presence.coerceIn(0f, 1f),
        top = hsvColor(main.hue, sat, 0.56f),
        mid = hsvColor(wrapHue(main.hue + 0.35f * delta), sat * 0.95f, 0.42f),
        deep = hsvColor(wrapHue(main.hue + 0.60f * delta), (sat * 0.9f).coerceIn(0.50f, 0.85f), 0.32f),
        lights = lights,
        mosaic = mosaic,
    )
}

private const val MosaicCols = 6
private const val MosaicRows = 9
private const val MosaicOutW = 24
private const val MosaicOutH = 36

/**
 * The cover as a 6x9 grid of colours, made vivid and dark enough to sit behind text, then smoothed up to 24x36.
 * Each cell averages its pixels weighting the colourful ones, so black bars, white paper and grey areas hardly
 * count; a cell without any colour borrows from its neighbours (no black patches).
 */
private fun buildMosaic(bitmap: Bitmap): ImageBitmap? {
    val bw = bitmap.width
    val bh = bitmap.height
    // only the middle of the artwork, like the palette
    val x0 = if (bw >= 10 && bh >= 10) (bw * 0.12f).toInt() else 0
    val x1 = if (bw >= 10 && bh >= 10) (bw * 0.88f).toInt() else bw
    val y0 = if (bw >= 10 && bh >= 10) (bh * 0.06f).toInt() else 0
    val y1 = if (bw >= 10 && bh >= 10) (bh * 0.78f).toInt() else bh
    val cw = x1 - x0
    val ch = y1 - y0
    if (cw < MosaicCols || ch < MosaicRows) return null

    val pixels = IntArray(bw * bh)
    bitmap.getPixels(pixels, 0, bw, 0, 0, bw, bh)
    val cells = MosaicCols * MosaicRows
    val sumR = FloatArray(cells)
    val sumG = FloatArray(cells)
    val sumB = FloatArray(cells)
    val sumW = FloatArray(cells)
    val sumColourful = FloatArray(cells)
    val count = IntArray(cells)
    for (y in y0 until y1) {
        val row = ((y - y0) * MosaicRows / ch).coerceAtMost(MosaicRows - 1)
        for (x in x0 until x1) {
            val col = ((x - x0) * MosaicCols / cw).coerceAtMost(MosaicCols - 1)
            val p = pixels[y * bw + x]
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr 8) and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            val mx = maxOf(r, maxOf(g, b))
            val mn = minOf(r, minOf(g, b))
            val colourful = if (mx > 0f) (mx - mn) / mx * mx else 0f
            val w = 0.04f + colourful
            val i = row * MosaicCols + col
            sumR[i] += r * w
            sumG[i] += g * w
            sumB[i] += b * w
            sumW[i] += w
            sumColourful[i] += colourful
            count[i]++
        }
    }
    var cr = FloatArray(cells)
    var cg = FloatArray(cells)
    var cb = FloatArray(cells)
    var valid = BooleanArray(cells)
    var anyValid = false
    for (i in 0 until cells) {
        if (count[i] > 0 && sumW[i] > 0f) {
            cr[i] = sumR[i] / sumW[i]
            cg[i] = sumG[i] / sumW[i]
            cb[i] = sumB[i] / sumW[i]
            valid[i] = sumColourful[i] / count[i] >= 0.06f
            if (valid[i]) anyValid = true
        }
    }
    if (!anyValid) return null

    // cells with no colour take the average of their coloured neighbours, a ring at a time
    for (pass in 0 until 12) {
        if (valid.all { it }) break
        val nr = cr.copyOf()
        val ng = cg.copyOf()
        val nb = cb.copyOf()
        val nv = valid.copyOf()
        for (row in 0 until MosaicRows) {
            for (col in 0 until MosaicCols) {
                val i = row * MosaicCols + col
                if (valid[i]) continue
                var sr = 0f
                var sg = 0f
                var sb = 0f
                var n = 0
                for (k in 0 until 4) {
                    val rr = row + (if (k == 0) 1 else if (k == 1) -1 else 0)
                    val cc = col + (if (k == 2) 1 else if (k == 3) -1 else 0)
                    if (rr < 0 || rr >= MosaicRows || cc < 0 || cc >= MosaicCols) continue
                    val j = rr * MosaicCols + cc
                    if (!valid[j]) continue
                    sr += cr[j]
                    sg += cg[j]
                    sb += cb[j]
                    n++
                }
                if (n > 0) {
                    nr[i] = sr / n
                    ng[i] = sg / n
                    nb[i] = sb / n
                    nv[i] = true
                }
            }
        }
        cr = nr
        cg = ng
        cb = nb
        valid = nv
    }

    // The "white patches": a cell that is an average of opposite colours (or white paper) is grey. Such cells take
    // the hue of their most colourful neighbour, and then every cell gets strong saturation and a darker value.
    val hsv = FloatArray(3)
    val cellHue = FloatArray(cells)
    val cellSat = FloatArray(cells)
    val cellVal = FloatArray(cells)
    for (i in 0 until cells) {
        if (!valid[i]) {
            // (cannot really happen: at least one cell was valid and the rings reach every cell)
            cr[i] = 0.2f
            cg[i] = 0.2f
            cb[i] = 0.2f
        }
        android.graphics.Color.RGBToHSV(
            (cr[i] * 255f + 0.5f).toInt().coerceIn(0, 255),
            (cg[i] * 255f + 0.5f).toInt().coerceIn(0, 255),
            (cb[i] * 255f + 0.5f).toInt().coerceIn(0, 255),
            hsv,
        )
        cellHue[i] = hsv[0]
        cellSat[i] = hsv[1]
        cellVal[i] = hsv[2]
    }
    val hueFixed = cellHue.copyOf()
    for (row in 0 until MosaicRows) {
        for (col in 0 until MosaicCols) {
            val i = row * MosaicCols + col
            if (cellSat[i] >= 0.18f) continue
            var bestJ = -1
            var bestSat = 0.18f
            for (k in 0 until 4) {
                val rr = row + (if (k == 0) 1 else if (k == 1) -1 else 0)
                val cc = col + (if (k == 2) 1 else if (k == 3) -1 else 0)
                if (rr < 0 || rr >= MosaicRows || cc < 0 || cc >= MosaicCols) continue
                val j = rr * MosaicCols + cc
                if (cellSat[j] > bestSat) {
                    bestSat = cellSat[j]
                    bestJ = j
                }
            }
            if (bestJ >= 0) hueFixed[i] = cellHue[bestJ]
        }
    }
    for (i in 0 until cells) {
        hsv[0] = hueFixed[i]
        hsv[1] = (cellSat[i] * 1.5f + 0.14f).coerceIn(0.50f, 0.95f)
        hsv[2] = (0.12f + 0.36f * cellVal[i]).coerceIn(0.18f, 0.46f)
        val c = android.graphics.Color.HSVToColor(hsv)
        cr[i] = android.graphics.Color.red(c) / 255f
        cg[i] = android.graphics.Color.green(c) / 255f
        cb[i] = android.graphics.Color.blue(c) / 255f
    }

    // smooth up-sampling (smoothstep weights): no creases along the grid lines when it is stretched on screen
    val out = IntArray(MosaicOutW * MosaicOutH)
    for (oy in 0 until MosaicOutH) {
        val gy = (oy + 0.5f) / MosaicOutH * MosaicRows - 0.5f
        val fy = floor(gy)
        val ty = smooth(gy - fy)
        val ya = fy.toInt().coerceIn(0, MosaicRows - 1)
        val yb = (fy.toInt() + 1).coerceIn(0, MosaicRows - 1)
        for (ox in 0 until MosaicOutW) {
            val gx = (ox + 0.5f) / MosaicOutW * MosaicCols - 0.5f
            val fx = floor(gx)
            val tx = smooth(gx - fx)
            val xa = fx.toInt().coerceIn(0, MosaicCols - 1)
            val xb = (fx.toInt() + 1).coerceIn(0, MosaicCols - 1)
            val i00 = ya * MosaicCols + xa
            val i01 = ya * MosaicCols + xb
            val i10 = yb * MosaicCols + xa
            val i11 = yb * MosaicCols + xb
            val r = (cr[i00] * (1 - tx) + cr[i01] * tx) * (1 - ty) + (cr[i10] * (1 - tx) + cr[i11] * tx) * ty
            val g = (cg[i00] * (1 - tx) + cg[i01] * tx) * (1 - ty) + (cg[i10] * (1 - tx) + cg[i11] * tx) * ty
            val b = (cb[i00] * (1 - tx) + cb[i01] * tx) * (1 - ty) + (cb[i10] * (1 - tx) + cb[i11] * tx) * ty
            out[oy * MosaicOutW + ox] = android.graphics.Color.rgb(
                (r * 255f + 0.5f).toInt().coerceIn(0, 255),
                (g * 255f + 0.5f).toInt().coerceIn(0, 255),
                (b * 255f + 0.5f).toInt().coerceIn(0, 255),
            )
        }
    }
    return Bitmap.createBitmap(out, MosaicOutW, MosaicOutH, Bitmap.Config.ARGB_8888).asImageBitmap()
}

private fun smooth(t: Float): Float = t * t * (3f - 2f * t)

/** Same hue, but saturated and bright enough to glow, and never so bright that white text over it suffers. */
private fun vividColor(hue: Float, sat: Float, value: Float): Color {
    val s = (sat * 1.1f).coerceIn(0.62f, 1f)
    var v = value.coerceIn(0.75f, 0.90f)
    var color = hsvColor(hue, s, v)
    var steps = 0
    while (relativeLuma(color) > MaxLightLuma && steps < 10 && v > 0.45f) {
        v *= 0.93f
        color = hsvColor(hue, s, v)
        steps++
    }
    return color
}

private const val MaxLightLuma = 0.22f

private fun hsvColor(hue: Float, sat: Float, value: Float): Color =
    Color(android.graphics.Color.HSVToColor(floatArrayOf(wrapHue(hue), sat.coerceIn(0f, 1f), value.coerceIn(0f, 1f))))

private fun wrapHue(hue: Float): Float = ((hue % 360f) + 360f) % 360f

private fun hueDistance(a: Float, b: Float): Float {
    val d = abs(a - b) % 360f
    return minOf(d, 360f - d)
}

private fun relativeLuma(color: Color): Float {
    fun linear(c: Float): Float = if (c <= 0.03928f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    return 0.2126f * linear(color.red) + 0.7152f * linear(color.green) + 0.0722f * linear(color.blue)
}
// ANZ <--
