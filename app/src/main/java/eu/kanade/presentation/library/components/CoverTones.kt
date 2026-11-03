package eu.kanade.presentation.library.components

import android.app.Application
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import coil3.BitmapImage
import coil3.Image
import coil3.asDrawable
import coil3.compose.AsyncImagePainter
import eu.kanade.tachiyomi.util.system.CoverColorObserver
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
 * [mosaic] is a tiny, smooth, vivid picture of the cover (32x48 px) that the backdrop stretches over the whole
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

    // ANZ: the full colours of every cover seen are kept on the phone, so the next app start paints the real
    // base and all the lights from the very first frame (only the blurred picture is rebuilt, and it fades in)
    private const val PrefsName = "anizen_cover_tones"
    private const val MaxSaved = 200

    private val prefs by lazy {
        Injekt.get<Application>().getSharedPreferences(PrefsName, android.content.Context.MODE_PRIVATE)
    }

    init {
        try {
            for ((key, value) in prefs.all) {
                val id = key.toLongOrNull() ?: continue
                val tones = (value as? String)?.let(::decode) ?: continue
                cache[id] = tones
            }
        } catch (_: Throwable) {
        }
    }

    private fun encode(t: CoverTones): String =
        listOf(
            t.coverVersion.toString(),
            t.presence.toString(),
            t.top.toArgb().toString(),
            t.mid.toArgb().toString(),
            t.deep.toArgb().toString(),
            t.lights.joinToString(",") { it.toArgb().toString() },
        ).joinToString("|")

    private fun decode(raw: String): CoverTones? {
        val p = raw.split('|')
        if (p.size != 6) return null
        val lights = if (p[5].isEmpty()) emptyList() else p[5].split(',').map { Color(it.toIntOrNull() ?: return null) }
        if (lights.isNotEmpty() && lights.size < 4) return null
        return CoverTones(
            coverVersion = p[0].toLongOrNull() ?: return null,
            presence = p[1].toFloatOrNull() ?: return null,
            top = Color(p[2].toIntOrNull() ?: return null),
            mid = Color(p[3].toIntOrNull() ?: return null),
            deep = Color(p[4].toIntOrNull() ?: return null),
            lights = lights,
        )
    }

    private fun save(id: Long, tones: CoverTones) {
        val value = encode(tones)
        scope.launch {
            try {
                val editor = prefs.edit()
                if (prefs.all.size > MaxSaved) editor.clear()
                editor.putString(id.toString(), value).apply()
            } catch (_: Throwable) {
            }
        }
    }

    fun get(animeId: Long, coverVersion: Long): CoverTones? =
        cache[animeId]?.takeIf { it.coverVersion == coverVersion }

    /** Reads the colours of a cover that has just loaded, once per cover version. Call it on the main thread. */
    fun request(cover: AnimeCover, result: AsyncImagePainter.State.Success) {
        val id = cover.animeId
        val version = cover.lastModified
        // a cover restored from the phone has its colours but not its blurred picture yet: read it again once
        if (get(id, version)?.mosaic != null || !inFlight.add(id)) return
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
                    cache[id] = tones
                    save(id, tones)
                    // ANZ: remember the real main colour on the phone, so the next app start already
                    // paints this cover's own colour instead of the one the old extractor saved
                    tones.lights.firstOrNull()?.let { main ->
                        CoverColorObserver.update(id, main.toArgb(), version)
                    }
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
        // a small copy is plenty to find the main colours
        val scale = 160f / maxOf(software.width, software.height).coerceAtLeast(1)
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

private const val BlurCols = 32
private const val BlurRows = 48
private const val HueBins = 12
private const val BlurSmall = 3
private const val BlurWide = 8
private const val BlurFinal = 2
private const val HuePower = 2f
private const val HuePrior = 0.6f
private const val BlurMaxLuma = 0.22f

private class Voted(val r: FloatArray, val g: FloatArray, val b: FloatArray, val mass: FloatArray)

/**
 * A blurred, vivid copy of the cover (32x48 px, the screen stretches it): the colours of the artwork where they really
 * are. Colours are not averaged (teal + pink would give mud): in every area the dominant hue wins, the cover's main
 * hues weigh more than small accents, and white / grey / black pixels do not vote, so they take the hue of what is
 * around them. Then saturation gets a floor and brightness a ceiling (white text stays readable).
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
    if (cw < 8 || ch < 8) return null

    val pixels = IntArray(bw * bh)
    bitmap.getPixels(pixels, 0, bw, 0, 0, bw, bh)
    val cells = BlurCols * BlurRows
    val binW = FloatArray(HueBins * cells)
    val binR = FloatArray(HueBins * cells)
    val binG = FloatArray(HueBins * cells)
    val binB = FloatArray(HueBins * cells)
    val sumV = FloatArray(cells)
    val count = FloatArray(cells)
    val hsv = FloatArray(3)
    for (y in y0 until y1) {
        val row = ((y - y0) * BlurRows / ch).coerceAtMost(BlurRows - 1)
        for (x in x0 until x1) {
            val col = ((x - x0) * BlurCols / cw).coerceAtMost(BlurCols - 1)
            val p = pixels[y * bw + x]
            android.graphics.Color.colorToHSV(p, hsv)
            val cell = row * BlurCols + col
            sumV[cell] += hsv[2]
            count[cell] += 1f
            // only pixels with real colour vote for a hue (greys, whites and blacks hardly do)
            val w = ramp(0.10f, 0.35f, hsv[1]) * ramp(0.04f, 0.20f, hsv[2])
            if (w > 0f) {
                val k = (hsv[0] / 30f).toInt().coerceIn(0, HueBins - 1) * cells + cell
                binW[k] += w
                binR[k] += w * ((p shr 16) and 0xFF) / 255f
                binG[k] += w * ((p shr 8) and 0xFF) / 255f
                binB[k] += w * (p and 0xFF) / 255f
            }
        }
    }

    val small = vote(binW, binR, binG, binB, count, cells, BlurSmall)
    val wide = vote(binW, binR, binG, binB, count, cells, BlurWide)
    if ((wide.mass.maxOrNull() ?: 0f) < 0.01f) return null // a cover without any colour: the page keeps its tint

    val vBlur = blur3(sumV, BlurSmall)
    val cBlur = blur3(count, BlurSmall)
    val outR = FloatArray(cells)
    val outG = FloatArray(cells)
    val outB = FloatArray(cells)
    val hsvSmall = FloatArray(3)
    val hsvWide = FloatArray(3)
    for (i in 0 until cells) {
        // where there is little colour nearby, the wide (calmer) reading takes over
        val t = smooth(((small.mass[i] - 0.03f) / (0.20f - 0.03f)).coerceIn(0f, 1f))
        val r = small.r[i] * t + wide.r[i] * (1f - t)
        val g = small.g[i] * t + wide.g[i] * (1f - t)
        val b = small.b[i] * t + wide.b[i] * (1f - t)
        rgbToHsv(r, g, b, hsvSmall)
        rgbToHsv(wide.r[i], wide.g[i], wide.b[i], hsvWide)
        var hue = hsvSmall[0]
        var sat = hsvSmall[1]
        if (sat < 0.12f) {
            hue = hsvWide[0]
            sat = maxOf(sat, hsvWide[1])
        }
        sat = (sat * 1.35f + 0.12f).coerceIn(0.50f, 0.95f)
        // brightness follows the artwork (light parts of the cover are the light parts of the backdrop)
        var value = (0.18f + 0.46f * (vBlur[i] / cBlur[i].coerceAtLeast(1e-6f))).coerceIn(0.27f, 0.60f)
        var argb = android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value))
        var steps = 0
        while (relativeLuma(Color(argb)) > BlurMaxLuma && steps < 8) {
            value *= 0.94f
            argb = android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value))
            steps++
        }
        outR[i] = android.graphics.Color.red(argb) / 255f
        outG[i] = android.graphics.Color.green(argb) / 255f
        outB[i] = android.graphics.Color.blue(argb) / 255f
    }

    // a last, light blur so the border between two winning hues is soft
    val fr = blur3(outR, BlurFinal)
    val fg = blur3(outG, BlurFinal)
    val fb = blur3(outB, BlurFinal)
    val out = IntArray(cells) {
        android.graphics.Color.rgb(
            (fr[it] * 255f + 0.5f).toInt().coerceIn(0, 255),
            (fg[it] * 255f + 0.5f).toInt().coerceIn(0, 255),
            (fb[it] * 255f + 0.5f).toInt().coerceIn(0, 255),
        )
    }
    return Bitmap.createBitmap(out, BlurCols, BlurRows, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** For every cell, the colour of the winning hue around it (blurred by [radius] cells) and how much colour is there. */
private fun vote(
    binW: FloatArray,
    binR: FloatArray,
    binG: FloatArray,
    binB: FloatArray,
    count: FloatArray,
    cells: Int,
    radius: Int,
): Voted {
    val bw = Array(HueBins) { blur3(binW.copyOfRange(it * cells, (it + 1) * cells), radius) }
    val br = Array(HueBins) { blur3(binR.copyOfRange(it * cells, (it + 1) * cells), radius) }
    val bg = Array(HueBins) { blur3(binG.copyOfRange(it * cells, (it + 1) * cells), radius) }
    val bb = Array(HueBins) { blur3(binB.copyOfRange(it * cells, (it + 1) * cells), radius) }
    val blurCount = blur3(count, radius)

    // the cover's main hues weigh more than its small accents
    val global = FloatArray(HueBins) { bw[it].sum() }
    val globalMax = (global.maxOrNull() ?: 0f).coerceAtLeast(1e-9f)
    val prior = FloatArray(HueBins) { (global[it] / globalMax).pow(HuePrior) }
    var scoreMax = 1e-9f
    for (h in 0 until HueBins) {
        val prev = (h + HueBins - 1) % HueBins
        val next = (h + 1) % HueBins
        for (i in 0 until cells) {
            val score = bw[h][i] + 0.5f * (bw[prev][i] + bw[next][i])
            if (score > scoreMax) scoreMax = score
        }
    }

    val outR = FloatArray(cells)
    val outG = FloatArray(cells)
    val outB = FloatArray(cells)
    val mass = FloatArray(cells)
    for (i in 0 until cells) {
        var total = 0f
        var sr = 0f
        var sg = 0f
        var sb = 0f
        var all = 0f
        for (h in 0 until HueBins) {
            val w = bw[h][i]
            all += w
            if (w <= 1e-7f) continue
            // a hue also counts for its two neighbouring bins
            val score = w + 0.5f * (bw[(h + HueBins - 1) % HueBins][i] + bw[(h + 1) % HueBins][i])
            val u = (score / scoreMax).pow(HuePower) * prior[h]
            total += u
            sr += u * br[h][i] / w
            sg += u * bg[h][i] / w
            sb += u * bb[h][i] / w
        }
        if (total > 1e-12f) {
            outR[i] = sr / total
            outG[i] = sg / total
            outB[i] = sb / total
        }
        mass[i] = all / blurCount[i].coerceAtLeast(1e-6f)
    }
    return Voted(outR, outG, outB, mass)
}

/** Three box blurs in a row (close to a gaussian) of [radius] cells over the grid; the edges repeat. */
private fun blur3(src: FloatArray, radius: Int): FloatArray {
    var cur = src.copyOf()
    val tmp = FloatArray(src.size)
    val n = (2 * radius + 1).toFloat()
    repeat(3) {
        for (row in 0 until BlurRows) {
            for (col in 0 until BlurCols) {
                var sum = 0f
                for (d in -radius..radius) sum += cur[row * BlurCols + (col + d).coerceIn(0, BlurCols - 1)]
                tmp[row * BlurCols + col] = sum / n
            }
        }
        for (row in 0 until BlurRows) {
            for (col in 0 until BlurCols) {
                var sum = 0f
                for (d in -radius..radius) sum += tmp[(row + d).coerceIn(0, BlurRows - 1) * BlurCols + col]
                cur[row * BlurCols + col] = sum / n
            }
        }
    }
    return cur
}

private fun rgbToHsv(r: Float, g: Float, b: Float, out: FloatArray) {
    android.graphics.Color.RGBToHSV(
        (r * 255f + 0.5f).toInt().coerceIn(0, 255),
        (g * 255f + 0.5f).toInt().coerceIn(0, 255),
        (b * 255f + 0.5f).toInt().coerceIn(0, 255),
        out,
    )
}

private fun smooth(t: Float): Float = t * t * (3f - 2f * t)

private fun ramp(edge0: Float, edge1: Float, x: Float): Float = smooth(((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f))

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

private const val MaxLightLuma = 0.30f

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
