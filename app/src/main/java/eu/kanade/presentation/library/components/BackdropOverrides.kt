package eu.kanade.presentation.library.components

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

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
    /** True when any hand adjustment is on. */
    val adjusted: Boolean
        get() = light != 0f || color != 0f || tone != 0f || force != 0f || exact

    fun withoutAdjustments(): BackdropMarker =
        copy(light = 0f, color = 0f, tone = 0f, force = 0f, exact = false)
}

/** One hand-made backdrop: up to [MaxMarkers] markers and an overall strength (1 = as designed). */
internal class BackdropEdit(
    val markers: List<BackdropMarker>,
    val strength: Float = 1f,
) {
    companion object {
        const val MaxMarkers = 4
    }
}

/**
 * The edits made by hand. When a cover has one, the Home carousel uses it instead of the colours read automatically;
 * without one nothing changes. Kept in its own small file, apart from the automatic colours, so that improving the
 * automatic reading never erases anybody's edits.
 */
internal object BackdropOverrides {

    private const val FileName = "backdrop_edits.txt"
    private const val Header = "ANZ-BACKDROP 3"
    private const val HeaderV2 = "ANZ-BACKDROP 2"
    private const val HeaderV1 = "ANZ-BACKDROP 1"

    /** By anime id. Observable, so the Home page changes the moment an edit is saved or removed. */
    private val edits = mutableStateMapOf<Long, BackdropEdit>()

    /** The tones built from each edit (main thread only). */
    private val built = HashMap<Long, Pair<BackdropEdit, CoverTones>>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fileLock = Any()

    init {
        load()
    }

    fun editOf(animeId: Long): BackdropEdit? = edits[animeId]

    /** The colours the backdrop of this anime must use: its edit when it has one, otherwise [auto]. */
    fun apply(animeId: Long, auto: CoverTones?): CoverTones? {
        val edit = edits[animeId] ?: return auto
        val known = built[animeId]
        if (known != null && known.first === edit) return known.second
        val tones = tonesOf(edit)
        built[animeId] = edit to tones
        return tones
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

    fun save(animeId: Long, edit: BackdropEdit) {
        edits[animeId] = edit
        persist()
    }

    fun clear(animeId: Long) {
        if (edits.remove(animeId) != null) {
            built.remove(animeId)
            persist()
        }
    }

    private fun persist() {
        val text = buildString {
            append(Header).append('\n')
            for ((id, e) in edits.entries.sortedBy { it.key }) {
                append(id).append(' ')
                append(e.strength).append(' ')
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
                append('\n')
            }
        }
        scope.launch {
            try {
                synchronized(fileLock) {
                    val dir = Injekt.get<Application>().filesDir
                    val tmp = File(dir, "$FileName.tmp")
                    tmp.writeText(text)
                    val target = File(dir, FileName)
                    if (!tmp.renameTo(target)) {
                        target.writeText(text)
                        tmp.delete()
                    }
                }
            } catch (_: Throwable) {
                // the edit still works until the app closes
            }
        }
    }

    private fun load() {
        try {
            val file = File(Injekt.get<Application>().filesDir, FileName)
            if (!file.exists()) return
            val lines = file.readLines()
            when (lines.firstOrNull()?.trim()) {
                Header -> for (line in lines.drop(1)) parseV3(line)
                HeaderV2 -> for (line in lines.drop(1)) parseV2(line)
                HeaderV1 -> for (line in lines.drop(1)) parseV1(line)
            }
        } catch (_: Throwable) {
            // an unreadable file only means the automatic colours are used
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
        val strength = p.getOrNull(4)?.toFloatOrNull() ?: 1f
        edits[id] = BackdropEdit(listOf(BackdropMarker(x, y, argb)), strength)
    }

    /**
     * "id strength count" followed by "x y argb zone strength light color tone force exact" for every marker
     * (exact is 1 or 0).
     */
    private fun parseV3(line: String) {
        val p = line.trim().split(' ')
        if (p.size < 3) return
        val id = p[0].toLongOrNull() ?: return
        val strength = p[1].toFloatOrNull() ?: return
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
        val strength = p[1].toFloatOrNull() ?: return
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
