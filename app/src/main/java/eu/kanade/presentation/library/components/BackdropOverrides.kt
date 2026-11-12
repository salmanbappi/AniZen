package eu.kanade.presentation.library.components

import android.app.Application
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.net.toUri
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import com.hippo.unifile.UniFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import tachiyomi.domain.storage.service.StoragePreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.Locale
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

// ANZ --> Backdrops edited by hand ("Editar fondo"), one per anime, kept between launches.

/**
 * One marker. [x] and [y] are where it sits on the cover card (0 to 1, from the top left), [argb] the colour that was
 * under it, [zone] the part of the backdrop it colours (see [BackdropPaint.ZoneAuto]) and [strength] how strong it is.
 *
 * The hand adjustments each go from -1 to 1 (0 = untouched): [light] (Brillo), [color] (Color), [tone] (Tono) and
 * [force] (Fuerza, how much it rules over the other colours). With [exact] the colour is used as it is.
 */
internal data class BackdropMarker(
    val x: Float,
    val y: Float,
    val argb: Int,
    val zone: Int = BackdropPaint.ZoneAuto,
    val strength: Float = 1f,
    val light: Float = 0f,
    val color: Float = 0f,
    val tone: Float = 0f,
    val force: Float = 0f,
    val exact: Boolean = false,
) {
    /** True when any hand adjustment is on (including a zone chosen by hand). */
    val adjusted: Boolean
        get() = light != 0f || color != 0f || tone != 0f || force != 0f || exact || zone != BackdropPaint.ZoneAuto

    fun withoutAdjustments(): BackdropMarker =
        copy(zone = BackdropPaint.ZoneAuto, light = 0f, color = 0f, tone = 0f, force = 0f, exact = false)
}

/**
 * Hand changes on top of the automatic backdrop, each from -1 to 1 (0 = none): [light] (Brillo), [color] (Color) and
 * [tone] (Tono). They are used only by an edit without markers.
 */
internal data class BackdropTweak(
    val light: Float = 0f,
    val color: Float = 0f,
    val tone: Float = 0f,
    /** Changes of one section of the backdrop each (the four lights, in the order of the colour engine), or none. */
    val spots: List<SpotTweak> = emptyList(),
) {
    val active: Boolean
        get() = light != 0f || color != 0f || tone != 0f || spots.any { it.active }
}

/**
 * One section of the automatic backdrop (there are four, one per light of the colour engine). Its colour lives around
 * the spot ([x], [y] on the cover, 0 to 1). It can take another colour ([pickArgb], picked on the cover at [pickX],
 * [pickY]), be taken off ([removed]: its colour disappears and the others cover the place), and have its lightness
 * ([light]), colour ([color]) and hue ([tone]) changed, each from -1 to 1 (0 = untouched). [reach] makes the section
 * smaller (-1) or larger (1).
 */
internal data class SpotTweak(
    val x: Float,
    val y: Float,
    val light: Float = 0f,
    val color: Float = 0f,
    val tone: Float = 0f,
    val reach: Float = 0f,
    val removed: Boolean = false,
    val pickX: Float = -1f,
    val pickY: Float = -1f,
    /** The colour picked on the cover for this section, or 0 when it keeps the colour the engine found. */
    val pickArgb: Int = 0,
    /** Where in the backdrop the colour goes: 0 where it lives ([x], [y]), or one of the BackdropPaint zones. */
    val zone: Int = 0,
    /** How much the colour rules over the automatic one around it, from -1 (softly) to 1 (fully); 0 = usual. */
    val force: Float = 0f,
) {
    val picked: Boolean
        get() = pickArgb != 0

    /** Where in the backdrop (0 to 1, like the cover) the colour of this section sits. */
    val atX: Float
        get() = when (zone) {
            BackdropPaint.ZoneLeft -> 0.12f
            BackdropPaint.ZoneRight -> 0.88f
            BackdropPaint.ZoneTop, BackdropPaint.ZoneBottom -> 0.5f
            else -> x
        }

    val atY: Float
        get() = when (zone) {
            BackdropPaint.ZoneTop -> 0.12f
            BackdropPaint.ZoneBottom -> 0.88f
            BackdropPaint.ZoneLeft, BackdropPaint.ZoneRight -> 0.5f
            else -> y
        }

    /** True when it changes the backdrop (a different reach or force alone changes nothing). */
    val active: Boolean
        get() = removed || picked || zone != 0 || light != 0f || color != 0f || tone != 0f
}

