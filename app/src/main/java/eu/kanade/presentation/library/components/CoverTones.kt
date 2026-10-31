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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
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
 * [mosaic] is a tiny, smooth, vivid picture of the cover (27x60 px) that the backdrop stretches over the whole
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

private const val MosaicCols = 8
private const val MosaicRows = 12
private const val MosaicOutW = 27
private const val MosaicOutH = 60
private const val HueBins = 12

// Where the cover card rests on the Home screen (fractions of the screen height). The mosaic is laid out so that
// the part of the cover next to the card gets the colour of that part; above and below the card the colours of the
// first and last rows simply go on.
private const val CardTop = 0.22f
private const val CardBottom = 0.80f

// The mosaic never gets brighter than this (relative luminance), so white text stays easy to read over it.
private const val MosaicMaxLuma = 0.16f

/**
 * The cover as an 8x12 grid of colours, smoothed up to 27x60 and laid out like the screen.
 *
 * Every cell takes the colour that is really in it (its dominant hue, not an average: teal and pink must not
 * average into grey), made vivid; its brightness follows the artwork (white sleeves are the light parts of the
 * backdrop, never white). A cell with no colour (black bars, white paper, grey) borrows the hue of its most
 * colourful neighbour. The smoothing blends hues along the colour wheel, so it never passes through grey.
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
    val binW = FloatArray(cells * HueBins)
    val binS = FloatArray(cells * HueBins)
    val binX = FloatArray(cells * HueBins)
    val binY = FloatArray(cells * HueBins)
    val sumV = FloatArray(cells)
    val count = IntArray(cells)
    val hsv = FloatArray(3)
    val degToRad = PI.toFloat() / 180f
    for (y in y0 until y1) {
        val row = ((y - y0) * MosaicRows / ch).coerceAtMost(MosaicRows - 1)
        for (x in x0 until x1) {
            val col = ((x - x0) * MosaicCols / cw).coerceAtMost(MosaicCols - 1)
            android.graphics.Color.colorToHSV(pixels[y * bw + x], hsv)
            val i = row * MosaicCols + col
            sumV[i] += hsv[2]
            count[i]++
            // only pixels with real colour vote for the hue (greys, whites and blacks do not)
            val wc = ramp(0.12f, 0.40f, hsv[1]) * ramp(0.10f, 0.35f, hsv[2])
            if (wc > 0f) {
                val k = i * HueBins + (hsv[0] / 30f).toInt().coerceIn(0, HueBins - 1)
                val rad = hsv[0] * degToRad
                binW[k] += wc
                binS[k] += wc * hsv[1]
                binX[k] += wc * cos(rad)
                binY[k] += wc * sin(rad)
            }
        }
    }

    var cellHue = FloatArray(cells)
    var cellSat = FloatArray(cells)
    var cellScore = FloatArray(cells)
    var valid = BooleanArray(cells)
    for (i in 0 until cells) {
        if (count[i] == 0) continue
        var best = -1f
        var bestBin = 0
        for (b in 0 until HueBins) {
            val prev = (b + HueBins - 1) % HueBins
            val next = (b + 1) % HueBins
            val score = binW[i * HueBins + b] + 0.5f * (binW[i * HueBins + prev] + binW[i * HueBins + next])
            if (score > best) {
                best = score
                bestBin = b
            }
        }
        cellScore[i] = best / count[i]
        if (best <= 0f || cellScore[i] < 0.06f) continue
        var w = 0f
        var s = 0f
        var x = 0f
        var y = 0f
        for (d in -1..1) {
            val k = i * HueBins + (bestBin + d + HueBins) % HueBins
            w += binW[k]
            s += binS[k]
            x += binX[k]
            y += binY[k]
        }
        if (w <= 0f) continue
        cellHue[i] = wrapHue(atan2(y, x) / degToRad)
        cellSat[i] = s / w
        valid[i] = true
    }
    if (valid.none { it }) return null

    // cells with no colour copy the hue of their most colourful neighbour, a ring at a time (no mixing, no grey)
    for (pass in 0 until 16) {
        if (valid.all { it }) break
        val nh = cellHue.copyOf()
        val ns = cellSat.copyOf()
        val nv = valid.copyOf()
        for (row in 0 until MosaicRows) {
            for (col in 0 until MosaicCols) {
                val i = row * MosaicCols + col
                if (valid[i]) continue
                var bestJ = -1
                var bestScore = -1f
                for (k in 0 until 4) {
                    val rr = row + (if (k == 0) 1 else if (k == 1) -1 else 0)
                    val cc = col + (if (k == 2) 1 else if (k == 3) -1 else 0)
                    if (rr < 0 || rr >= MosaicRows || cc < 0 || cc >= MosaicCols) continue
                    val j = rr * MosaicCols + cc
                    if (valid[j] && cellScore[j] > bestScore) {
                        bestScore = cellScore[j]
                        bestJ = j
                    }
                }
                if (bestJ >= 0) {
                    nh[i] = cellHue[bestJ]
                    ns[i] = cellSat[bestJ]
                    nv[i] = true
                }
            }
        }
        cellHue = nh
        cellSat = ns
        valid = nv
    }

    // vivid and never grey; brightness follows the artwork but stays in 0.20..0.54
    val cellVal = FloatArray(cells)
    for (i in 0 until cells) {
        val mean = if (count[i] > 0) sumV[i] / count[i] else 0.3f
        cellSat[i] = (cellSat[i] * 1.2f + 0.10f).coerceIn(0.58f, 0.95f)
        cellVal[i] = 0.20f + 0.34f * mean.coerceIn(0f, 1f)
    }

    // smooth up-sampling, hue going the short way round the colour wheel; rows follow the card position on screen
    val out = IntArray(MosaicOutW * MosaicOutH)
    for (oy in 0 until MosaicOutH) {
        val screenY = (oy + 0.5f) / MosaicOutH
        val t = ((screenY - CardTop) / (CardBottom - CardTop)).coerceIn(0f, 1f)
        val gy = t * MosaicRows - 0.5f
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
            val h = lerpHue(lerpHue(cellHue[i00], cellHue[i01], tx), lerpHue(cellHue[i10], cellHue[i11], tx), ty)
            val s = (cellSat[i00] * (1 - tx) + cellSat[i01] * tx) * (1 - ty) +
                (cellSat[i10] * (1 - tx) + cellSat[i11] * tx) * ty
            var v = (cellVal[i00] * (1 - tx) + cellVal[i01] * tx) * (1 - ty) +
                (cellVal[i10] * (1 - tx) + cellVal[i11] * tx) * ty
            var argb = android.graphics.Color.HSVToColor(floatArrayOf(h, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f)))
            var steps = 0
            while (relativeLuma(Color(argb)) > MosaicMaxLuma && steps < 8 && v > 0.16f) {
                v *= 0.94f
                argb = android.graphics.Color.HSVToColor(floatArrayOf(h, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f)))
                steps++
            }
            out[oy * MosaicOutW + ox] = argb
        }
    }
    return Bitmap.createBitmap(out, MosaicOutW, MosaicOutH, Bitmap.Config.ARGB_8888).asImageBitmap()
}

private fun smooth(t: Float): Float = t * t * (3f - 2f * t)

private fun ramp(edge0: Float, edge1: Float, x: Float): Float = smooth(((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f))

/** Hue halfway between [a] and [b] along the shorter way round the colour wheel. */
private fun lerpHue(a: Float, b: Float, t: Float): Float = wrapHue(a + (((b - a + 540f) % 360f) - 180f) * t)

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
