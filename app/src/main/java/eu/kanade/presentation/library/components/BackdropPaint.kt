package eu.kanade.presentation.library.components

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

// ANZ --> Paints a carousel backdrop from the colours the user picked by hand ("Editar fondo").
// Pure Kotlin on purpose (no Android classes): colours in, ARGB ints out.

/**
 * Turns a picked colour into everything the backdrop needs (the same pieces the automatic colour engine produces):
 * the base tints, four lights and the 32 x 48 blurred mosaic.
 *
 * The pick is a starting point, not a paint can: its lightness is moved into the range the backdrop works in, a pale
 * or dull pick keeps its hue but gets enough colour to read as that hue, and reds and magentas stay below the
 * electric limit (the same hue-dependent cap the automatic engine uses).
 */
internal object BackdropPaint {

    const val Cols = 32
    const val Rows = 48

    class Result(
        val top: Int,
        val mid: Int,
        val deep: Int,
        /** Four lights, same order as the automatic engine. */
        val lights: IntArray,
        /** [Cols] x [Rows] ARGB pixels, row by row. */
        val mosaic: IntArray,
        val presence: Float,
    )

    private val HueKeys = floatArrayOf(0f, 30f, 60f, 100f, 150f, 200f, 260f, 300f, 330f, 360f)
    private val HueCaps = floatArrayOf(0.78f, 0.78f, 0.90f, 0.90f, 0.95f, 0.95f, 0.95f, 0.90f, 0.84f, 0.78f)

    /** The most saturation a hue may reach (fraction of what the screen can show at that lightness). */
    private fun satCap(hueDeg: Float): Float {
        val h = ((hueDeg % 360f) + 360f) % 360f
        for (i in 1 until HueKeys.size) {
            if (h <= HueKeys[i]) {
                val t = (h - HueKeys[i - 1]) / (HueKeys[i] - HueKeys[i - 1])
                return HueCaps[i - 1] + (HueCaps[i] - HueCaps[i - 1]) * t
            }
        }
        return HueCaps[HueCaps.size - 1]
    }

    // ---- OKLab ----

    private fun toLinear(channel: Int): Float {
        val v = channel / 255f
        return if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
    }

    private fun fromLinear(linear: Float): Int {
        val x = linear.coerceIn(0f, 1f)
        val s = if (x <= 0.0031308f) x * 12.92f else 1.055f * x.pow(1f / 2.4f) - 0.055f
        return (s * 255f + 0.5f).toInt().coerceIn(0, 255)
    }

    private fun cbrt(x: Float): Float = if (x < 0f) -((-x).pow(1f / 3f)) else x.pow(1f / 3f)

    /** L, a, b of an sRGB colour. */
    private fun oklab(argb: Int, out: FloatArray) {
        val r = toLinear((argb shr 16) and 0xFF)
        val g = toLinear((argb shr 8) and 0xFF)
        val b = toLinear(argb and 0xFF)
        val l = cbrt(0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b)
        val m = cbrt(0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b)
        val s = cbrt(0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b)
        out[0] = 0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s
        out[1] = 1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s
        out[2] = 0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s
    }

    /** Linear sRGB of an OKLab colour (not clamped). */
    private fun linearRgb(lightness: Float, a: Float, b: Float, out: FloatArray) {
        val l = (lightness + 0.3963377774f * a + 0.2158037573f * b).let { it * it * it }
        val m = (lightness - 0.1055613458f * a - 0.0638541728f * b).let { it * it * it }
        val s = (lightness - 0.0894841775f * a - 1.2914855480f * b).let { it * it * it }
        out[0] = 4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s
        out[1] = -1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s
        out[2] = -0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s
    }

    private fun inGamut(lightness: Float, chroma: Float, hueRad: Float, scratch: FloatArray): Boolean {
        linearRgb(lightness, chroma * cos(hueRad), chroma * sin(hueRad), scratch)
        return scratch[0] >= -0.0005f && scratch[0] <= 1.0005f &&
            scratch[1] >= -0.0005f && scratch[1] <= 1.0005f &&
            scratch[2] >= -0.0005f && scratch[2] <= 1.0005f
    }

    /** The most chroma the screen can show at this lightness and hue. */
    private fun gamutMax(lightness: Float, hueRad: Float, scratch: FloatArray): Float {
        var lo = 0f
        var hi = 0.40f
        repeat(11) {
            val mid = (lo + hi) / 2f
            if (inGamut(lightness, mid, hueRad, scratch)) lo = mid else hi = mid
        }
        return lo
    }