/**
 * One hand-made backdrop: up to [MaxMarkers] markers and an overall strength (1 = as designed, [MinStrength] to
 * [MaxStrength]). Without markers it is the automatic backdrop with the [tweak] on top of it.
 */
internal class BackdropEdit(
    val markers: List<BackdropMarker>,
    val strength: Float = 1f,
    val tweak: BackdropTweak = BackdropTweak(),
) {
    companion object {
        const val MaxMarkers = 4
        const val MinStrength = 0.2f
        const val MaxStrength = 1.6f
    }
}

/**
 * The edits made by hand. When a cover has one, the Home carousel uses it instead of the colours read automatically;
 * without one nothing changes. Kept in its own small file, apart from the automatic colours, so that improving the
 * automatic reading never erases anybody's edits.
 */
internal object BackdropOverrides {

    private const val FileName = "backdrop_edits.txt"

    /** The readable report of the edits: kept inside the app and copied to the AniZen folder with the edits. */
    private const val ReportName = "backdrop_report.txt"
    private const val Header = "ANZ-BACKDROP 4"
    private const val HeaderV3 = "ANZ-BACKDROP 3"
    private const val HeaderV2 = "ANZ-BACKDROP 2"
    private const val HeaderV1 = "ANZ-BACKDROP 1"

    /** By anime id. Observable, so the Home page changes the moment an edit is saved or removed. */
    private val edits = mutableStateMapOf<Long, BackdropEdit>()

    /** The tones built from each edit (main thread only); [auto] is what an edit without markers was built on. */
    private class Built(val edit: BackdropEdit, val auto: CoverTones?, val tones: CoverTones)

    private val built = HashMap<Long, Built>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fileLock = Any()

    /** A readable line about each edit (title, automatic colours, what was changed), by anime id. */
    private val notes = HashMap<Long, String>()

    init {
        load()
        // edits made before the copy in the AniZen folder existed get their copy now
        if (edits.isNotEmpty()) persist(announce = false)
    }

    fun editOf(animeId: Long): BackdropEdit? = edits[animeId]

    /** The colours the backdrop of this anime must use: its edit when it has one, otherwise [auto]. */
    fun apply(animeId: Long, auto: CoverTones?): CoverTones? {
        val edit = edits[animeId] ?: return auto
        val onAuto = edit.markers.isEmpty()
        // an edit without markers works on the automatic colours, which may not be ready yet
        if (onAuto && auto == null) return null
        val known = built[animeId]
        if (known != null && known.edit === edit && (!onAuto || known.auto === auto)) return known.tones
        val tones = if (onAuto && auto != null) tweaked(auto, edit.tweak) else tonesOf(edit)
        built[animeId] = Built(edit, if (onAuto) auto else null, tones)
        return tones
    }

