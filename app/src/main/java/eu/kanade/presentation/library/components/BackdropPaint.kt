package eu.kanade.presentation.library.components

import kotlin.math.PI
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

    private class Tone(val hueDeg: Float, val neutral: Boolean, val chroma: Float, val strength: Float) {
        val scratch = FloatArray(3)

        /** The chroma this tone may have at [lightness] (hue shifted by [turn] degrees), [k] times the picked chroma. */
        fun chromaAt(lightness: Float, turn: Float, k: Float): Float {
            if (neutral) return 0f
            val h = hueDeg + turn
            val cap = gamutMax(lightness, rad(h), scratch) * min(0.98f, satCap(h) * max(1f, strength))
            return min(chroma * k, cap)
        }

        /** ARGB at the given lightness; if it is brighter than [maxLuma] it is lowered until it is not. */
        fun colour(lightness: Float, turn: Float, k: Float, maxLuma: Float): Int {
            val h = hueDeg + turn
            var l = lightness
            var out = 0
            repeat(24) {
                val c = chromaAt(l, turn, k)
                linearRgb(l, c * cos(rad(h)), c * sin(rad(h)), scratch)
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

    /** The whole backdrop for one picked colour. [strength] 1 = as designed, more = stronger colour. */
    fun paint(picked: Int, strength: Float = 1f): Result {
        val lab = FloatArray(3)
        oklab(picked, lab)
        val c0 = hypot(lab[1], lab[2])
        val neutral = c0 < 0.012f
        var hue = Math.toDegrees(atan2(lab[2], lab[1]).toDouble()).toFloat()
        hue = ((hue % 360f) + 360f) % 360f
        // A dull pick keeps its hue but gets enough colour to read as that hue; a strong one is only polished.
        val base = if (neutral) 0f else (c0 * 1.5f + 0.05f).coerceIn(0.085f, 0.30f) * strength
        val tone = Tone(hue, neutral, base, strength)

        val lights = intArrayOf(
            tone.colour(0.66f, 0f, 1f, 0.30f),
            tone.colour(0.62f, 7f, 1f, 0.30f),
            tone.colour(0.70f, -7f, 1f, 0.30f),
            tone.colour(0.66f, 14f, 1f, 0.30f),
        )
        val top = tone.colour(0.42f, 0f, 0.85f, 1f)
        val mid = tone.colour(0.32f, 0f, 0.80f, 1f)
        val deep = tone.colour(0.23f, 0f, 0.70f, 1f)

        val mosaic = IntArray(Cols * Rows)
        for (cy in 0 until Rows) {
            val t = cy / (Rows - 1f)
            for (cx in 0 until Cols) {
                val u = cx / (Cols - 1f)
                // lighter at the top, darker towards the bottom, a hint of light from the upper left
                val l = (0.58f - 0.27f * t + 0.04f * (1f - u) * (1f - t)).coerceIn(0.28f, 0.62f)
                mosaic[cy * Cols + cx] = tone.colour(l, 8f * (u - 0.5f), 0.95f - 0.20f * t, 0.25f)
            }
        }
        return Result(top, mid, deep, lights, mosaic, 0.6f)
    }
}
// ANZ <--