    private fun rad(deg: Float): Float = (deg * PI / 180.0).toFloat()

    // ---- zones: where in the backdrop a colour lives ----

    const val ZoneAuto = 0
    const val ZoneTop = 1
    const val ZoneBottom = 2
    const val ZoneLeft = 3
    const val ZoneRight = 4

    /**
     * A marker as the painter needs it: where it sits on the card (0 to 1), its colour, its zone and its strength.
     *
     * The four hand adjustments go from -1 to 1 (0 = untouched): [light] makes the colour lighter or darker, [color]
     * weaker or stronger, [tone] turns its hue, [force] lets it rule more or less over the other colours around it.
     * With [exact] the colour is used as it is: no softening of strong hues, no minimum of colour, and its own
     * lightness is kept (so a white pick stays white instead of turning into a mid grey).
     */
    class Pick(
        val argb: Int,
        val zone: Int = ZoneAuto,
        val x: Float = 0.5f,
        val y: Float = 0.5f,
        val strength: Float = 1f,
        val light: Float = 0f,
        val color: Float = 0f,
        val tone: Float = 0f,
        val force: Float = 0f,
        val exact: Boolean = false,
    )

    /** How far apart two colours are in hue and chroma (OKLab a/b plane; about 0.05 is a clearly different shade). */
    fun colourDistance(a: Int, b: Int): Float {
        val la = FloatArray(3)
        val lb = FloatArray(3)
        oklab(a, la)
        oklab(b, lb)
        return hypot(la[1] - lb[1], la[2] - lb[2])
    }

    /**
     * The same colour made lighter or darker ([lightShift] added to its OKLab lightness), weaker or stronger (chroma
     * times [chromaFactor]) and with its hue turned by [turnDegrees]. It never leaves what the screen can show.
     */
    fun tweak(argb: Int, lightShift: Float, chromaFactor: Float, turnDegrees: Float): Int {
        val lab = FloatArray(3)
        oklab(argb, lab)
        val c0 = hypot(lab[1], lab[2])
        val hueRad = (if (c0 < 1e-5f) 0f else atan2(lab[2], lab[1])) + rad(turnDegrees)
        val l = (lab[0] + lightShift).coerceIn(0.02f, 0.99f)
        val scratch = FloatArray(3)
        val c = if (c0 < 1e-5f) 0f else min(c0 * chromaFactor, gamutMax(l, hueRad, scratch))
        linearRgb(l, c * cos(hueRad), c * sin(hueRad), scratch)
        return (0xFF shl 24) or
            (fromLinear(scratch[0]) shl 16) or
            (fromLinear(scratch[1]) shl 8) or
            fromLinear(scratch[2])
    }

    /** What each adjustment means in the painter's own units. */
    fun lightShift(v: Float): Float = v * 0.35f
    fun colourFactor(v: Float): Float = if (v >= 0f) 1f + 1.6f * v else 1f + v
    fun toneDegrees(v: Float): Float = v * 60f
    fun forceWeight(v: Float): Float = if (v >= 0f) 1f + 2f * v else 1f + 0.75f * v