    /**
     * The automatic colours with the hand changes on top (also used for the live preview). The changes to the whole
     * backdrop apply everywhere. Each section works around its spot: it can take another colour, be taken off and have
     * its lightness, colour and hue changed. They fade out with the distance to the spot, so the rest stays as the
     * colour engine made it.
     */
    fun tweaked(auto: CoverTones, tweak: BackdropTweak): CoverTones {
        val spots = tweak.spots.take(4)
        val globalShift = BackdropPaint.lightShift(tweak.light)
        val globalFactor = BackdropPaint.colourFactor(tweak.color)
        val globalTurn = BackdropPaint.toneDegrees(tweak.tone)

        // one colour with the global change and the sections it belongs to ([w] = how much of each section reaches it)
        fun adjust(argb: Int, w: FloatArray): Int {
            var shift = globalShift
            var extra = 0f
            var turn = globalTurn
            for (i in spots.indices) {
                val s = spots[i]
                if (s.removed) continue
                shift += w[i] * BackdropPaint.lightShift(s.light)
                extra += w[i] * (BackdropPaint.colourFactor(s.color) - 1f)
                turn += w[i] * BackdropPaint.toneDegrees(s.tone)
            }
            val factor = max(0f, globalFactor * (1f + extra))
            if (shift == 0f && factor == 1f && turn == 0f) return argb
            return BackdropPaint.tweak(argb, shift, factor, turn)
        }

        fun weights(vararg parts: Pair<Int, Float>): FloatArray {
            val w = FloatArray(spots.size)
            for ((i, share) in parts) if (i in w.indices) w[i] = share
            return w
        }

        // what a picked colour becomes in the mosaic and in a light (graded the way the colour engine grades)
        val picked = spots.map { if (it.pickArgb != 0 && !it.removed) gradedPick(it.pickArgb) else null }

        // for a section taken off: the mix of what the others have, the nearer ones counting more
        fun fill(i: Int, colourOf: (Int) -> Int?): Int? {
            var sr = 0f
            var sg = 0f
            var sb = 0f
            var sw = 0f
            for (j in spots.indices) {
                if (j == i || spots[j].removed) continue
                val c = colourOf(j) ?: continue
                val dx = (spots[j].atX - spots[i].atX) * 0.7f
                val dy = spots[j].atY - spots[i].atY
                val weight = 1f / (dx * dx + dy * dy + 0.02f)
                sr += weight * ((c shr 16) and 255)
                sg += weight * ((c shr 8) and 255)
                sb += weight * (c and 255)
                sw += weight
            }
            if (sw <= 0f) return null
            return (0xFF shl 24) or
                (((sr / sw + 0.5f).toInt().coerceIn(0, 255)) shl 16) or
                (((sg / sw + 0.5f).toInt().coerceIn(0, 255)) shl 8) or
                ((sb / sw + 0.5f).toInt().coerceIn(0, 255))
        }

        val mosaic = auto.mosaic?.let { picture ->
            try {
                val bitmap = picture.asAndroidBitmap()
                val w = bitmap.width
                val h = bitmap.height
                val px = IntArray(w * h)
                bitmap.getPixels(px, 0, w, 0, 0, w, h)
                val original = px.copyOf()

                // the colour of a small area of the automatic mosaic
                fun around(fx: Float, fy: Float): Int {
                    val cx = (fx.coerceIn(0f, 1f) * w).toInt().coerceIn(0, w - 1)
                    val cy = (fy.coerceIn(0f, 1f) * h).toInt().coerceIn(0, h - 1)
                    var r = 0
                    var g = 0
                    var b = 0
                    var n = 0
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            val c = original[(cy + dy).coerceIn(0, h - 1) * w + (cx + dx).coerceIn(0, w - 1)]
                            r += (c shr 16) and 255
                            g += (c shr 8) and 255
                            b += c and 255
                            n++
                        }
                    }
                    return (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
                }

                // the colour each section has: the one picked for it, or the one the engine found where it lives
                val own = IntArray(spots.size) { around(spots[it].x, spots[it].y) }
                val colourNow = IntArray(spots.size) { picked[it]?.first ?: own[it] }
                // the colour each section puts in its place (0 = it keeps the automatic one there)
                val replacement = IntArray(spots.size) { i ->
                    when {
                        spots[i].removed -> fill(i) { j -> colourNow[j] } ?: around(0.5f, 0.5f)
                        picked[i] != null -> picked[i]!!.first
                        spots[i].zone != 0 -> own[i]
                        else -> 0
                    }
                }
                val forceOf = FloatArray(spots.size) { 2f.pow(spots[it].force.coerceIn(-1f, 1f)) }

                val share = FloatArray(spots.size)
                for (k in px.indices) {
                    val u = ((k % w) + 0.5f) / w
                    val v = ((k / w) + 0.5f) / h
                    var sum = 0f
                    for (i in spots.indices) {
                        val s = spots[i]
                        val sigma = 0.22f * 2f.pow(s.reach.coerceIn(-1f, 1f))
                        val dx = (u - s.atX) * 0.7f
                        val dy = v - s.atY
                        val value = if (s.active) exp(-(dx * dx + dy * dy) / (2f * sigma * sigma)) else 0f
                        share[i] = value
                        sum += value
                    }
                    if (sum > 1f) for (i in spots.indices) share[i] /= sum

                    // the colours the sections put here, in place of the automatic one
                    var total = 0f
                    var mr = 0f
                    var mg = 0f
                    var mb = 0f
                    for (i in spots.indices) {
                        val c = replacement[i]
                        if (c == 0 || share[i] <= 0f) continue
                        val part = min(1f, share[i] * 1.4f * forceOf[i])
                        total += part
                        mr += part * ((c shr 16) and 255)
                        mg += part * ((c shr 8) and 255)
                        mb += part * (c and 255)
                    }
                    var colour = px[k]
                    if (total > 0f) {
                        val t = min(total, 1f)
                        val old = px[k]
                        val r = (((old shr 16) and 255) * (1f - t) + mr / total * t + 0.5f).toInt().coerceIn(0, 255)
                        val g = (((old shr 8) and 255) * (1f - t) + mg / total * t + 0.5f).toInt().coerceIn(0, 255)
                        val b = ((old and 255) * (1f - t) + mb / total * t + 0.5f).toInt().coerceIn(0, 255)
                        colour = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                    px[k] = adjust(colour, share)
                }
                Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
            } catch (_: Throwable) {
                picture
            }
        }

        val lights = auto.lights.mapIndexed { i, c ->
            val s = spots.getOrNull(i)
            val base = when {
                s == null -> c.toArgb()
                s.removed -> fill(i) { j -> picked[j]?.second ?: auto.lights.getOrNull(j)?.toArgb() } ?: c.toArgb()
                picked[i] != null -> picked[i]!!.second
                else -> c.toArgb()
            }
            Color(adjust(base, weights(i to 1f)))
        }
        val topShare = weights(0 to 0.5f, 2 to 0.5f)
        val restShare = weights(0 to 0.25f, 1 to 0.25f, 2 to 0.25f, 3 to 0.25f)
        return CoverTones(
            coverVersion = auto.coverVersion,
            presence = auto.presence,
            top = Color(adjust(auto.top.toArgb(), topShare)),
            mid = Color(adjust(auto.mid.toArgb(), restShare)),
            deep = Color(adjust(auto.deep.toArgb(), restShare)),
            lights = lights,
            mosaic = mosaic,
        )
    }

