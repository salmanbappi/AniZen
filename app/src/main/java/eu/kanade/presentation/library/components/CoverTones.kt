package eu.kanade.presentation.library.components

import android.app.Application
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import coil3.BitmapImage
import coil3.Image
import coil3.asDrawable
import coil3.compose.AsyncImagePainter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.anime.model.AnimeCover
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.nio.ByteBuffer
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

    // ANZ --> the colours (and the blurred picture) of every cover seen are kept on disk, so the Home page
    // opens already lit instead of waiting for the cover to load.
    /** Kept between launches; insertion order = oldest first. Guarded by itself. */
    private val stored = LinkedHashMap<Long, CoverTones>()

    private var saveJob: Job? = null

    init {
        loadStored()
    }
    // ANZ <--

    fun get(animeId: Long, coverVersion: Long): CoverTones? =
        cache[animeId]?.takeIf { it.coverVersion == coverVersion }
            ?: synchronized(stored) { stored[animeId] }?.takeIf { it.coverVersion == coverVersion }

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
                    keep(id, tones)
                }
            }
        }
    }

    // ANZ -->
    /** Remembers [tones] for the next launch (saved a few seconds after the last change). Main thread only. */
    private fun keep(id: Long, tones: CoverTones) {
        synchronized(stored) {
            stored.remove(id)
            stored[id] = tones
            while (stored.size > MaxStored) {
                val eldest = stored.keys.iterator()
                eldest.next()
                eldest.remove()
            }
        }
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(2500)
            save()
        }
    }

    private fun loadStored() {
        try {
            val file = File(Injekt.get<Application>().filesDir, StoreFile)
            if (!file.exists()) return
            val buf = ByteBuffer.wrap(file.readBytes())
            if (buf.remaining() < 12 || buf.getInt() != StoreMagic || buf.getInt() != StoreFormat) return
            val count = buf.getInt()
            val loaded = LinkedHashMap<Long, CoverTones>()
            for (n in 0 until count) {
                val id = buf.getLong()
                val version = buf.getLong()
                val presence = buf.getFloat()
                val top = Color(buf.getInt())
                val mid = Color(buf.getInt())
                val deep = Color(buf.getInt())
                val lightCount = buf.getInt()
                if (lightCount !in 0..8) return
                val lights = List(lightCount) { Color(buf.getInt()) }
                var mosaic: ImageBitmap? = null
                if (buf.getInt() == 1) {
                    val pixels = IntArray(BlurCols * BlurRows)
                    for (i in pixels.indices) pixels[i] = buf.getInt()
                    mosaic = Bitmap.createBitmap(pixels, BlurCols, BlurRows, Bitmap.Config.ARGB_8888).asImageBitmap()
                }
                loaded[id] = CoverTones(version, presence, top, mid, deep, lights, mosaic)
            }
            synchronized(stored) { stored.putAll(loaded) }
        } catch (_: Throwable) {
            // a damaged file only means the colours are read again from the covers
        }
    }

    @Synchronized
    private fun save() {
        try {
            val entries = synchronized(stored) { stored.map { it.key to it.value } }
            if (entries.isEmpty()) return
            val pixels = entries.map { pixelsOf(it.second.mosaic) }
            var size = 12
            for (i in entries.indices) {
                size += 8 + 8 + 4 + 12 + 4 + 4 * entries[i].second.lights.size + 4 + 4 * (pixels[i]?.size ?: 0)
            }
            val buf = ByteBuffer.allocate(size)
            buf.putInt(StoreMagic)
            buf.putInt(StoreFormat)
            buf.putInt(entries.size)
            for (i in entries.indices) {
                val (id, tones) = entries[i]
                buf.putLong(id)
                buf.putLong(tones.coverVersion)
                buf.putFloat(tones.presence)
                buf.putInt(tones.top.toArgb())
                buf.putInt(tones.mid.toArgb())
                buf.putInt(tones.deep.toArgb())
                buf.putInt(tones.lights.size)
                for (light in tones.lights) buf.putInt(light.toArgb())
                val px = pixels[i]
                if (px == null) {
                    buf.putInt(0)
                } else {
                    buf.putInt(1)
                    for (p in px) buf.putInt(p)
                }
            }
            val dir = Injekt.get<Application>().filesDir
            val tmp = File(dir, "$StoreFile.tmp")
            tmp.writeBytes(buf.array())
            val target = File(dir, StoreFile)
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
        } catch (_: Throwable) {
            // not saving only costs the instant start next time
        }
    }

    private fun pixelsOf(image: ImageBitmap?): IntArray? {
        val bitmap = image?.asAndroidBitmap() ?: return null
        if (bitmap.width != BlurCols || bitmap.height != BlurRows) return null
        val out = IntArray(BlurCols * BlurRows)
        bitmap.getPixels(out, 0, BlurCols, 0, 0, BlurCols, BlurRows)
        return out
    }
    // ANZ <--

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
        // The colour engine (see CoverColorEngine) does the work; the palette below is only the fallback for a cover
        // the engine cannot read.
        val engine = try {
            val px = IntArray(small.width * small.height)
            small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
            CoverColorEngine.analyze(px, small.width, small.height)
        } catch (_: Throwable) {
            null
        }
        if (engine != null) {
            val image = Bitmap.createBitmap(
                engine.mosaic,
                CoverColorEngine.Cols,
                CoverColorEngine.Rows,
                Bitmap.Config.ARGB_8888,
            ).asImageBitmap()
            return CoverTones(
                coverVersion = version,
                presence = engine.presence,
                top = Color(engine.top),
                mid = Color(engine.mid),
                deep = Color(engine.deep),
                lights = engine.lights.map { Color(it) },
                mosaic = image,
            )
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
    var pale = 0f
    val paleCandidates = ArrayList<Candidate>()
    for (swatch in swatches) {
        android.graphics.Color.colorToHSV(swatch.rgb, hsv)
        if (hsv[2] < 0.12f) continue
        val share = swatch.population.toFloat() / total
        // area counts (its square root, so small bright details still get a say), and so does colourfulness
        val score = sqrt(share) * (0.35f + hsv[1]) * (0.70f + 0.30f * hsv[2])
        if (hsv[1] >= 0.22f) {
            presence += share
            candidates.add(Candidate(hsv[0], hsv[1], hsv[2], score))
        } else if (hsv[1] >= 0.14f) {
            // very pale tints: only used when the cover has almost no real colour (see below)
            pale += share
            paleCandidates.add(Candidate(hsv[0], hsv[1], hsv[2], score))
        }
        // greys, blacks and whites carry no hue
    }
    // A pale cover whose only vivid spots are a few letters or details: the pale tint of the artwork leads instead
    if (presence < 0.12f && paleCandidates.isNotEmpty()) {
        candidates.addAll(paleCandidates)
        presence += 0.5f * pale
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
        vividColor(wrapHue(main.hue - 14f), maxOf(main.sat, 0.58f + 0.17f * ramp(0.16f, 0.26f, main.sat)), 1f),
    )

    // the dark base stays in the main hue, leaning a little towards the second one
    val delta = (((picked[1].hue - main.hue + 540f) % 360f) - 180f).coerceIn(-60f, 60f)
    val satFloor = 0.54f + 0.16f * ramp(0.16f, 0.26f, main.sat)
    val sat = (main.sat * 1.15f).coerceIn(satFloor, 0.95f)
    return CoverTones(
        coverVersion = version,
        presence = presence.coerceIn(0f, 1f),
        top = hsvColor(main.hue, sat, 0.60f),
        mid = hsvColor(wrapHue(main.hue + 0.35f * delta), sat * 0.95f, 0.42f),
        deep = hsvColor(wrapHue(main.hue + 0.60f * delta), (sat * 0.9f).coerceIn(0.50f, 0.85f), 0.32f),
        lights = lights,
        mosaic = mosaic,
    )
}

