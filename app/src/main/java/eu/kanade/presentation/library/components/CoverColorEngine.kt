package eu.kanade.presentation.library.components

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

// ANZ --> Colour engine of the Home backdrop (plain Kotlin on purpose: no Android types, so it is easy to test).
//
// What it does with a cover (a small bitmap, 160 px on its long side):
//  1. Works in OKLab / OKLCH, a colour space where the same lightness and chroma look the same in every hue
//     (HSV makes brown turn crimson and yellow turn mustard).
//  2. Every pixel with real colour votes for its hue family. Browns and skin tones count less, the lower part of the
//     cover counts less (titles live there), thin strokes are dropped and so is every patch of colour that is too small
//     to be an area (letters, logos, little details), so a title can no longer colour the whole page.
//  3. The votes are spread over a 32x48 grid: in each cell the dominant hue wins (colours are never averaged, teal +
//     pink would give mud). Cells without any colour nearby take the main colour of the cover, not grey.
//  4. Each cell is graded in OKLCH: dark enough for white text, a soft chroma for pale covers and a vivid one for
//     colourful ones, always inside the sRGB gamut.
//  5. The lights and the dark base come from the same hue statistics as the picture, so everything agrees.

/** Everything the Home backdrop needs from one cover, as plain numbers (ARGB ints). */
internal class EngineResult(
    /** [CoverColorEngine.Cols] x [CoverColorEngine.Rows] ARGB pixels, row by row. */
    val mosaic: IntArray,
    val top: Int,
    val mid: Int,
    val deep: Int,
    /** Four lights, the first one in the main hue of the cover. */
    val lights: IntArray,
    /** 0..1, how much of the artwork has real colour (a grey cover gets calmer lights). */
    val presence: Float,
    /** Debug: the hue statistics (mass per 30 degree family). */
    val masses: FloatArray,
)

/**
 * What the engine looked at in a cover, for the "Editar fondo" window to show. Filled only when the caller passes one to
 * [CoverColorEngine.analyze]; the colours the engine returns are the same with or without it.
 */
internal class EngineExplain {
    /** The part of the cover that was analysed: left, top, right, bottom as fractions of the whole cover. */
    var area = floatArrayOf(0f, 0f, 1f, 1f)

    /** Size of [kept]. */
    var keptW = 0
    var keptH = 0

    /** [keptW] x [keptH] values, row by row, over [area]: how much each part counts for the colours (0 = not at all). */
    var kept = FloatArray(0)

    /**
     * For each of the four lights (top-left, right, top-right, left): the rectangle of the cover its colour was taken
     * from (left, top, right, bottom as fractions of the whole cover), or null when no part of the cover had colour
     * and the colours of the whole cover were used instead.
     */
    val lightRect = arrayOfNulls<FloatArray>(4)

    /** For each light: where its colour was found (x, y as fractions of the whole cover), or null. */
    val lightAt = arrayOfNulls<FloatArray>(4)
}

internal object CoverColorEngine {
    const val Cols = 32
    const val Rows = 48

    /**
     * Overall intensity of the backdrop colours. 1.0 is the standard look; lower is calmer (0.7 is soft), higher is
     * louder (1.25 is strong). Change it and compile again: the saved colours are recalculated by themselves.
     */
    const val Intensity = 1.0f

    private const val Bins = 12
    private const val OpenRadius = 2
    private const val BlurSmall = 3
    private const val BlurWide = 8
    private const val HueExponent = 2.0f
    private const val HuePrior = 0.6f
    private const val FillMass = 0.04f
    private const val MinAreaShare = 0.010f
    private const val BrownCut = 0.65f
    private const val SkinCut = 0.50f
    private const val MaxMosaicLuma = 0.25f
    private const val MaxLightLuma = 0.30f

    // how much of the sRGB gamut a colour may use. 1.0 is the electric edge (pure red with no green or blue in it).
    // Reds, crimsons and magentas are the loudest colours on a dark page, so they stop earlier than blues and greens.
    private val SatHue = floatArrayOf(0f, 0.78f, 30f, 0.78f, 60f, 0.90f, 100f, 0.90f, 150f, 0.95f, 200f, 0.95f, 260f, 0.95f, 300f, 0.90f, 330f, 0.84f, 360f, 0.78f)
    private const val FillSoften = 0.12f
    private const val RedAccentSat = 0.93f
    private const val DensityRadius = 6
    // Where each light takes its colour from: the margin of the cover on the side where the light sits. Three rectangles
    // per side (left, top, right, bottom as fractions of the analysed area), each one reaching further in: when the
    // outer margin is white, grey or black the scan moves inwards to the first real colour.
    // Order: top-left, right, top-right, left, top band.
    private val EdgeRects = arrayOf(
        floatArrayOf(0f, 0f, 0.55f, 0.32f, 0f, 0f, 0.62f, 0.48f, 0f, 0f, 0.75f, 0.65f),
        floatArrayOf(0.62f, 0.22f, 1f, 0.78f, 0.50f, 0.15f, 1f, 0.85f, 0.35f, 0.10f, 1f, 0.90f),
        floatArrayOf(0.45f, 0f, 1f, 0.32f, 0.38f, 0f, 1f, 0.48f, 0.25f, 0f, 1f, 0.65f),
        floatArrayOf(0f, 0.22f, 0.38f, 0.78f, 0f, 0.15f, 0.50f, 0.85f, 0f, 0.10f, 0.65f, 0.90f),
        floatArrayOf(0.15f, 0f, 0.85f, 0.30f, 0.10f, 0f, 0.90f, 0.45f, 0f, 0f, 1f, 0.60f),
    )
    private const val EdgeMinCover = 0.035f
    private const val OliveTurn = 14f
    private const val RedDeepen = 0.03f
    private const val OliveLift = 0.04f
    private val MosaicSatScale = 1.00f * Intensity
    private val LightSatScale = 1.04f * Intensity