    private val gradedCache = HashMap<Int, Pair<Int, Int>>()

    /** A colour picked on the cover as the backdrop would have it: its mosaic colour and its light colour. */
    private fun gradedPick(argb: Int): Pair<Int, Int> = synchronized(gradedCache) {
        gradedCache[argb] ?: run {
            val r = BackdropPaint.paint(listOf(BackdropPaint.Pick(argb)), 1f)
            var sr = 0L
            var sg = 0L
            var sb = 0L
            for (c in r.mosaic) {
                sr += (c shr 16) and 255
                sg += (c shr 8) and 255
                sb += c and 255
            }
            val n = r.mosaic.size.coerceAtLeast(1)
            val mosaicColour = (0xFF shl 24) or ((sr / n).toInt() shl 16) or ((sg / n).toInt() shl 8) or (sb / n).toInt()
            val pair = Pair(mosaicColour, r.lights[0])
            if (gradedCache.size > 96) gradedCache.clear()
            gradedCache[argb] = pair
            pair
        }
    }

    /** The colour the mosaic of [tones] has around a spot of the cover (x and y from 0 to 1), or null. */
    fun colourAround(tones: CoverTones, x: Float, y: Float): Int? {
        val picture = tones.mosaic ?: return null
        return try {
            val bitmap = picture.asAndroidBitmap()
            val w = bitmap.width
            val h = bitmap.height
            if (w <= 0 || h <= 0) return null
            val cx = (x.coerceIn(0f, 1f) * w).toInt().coerceIn(0, w - 1)
            val cy = (y.coerceIn(0f, 1f) * h).toInt().coerceIn(0, h - 1)
            var r = 0
            var g = 0
            var b = 0
            var n = 0
            for (dy in -1..1) {
                for (dx in -1..1) {
                    val c = bitmap.getPixel((cx + dx).coerceIn(0, w - 1), (cy + dy).coerceIn(0, h - 1))
                    r += (c shr 16) and 255
                    g += (c shr 8) and 255
                    b += c and 255
                    n++
                }
            }
            (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
        } catch (_: Throwable) {
            null
        }
    }

    /** Builds the backdrop colours for an edit (also used for the live preview while it is being made). */
    fun tonesOf(edit: BackdropEdit): CoverTones {
        val picks = edit.markers.map {
            BackdropPaint.Pick(
                argb = it.argb,
                zone = it.zone,
                x = it.x,
                y = it.y,
                strength = it.strength,
                light = it.light,
                color = it.color,
                tone = it.tone,
                force = it.force,
                exact = it.exact,
            )
        }
        val r = BackdropPaint.paint(picks, edit.strength)
        val mosaic = Bitmap.createBitmap(
            r.mosaic,
            BackdropPaint.Cols,
            BackdropPaint.Rows,
            Bitmap.Config.ARGB_8888,
        ).asImageBitmap()
        return CoverTones(
            coverVersion = 0L,
            presence = r.presence,
            top = Color(r.top),
            mid = Color(r.mid),
            deep = Color(r.deep),
            lights = r.lights.map { Color(it) },
            mosaic = mosaic,
        )
    }

    /** Saves an edit; [title] and [auto] (the automatic colours) only make the report in the AniZen folder clearer. */
    fun save(animeId: Long, edit: BackdropEdit, title: String? = null, auto: CoverTones? = null) {
        edits[animeId] = edit
        if (title != null) synchronized(notes) { notes[animeId] = describe(animeId, title, auto, edit) }
        persist()
    }

    /** The same edit for several anime (the other seasons of a series); the files are written once. */
    fun saveAll(animeIds: Collection<Long>, edit: BackdropEdit, title: String? = null, auto: CoverTones? = null) {
        for (id in animeIds) edits[id] = edit
        if (title != null) {
            synchronized(notes) {
                for (id in animeIds) notes[id] = describe(id, "$title (copiado a otras temporadas)", auto, edit)
            }
        }
        persist()
    }

    fun clear(animeId: Long) {
        if (edits.remove(animeId) != null) {
            built.remove(animeId)
            synchronized(notes) { notes.remove(animeId) }
            persist()
        }
    }

    private fun f2(v: Float): String = String.format(Locale.US, "%.2f", v)

    private fun hex(argb: Int): String = String.format(Locale.US, "#%06X", argb and 0xFFFFFF)

    /** One line that says everything about an edit, so the colour engine can learn from what was chosen by hand. */
    private fun describe(animeId: Long, title: String, auto: CoverTones?, edit: BackdropEdit): String = buildString {
        append("«").append(title.replace('\n', ' ').replace('\t', ' ')).append("» id=").append(animeId)
        append(" | automático: ")
        if (auto == null) {
            append("no disponible")
        } else {
            append("arriba=").append(hex(auto.top.toArgb()))
            append(" medio=").append(hex(auto.mid.toArgb()))
            append(" fondo=").append(hex(auto.deep.toArgb()))
            append(" luces=").append(auto.lights.joinToString(",") { hex(it.toArgb()) })
        }
        append(" | fuerza general=").append(f2(edit.strength))
        val t = edit.tweak
        append(" | todo: brillo=").append(f2(t.light))
        append(" color=").append(f2(t.color))
        append(" tono=").append(f2(t.tone))
        append(" | marcadores: ")
        if (edit.markers.isEmpty()) append("ninguno")
        append(
            edit.markers.joinToString("; ") { m ->
                "x=${f2(m.x)} y=${f2(m.y)} ${hex(m.argb)} zona=${m.zone} fuerza=${f2(m.strength)} " +
                    "brillo=${f2(m.light)} color=${f2(m.color)} tono=${f2(m.tone)} manda=${f2(m.force)} " +
                    "exacto=${if (m.exact) 1 else 0}"
            },
        )
        append(" | secciones: ")
        if (t.spots.isEmpty()) append("ninguna")
        append(
            t.spots.withIndex().joinToString("; ") { (i, sp) ->
                "${i + 1}: lugar=(${f2(sp.x)},${f2(sp.y)}) " +
                    (if (sp.picked) "color elegido=${hex(sp.pickArgb)} en=(${f2(sp.pickX)},${f2(sp.pickY)}) " else "") +
                    "zona=${sp.zone} fuerza=${f2(sp.force)} alcance=${f2(sp.reach)} " +
                    "brillo=${f2(sp.light)} color=${f2(sp.color)} tono=${f2(sp.tone)} quitado=${if (sp.removed) 1 else 0}"
            },
        )
    }

    /** The AniZen folder (the storage folder chosen in the app), or null when it cannot be reached. */
    private fun folder(): UniFile? = try {
        val uri = Injekt.get<StoragePreferences>().baseStorageDirectory().get()
        UniFile.fromUri(Injekt.get<Application>(), uri.toUri())?.takeIf { it.exists() }
    } catch (_: Throwable) {
        null
    }

    /** Writes the files into the AniZen folder (each one replaces the one that was there). */
    private fun copyToFolder(files: List<Pair<String, String>>): Boolean = try {
        val dir = folder()
        if (dir == null) {
            false
        } else {
            var all = true
            for ((name, content) in files) {
                dir.findFile(name)?.delete()
                val target = dir.createFile(name)
                if (target == null) {
                    all = false
                } else {
                    target.openOutputStream().use { it.write(content.toByteArray()) }
                }
            }
            all
        }
    } catch (_: Throwable) {
        false
    }

    /** Brings back the files from the AniZen folder (after the app was reinstalled, say). */
    private fun restoreFromFolder(dir: File) {
        try {
            val base = folder() ?: return
            for (name in listOf(FileName, ReportName)) {
                val source = base.findFile(name) ?: continue
                val bytes = source.openInputStream().use { it.readBytes() }
                if (bytes.isNotEmpty()) File(dir, name).writeBytes(bytes)
            }
        } catch (_: Throwable) {
            // without the copy the automatic colours are used
        }
    }

    private fun tellCopyResult(ok: Boolean) {
        try {
            val context = Injekt.get<Application>()
            val text = if (ok) {
                "Respaldo guardado en la carpeta AniZen"
            } else {
                "No se pudo guardar el respaldo en la carpeta AniZen"
            }
            Handler(Looper.getMainLooper()).post { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
        } catch (_: Throwable) {
            // only a message
        }
    }

    private fun persist(announce: Boolean = true) {
        val text = buildString {
            append(Header).append('\n')
            for ((id, e) in edits.entries.sortedBy { it.key }) {
                append(id).append(' ')
                append(e.strength).append(' ')
                append(e.tweak.light).append(' ')
                append(e.tweak.color).append(' ')
                append(e.tweak.tone).append(' ')
                append(e.markers.size)
                for (m in e.markers) {
                    append(' ').append(m.x)
                    append(' ').append(m.y)
                    append(' ').append(m.argb)
                    append(' ').append(m.zone)
                    append(' ').append(m.strength)
                    append(' ').append(m.light)
                    append(' ').append(m.color)
                    append(' ').append(m.tone)
                    append(' ').append(m.force)
                    append(' ').append(if (m.exact) 1 else 0)
                }
                val spots = if (e.tweak.active) e.tweak.spots.take(4) else emptyList()
                append(' ').append(spots.size)
                for (sp in spots) {
                    append(' ').append(sp.x)
                    append(' ').append(sp.y)
                    append(' ').append(sp.light)
                    append(' ').append(sp.color)
                    append(' ').append(sp.tone)
                    append(' ').append(sp.reach)
                }
                // which sections are taken off, one digit each (files from before this have none)
                if (spots.isNotEmpty()) {
                    append(' ').append(spots.joinToString("") { if (it.removed) "1" else "0" })
                    // the colour picked for each section: "x,y,argb" joined by ";"
                    append(' ').append(spots.joinToString(";") { "${it.pickX},${it.pickY},${it.pickArgb}" })
                    // where each colour goes and how strongly: "zone,force" joined by ";"
                    append(' ').append(spots.joinToString(";") { "${it.zone},${it.force}" })
                }
                append('\n')
            }
        }
        // the readable report: one line for every edit (those saved before the report existed have no title)
        val report = buildString {
            append("# AniZen - fondos editados a mano (informe para mejorar el motor de colores)\n")
            append("# Cada línea: id, tabulador y la descripción. Los ajustes van de -1 a 1; x e y son fracciones de la portada.\n")
            synchronized(notes) {
                for ((id, e) in edits.entries.sortedBy { it.key }) {
                    append(id).append('\t').append(notes[id] ?: describe(id, "(sin título)", null, e)).append('\n')
                }
            }
        }
        scope.launch {
            try {
                synchronized(fileLock) {
                    val dir = Injekt.get<Application>().filesDir
                    for ((name, content) in listOf(FileName to text, ReportName to report)) {
                        val tmp = File(dir, "$name.tmp")
                        tmp.writeText(content)
                        val target = File(dir, name)
                        if (!tmp.renameTo(target)) {
                            target.writeText(content)
                            tmp.delete()
                        }
                    }
                }
            } catch (_: Throwable) {
                // the edit still works until the app closes
            }
            val copied = copyToFolder(listOf(FileName to text, ReportName to report))
            if (announce) tellCopyResult(copied)
        }
    }

    private fun load() {
        try {
            val dir = Injekt.get<Application>().filesDir
            val file = File(dir, FileName)
            if (!file.exists()) restoreFromFolder(dir)
            loadNotes(File(dir, ReportName))
            if (!file.exists()) return
            val lines = file.readLines()
            when (lines.firstOrNull()?.trim()) {
                Header -> for (line in lines.drop(1)) parseV4(line)
                HeaderV3 -> for (line in lines.drop(1)) parseV3(line)
                HeaderV2 -> for (line in lines.drop(1)) parseV2(line)
                HeaderV1 -> for (line in lines.drop(1)) parseV1(line)
            }
        } catch (_: Throwable) {
            // an unreadable file only means the automatic colours are used
        }
    }

    private fun loadNotes(file: File) {
        try {
            if (!file.exists()) return
            synchronized(notes) {
                for (line in file.readLines()) {
                    if (line.startsWith("#")) continue
                    val cut = line.indexOf('\t')
                    if (cut <= 0) continue
                    val id = line.substring(0, cut).toLongOrNull() ?: continue
                    notes[id] = line.substring(cut + 1)
                }
            }
        } catch (_: Throwable) {
            // the report is only an extra
        }
    }

    /** The first version of the file: one marker per anime, "id x y argb strength". */
    private fun parseV1(line: String) {
        val p = line.trim().split(' ')
        if (p.size < 4) return
        val id = p[0].toLongOrNull() ?: return
        val x = p[1].toFloatOrNull() ?: return
        val y = p[2].toFloatOrNull() ?: return
        val argb = p[3].toIntOrNull() ?: return
        val strength = (p.getOrNull(4)?.toFloatOrNull() ?: 1f).coerceIn(BackdropEdit.MinStrength, BackdropEdit.MaxStrength)
        edits[id] = BackdropEdit(listOf(BackdropMarker(x, y, argb)), strength)
    }

    /**
     * "id strength tweakLight tweakColor tweakTone count" followed by "x y argb zone strength light color tone force
     * exact" for every marker (exact is 1 or 0). An edit may have no markers when it has a tweak.
     */
    private fun parseV4(line: String) {
        val p = line.trim().split(' ')
        if (p.size < 6) return
        val id = p[0].toLongOrNull() ?: return
        val strength = (p[1].toFloatOrNull() ?: return).coerceIn(BackdropEdit.MinStrength, BackdropEdit.MaxStrength)
        val tweakLight = (p[2].toFloatOrNull() ?: return).coerceIn(-1f, 1f)
        val tweakColor = (p[3].toFloatOrNull() ?: return).coerceIn(-1f, 1f)
        val tweakTone = (p[4].toFloatOrNull() ?: return).coerceIn(-1f, 1f)
        val count = p[5].toIntOrNull() ?: return
        if (count !in 0..BackdropEdit.MaxMarkers || p.size < 6 + count * 10) return
        // after the markers: how many sections have changes (files from before sections have none), then each one
        val spotsAt = 6 + count * 10
        val spotCount = (p.getOrNull(spotsAt)?.toIntOrNull() ?: 0).coerceIn(0, 4)
        val spots = ArrayList<SpotTweak>(spotCount)
        if (p.size >= spotsAt + 1 + spotCount * 6) {
            val flags = p.getOrNull(spotsAt + 1 + spotCount * 6)?.takeIf { it.length == spotCount }
            val picks = p.getOrNull(spotsAt + 2 + spotCount * 6)?.split(';')?.takeIf { it.size == spotCount }
            val places = p.getOrNull(spotsAt + 3 + spotCount * 6)?.split(';')?.takeIf { it.size == spotCount }
            for (i in 0 until spotCount) {
                val at = spotsAt + 1 + i * 6
                spots += SpotTweak(
                    x = (p[at].toFloatOrNull() ?: return).coerceIn(0f, 1f),
                    y = (p[at + 1].toFloatOrNull() ?: return).coerceIn(0f, 1f),
                    light = (p[at + 2].toFloatOrNull() ?: return).coerceIn(-1f, 1f),
                    color = (p[at + 3].toFloatOrNull() ?: return).coerceIn(-1f, 1f),
                    tone = (p[at + 4].toFloatOrNull() ?: return).coerceIn(-1f, 1f),
                    reach = (p[at + 5].toFloatOrNull() ?: return).coerceIn(-1f, 1f),
                    removed = flags != null && flags[i] == '1',
                    pickX = picks?.get(i)?.split(',')?.getOrNull(0)?.toFloatOrNull() ?: -1f,
                    pickY = picks?.get(i)?.split(',')?.getOrNull(1)?.toFloatOrNull() ?: -1f,
                    pickArgb = picks?.get(i)?.split(',')?.getOrNull(2)?.toIntOrNull() ?: 0,
                    zone = (places?.get(i)?.split(',')?.getOrNull(0)?.toIntOrNull() ?: 0).coerceIn(0, 4),
                    force = (places?.get(i)?.split(',')?.getOrNull(1)?.toFloatOrNull() ?: 0f).coerceIn(-1f, 1f),
                )
            }
        }
        val tweak = BackdropTweak(tweakLight, tweakColor, tweakTone, spots)
        if (count == 0 && !tweak.active) return
        val markers = ArrayList<BackdropMarker>(count)
        for (i in 0 until count) {
            val at = 6 + i * 10
            markers += BackdropMarker(
                x = p[at].toFloatOrNull() ?: return,
                y = p[at + 1].toFloatOrNull() ?: return,
                argb = p[at + 2].toIntOrNull() ?: return,
                zone = p[at + 3].toIntOrNull() ?: return,
                strength = p[at + 4].toFloatOrNull() ?: return,
                light = (p[at + 5].toFloatOrNull() ?: return).coerceIn(-1f, 1f),
                color = (p[at + 6].toFloatOrNull() ?: return).coerceIn(-1f, 1f),
                tone = (p[at + 7].toFloatOrNull() ?: return).coerceIn(-1f, 1f),
                force = (p[at + 8].toFloatOrNull() ?: return).coerceIn(-1f, 1f),
                exact = p[at + 9] == "1",
            )
        }
        edits[id] = BackdropEdit(markers, strength, tweak)
    }

    /**
     * The third version of the file: "id strength count" followed by "x y argb zone strength light color tone force
     * exact" for every marker (exact is 1 or 0).
     */
    private fun parseV3(line: String) {
        val p = line.trim().split(' ')
        if (p.size < 3) return
        val id = p[0].toLongOrNull() ?: return
        val strength = (p[1].toFloatOrNull() ?: return).coerceIn(BackdropEdit.MinStrength, BackdropEdit.MaxStrength)
        val count = p[2].toIntOrNull() ?: return
        if (count !in 1..BackdropEdit.MaxMarkers || p.size < 3 + count * 10) return
        val markers = ArrayList<BackdropMarker>(count)
        for (i in 0 until count) {
            val at = 3 + i * 10
            markers += BackdropMarker(
                x = p[at].toFloatOrNull() ?: return,
                y = p[at + 1].toFloatOrNull() ?: return,
                argb = p[at + 2].toIntOrNull() ?: return,
                zone = p[at + 3].toIntOrNull() ?: return,
                strength = p[at + 4].toFloatOrNull() ?: return,
                light = (p[at + 5].toFloatOrNull() ?: return).coerceIn(-1f, 1f),
                color = (p[at + 6].toFloatOrNull() ?: return).coerceIn(-1f, 1f),
                tone = (p[at + 7].toFloatOrNull() ?: return).coerceIn(-1f, 1f),
                force = (p[at + 8].toFloatOrNull() ?: return).coerceIn(-1f, 1f),
                exact = p[at + 9] == "1",
            )
        }
        edits[id] = BackdropEdit(markers, strength)
    }

    /** The second version of the file: "id strength count" followed by "x y argb zone strength" for every marker. */
    private fun parseV2(line: String) {
        val p = line.trim().split(' ')
        if (p.size < 3) return
        val id = p[0].toLongOrNull() ?: return
        val strength = (p[1].toFloatOrNull() ?: return).coerceIn(BackdropEdit.MinStrength, BackdropEdit.MaxStrength)
        val count = p[2].toIntOrNull() ?: return
        if (count !in 1..BackdropEdit.MaxMarkers || p.size < 3 + count * 5) return
        val markers = ArrayList<BackdropMarker>(count)
        for (i in 0 until count) {
            val at = 3 + i * 5
            markers += BackdropMarker(
                x = p[at].toFloatOrNull() ?: return,
                y = p[at + 1].toFloatOrNull() ?: return,
                argb = p[at + 2].toIntOrNull() ?: return,
                zone = p[at + 3].toIntOrNull() ?: return,
                strength = p[at + 4].toFloatOrNull() ?: return,
            )
        }
        edits[id] = BackdropEdit(markers, strength)
    }
}
// ANZ <--