    private fun blend(a: Int, b: Int, t: Float): Int {
        val u = 1f - t
        val r = (((a shr 16) and 0xFF) * u + ((b shr 16) and 0xFF) * t + 0.5f).toInt().coerceIn(0, 255)
        val g = (((a shr 8) and 0xFF) * u + ((b shr 8) and 0xFF) * t + 0.5f).toInt().coerceIn(0, 255)
        val bl = ((a and 0xFF) * u + (b and 0xFF) * t + 0.5f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }

    /** The zone a marker belongs to by where it sits on the cover (left of the cover = left of the backdrop). */
    fun zoneOf(x: Float, y: Float): Int {
        val dx = x - 0.5f
        val dy = y - 0.5f
        return if (abs(dy) * 0.85f >= abs(dx)) {
            if (dy < 0f) ZoneTop else ZoneBottom
        } else {
            if (dx < 0f) ZoneLeft else ZoneRight
        }
    }

    /** How much colour a pixel has (about 0 for white, grey and black). */
    fun chromaOf(argb: Int): Float {
        val lab = FloatArray(3)
        oklab(argb, lab)
        return hypot(lab[1], lab[2])
    }

    /**
     * Mixes colours (as a/b vectors) the way a painter would: the hue moves between them and the chroma follows the
     * mix, so two colours never cancel into grey. Opposite hues lean towards the stronger one instead of dipping
     * through grey. Weights need not add up to one.
     */
    private fun mix(va: FloatArray, vb: FloatArray, w: FloatArray, n: Int, out: FloatArray) {
        var sw = 0f
        var sa = 0f
        var sb = 0f
        var sc = 0f
        var best = -1
        var bestW = -1f
        for (i in 0 until n) {
            val wi = w[i]
            if (wi <= 0f) continue
            val c = hypot(va[i], vb[i])
            sw += wi
            sa += wi * va[i]
            sb += wi * vb[i]
            sc += wi * c
            if (wi * c > bestW) {
                bestW = wi * c
                best = i
            }
        }
        if (sw <= 0f || best < 0) {
            out[0] = 0f
            out[1] = 0f
            return
        }
        sa /= sw
        sb /= sw
        sc /= sw
        if (sc < 1e-5f) {
            out[0] = 0f
            out[1] = 0f
            return
        }
        val m = hypot(sa, sb)
        var ra = sa
        var rb = sb
        if (m < 0.5f * sc) {
            val bc = hypot(va[best], vb[best]).coerceAtLeast(1e-6f)
            val t = 1f - m / (0.5f * sc)
            ra = sa * (1f - t) + va[best] / bc * sc * t
            rb = sb * (1f - t) + vb[best] / bc * sc * t
        }
        val rm = hypot(ra, rb)
        if (rm > 1e-6f) {
            ra *= sc / rm
            rb *= sc / rm
        }
        out[0] = ra
        out[1] = rb
    }

    /** Colour (a/b vector) to ARGB at a lightness; caps saturation by hue and brightness by [maxLuma]. */
    private class Painter(val capScale: Float) {
        val scratch = FloatArray(3)

        fun colour(va: Float, vb: Float, lightness: Float, turn: Float, k: Float, maxLuma: Float): Int {
            val c0 = hypot(va, vb) * k
            val hue = if (c0 < 1e-5f) 0f else Math.toDegrees(atan2(vb, va).toDouble()).toFloat() + turn
            var l = lightness
            var out = 0
            repeat(24) {
                val c = if (c0 < 1e-5f) {
                    0f
                } else {
                    min(c0, gamutMax(l, rad(hue), scratch) * min(0.98f, satCap(hue) * capScale))
                }
                linearRgb(l, c * cos(rad(hue)), c * sin(rad(hue)), scratch)
                val r = scratch[0].coerceIn(0f, 1f)
                val g = scratch[1].coerceIn(0f, 1f)
                val b = scratch[2].coerceIn(0f, 1f)
                out = (0xFF shl 24) or (fromLinear(r) shl 16) or (fromLinear(g) shl 8) or fromLinear(b)
                val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                if (luma <= maxLuma) return out
                l -= 0.012f
            }
            return out
        }

        /**
         * The colour as it is: it is only brought inside what the screen can show (less colour, never another hue or
         * another lightness), without the softening [colour] does.
         */
        fun exact(va: Float, vb: Float, lightness: Float): Int {
            val c0 = hypot(va, vb)
            val hueRad = if (c0 < 1e-5f) 0f else atan2(vb, va)
            val l = lightness.coerceIn(0.04f, 0.985f)
            val c = if (c0 < 1e-5f) 0f else min(c0, gamutMax(l, hueRad, scratch))
            linearRgb(l, c * cos(hueRad), c * sin(hueRad), scratch)
            return (0xFF shl 24) or
                (fromLinear(scratch[0]) shl 16) or
                (fromLinear(scratch[1]) shl 8) or
                fromLinear(scratch[2])
        }
    }

    /**
     * The colour of a small disc of a picture, averaged the way the eye does (in OKLab), so a pick on a thin strand of
     * hair or a speck of noise does not decide the colour on its own.
     */
    fun sample(pixels: IntArray, width: Int, height: Int, cx: Float, cy: Float, radius: Float): Int {
        val r = max(1f, radius)
        val x0 = max(0, floor(cx - r).toInt())
        val x1 = min(width - 1, floor(cx + r).toInt())
        val y0 = max(0, floor(cy - r).toInt())
        val y1 = min(height - 1, floor(cy + r).toInt())
        val lab = FloatArray(3)
        var sl = 0.0
        var sa = 0.0
        var sb = 0.0
        var n = 0
        for (y in y0..y1) {
            for (x in x0..x1) {
                val dx = x + 0.5f - cx
                val dy = y + 0.5f - cy
                if (dx * dx + dy * dy > r * r) continue
                val px = pixels[y * width + x]
                if ((px ushr 24) < 128) continue
                oklab(px, lab)
                sl += lab[0]
                sa += lab[1]
                sb += lab[2]
                n++
            }
        }
        if (n == 0) {
            val x = cx.toInt().coerceIn(0, width - 1)
            val y = cy.toInt().coerceIn(0, height - 1)
            return pixels[y * width + x] or (0xFF shl 24)
        }
        val scratch = FloatArray(3)
        linearRgb((sl / n).toFloat(), (sa / n).toFloat(), (sb / n).toFloat(), scratch)
        return (0xFF shl 24) or
            (fromLinear(scratch[0]) shl 16) or
            (fromLinear(scratch[1]) shl 8) or
            fromLinear(scratch[2])
    }

    /**
     * The whole backdrop for the picked colours. [strength] 1 = as designed, more = stronger colour.
     *
     * Every marker lives in a zone of the backdrop (its own, or by where it sits on the cover): the top band, the
     * bottom, the left or the right side. The backdrop is a smooth field that takes each zone's colour near its
     * edge of the screen and mixes the neighbours between them. A zone nobody picked borrows from the nearest one.
     *
     * Each zone carries, besides its colour, a lightness shift (the Brillo adjustment), the lightness its colour has
     * when it is used as it is, and how "exact" it is (0 = painted the designed way, 1 = used as it is).
     */
    fun paint(picks: List<Pick>, strength: Float = 1f): Result {
        val lab = FloatArray(3)
        val zoneA = FloatArray(5)
        val zoneB = FloatArray(5)
        val zoneDl = FloatArray(5)
        val zoneLx = FloatArray(5) { 0.5f }
        val zoneEx = FloatArray(5)
        val zoneW = FloatArray(5) { 1f }
        val has = BooleanArray(5)
        var maxStrength = 1f
        for (zone in 1..4) {
            val va = FloatArray(picks.size)
            val vb = FloatArray(picks.size)
            val w = FloatArray(picks.size)
            val dls = FloatArray(picks.size)
            val lxs = FloatArray(picks.size)
            val exs = FloatArray(picks.size)
            var n = 0
            for (p in picks) {
                val z = if (p.zone == ZoneAuto) zoneOf(p.x, p.y) else p.zone
                if (z != zone) continue
                oklab(p.argb, lab)
                val c0 = hypot(lab[1], lab[2])
                val dl = lightShift(p.light)
                val k = p.strength * strength * colourFactor(p.color)
                var ca = 0f
                var cb = 0f
                if (c0 >= 0.012f) {
                    // A dull pick keeps its hue but gets enough colour to read as that hue (unless it is used as it is).
                    val c = if (p.exact) c0 * k else (c0 * 1.5f + 0.05f).coerceIn(0.085f, 0.30f) * k
                    ca = c * lab[1] / c0
                    cb = c * lab[2] / c0
                    val turn = rad(toneDegrees(p.tone))
                    if (turn != 0f) {
                        val cs = cos(turn)
                        val sn = sin(turn)
                        val ra = ca * cs - cb * sn
                        val rb = ca * sn + cb * cs
                        ca = ra
                        cb = rb
                    }
                }
                if (!p.exact) maxStrength = max(maxStrength, k)
                va[n] = ca
                vb[n] = cb
                w[n] = forceWeight(p.force)
                dls[n] = dl
                lxs[n] = (lab[0] + dl).coerceIn(0.04f, 0.985f)
                exs[n] = if (p.exact) 1f else 0f
                n++
            }
            if (n > 0) {
                val out = FloatArray(2)
                mix(va, vb, w, n, out)
                zoneA[zone] = out[0]
                zoneB[zone] = out[1]
                var sw = 0f
                var sdl = 0f
                var slx = 0f
                var sex = 0f
                for (i in 0 until n) {
                    sw += w[i]
                    sdl += w[i] * dls[i]
                    slx += w[i] * lxs[i]
                    sex += w[i] * exs[i]
                }
                zoneDl[zone] = sdl / sw
                zoneLx[zone] = slx / sw
                zoneEx[zone] = sex / sw
                zoneW[zone] = sw / n
                has[zone] = true
            }
        }
        if (!has[1] && !has[2] && !has[3] && !has[4]) {
            // nothing picked: a neutral backdrop
            has[ZoneTop] = true
        }
        val painter = Painter(maxStrength)
        val tmp = FloatArray(2)

        /** One colour of the backdrop: the designed way, the exact way, or a mix of both when a zone has both. */
        fun tone(
            a: Float,
            b: Float,
            dl: Float,
            lx: Float,
            ex: Float,
            roleL: Float,
            turn: Float,
            k: Float,
            maxLuma: Float,
            exactScale: Float,
        ): Int {
            val nat = if (ex < 0.999f) {
                painter.colour(a, b, (roleL + dl).coerceIn(0.08f, 0.97f), turn, k, maxLuma + max(0f, dl) * 2f)
            } else {
                0
            }
            val exc = if (ex > 0.001f) painter.exact(a, b, lx * exactScale) else 0
            return when {
                ex <= 0.001f -> nat
                ex >= 0.999f -> exc
                else -> blend(nat, exc, ex)
            }
        }

        /** The first zone of [order] that has a colour (0 = none, which reads as no colour at all). */
        fun source(order: IntArray): Int {
            for (z in order) {
                if (has[z]) return z
            }
            return 0
        }

        val zl = source(intArrayOf(ZoneLeft, ZoneTop, ZoneRight, ZoneBottom))
        val zr = source(intArrayOf(ZoneRight, ZoneTop, ZoneLeft, ZoneBottom))
        val zt = source(intArrayOf(ZoneTop, ZoneLeft, ZoneRight, ZoneBottom))
        val zb = source(intArrayOf(ZoneBottom, ZoneLeft, ZoneRight, ZoneTop))

        // the middle of the picture: every zone that has a colour counts the same
        var midDl = 0f
        var midLx = 0f
        var midEx = 0f
        run {
            val va = FloatArray(4)
            val vb = FloatArray(4)
            val w = FloatArray(4)
            var count = 0
            for (z in 1..4) {
                va[z - 1] = zoneA[z]
                vb[z - 1] = zoneB[z]
                w[z - 1] = if (has[z]) 1f else 0f
                if (has[z]) {
                    count++
                    midDl += zoneDl[z]
                    midLx += zoneLx[z]
                    midEx += zoneEx[z]
                }
            }
            mix(va, vb, w, 4, tmp)
            if (count > 0) {
                midDl /= count
                midLx /= count
                midEx /= count
            } else {
                midLx = 0.5f
            }
        }
        val midA = tmp[0]
        val midB = tmp[1]

        val lights = intArrayOf(
            tone(zoneA[zl], zoneB[zl], zoneDl[zl], zoneLx[zl], zoneEx[zl], 0.66f, 0f, 1f, 0.30f, 1f),
            tone(zoneA[zr], zoneB[zr], zoneDl[zr], zoneLx[zr], zoneEx[zr], 0.62f, 7f, 1f, 0.30f, 1f),
            tone(zoneA[zt], zoneB[zt], zoneDl[zt], zoneLx[zt], zoneEx[zt], 0.70f, -7f, 1f, 0.30f, 1f),
            tone(zoneA[zb], zoneB[zb], zoneDl[zb], zoneLx[zb], zoneEx[zb], 0.66f, 14f, 1f, 0.30f, 1f),
        )
        val top = tone(zoneA[zt], zoneB[zt], zoneDl[zt], zoneLx[zt], zoneEx[zt], 0.42f, 0f, 0.85f, 1f, 0.94f)
        val mid = tone(midA, midB, midDl, midLx, midEx, 0.32f, 0f, 0.80f, 1f, 0.88f)
        val deep = tone(zoneA[zb], zoneB[zb], zoneDl[zb], zoneLx[zb], zoneEx[zb], 0.23f, 0f, 0.70f, 1f, 0.80f)

        // the field: where each zone has its say (anchors just outside the screen; the screen is about twice as tall)
        val anchorU = floatArrayOf(0f, 0.5f, 0.5f, -0.05f, 1.05f)
        val anchorV = floatArrayOf(0f, -0.05f, 1.05f, 0.45f, 0.45f)
        val fieldA = FloatArray(4)
        val fieldB = FloatArray(4)
        val fieldW = FloatArray(4)
        for (z in 1..4) {
            fieldA[z - 1] = zoneA[z]
            fieldB[z - 1] = zoneB[z]
        }
        val mosaic = IntArray(Cols * Rows)
        for (cy in 0 until Rows) {
            val t = cy / (Rows - 1f)
            for (cx in 0 until Cols) {
                val u = cx / (Cols - 1f)
                var sw = 0f
                var sdl = 0f
                var slx = 0f
                var sex = 0f
                for (z in 1..4) {
                    if (!has[z]) {
                        fieldW[z - 1] = 0f
                        continue
                    }
                    val du = u - anchorU[z]
                    val dv = (t - anchorV[z]) * 2f
                    val d2 = du * du + dv * dv + 0.03f
                    val wz = zoneW[z] / (d2 * kotlin.math.sqrt(d2))
                    fieldW[z - 1] = wz
                    sw += wz
                    sdl += wz * zoneDl[z]
                    slx += wz * zoneLx[z]
                    sex += wz * zoneEx[z]
                }
                mix(fieldA, fieldB, fieldW, 4, tmp)
                val dl = if (sw > 0f) sdl / sw else 0f
                val lx = if (sw > 0f) slx / sw else 0.5f
                val ex = if (sw > 0f) sex / sw else 0f
                // lighter at the top, darker towards the bottom, a hint of light from the upper left
                val l = (0.58f - 0.27f * t + 0.04f * (1f - u) * (1f - t)).coerceIn(0.28f, 0.62f)
                mosaic[cy * Cols + cx] = tone(
                    tmp[0], tmp[1], dl, lx, ex,
                    l, 8f * (u - 0.5f), 0.95f - 0.20f * t, 0.25f, 1f - 0.18f * t,
                )
            }
        }
        return Result(top, mid, deep, lights, mosaic, 0.6f)
    }

    /**
     * Where to put markers so that they pick up [targets] (colours the automatic reading found): the points of the
     * picture, inside the part the card shows, whose colour is closest to each target. Returns x, y pairs (0 to 1 on
     * the card), at most [max] of them, none of them close together.
     */
    fun suggest(pixels: IntArray, width: Int, height: Int, visW: Float, visH: Float, targets: IntArray, max: Int): FloatArray {
        val gx = 20
        val gy = 28
        val cells = gx * gy
        val cl = FloatArray(cells)
        val ca = FloatArray(cells)
        val cb = FloatArray(cells)
        val lab = FloatArray(3)
        val radius = 0.035f * visW * width
        for (j in 0 until gy) {
            for (i in 0 until gx) {
                val nx = 0.06f + 0.88f * (i + 0.5f) / gx
                val ny = 0.06f + 0.88f * (j + 0.5f) / gy
                val ix = (0.5f - visW / 2f + nx * visW) * width
                val iy = (0.5f - visH / 2f + ny * visH) * height
                oklab(sample(pixels, width, height, ix, iy, radius), lab)
                cl[j * gx + i] = lab[0]
                ca[j * gx + i] = lab[1]
                cb[j * gx + i] = lab[2]
            }
        }
        val chosen = ArrayList<Int>()
        val seenA = ArrayList<Float>()
        val seenB = ArrayList<Float>()
        for (target in targets) {
            if (chosen.size >= max) break
            oklab(target, lab)
            // the lights are brighter and stronger than the picture itself, so only the hue and a part of the chroma count
            val ta = lab[1] * 0.6f
            val tb = lab[2] * 0.6f
            if (hypot(lab[1], lab[2]) < 0.04f) continue
            var same = false
            for (k in seenA.indices) {
                if (hypot(ta - seenA[k], tb - seenB[k]) < 0.05f) same = true
            }
            if (same) continue
            var best = -1
            var bestD = Float.MAX_VALUE
            for (c in 0 until cells) {
                val cxn = 0.06f + 0.88f * ((c % gx) + 0.5f) / gx
                val cyn = 0.06f + 0.88f * ((c / gx) + 0.5f) / gy
                var near = false
                for (o in chosen) {
                    val ox = 0.06f + 0.88f * ((o % gx) + 0.5f) / gx
                    val oy = 0.06f + 0.88f * ((o / gx) + 0.5f) / gy
                    if (hypot(cxn - ox, (cyn - oy) * 0.69f) < 0.18f) near = true
                }
                if (near) continue
                val d = hypot(ca[c] - ta, cb[c] - tb)
                if (d < bestD) {
                    bestD = d
                    best = c
                }
            }
            if (best >= 0) {
                chosen.add(best)
                seenA.add(ta)
                seenB.add(tb)
            }
        }
        val out = FloatArray(chosen.size * 2)
        for ((n, c) in chosen.withIndex()) {
            out[n * 2] = 0.06f + 0.88f * ((c % gx) + 0.5f) / gx
            out[n * 2 + 1] = 0.06f + 0.88f * ((c / gx) + 0.5f) / gy
        }
        return out
    }
}
// ANZ <--