// ANZ --> file where the colours are kept; raise StoreFormat whenever the colour formulas change, so old data is dropped
private const val StoreFile = "cover_tones.bin"
private const val StoreMagic = 0x414E5A54
private const val StoreFormat = 7
private const val MaxStored = 48
// ANZ <--

private const val BlurCols = 32
private const val BlurRows = 48
private const val HueBins = 12
private const val BlurSmall = 3
private const val BlurWide = 8
private const val BlurFinal = 2
private const val HuePower = 2f
private const val HuePrior = 0.6f
private const val BlurMaxLuma = 0.25f
private const val OpenRadius = 2
private const val HaloCols = 48
private const val HaloRows = 72
private const val HaloLight = 1.35f

/**
 * The glow that sits around the hero card: the blurred cover colours (a little brighter), stretched over the card plus a
 * margin and fading out towards its edges, so the card seems to float in its own light. [fx] and [fy] are the margin as
 * a fraction of the whole width / height.
 */
internal fun buildHalo(mosaic: ImageBitmap, fx: Float, fy: Float): ImageBitmap? {
    val source = mosaic.asAndroidBitmap()
    if (source.width != BlurCols || source.height != BlurRows) return null
    val px = IntArray(BlurCols * BlurRows)
    source.getPixels(px, 0, BlurCols, 0, 0, BlurCols, BlurRows)
    val out = IntArray(HaloCols * HaloRows)
    for (y in 0 until HaloRows) {
        val v = (y + 0.5f) / HaloRows
        val sy = (v * BlurRows - 0.5f).coerceIn(0f, BlurRows - 1f)
        val y0 = sy.toInt()
        val y1 = minOf(y0 + 1, BlurRows - 1)
        val ty = sy - y0
        val edgeY = smooth((minOf(v, 1f - v) / fy.coerceAtLeast(0.01f)).coerceIn(0f, 1f))
        for (x in 0 until HaloCols) {
            val u = (x + 0.5f) / HaloCols
            val sx = (u * BlurCols - 0.5f).coerceIn(0f, BlurCols - 1f)
            val x0 = sx.toInt()
            val x1 = minOf(x0 + 1, BlurCols - 1)
            val tx = sx - x0
            val edgeX = smooth((minOf(u, 1f - u) / fx.coerceAtLeast(0.01f)).coerceIn(0f, 1f))
            var r = 0f
            var g = 0f
            var b = 0f
            for (k in 0 until 4) {
                val xx = if ((k and 1) == 0) x0 else x1
                val yy = if ((k and 2) == 0) y0 else y1
                val wx = if ((k and 1) == 0) 1f - tx else tx
                val wy = if ((k and 2) == 0) 1f - ty else ty
                val p = px[yy * BlurCols + xx]
                r += wx * wy * ((p shr 16) and 0xFF)
                g += wx * wy * ((p shr 8) and 0xFF)
                b += wx * wy * (p and 0xFF)
            }
            val ri = (r * HaloLight).toInt().coerceIn(0, 255)
            val gi = (g * HaloLight).toInt().coerceIn(0, 255)
            val bi = (b * HaloLight).toInt().coerceIn(0, 255)
            val ai = ((edgeX * edgeY) * 255f + 0.5f).toInt().coerceIn(0, 255)
            out[y * HaloCols + x] = (ai shl 24) or (ri shl 16) or (gi shl 8) or bi
        }
    }
    return Bitmap.createBitmap(out, HaloCols, HaloRows, Bitmap.Config.ARGB_8888).asImageBitmap()
}

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
    // Pass 1: the weight and the hue family of every pixel in the middle of the artwork
    val area = cw * ch
    val weight = FloatArray(area)
    val family = IntArray(area) { -1 }
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
                val at = (y - y0) * cw + (x - x0)
                weight[at] = w
                family[at] = (hsv[0] / 30f).toInt().coerceIn(0, HueBins - 1)
            }
        }
    }
    // Thin strokes (letters, logos, outlines) are not areas of colour: a hue only counts where it forms a patch wider
    // than a few pixels, so a handful of bright letters cannot colour the whole page.
    val kept = FloatArray(area)
    val layer = FloatArray(area)
    for (f in 0 until HueBins) {
        var any = false
        for (i in 0 until area) {
            val v = if (family[i] == f) weight[i] else 0f
            layer[i] = v
            if (v > 0f) any = true
        }
        if (!any) continue
        val opened = openMap(layer, cw, ch, OpenRadius)
        for (i in 0 until area) if (family[i] == f) kept[i] = opened[i]
    }
    // Pass 2: what survives votes
    for (y in y0 until y1) {
        val row = ((y - y0) * BlurRows / ch).coerceAtMost(BlurRows - 1)
        for (x in x0 until x1) {
            val at = (y - y0) * cw + (x - x0)
            val w = kept[at]
            if (w <= 0f) continue
            val col = ((x - x0) * BlurCols / cw).coerceAtMost(BlurCols - 1)
            val p = pixels[y * bw + x]
            val k = family[at] * cells + row * BlurCols + col
            binW[k] += w
            binR[k] += w * ((p shr 16) and 0xFF) / 255f
            binG[k] += w * ((p shr 8) and 0xFF) / 255f
            binB[k] += w * (p and 0xFF) / 255f
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
        // pastel parts keep a calmer floor, so a pale cover turns into a clean tint and not into neon
        val satFloor = 0.56f + 0.10f * ramp(0.14f, 0.30f, sat)
        sat = (sat * 1.5f + 0.18f).coerceIn(satFloor, 1.0f)
        // brightness follows the artwork (light parts of the cover are the light parts of the backdrop)
        var value = (0.20f + 0.50f * (vBlur[i] / cBlur[i].coerceAtLeast(1e-6f))).coerceIn(0.30f, 0.64f)
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

/** Morphological opening (shrink, then grow, square window of [radius]): keeps only shapes wider than the window. */
private fun openMap(src: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
    val a = FloatArray(src.size)
    val b = FloatArray(src.size)
    slide(src, a, w, h, radius, horizontal = true, takeMin = true)
    slide(a, b, w, h, radius, horizontal = false, takeMin = true)
    slide(b, a, w, h, radius, horizontal = true, takeMin = false)
    slide(a, b, w, h, radius, horizontal = false, takeMin = false)
    return b
}

private fun slide(src: FloatArray, dst: FloatArray, w: Int, h: Int, radius: Int, horizontal: Boolean, takeMin: Boolean) {
    for (y in 0 until h) {
        for (x in 0 until w) {
            var best = if (takeMin) Float.MAX_VALUE else -Float.MAX_VALUE
            for (d in -radius..radius) {
                val xx = if (horizontal) (x + d).coerceIn(0, w - 1) else x
                val yy = if (horizontal) y else (y + d).coerceIn(0, h - 1)
                val v = src[yy * w + xx]
                if (takeMin) {
                    if (v < best) best = v
                } else {
                    if (v > best) best = v
                }
            }
            dst[y * w + x] = best
        }
    }
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