    private val toLinear = FloatArray(256) { i ->
        val c = i / 255f
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    }

    // ---------- colour maths ----------

    private fun ramp(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun toCoded(linear: Float): Float {
        val c = linear.coerceIn(0f, 1f)
        return if (c <= 0.0031308f) 12.92f * c else 1.055f * c.pow(1f / 2.4f) - 0.055f
    }

    /** sRGB (0..255 ints packed as ARGB) to OKLab, written into [out] (L, a, b). */
    private fun srgbToOklab(argb: Int, out: FloatArray) {
        val r = toLinear[(argb shr 16) and 0xFF]
        val g = toLinear[(argb shr 8) and 0xFF]
        val b = toLinear[argb and 0xFF]
        val l = cbrt(0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b)
        val m = cbrt(0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b)
        val s = cbrt(0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b)
        out[0] = 0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s
        out[1] = 1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s
        out[2] = 0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s
    }

    /** OKLab to coded sRGB (0..1) in [out]; returns true when the colour is inside the sRGB gamut. */
    private fun oklabToSrgb(lightness: Float, a: Float, b: Float, out: FloatArray): Boolean {
        val l = lightness + 0.3963377774f * a + 0.2158037573f * b
        val m = lightness - 0.1055613458f * a - 0.0638541728f * b
        val s = lightness - 0.0894841775f * a - 1.2914855480f * b
        val l3 = l * l * l
        val m3 = m * m * m
        val s3 = s * s * s
        val r = 4.0767416621f * l3 - 3.3077115913f * m3 + 0.2309699292f * s3
        val g = -1.2684380046f * l3 + 2.6097574011f * m3 - 0.3413193965f * s3
        val bl = -0.0041960863f * l3 - 0.7034186147f * m3 + 1.7076147010f * s3
        val eps = 0.0005f
        val inside = r >= -eps && r <= 1f + eps && g >= -eps && g <= 1f + eps && bl >= -eps && bl <= 1f + eps
        out[0] = toCoded(r)
        out[1] = toCoded(g)
        out[2] = toCoded(bl)
        return inside
    }

    /** OKLCH to coded sRGB; when the colour is outside the gamut its chroma is reduced until it fits. */
    private fun oklchToSrgb(lightness: Float, chroma: Float, hueDeg: Float, out: FloatArray) {
        val rad = hueDeg * (PI.toFloat() / 180f)
        val cs = cos(rad)
        val sn = sin(rad)
        if (oklabToSrgb(lightness, chroma * cs, chroma * sn, out)) return
        var lo = 0f
        var hi = chroma
        for (i in 0 until 14) {
            val mid = (lo + hi) * 0.5f
            if (oklabToSrgb(lightness, mid * cs, mid * sn, out)) lo = mid else hi = mid
        }
        oklabToSrgb(lightness, lo * cs, lo * sn, out)
    }

    private fun linearOf(coded: Float): Float =
        if (coded <= 0.04045f) coded / 12.92f else ((coded + 0.055f) / 1.055f).pow(2.4f)

    /** Relative luminance (0..1) of a coded sRGB colour. */
    private fun luma(rgb: FloatArray): Float =
        0.2126f * linearOf(rgb[0]) + 0.7152f * linearOf(rgb[1]) + 0.0722f * linearOf(rgb[2])

    private fun pack(rgb: FloatArray): Int {
        val r = (rgb[0] * 255f + 0.5f).toInt().coerceIn(0, 255)
        val g = (rgb[1] * 255f + 0.5f).toInt().coerceIn(0, 255)
        val b = (rgb[2] * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun hueOf(a: Float, b: Float): Float {
        val deg = atan2(b, a) * (180f / PI.toFloat())
        return if (deg < 0f) deg + 360f else deg
    }

    /** 1 right on red / crimson / orange-red, fading to 0 about 50 degrees away. */
    private fun redness(hueDeg: Float): Float {
        val h = ((hueDeg % 360f) + 360f) % 360f
        val away = abs(h - 12f)
        return 1f - ramp(20f, 55f, minOf(away, 360f - away))
    }

    /** 1 across the whole warm side (crimson, red, orange, amber), fading to 0 well before pink-violet and yellow-green. */
    private fun warmness(hueDeg: Float): Float {
        val h = ((hueDeg % 360f) + 360f) % 360f
        val away = abs(h - 30f)
        return 1f - ramp(36f, 76f, minOf(away, 360f - away))
    }

    /**
     * Share of the gamut a hue may use (see [SatHue]). [calm] 0..1 is how much red fills what is seen: a red that is the
     * whole picture is calmed, while a red accent next to a blue keeps its full colour.
     */
    private fun hueSat(hueDeg: Float, calm: Float): Float {
        val h = ((hueDeg % 360f) + 360f) % 360f
        var i = 0
        while (i < SatHue.size - 2 && h > SatHue[i + 2]) i += 2
        val h0 = SatHue[i]
        val h1 = SatHue[i + 2]
        val t = if (h1 > h0) ((h - h0) / (h1 - h0)).coerceIn(0f, 1f) else 0f
        val s = SatHue[i + 1] + (SatHue[i + 3] - SatHue[i + 1]) * t
        return s + (1f - calm) * maxOf(0f, RedAccentSat - s) * warmness(h)
    }

    /** Yellow-olive turns into khaki mud when it is dark: nudged towards amber, which stays golden. */
    private fun hueNudge(hueDeg: Float): Float {
        val h = ((hueDeg % 360f) + 360f) % 360f
        val zone = ramp(86f, 98f, h) * (1f - ramp(112f, 124f, h))
        return h - OliveTurn * zone
    }

    /** Lightness shift of a hue: reds go deeper (carmine instead of salmon), yellow-olive goes lighter (gold instead of mud). */
    private fun hueShiftL(hueDeg: Float, calm: Float): Float {
        val h = ((hueDeg % 360f) + 360f) % 360f
        val olive = ramp(62f, 78f, h) * (1f - ramp(112f, 135f, h))
        return -RedDeepen * redness(h) * calm + OliveLift * olive
    }

    /** The most chroma sRGB can show at this lightness and hue. */
    private fun gamutMax(lightness: Float, hueDeg: Float, out: FloatArray): Float {
        val rad = hueDeg * (PI.toFloat() / 180f)
        val cs = cos(rad)
        val sn = sin(rad)
        var lo = 0f
        var hi = 0.40f
        for (i in 0 until 11) {
            val mid = (lo + hi) * 0.5f
            if (oklabToSrgb(lightness, mid * cs, mid * sn, out)) lo = mid else hi = mid
        }
        return lo
    }

    /**
     * A colour of the given OKLCH, darkened until its luma fits [maxLuma] (white text and the page need that) and never
     * using more than [sat] of the gamut at its own lightness and hue.
     */
    private fun graded(lightness: Float, chroma: Float, hueDeg: Float, maxLuma: Float, minLightness: Float, sat: Float, calm: Float, out: FloatArray) {
        var l = lightness
        var c = minOf(chroma, minOf(sat * hueSat(hueDeg, calm), 1f) * gamutMax(l, hueDeg, out))
        oklchToSrgb(l, c, hueDeg, out)
        var steps = 0
        while (luma(out) > maxLuma && steps < 12 && l > minLightness) {
            l *= 0.965f
            c = minOf(chroma, minOf(sat * hueSat(hueDeg, calm), 1f) * gamutMax(l, hueDeg, out))
            oklchToSrgb(l, c, hueDeg, out)
            steps++
        }
    }

    /**
     * Blends two colours of the a/b plane the way a painter would: the hue moves between them and the chroma follows
     * the mix, instead of the two colours cancelling into grey (orange + blue must not give a dull brown).
     */
    private fun mixAB(a1: Float, b1: Float, a2: Float, b2: Float, t: Float, out: FloatArray) {
        val a = a1 * t + a2 * (1f - t)
        val b = b1 * t + b2 * (1f - t)
        val c1 = hypot(a1, b1) * t
        val c2 = hypot(a2, b2) * (1f - t)
        val target = c1 + c2
        val m = hypot(a, b)
        if (m < 0.35f * target) {
            // opposite hues: the stronger one wins
            if (c1 >= c2) {
                out[0] = a1
                out[1] = b1
            } else {
                out[0] = a2
                out[1] = b2
            }
        } else if (m > 1e-6f) {
            val k = target / m
            out[0] = a * k
            out[1] = b * k
        } else {
            out[0] = a
            out[1] = b
        }
    }

    /**
     * The dominant colour of a rectangle of the analysed area ([rect] = left, top, right, bottom as fractions of it):
     * the hue cluster with the most mass, as lightness / a / b in [out]. Returns how much of the rectangle that cluster
     * covers, 0 when there is no colour in it at all.
     */
    private fun regionColour(
        kept: FloatArray, family: IntArray, pl: FloatArray, pa: FloatArray, pb: FloatArray,
        cw: Int, ch: Int, rect: FloatArray, out: FloatArray, centroid: FloatArray? = null,
    ): Float {
        val x0 = (rect[0] * cw).toInt().coerceIn(0, cw - 1)
        val x1 = (rect[2] * cw).toInt().coerceIn(x0 + 1, cw)
        val y0 = (rect[1] * ch).toInt().coerceIn(0, ch - 1)
        val y1 = (rect[3] * ch).toInt().coerceIn(y0 + 1, ch)
        val m = FloatArray(Bins)
        val sl = FloatArray(Bins)
        val sa = FloatArray(Bins)
        val sb = FloatArray(Bins)
        val sx = if (centroid != null) FloatArray(Bins) else null
        val sy = if (centroid != null) FloatArray(Bins) else null
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                val at = y * cw + x
                val w = kept[at]
                if (w <= 0f) continue
                val f = family[at]
                m[f] += w
                sl[f] += w * pl[at]
                sa[f] += w * pa[at]
                sb[f] += w * pb[at]
                if (sx != null && sy != null) {
                    sx[f] += w * (x + 0.5f)
                    sy[f] += w * (y + 0.5f)
                }
            }
        }
        var best = -1
        var bestScore = 0f
        for (f in 0 until Bins) {
            val score = m[f] + 0.5f * (m[(f + Bins - 1) % Bins] + m[(f + 1) % Bins])
            if (score > bestScore) {
                bestScore = score
                best = f
            }
        }
        if (best < 0) return 0f
        var tm = 0f
        var tl = 0f
        var ta = 0f
        var tb = 0f
        var tx = 0f
        var ty = 0f
        for (d in -1..1) {
            val k = (best + d + Bins) % Bins
            tm += m[k]
            tl += sl[k]
            ta += sa[k]
            tb += sb[k]
            if (sx != null && sy != null) {
                tx += sx[k]
                ty += sy[k]
            }
        }
        if (tm < 1e-6f) return 0f
        if (centroid != null) {
            centroid[0] = tx / tm
            centroid[1] = ty / tm
        }
        out[0] = tl / tm
        out[1] = ta / tm
        out[2] = tb / tm
        return tm / ((x1 - x0) * (y1 - y0))
    }

    // ---------- image helpers ----------

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

    /** Morphological opening: keeps only shapes wider than the window (thin strokes disappear). */
    private fun openMap(src: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        val a = FloatArray(src.size)
        val b = FloatArray(src.size)
        slide(src, a, w, h, radius, horizontal = true, takeMin = true)
        slide(a, b, w, h, radius, horizontal = false, takeMin = true)
        slide(b, a, w, h, radius, horizontal = true, takeMin = false)
        slide(a, b, w, h, radius, horizontal = false, takeMin = false)
        return b
    }

    /**
     * Drops the patches of colour that are too small to be an area. Patches are measured per hue family together with
     * its two neighbours, so a smooth gradient that crosses the border of two families is still one patch.
     */
    private fun keepLargeAreas(weight: FloatArray, family: IntArray, w: Int, h: Int, minArea: Int): FloatArray {
        val n = w * h
        val kept = FloatArray(n)
        val label = IntArray(n)
        val queue = IntArray(n)
        for (f in 0 until Bins) {
            val lo = (f + Bins - 1) % Bins
            val hi = (f + 1) % Bins
            java.util.Arrays.fill(label, 0)
            var next = 0
            for (start in 0 until n) {
                if (label[start] != 0 || weight[start] <= 0f) continue
                val fs = family[start]
                if (fs != f && fs != lo && fs != hi) continue
                next++
                var head = 0
                var tail = 0
                queue[tail++] = start
                label[start] = next
                while (head < tail) {
                    val p = queue[head++]
                    val px = p % w
                    val py = p / w
                    for (k in 0 until 4) {
                        val qx = if (k == 0) px - 1 else if (k == 1) px + 1 else px
                        val qy = if (k == 2) py - 1 else if (k == 3) py + 1 else py
                        if (qx < 0 || qy < 0 || qx >= w || qy >= h) continue
                        val q = qy * w + qx
                        if (label[q] != 0 || weight[q] <= 0f) continue
                        val fq = family[q]
                        if (fq != f && fq != lo && fq != hi) continue
                        label[q] = next
                        queue[tail++] = q
                    }
                }
                if (tail >= minArea) {
                    for (i in 0 until tail) {
                        val p = queue[i]
                        if (family[p] == f) kept[p] = weight[p]
                    }
                }
            }
        }
        return kept
    }

    private fun boxBlur(src: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        var cur = src.copyOf()
        var tmp = FloatArray(src.size)
        for (pass in 0 until 3) {
            for (y in 0 until h) {
                for (x in 0 until w) {
                    var s = 0f
                    var c = 0
                    for (d in -radius..radius) {
                        val xx = x + d
                        if (xx in 0 until w) {
                            s += cur[y * w + xx]
                            c++
                        }
                    }
                    tmp[y * w + x] = s / c
                }
            }
            for (y in 0 until h) {
                for (x in 0 until w) {
                    var s = 0f
                    var c = 0
                    for (d in -radius..radius) {
                        val yy = y + d
                        if (yy in 0 until h) {
                            s += tmp[yy * w + x]
                            c++
                        }
                    }
                    cur[y * w + x] = s / c
                }
            }
        }
        return cur
    }

    private class Voted(val l: FloatArray, val a: FloatArray, val b: FloatArray, val mass: FloatArray)

    /** In every cell, the colour of the winning hue around it (the maps blurred by [radius] cells). */
    private fun vote(
        binW: Array<FloatArray>,
        binL: Array<FloatArray>,
        binA: Array<FloatArray>,
        binB: Array<FloatArray>,
        count: FloatArray,
        radius: Int,
    ): Voted {
        val cells = Cols * Rows
        val w = Array(Bins) { boxBlur(binW[it], Cols, Rows, radius) }
        val l = Array(Bins) { boxBlur(binL[it], Cols, Rows, radius) }
        val a = Array(Bins) { boxBlur(binA[it], Cols, Rows, radius) }
        val b = Array(Bins) { boxBlur(binB[it], Cols, Rows, radius) }
        val cnt = boxBlur(count, Cols, Rows, radius)
        // the cover's main hues weigh more than small accents
        val glob = FloatArray(Bins)
        var globMax = 1e-9f
        for (f in 0 until Bins) {
            var s = 0f
            for (c in 0 until cells) s += w[f][c]
            glob[f] = s
            if (s > globMax) globMax = s
        }
        val prior = FloatArray(Bins) { (glob[it] / globMax).pow(HuePrior) }
        val score = Array(Bins) { FloatArray(cells) }
        var scoreMax = 1e-9f
        for (f in 0 until Bins) {
            val lo = (f + Bins - 1) % Bins
            val hi = (f + 1) % Bins
            for (c in 0 until cells) {
                val s = w[f][c] + 0.5f * (w[lo][c] + w[hi][c])
                score[f][c] = s
                if (s > scoreMax) scoreMax = s
            }
        }
        val outL = FloatArray(cells)
        val outA = FloatArray(cells)
        val outB = FloatArray(cells)
        val mass = FloatArray(cells)
        for (c in 0 until cells) {
            var tot = 0f
            var sl = 0f
            var sa = 0f
            var sb = 0f
            var wsum = 0f
            var sc = 0f
            var bestU = 0f
            var bestA = 0f
            var bestB = 0f
            for (f in 0 until Bins) {
                wsum += w[f][c]
                if (w[f][c] <= 1e-7f) continue
                val u = (score[f][c] / scoreMax).pow(HueExponent) * prior[f]
                val fa = a[f][c] / w[f][c]
                val fb = b[f][c] / w[f][c]
                tot += u
                sl += u * l[f][c] / w[f][c]
                sa += u * fa
                sb += u * fb
                sc += u * hypot(fa, fb)
                if (u > bestU) {
                    bestU = u
                    bestA = fa
                    bestB = fb
                }
            }
            if (tot > 1e-12f) {
                outL[c] = sl / tot
                var va = sa / tot
                var vb = sb / tot
                // hues that compete in a cell must not cancel into grey: keep the chroma of the mix
                val m = hypot(va, vb)
                val target = sc / tot
                if (m < 0.35f * target) {
                    va = bestA
                    vb = bestB
                } else if (m > 1e-6f) {
                    va *= target / m
                    vb *= target / m
                }
                outA[c] = va
                outB[c] = vb
            }
            mass[c] = wsum / maxOf(cnt[c], 1e-6f)
        }
        return Voted(outL, outA, outB, mass)
    }

    private fun circularDistance(a: Int, b: Int): Int {
        val d = (a - b + Bins) % Bins
        return minOf(d, Bins - d)
    }

    // ---------- the engine ----------

    /** [pixels]: ARGB, [width] x [height], a cover scaled to about 160 px. Null when the cover has no usable colour. */
    fun analyze(pixels: IntArray, width: Int, height: Int, explain: EngineExplain? = null): EngineResult? {
        if (width < 16 || height < 16 || pixels.size < width * height) return null
        // the middle of the artwork: frames and black bars at the sides, the very bottom edge
        val x0 = (width * 0.10f).toInt()
        val x1 = (width * 0.90f).toInt()
        val y0 = (height * 0.05f).toInt()
        val y1 = (height * 0.92f).toInt()
        val cw = x1 - x0
        val ch = y1 - y0
        if (cw < 8 || ch < 8) return null
        val area = cw * ch

        val cells = Cols * Rows
        val lab = FloatArray(3)
        val weight = FloatArray(area)
        val family = IntArray(area)
        val pl = FloatArray(area)
        val pa = FloatArray(area)
        val pb = FloatArray(area)
        val sumL = FloatArray(cells)
        val count = FloatArray(cells)
        val coloured = FloatArray(area)
        var rawSum = 0f
        for (y in y0 until y1) {
            val row = ((y - y0) * Rows / ch).coerceAtMost(Rows - 1)
            val yFrac = y.toFloat() / height
            val position = 1f - 0.55f * ramp(0.60f, 0.92f, yFrac)
            for (x in x0 until x1) {
                val col = ((x - x0) * Cols / cw).coerceAtMost(Cols - 1)
                val at = (y - y0) * cw + (x - x0)
                srgbToOklab(pixels[y * width + x], lab)
                val lightness = lab[0]
                val chroma = hypot(lab[1], lab[2])
                val hue = hueOf(lab[1], lab[2])
                pl[at] = lightness
                if (chroma >= 0.05f && lightness >= 0.12f) coloured[at] = 1f
                rawSum += ramp(0.05f, 0.11f, chroma) * ramp(0.12f, 0.26f, lightness)
                pa[at] = lab[1]
                pb[at] = lab[2]
                family[at] = (hue / 30f).toInt().coerceIn(0, Bins - 1)
                val cell = row * Cols + col
                sumL[cell] += lightness
                count[cell] += 1f
                // only pixels with real colour vote: pale tints count, greys, whites and blacks hardly do
                var w = ramp(0.018f, 0.075f, chroma) * ramp(0.10f, 0.26f, lightness)
                if (w > 0f) {
                    // browns (hair, wood, shadows) and skin tones are rarely the mood of a cover
                    val warm = ramp(30f, 42f, hue)
                    val brown = warm * (1f - ramp(78f, 92f, hue)) * (1f - ramp(0.52f, 0.64f, lightness)) *
                        (1f - ramp(0.10f, 0.15f, chroma))
                    val skin = warm * (1f - ramp(72f, 86f, hue)) * ramp(0.62f, 0.74f, lightness) *
                        (1f - ramp(0.085f, 0.12f, chroma))
                    w *= (1f - BrownCut * brown) * (1f - SkinCut * skin) * position
                }
                weight[at] = w
            }
        }

        // thin strokes first, then patches that are too small to be an area
        val opened = FloatArray(area)
        val layer = FloatArray(area)
        for (f in 0 until Bins) {
            var any = false
            for (i in 0 until area) {
                val v = if (family[i] == f) weight[i] else 0f
                layer[i] = v
                if (v > 0f) any = true
            }
            if (!any) continue
            val o = openMap(layer, cw, ch, OpenRadius)
            for (i in 0 until area) if (family[i] == f) opened[i] = o[i]
        }
        val minArea = maxOf(40, (area * MinAreaShare).toInt())
        val kept = keepLargeAreas(opened, family, cw, ch, minArea)

        // Thin or small colour that sits inside a colourful picture (flames, sparkles, hair strands, petals) is part of
        // the picture and comes back. Colour on a neutral patch (a title block, a logo) stays out.
        val integral = FloatArray((cw + 1) * (ch + 1))
        for (y in 0 until ch) {
            var rowSum = 0f
            for (x in 0 until cw) {
                rowSum += coloured[y * cw + x]
                integral[(y + 1) * (cw + 1) + (x + 1)] = integral[y * (cw + 1) + (x + 1)] + rowSum
            }
        }
        for (y in 0 until ch) {
            val ya = maxOf(0, y - DensityRadius)
            val yb = minOf(ch, y + DensityRadius + 1)
            for (x in 0 until cw) {
                val at = y * cw + x
                if (weight[at] <= kept[at]) continue
                val xa = maxOf(0, x - DensityRadius)
                val xb = minOf(cw, x + DensityRadius + 1)
                val sum = integral[yb * (cw + 1) + xb] - integral[ya * (cw + 1) + xb] -
                    integral[yb * (cw + 1) + xa] + integral[ya * (cw + 1) + xa]
                val density = sum / ((xb - xa) * (yb - ya))
                kept[at] += (weight[at] - kept[at]) * ramp(0.50f, 0.80f, density)
            }
        }

        if (explain != null) {
            explain.area = floatArrayOf(x0.toFloat() / width, y0.toFloat() / height, x1.toFloat() / width, y1.toFloat() / height)
            explain.keptW = cw
            explain.keptH = ch
            explain.kept = kept.copyOf()
        }

        // what survives votes, spread over the grid
        val binW = Array(Bins) { FloatArray(cells) }
        val binL = Array(Bins) { FloatArray(cells) }
        val binA = Array(Bins) { FloatArray(cells) }
        val binB = Array(Bins) { FloatArray(cells) }
        for (y in 0 until ch) {
            val row = (y * Rows / ch).coerceAtMost(Rows - 1)
            for (x in 0 until cw) {
                val at = y * cw + x
                val w = kept[at]
                if (w <= 0f) continue
                val col = (x * Cols / cw).coerceAtMost(Cols - 1)
                val cell = row * Cols + col
                val f = family[at]
                binW[f][cell] += w
                binL[f][cell] += w * pl[at]
                binA[f][cell] += w * pa[at]
                binB[f][cell] += w * pb[at]
            }
        }

        // hue statistics of the whole cover
        val massOf = FloatArray(Bins)
        val statL = FloatArray(Bins)
        val statA = FloatArray(Bins)
        val statB = FloatArray(Bins)
        var totalMass = 0f
        for (f in 0 until Bins) {
            var m = 0f
            var sl = 0f
            var sa = 0f
            var sb = 0f
            for (c in 0 until cells) {
                m += binW[f][c]
                sl += binL[f][c]
                sa += binA[f][c]
                sb += binB[f][c]
            }
            massOf[f] = m
            if (m > 1e-9f) {
                statL[f] = sl / m
                statA[f] = sa / m
                statB[f] = sb / m
            }
            totalMass += m
        }
        if (totalMass < area * 0.004f) return null
        val coverage = totalMass / area
        val presence = (coverage * 2.2f).coerceIn(0f, 1f)
        // a cover with almost no colour must not get an invented, vivid one out of a few leftover pixels
        val vivid = maxOf(ramp(0.010f, 0.100f, coverage), ramp(0.04f, 0.22f, rawSum / area))

        val clusterScore = FloatArray(Bins) {
            massOf[it] + 0.5f * (massOf[(it + Bins - 1) % Bins] + massOf[(it + 1) % Bins])
        }
        // the cluster colour: the family and its two neighbours, weighted by their mass
        val cl = FloatArray(Bins)
        val ca = FloatArray(Bins)
        val cb = FloatArray(Bins)
        for (f in 0 until Bins) {
            var m = 0f
            var sl = 0f
            var sa = 0f
            var sb = 0f
            for (d in -1..1) {
                val k = (f + d + Bins) % Bins
                m += massOf[k]
                sl += massOf[k] * statL[k]
                sa += massOf[k] * statA[k]
                sb += massOf[k] * statB[k]
            }
            if (m > 1e-9f) {
                cl[f] = sl / m
                ca[f] = sa / m
                cb[f] = sb / m
            }
        }
        var mainBin = 0
        for (f in 1 until Bins) if (clusterScore[f] > clusterScore[mainBin]) mainBin = f
        val fillL = cl[mainBin]
        val fillA = ca[mainBin]
        val fillB = cb[mainBin]

        // how colourful the cover is overall: pale covers get a soft chroma, colourful ones a vivid one
        var chromaSum = 0f
        for (f in 0 until Bins) chromaSum += massOf[f] * hypot(statA[f], statB[f])
        val coverChroma = chromaSum / totalMass
        val minChroma = (0.040f + 0.075f * vivid + 0.030f * ramp(0.04f, 0.11f, coverChroma)) * Intensity
        val maxChroma = (0.075f + 0.185f * vivid) * Intensity
        val lightMax = (0.095f + 0.115f * vivid) * Intensity

        val small = vote(binW, binL, binA, binB, count, BlurSmall)
        val wide = vote(binW, binL, binA, binB, count, BlurWide)
        val localL = boxBlur(FloatArray(cells) { sumL[it] / maxOf(count[it], 1f) }, Cols, Rows, BlurSmall)

        // pass 1: the colour of every cell before grading
        val cellA = FloatArray(cells)
        val cellB = FloatArray(cells)
        val cellKeep = FloatArray(cells)
        val mixed = FloatArray(2)
        for (c in 0 until cells) {
            val t = ramp(0.03f, 0.20f, small.mass[c])
            mixAB(small.a[c], small.b[c], wide.a[c], wide.b[c], t, mixed)
            // no colour at all nearby: the cover's main colour fills in
            val keep = ramp(0f, FillMass, wide.mass[c])
            mixAB(mixed[0], mixed[1], fillA, fillB, keep, mixed)
            cellA[c] = mixed[0]
            cellB[c] = mixed[1]
            cellKeep[c] = keep
        }

        // how much of what is seen around the card is red: a red that is the whole picture is calmed (Clevatess), a red
        // accent beside a blue keeps its colour (Kimi to Boku)
        var warm = 0f
        var seen = 0f
        for (c in 0 until cells) {
            val y = ((c / Cols) + 0.5f) / Rows
            val x = ((c % Cols) + 0.5f) / Cols
            val visible = maxOf(
                1f - ramp(0.22f, 0.32f, y),
                0.5f * ramp(0.76f, 0.84f, y),
                1f - ramp(0.06f, 0.12f, minOf(x, 1f - x)),
                0.08f,
            )
            val w = visible * ramp(0.03f, 0.10f, hypot(cellA[c], cellB[c]))
            warm += w * warmness(hueOf(cellA[c], cellB[c]))
            seen += w
        }
        val calm = if (seen > 1e-6f) ramp(0.55f, 0.80f, warm / seen) else 1f

        // pass 2: grading
        val rgb = FloatArray(3)
        val coded = FloatArray(cells * 3)
        for (c in 0 until cells) {
            val keep = cellKeep[c]
            val a = cellA[c]
            val b = cellB[c]
            val chroma = hypot(a, b)
            val hue = hueNudge(hueOf(a, b))
            // cells without colour of their own (dark or white parts of the cover) take the main hue, but softer
            val outChroma = ((chroma * 1.7f + 0.04f) * Intensity).coerceIn(minChroma, maxChroma) * (1f - FillSoften * (1f - keep))
            val outLightness = 0.31f + 0.27f * ramp(0.22f, 0.86f, localL[c])
            graded(outLightness + hueShiftL(hue, calm), outChroma, hue, MaxMosaicLuma, 0.22f, MosaicSatScale, calm, rgb)
            coded[c * 3] = rgb[0]
            coded[c * 3 + 1] = rgb[1]
            coded[c * 3 + 2] = rgb[2]
        }
        // a last soft blur so the grid never shows
        val mosaic = IntArray(cells)
        val chan = Array(3) { k -> boxBlur(FloatArray(cells) { coded[it * 3 + k] }, Cols, Rows, 2) }
        for (c in 0 until cells) {
            rgb[0] = chan[0][c]
            rgb[1] = chan[1][c]
            rgb[2] = chan[2][c]
            mosaic[c] = pack(rgb)
        }

        // the dark base and the fallback hues: up to three clearly different hue clusters of the whole cover
        val picked = ArrayList<Int>(3)
        for (round in 0 until 3) {
            var best = -1
            var bestScore = 0f
            for (f in 0 until Bins) {
                if (picked.any { circularDistance(f, it) <= 1 }) continue
                if (clusterScore[f] > bestScore) {
                    bestScore = clusterScore[f]
                    best = f
                }
            }
            if (best < 0) break
            if (round > 0 && bestScore < 0.18f * clusterScore[picked[0]]) break
            picked.add(best)
        }
        val hues = FloatArray(3)
        val chromas = FloatArray(3)
        val mainHue = hueNudge(hueOf(ca[picked[0]], cb[picked[0]]))
        for (i in 0 until 3) {
            if (i < picked.size) {
                hues[i] = hueNudge(hueOf(ca[picked[i]], cb[picked[i]]))
                chromas[i] = hypot(ca[picked[i]], cb[picked[i]])
            } else {
                // a cover with a single hue: neighbouring hues of it instead
                hues[i] = (mainHue + (if (i == 1) 24f else -24f) + 360f) % 360f
                chromas[i] = chromas[0]
            }
        }

        // the colour of each side of the cover: every light takes the colour of the margin it sits next to
        val sideHue = FloatArray(5)
        val sideChroma = FloatArray(5)
        val tmp = FloatArray(3)
        for (side in 0 until 5) {
            val fallback = if (side < 3) side else 0
            sideHue[side] = hues[fallback]
            sideChroma[side] = chromas[fallback]
            if (side == 3) {
                sideHue[side] = (mainHue - 14f + 360f) % 360f
            }
            val rects = EdgeRects[side]
            val centre = if (explain != null) FloatArray(2) else null
            for (step in 0 until 3) {
                val rect = floatArrayOf(rects[step * 4], rects[step * 4 + 1], rects[step * 4 + 2], rects[step * 4 + 3])
                val cover = regionColour(kept, family, pl, pa, pb, cw, ch, rect, tmp, centre)
                if (cover >= EdgeMinCover) {
                    sideHue[side] = hueNudge(hueOf(tmp[1], tmp[2]))
                    sideChroma[side] = hypot(tmp[1], tmp[2])
                    if (explain != null && centre != null && side < 4) {
                        explain.lightRect[side] = floatArrayOf(
                            (x0 + rect[0] * cw) / width,
                            (y0 + rect[1] * ch) / height,
                            (x0 + rect[2] * cw) / width,
                            (y0 + rect[3] * ch) / height,
                        )
                        explain.lightAt[side] = floatArrayOf((x0 + centre[0]) / width, (y0 + centre[1]) / height)
                    }
                    break
                }
            }
        }

        val lights = IntArray(4)
        val lightSat = LightSatScale
        // top-left, right, top-right, left: the same spots where the home screen hangs its lights
        val order = intArrayOf(0, 1, 2, 3)
        for (i in 0 until 4) {
            val side = order[i]
            val hue = sideHue[side]
            val shift = if (i == 3) 1.2f * hueShiftL(hue, calm) + 0.04f else 1.2f * hueShiftL(hue, calm)
            graded(
                0.66f + shift,
                ((sideChroma[side] * 1.5f + 0.05f) * Intensity).coerceIn((0.060f + 0.040f * vivid) * Intensity, lightMax),
                hue, MaxLightLuma, 0.45f, lightSat, calm, rgb,
            )
            lights[i] = pack(rgb)
        }

        var delta = ((hues[1] - mainHue + 540f) % 360f) - 180f
        delta = delta.coerceIn(-60f, 60f)
        val topHue = sideHue[4]
        val topChroma = sideChroma[4]
        val baseChroma = (topChroma * 1.3f + 0.05f).coerceIn(0.05f + 0.04f * vivid, lightMax)
        val deepChroma = (chromas[0] * 1.3f + 0.05f).coerceIn(0.05f + 0.04f * vivid, lightMax)
        graded(0.42f, baseChroma, topHue, 1f, 0.2f, lightSat, calm, rgb)
        val top = pack(rgb)
        graded(0.32f, (baseChroma + deepChroma) * 0.475f, (mainHue + 0.35f * delta + 360f) % 360f, 1f, 0.2f, lightSat, calm, rgb)
        val mid = pack(rgb)
        graded(0.25f, (deepChroma * 0.9f).coerceAtLeast(0.07f), (mainHue + 0.60f * delta + 360f) % 360f, 1f, 0.2f, lightSat, calm, rgb)
        val deep = pack(rgb)

        return EngineResult(mosaic, top, mid, deep, lights, presence, massOf)
    }
}
// ANZ <--
