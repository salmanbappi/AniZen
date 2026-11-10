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

    /** A marker as the painter needs it: where it sits on the card (0 to 1), its colour, its zone and its strength. */
    class Pick(
        val argb: Int,
        val zone: Int = ZoneAuto,
        val x: Float = 0.5f,
        val y: Float = 0.5f,
        val strength: Float = 1f,
    )

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
     */
    fun paint(picks: List<Pick>, strength: Float = 1f): Result {
        val lab = FloatArray(3)
        val zoneA = FloatArray(5)
        val zoneB = FloatArray(5)
        val has = BooleanArray(5)
        var maxStrength = 1f
        for (zone in 1..4) {
            val va = FloatArray(picks.size)
            val vb = FloatArray(picks.size)
            val w = FloatArray(picks.size)
            var n = 0
            for (p in picks) {
                val z = if (p.zone == ZoneAuto) zoneOf(p.x, p.y) else p.zone
                if (z != zone) continue
                oklab(p.argb, lab)
                val c0 = hypot(lab[1], lab[2])
                val k = p.strength * strength
                maxStrength = max(maxStrength, k)
                if (c0 < 0.012f) {
                    va[n] = 0f
                    vb[n] = 0f
                } else {
                    // A dull pick keeps its hue but gets enough colour to read as that hue.
                    val c = (c0 * 1.5f + 0.05f).coerceIn(0.085f, 0.30f) * k
                    va[n] = c * lab[1] / c0
                    vb[n] = c * lab[2] / c0
                }
                w[n] = 1f
                n++
            }
            if (n > 0) {
                val out = FloatArray(2)
                mix(va, vb, w, n, out)
                zoneA[zone] = out[0]
                zoneB[zone] = out[1]
                has[zone] = true
            }
        }
        if (!has[1] && !has[2] && !has[3] && !has[4]) {
            // nothing picked: a neutral backdrop
            has[ZoneTop] = true
        }
        val painter = Painter(maxStrength)
        val tmp = FloatArray(2)

        fun zoneColour(order: IntArray, out: FloatArray) {
            for (z in order) {
                if (has[z]) {
                    out[0] = zoneA[z]
                    out[1] = zoneB[z]
                    return
                }
            }
            out[0] = 0f
            out[1] = 0f
        }

        val leftV = FloatArray(2)
        val rightV = FloatArray(2)
        val topV = FloatArray(2)
        val bottomV = FloatArray(2)
        zoneColour(intArrayOf(ZoneLeft, ZoneTop, ZoneRight, ZoneBottom), leftV)
        zoneColour(intArrayOf(ZoneRight, ZoneTop, ZoneLeft, ZoneBottom), rightV)
        zoneColour(intArrayOf(ZoneTop, ZoneLeft, ZoneRight, ZoneBottom), topV)
        zoneColour(intArrayOf(ZoneBottom, ZoneLeft, ZoneRight, ZoneTop), bottomV)
        // the middle of the picture: every zone that has a colour counts the same
        run {
            val va = FloatArray(4)
            val vb = FloatArray(4)
            val w = FloatArray(4)
            for (z in 1..4) {
                va[z - 1] = zoneA[z]
                vb[z - 1] = zoneB[z]
                w[z - 1] = if (has[z]) 1f else 0f
            }
            mix(va, vb, w, 4, tmp)
        }
        val midV = floatArrayOf(tmp[0], tmp[1])

        val lights = intArrayOf(
            painter.colour(leftV[0], leftV[1], 0.66f, 0f, 1f, 0.30f),
            painter.colour(rightV[0], rightV[1], 0.62f, 7f, 1f, 0.30f),
            painter.colour(topV[0], topV[1], 0.70f, -7f, 1f, 0.30f),
            painter.colour(bottomV[0], bottomV[1], 0.66f, 14f, 1f, 0.30f),
        )
        val top = painter.colour(topV[0], topV[1], 0.42f, 0f, 0.85f, 1f)
        val mid = painter.colour(midV[0], midV[1], 0.32f, 0f, 0.80f, 1f)
        val deep = painter.colour(bottomV[0], bottomV[1], 0.23f, 0f, 0.70f, 1f)

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
                for (z in 1..4) {
                    if (!has[z]) {
                        fieldW[z - 1] = 0f
                        continue
                    }
                    val du = u - anchorU[z]
                    val dv = (t - anchorV[z]) * 2f
                    val d2 = du * du + dv * dv + 0.03f
                    fieldW[z - 1] = 1f / (d2 * kotlin.math.sqrt(d2))
                }
                mix(fieldA, fieldB, fieldW, 4, tmp)
                // lighter at the top, darker towards the bottom, a hint of light from the upper left
                val l = (0.58f - 0.27f * t + 0.04f * (1f - u) * (1f - t)).coerceIn(0.28f, 0.62f)
                mosaic[cy * Cols + cx] = painter.colour(tmp[0], tmp[1], l, 8f * (u - 0.5f), 0.95f - 0.20f * t, 0.25f)
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
