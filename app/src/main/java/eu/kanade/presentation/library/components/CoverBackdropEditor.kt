package eu.kanade.presentation.library.components

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import coil3.BitmapImage
import coil3.asDrawable
import eu.kanade.presentation.anime.components.AnimeCover
import eu.kanade.tachiyomi.ui.library.LibraryItem
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.anime.model.asAnimeCover
import kotlin.math.abs
import kotlin.math.roundToInt

// ANZ --> "Editar fondo": per-cover editor of the carousel backdrop.
// Up to four markers. Touch the cover to add one, touch a marker to choose it, slide to move it (a magnifier shows the
// exact colour). "Ajustar" opens the hand adjustments of the chosen marker (Brillo, Color, Tono, Fuerza and "Color
// exacto", the zone and the overall strength). "Comparar" (held down) shows the automatic backdrop, "Deshacer" goes
// back one step, "Copiar a otras temporadas" gives the same backdrop to the other seasons of the series. Save keeps the
// backdrop for this cover, "Volver a automático" removes it.
// When the window opens on a cover without an edit it shows where the automatic backdrop takes its colours from (what
// part of the cover counts, and the area each of the four lights looks at); "Ver origen" shows or hides that.
// "Ajustar el automático" changes the automatic backdrop itself (Brillo, Color, Tono) without markers.

/**
 * What the toolbar menu and the Home page agree on. The menu entry only shows while the Home carousel is on
 * screen ([available]) and asks the page to open the editor window ([show]).
 */
internal object BackdropEditor {
    var available by mutableStateOf(false)
    var open by mutableStateOf(false)

    fun show() {
        if (available) open = true
    }

    fun hide() {
        open = false
    }
}

/**
 * Lives inside the Home page. [enabled] is false when there is nothing to edit (another tab, searching). The cover
 * that is on show in the carousel is frozen when the window opens, so the carousel moving on underneath does not
 * change what is being edited. The carousel position is read here, never in the page itself, so the page does not
 * recompose every time the carousel moves.
 */
@Composable
internal fun BackdropEditorHost(
    heroes: ImmutableList<LibraryItem>,
    pager: PagerState,
    enabled: Boolean,
    library: List<LibraryItem>,
) {
    val editable = enabled && heroes.isNotEmpty()
    DisposableEffect(editable) {
        BackdropEditor.available = editable
        onDispose {
            BackdropEditor.available = false
            BackdropEditor.open = false
        }
    }
    if (editable && BackdropEditor.open) {
        val frozen = remember { heroes.getOrNull(pager.currentPage) }
        if (frozen != null) {
            CoverBackdropEditor(item = frozen, library = library, onClose = { BackdropEditor.hide() })
        }
    }
}

private const val CardRatio = 1f / 1.45f

/** The cover as a small picture the colours are read from, and what part of it the card shows. */
private class CoverSample(bitmap: Bitmap) {
    val w = bitmap.width
    val h = bitmap.height
    val pixels = IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h) }
    val image: ImageBitmap = bitmap.asImageBitmap()

    /** Fractions of the picture the card shows (the card crops it, centred). */
    val visW: Float
    val visH: Float

    init {
        val aspect = w.toFloat() / h
        if (aspect > CardRatio) {
            visH = 1f
            visW = CardRatio / aspect
        } else {
            visW = 1f
            visH = aspect / CardRatio
        }
    }

    fun imageX(nx: Float): Float = (0.5f - visW / 2f + nx * visW) * w
    fun imageY(ny: Float): Float = (0.5f - visH / 2f + ny * visH) * h

    /** Where a point of the whole cover (fractions) falls on the card: 0 to 1 each way, outside when the card crops it. */
    fun cardX(fx: Float): Float = (fx - (0.5f - visW / 2f)) / visW

    fun cardY(fy: Float): Float = (fy - (0.5f - visH / 2f)) / visH

    /** The colour under a point of the card (0 to 1 each way). */
    fun colourAt(nx: Float, ny: Float): Int =
        BackdropPaint.sample(pixels, w, h, imageX(nx), imageY(ny), 0.035f * visW * w)
}

private fun sampleBitmapOf(image: coil3.Image, resources: android.content.res.Resources): Bitmap? {
    val original = when (image) {
        is BitmapImage -> image.bitmap
        else -> image.asDrawable(resources).toBitmap()
    }
    val software = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && original.config == Bitmap.Config.HARDWARE) {
        original.copy(Bitmap.Config.ARGB_8888, false) ?: return null
    } else {
        original
    }
    val scale = 480f / maxOf(software.width, software.height).coerceAtLeast(1)
    return if (scale < 1f) {
        Bitmap.createScaledBitmap(
            software,
            (software.width * scale).toInt().coerceAtLeast(1),
            (software.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    } else {
        software
    }
}

@Composable
private fun CoverBackdropEditor(
    item: LibraryItem,
    library: List<LibraryItem>,
    onClose: () -> Unit,
) {
    val anime = item.libraryAnime.anime
    val cover = remember(anime.id, anime.thumbnailUrl, anime.coverLastModified) { anime.asAnimeCover() }
    val context = LocalContext.current
    val density = LocalDensity.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val screenWidthPx = with(density) { screenWidthDp.dp.toPx() }

    var coverBottom by remember { mutableFloatStateOf(0f) }
    var cardPos by remember { mutableStateOf(Offset.Zero) }
    var cardSize by remember { mutableStateOf(IntSize.Zero) }

    // the picture the colours are read from, once the cover has loaded
    var loaded by remember { mutableStateOf<coil3.Image?>(null) }
    var sample by remember { mutableStateOf<CoverSample?>(null) }
    LaunchedEffect(loaded) {
        val image = loaded ?: return@LaunchedEffect
        sample = withContext(Dispatchers.Default) {
            sampleBitmapOf(image, context.resources)?.let { CoverSample(it) }
        }
    }
    // the same analysis the automatic backdrop does, this time asked to say what it looked at
    LaunchedEffect(sample) {
        val s = sample ?: return@LaunchedEffect
        origin = withContext(Dispatchers.Default) {
            try {
                buildOrigin(s)
            } catch (_: Throwable) {
                null
            }
        }
    }

    // what is saved for this anime, and the markers as they are right now
    var saved by remember { mutableStateOf(BackdropOverrides.editOf(anime.id)) }
    var markers by remember { mutableStateOf(saved?.markers ?: emptyList()) }
    var selected by remember { mutableIntStateOf(if (markers.isEmpty()) -1 else 0) }
    var adjusting by remember { mutableStateOf(false) }
    // how strong the colour of the whole backdrop is (1 = as designed)
    var general by remember { mutableFloatStateOf(saved?.strength ?: 1f) }
    // while the "Comparar" button is held down the automatic backdrop is shown
    var comparing by remember { mutableStateOf(false) }
    var showCopy by remember { mutableStateOf(false) }
    // hand changes on top of the automatic backdrop (used while there are no markers), and whether their panel is open
    var tweak by remember { mutableStateOf(saved?.tweak ?: BackdropTweak()) }
    var tweakOpen by remember { mutableStateOf(saved?.markers?.isEmpty() == true) }
    // where the automatic colours come from; shown at first on a cover that has no edit
    var origin by remember { mutableStateOf<OriginView?>(null) }
    var showOrigin by remember { mutableStateOf(saved == null) }
    // the steps that can be undone (newest last), and when the last slider step was recorded
    val history = remember { mutableStateListOf<EditorSnapshot>() }
    var lastPanelStep by remember { mutableLongStateOf(0L) }
    var dragIndex by remember { mutableIntStateOf(-1) }
    var dragging by remember { mutableStateOf(false) }
    var touch by remember { mutableStateOf(Offset.Zero) }
    val scope = rememberCoroutineScope()
    val autoTones = CoverTonesCache.get(anime.id, anime.coverLastModified)

    val previewTones = remember(markers, general, tweak, autoTones) {
        when {
            markers.isNotEmpty() -> BackdropOverrides.tonesOf(BackdropEdit(markers, general))
            tweak.active && autoTones != null -> BackdropOverrides.tweaked(autoTones, tweak)
            else -> null
        }
    }
    // the tweak only counts while there are no markers
    val tweakNow = if (markers.isEmpty()) tweak else BackdropTweak()
    val generalNow = if (markers.isEmpty()) 1f else general
    val hasEdit = markers.isNotEmpty() || tweakNow.active
    val changed = hasEdit && (markers != saved?.markers || generalNow != saved?.strength || tweakNow != saved?.tweak)

    // the other seasons of this series found in the library, by their titles
    val seasons = remember(anime.id, library) {
        SeasonMatcher.relatives(
            anime.id,
            anime.title,
            library.map { it.libraryAnime.anime.id to it.libraryAnime.anime.title },
        )
    }

    // records the state before a change, so that "Deshacer" can bring it back
    fun pushHistory() {
        val last = history.lastOrNull()
        if (last != null && last.markers == markers && last.general == general && last.tweak == tweak) return
        history.add(EditorSnapshot(markers, general, selected, tweak))
        if (history.size > 40) history.removeAt(0)
    }

    // a slider moves many times in a row: only the first move (after a pause) is a step of its own
    fun pushPanelStep() {
        val now = System.currentTimeMillis()
        if (now - lastPanelStep > 600L) pushHistory()
        lastPanelStep = now
    }

    fun undo() {
        if (history.isEmpty()) return
        val last = history.removeAt(history.lastIndex)
        markers = last.markers
        general = last.general
        tweak = last.tweak
        selected = if (last.markers.isEmpty()) -1 else last.selected.coerceIn(0, last.markers.lastIndex)
        lastPanelStep = 0L
    }

    // the marker under a finger, or -1
    fun hit(position: Offset, size: IntSize): Int {
        val reach = with(density) { 30.dp.toPx() }
        var best = -1
        var bestDistance = reach * reach
        markers.forEachIndexed { i, m ->
            val dx = m.x * size.width - position.x
            val dy = m.y * size.height - position.y
            val d = dx * dx + dy * dy
            if (d <= bestDistance) {
                bestDistance = d
                best = i
            }
        }
        return best
    }

    // moves a marker to a point of the card and gives it the colour found there
    fun moveTo(index: Int, position: Offset, size: IntSize) {
        val s = sample ?: return
        if (size.width <= 0 || size.height <= 0 || index !in markers.indices) return
        val nx = (position.x / size.width).coerceIn(0f, 1f)
        val ny = (position.y / size.height).coerceIn(0f, 1f)
        val colour = s.colourAt(nx, ny)
        markers = markers.mapIndexed { i, m -> if (i == index) m.copy(x = nx, y = ny, argb = colour) else m }
    }

    // a finger went down on an empty spot: a new marker (while there is room), otherwise the chosen one moves there
    fun addAt(position: Offset, size: IntSize): Int {
        val s = sample ?: return -1
        if (size.width <= 0 || size.height <= 0) return -1
        pushHistory()
        if (markers.isEmpty()) {
            tweak = BackdropTweak()
            showOrigin = false
        }
        if (markers.size >= BackdropEdit.MaxMarkers) {
            val target = if (selected in markers.indices) selected else 0
            moveTo(target, position, size)
            return target
        }
        val nx = (position.x / size.width).coerceIn(0f, 1f)
        val ny = (position.y / size.height).coerceIn(0f, 1f)
        markers = markers + BackdropMarker(nx, ny, s.colourAt(nx, ny))
        return markers.lastIndex
    }

    val shape = RoundedCornerShape(26.dp)

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            // The same backdrop the carousel has: the automatic one, or the colours of the marker while there is one.
            EditorBackdrop(
                item = item,
                coverBottom = { coverBottom },
                forcedTones = if (comparing) null else previewTones,
                modifier = Modifier.fillMaxSize(),
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "Cerrar",
                            tint = Color.White,
                        )
                    }
                    Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                        Text(
                            text = "Editar fondo",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                        )
                        Text(
                            text = anime.title,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(
                        modifier = Modifier.padding(end = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (history.isNotEmpty()) {
                            PillChip("Deshacer", false, Modifier.width(82.dp)) { undo() }
                        }
                        if (hasEdit) {
                            HoldPill("Comparar", comparing, Modifier.width(88.dp)) { comparing = it }
                        }
                    }
                }
                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    val cardWidth = minOf(maxWidth * 0.82f, (maxHeight - 16.dp) * CardRatio)
                    Box(
                        modifier = Modifier
                            .width(cardWidth)
                            .clip(shape)
                            .border(1.dp, Color.White.copy(alpha = 0.14f), shape)
                            .onSizeChanged { cardSize = it }
                            .onGloballyPositioned { coords ->
                                val pos = coords.positionInRoot()
                                cardPos = pos
                                coverBottom = pos.y + coords.size.height
                            },
                    ) {
                        AnimeCover.Book(
                            data = cover,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RectangleShape,
                            onCoverLoaded = { _, result -> loaded = result.result.image },
                            measureRatio = false,
                            ratio = CardRatio,
                        )
                        // Touch the cover to add a marker, touch a marker to choose it, slide to move it.
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onTap = { position ->
                                            val i = hit(position, size)
                                            selected = if (i >= 0) i else addAt(position, size)
                                        },
                                    )
                                }
                                .pointerInput(Unit) {
                                    detectDragGestures(
                                        onDragStart = { position ->
                                            val i = hit(position, size)
                                            if (i >= 0) pushHistory()
                                            val target = if (i >= 0) i else addAt(position, size)
                                            selected = target
                                            dragIndex = target
                                            dragging = target >= 0
                                            touch = position
                                            if (i >= 0) moveTo(target, position, size)
                                        },
                                        onDragEnd = { dragging = false },
                                        onDragCancel = { dragging = false },
                                    ) { change, _ ->
                                        change.consume()
                                        touch = change.position
                                        moveTo(dragIndex, change.position, size)
                                    }
                                },
                        )
                        if (cardSize.width > 0) {
                            markers.forEachIndexed { index, marker ->
                                val chosen = index == selected
                                val markerSize = if (chosen) 38.dp else 30.dp
                                val markerPx = with(density) { markerSize.toPx() }
                                Box(
                                    modifier = Modifier
                                        .offset {
                                            IntOffset(
                                                (marker.x * cardSize.width - markerPx / 2f).roundToInt(),
                                                (marker.y * cardSize.height - markerPx / 2f).roundToInt(),
                                            )
                                        }
                                        .size(markerSize)
                                        .background(Color.Black.copy(alpha = 0.35f), CircleShape)
                                        .padding(2.dp)
                                        .border(if (chosen) 3.dp else 2.dp, Color.White, CircleShape)
                                        .padding(if (chosen) 5.dp else 4.dp)
                                        .background(Color(marker.argb), CircleShape),
                                )
                            }
                        }
                        // where the automatic colours come from, and the button that shows or hides it
                        val originNow = origin
                        val sampleNow = sample
                        if (originNow != null && sampleNow != null && cardSize.width > 0) {
                            if (showOrigin) {
                                OriginOverlay(originNow, sampleNow, cardSize, Modifier.matchParentSize())
                            }
                            PillChip(
                                label = if (showOrigin) "Ocultar origen" else "Ver origen",
                                selected = showOrigin,
                                modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
                                idle = Color.Black.copy(alpha = 0.55f),
                                textPadding = PaddingValues(horizontal = 12.dp),
                            ) { showOrigin = !showOrigin }
                        }
                        // while adjusting: a bar on the edge of the card shows which part of the backdrop the chosen
                        // marker paints
                        val zoneMarker = if (adjusting) markers.getOrNull(selected) else null
                        if (zoneMarker != null) {
                            val zone = effectiveZone(zoneMarker)
                            val bar = Modifier.background(Color.White.copy(alpha = 0.95f), RoundedCornerShape(2.dp))
                            when (zone) {
                                BackdropPaint.ZoneTop -> Box(
                                    Modifier.align(Alignment.TopCenter).padding(top = 8.dp).width(80.dp).height(4.dp).then(bar),
                                )
                                BackdropPaint.ZoneBottom -> Box(
                                    Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp).width(80.dp).height(4.dp).then(bar),
                                )
                                BackdropPaint.ZoneLeft -> Box(
                                    Modifier.align(Alignment.CenterStart).padding(start = 8.dp).width(4.dp).height(80.dp).then(bar),
                                )
                                BackdropPaint.ZoneRight -> Box(
                                    Modifier.align(Alignment.CenterEnd).padding(end = 8.dp).width(4.dp).height(80.dp).then(bar),
                                )
                                else -> Unit
                            }
                        }
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val chosenMarker = markers.getOrNull(selected)
                    val panelOpen = adjusting && chosenMarker != null
                    val tweakPanelOpen = markers.isEmpty() && tweakOpen
                    val originNow = origin
                    if (!panelOpen) {
                        if (showOrigin && originNow != null && !tweakPanelOpen) {
                            OriginLegend(originNow)
                        } else {
                            Text(
                                text = when {
                                    markers.isEmpty() && tweakNow.active && changed ->
                                        "Este es el fondo automático con tus ajustes. Pulsa Guardar cuando te guste."
                                    markers.isEmpty() && tweakNow.active -> "Este es el fondo guardado para esta portada."
                                    markers.isEmpty() && tweakPanelOpen ->
                                        "Mueve los controles: cambian el fondo automático tal como está. " +
                                            "Mantén pulsado «Comparar» para ver el original."
                                    markers.isEmpty() -> "Toca la portada para poner marcadores en los colores que quieres " +
                                        "(hasta ${BackdropEdit.MaxMarkers}), parte del fondo automático o ajústalo."
                                    changed -> "Toca un marcador para elegirlo; arrástralo para moverlo. " +
                                        "«Ajustar» cambia su color y zona. Mantén pulsado «Comparar» para ver el automático. " +
                                        "Pulsa Guardar cuando te guste."
                                    else -> "Este es el fondo guardado para esta portada."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.8f),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    if (!panelOpen && hasEdit && seasons.isNotEmpty()) {
                        TextButton(
                            onClick = { showCopy = true },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                        ) {
                            Text("Copiar a otras temporadas (${seasons.size})", maxLines = 1)
                        }
                    }
                    if (chosenMarker != null && !chosenMarker.exact && BackdropPaint.chromaOf(chosenMarker.argb) < 0.02f) {
                        Text(
                            text = "Punto casi blanco o gris: el fondo saldrá gris ahí. " +
                                "Para que salga así, activa «Color exacto» en Ajustar.",
                            modifier = Modifier.padding(top = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFFFD27F),
                            textAlign = TextAlign.Center,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    if (markers.isEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val sampleNow = sample
                                    if (sampleNow != null) {
                                        scope.launch {
                                            // first the spots the automatic backdrop really reads; if that is not known, a guess
                                            var fresh = origin?.let { originMarkers(it, sampleNow) }.orEmpty()
                                            if (fresh.isEmpty()) {
                                                val lights = autoTones?.lights
                                                if (lights != null) {
                                                    val points = withContext(Dispatchers.Default) {
                                                        BackdropPaint.suggest(
                                                            sampleNow.pixels,
                                                            sampleNow.w,
                                                            sampleNow.h,
                                                            sampleNow.visW,
                                                            sampleNow.visH,
                                                            lights.take(3).map { it.toArgb() }.toIntArray(),
                                                            3,
                                                        )
                                                    }
                                                    fresh = List(points.size / 2) { n ->
                                                        BackdropMarker(
                                                            x = points[n * 2],
                                                            y = points[n * 2 + 1],
                                                            argb = sampleNow.colourAt(points[n * 2], points[n * 2 + 1]),
                                                        )
                                                    }
                                                }
                                            }
                                            if (fresh.isNotEmpty()) {
                                                pushHistory()
                                                tweak = BackdropTweak()
                                                tweakOpen = false
                                                showOrigin = false
                                                markers = fresh
                                                selected = 0
                                            }
                                        }
                                    }
                                },
                                enabled = sample != null && (origin != null || autoTones != null),
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = Color.White,
                                    disabledContentColor = Color.White.copy(alpha = 0.35f),
                                ),
                            ) {
                                Text(
                                    text = "Partir del automático",
                                    maxLines = 2,
                                    textAlign = TextAlign.Center,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                            OutlinedButton(
                                onClick = {
                                    tweakOpen = !tweakOpen
                                    if (tweakOpen) showOrigin = false
                                },
                                enabled = autoTones != null,
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = if (tweakOpen) 0.9f else 0.5f)),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (tweakOpen) Color.White.copy(alpha = 0.18f) else Color.Transparent,
                                    contentColor = Color.White,
                                    disabledContentColor = Color.White.copy(alpha = 0.35f),
                                ),
                            ) {
                                Text(
                                    text = "Ajustar el automático",
                                    maxLines = 2,
                                    textAlign = TextAlign.Center,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                        }
                        if (tweakPanelOpen) {
                            Spacer(Modifier.height(4.dp))
                            TweakPanel(
                                tweak = tweak,
                                onChange = {
                                    pushPanelStep()
                                    tweak = it
                                },
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            markers.forEachIndexed { index, marker ->
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(Color(marker.argb))
                                        .border(if (index == selected) 3.dp else 1.dp, Color.White, CircleShape)
                                        .clickable { selected = index },
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            TextButton(
                                onClick = { adjusting = !adjusting },
                                enabled = chosenMarker != null,
                                contentPadding = PaddingValues(horizontal = 10.dp),
                                colors = ButtonDefaults.textButtonColors(
                                    containerColor = if (panelOpen) Color.White.copy(alpha = 0.18f) else Color.Transparent,
                                    contentColor = Color.White,
                                    disabledContentColor = Color.White.copy(alpha = 0.35f),
                                ),
                            ) {
                                Text("Ajustar", maxLines = 1)
                            }
                            TextButton(
                                onClick = {
                                    if (selected in markers.indices) {
                                        pushHistory()
                                        markers = markers.filterIndexed { i, _ -> i != selected }
                                        selected = markers.lastIndex
                                    }
                                },
                                enabled = selected in markers.indices,
                                contentPadding = PaddingValues(horizontal = 10.dp),
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = Color.White,
                                    disabledContentColor = Color.White.copy(alpha = 0.35f),
                                ),
                            ) {
                                Text("Quitar", maxLines = 1)
                            }
                        }
                        if (panelOpen && chosenMarker != null) {
                            Spacer(Modifier.height(4.dp))
                            AdjustPanel(
                                marker = chosenMarker,
                                general = general,
                                onChange = { changedMarker ->
                                    pushPanelStep()
                                    markers = markers.mapIndexed { i, m -> if (i == selected) changedMarker else m }
                                },
                                onGeneral = {
                                    pushPanelStep()
                                    general = it
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                BackdropOverrides.clear(anime.id)
                                saved = null
                                markers = emptyList()
                                selected = -1
                                general = 1f
                                tweak = BackdropTweak()
                                tweakOpen = false
                                history.clear()
                            },
                            enabled = hasEdit || saved != null,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color.White,
                                disabledContentColor = Color.White.copy(alpha = 0.35f),
                            ),
                        ) {
                            Text(
                                text = "Volver a automático",
                                maxLines = 2,
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                        Button(
                            onClick = {
                                if (hasEdit) {
                                    val edit = BackdropEdit(markers, generalNow, tweakNow)
                                    BackdropOverrides.save(anime.id, edit)
                                    saved = edit
                                    onClose()
                                }
                            },
                            enabled = changed,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.White,
                                contentColor = Color.Black,
                                disabledContainerColor = Color.White.copy(alpha = 0.25f),
                                disabledContentColor = Color.White.copy(alpha = 0.5f),
                            ),
                        ) {
                            Text("Guardar", maxLines = 1)
                        }
                    }
                }
            }
            if (showCopy && hasEdit) {
                CopySeasonsDialog(
                    seasons = seasons,
                    onDismiss = { showCopy = false },
                    onConfirm = { ids ->
                        val edit = BackdropEdit(markers, generalNow, tweakNow)
                        BackdropOverrides.saveAll(ids + anime.id, edit)
                        saved = edit
                        showCopy = false
                        onClose()
                    },
                )
            }
            // The magnifier: while the finger is down it shows the spot under it, close up.
            val s = sample
            val e = markers.getOrNull(dragIndex)
            if (dragging && s != null && e != null) {
                val diameter = 104.dp
                val diameterPx = with(density) { diameter.toPx() }
                val gapPx = with(density) { 44.dp.toPx() }
                val marginPx = with(density) { 8.dp.toPx() }
                val fingerX = cardPos.x + touch.x
                val fingerY = cardPos.y + touch.y
                var left = fingerX - diameterPx / 2f
                var top = fingerY - diameterPx - gapPx
                if (top < with(density) { 72.dp.toPx() }) top = fingerY + gapPx
                left = left.coerceIn(marginPx, maxOf(marginPx, screenWidthPx - diameterPx - marginPx))
                Loupe(
                    sample = s,
                    nx = e.x,
                    ny = e.y,
                    cardWidthPx = cardSize.width.toFloat().coerceAtLeast(1f),
                    modifier = Modifier
                        .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                        .size(diameter),
                )
            }
        }
    }
}

@Composable
private fun Loupe(
    sample: CoverSample,
    nx: Float,
    ny: Float,
    cardWidthPx: Float,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .clip(CircleShape)
            .border(2.dp, Color.White, CircleShape),
    ) {
        val zoom = 4f
        val side = (size.width / cardWidthPx * sample.visW * sample.w / zoom).roundToInt()
            .coerceIn(4, minOf(sample.w, sample.h))
        val cx = sample.imageX(nx)
        val cy = sample.imageY(ny)
        val sx = (cx - side / 2f).roundToInt().coerceIn(0, sample.w - side)
        val sy = (cy - side / 2f).roundToInt().coerceIn(0, sample.h - side)
        drawImage(
            image = sample.image,
            srcOffset = IntOffset(sx, sy),
            srcSize = IntSize(side, side),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            filterQuality = FilterQuality.None,
        )
        val centre = Offset((cx - sx) / side * size.width, (cy - sy) / side * size.height)
        drawCircle(
            color = Color.Black.copy(alpha = 0.55f),
            radius = 11.dp.toPx(),
            center = centre,
            style = Stroke(width = 1.dp.toPx()),
        )
        drawCircle(
            color = Color.White,
            radius = 10.dp.toPx(),
            center = centre,
            style = Stroke(width = 2.dp.toPx()),
        )
    }
}

/**
 * The hand adjustments of the chosen marker, in two tabs. "Color": four sliders (the middle is "as it is") and the
 * exact switch. "Zona": which part of the backdrop the marker paints and the overall strength of the colour. Every
 * change goes out as a new marker through [onChange] (or [onGeneral]); the preview behind the window follows the finger.
 */
@Composable
private fun AdjustPanel(
    marker: BackdropMarker,
    general: Float,
    onChange: (BackdropMarker) -> Unit,
    onGeneral: (Float) -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PillChip("Color", tab == 0, Modifier.width(76.dp)) { tab = 0 }
            Spacer(Modifier.width(6.dp))
            PillChip("Zona", tab == 1, Modifier.width(76.dp)) { tab = 1 }
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = { onChange(marker.withoutAdjustments()) },
                enabled = marker.adjusted,
                contentPadding = PaddingValues(horizontal = 10.dp),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = Color.White,
                    disabledContentColor = Color.White.copy(alpha = 0.35f),
                ),
            ) {
                Text("Restablecer", maxLines = 1)
            }
        }
        if (tab == 0) {
            AdjustRow("Brillo", marker.light, signed((marker.light * 100f).roundToInt())) {
                onChange(marker.copy(light = it))
            }
            AdjustRow("Color", marker.color, signed((marker.color * 100f).roundToInt())) {
                onChange(marker.copy(color = it))
            }
            AdjustRow("Tono", marker.tone, signed((marker.tone * BackdropPaint.toneDegrees(1f)).roundToInt()) + "°") {
                onChange(marker.copy(tone = it))
            }
            AdjustRow("Fuerza", marker.force, signed((marker.force * 100f).roundToInt())) {
                onChange(marker.copy(force = it))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Color exacto",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        maxLines = 1,
                    )
                    Text(
                        text = "El blanco sale blanco",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.6f),
                        maxLines = 1,
                    )
                }
                Switch(
                    checked = marker.exact,
                    onCheckedChange = { onChange(marker.copy(exact = it)) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.Black,
                        checkedTrackColor = Color.White,
                        checkedBorderColor = Color.White,
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = Color.White.copy(alpha = 0.25f),
                        uncheckedBorderColor = Color.White.copy(alpha = 0.5f),
                    ),
                )
            }
        } else {
            val now = effectiveZone(marker)
            Text(
                text = "Zona de este color: " +
                    if (marker.zone == BackdropPaint.ZoneAuto) "automática (${zoneName(now)})" else zoneName(now),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.85f),
                maxLines = 1,
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for ((zone, label) in ZoneChoices) {
                    PillChip(label, marker.zone == zone, Modifier.weight(1f)) { onChange(marker.copy(zone = zone)) }
                }
            }
            Spacer(Modifier.height(6.dp))
            AdjustRow("General", strengthToSlider(general), "${(general * 100f).roundToInt()}%") {
                onGeneral(strengthFromSlider(it))
            }
            Text(
                text = "General cambia la fuerza del color de todo el fondo.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.6f),
                maxLines = 1,
            )
        }
    }
}

private val ZoneChoices = listOf(
    BackdropPaint.ZoneAuto to "Auto",
    BackdropPaint.ZoneTop to "Arriba",
    BackdropPaint.ZoneBottom to "Abajo",
    BackdropPaint.ZoneLeft to "Izq.",
    BackdropPaint.ZoneRight to "Der.",
)

/** The zone a marker really paints: the one chosen by hand, or the one given by where it sits on the cover. */
private fun effectiveZone(marker: BackdropMarker): Int =
    if (marker.zone == BackdropPaint.ZoneAuto) BackdropPaint.zoneOf(marker.x, marker.y) else marker.zone

private fun zoneName(zone: Int): String = when (zone) {
    BackdropPaint.ZoneTop -> "arriba"
    BackdropPaint.ZoneBottom -> "abajo"
    BackdropPaint.ZoneLeft -> "izquierda"
    else -> "derecha"
}

/** The overall strength (0.2 to 1.6, 1 = as designed) as a slider position from -1 to 1 and back. */
private fun strengthFromSlider(v: Float): Float =
    if (v >= 0f) {
        1f + (BackdropEdit.MaxStrength - 1f) * v
    } else {
        1f + (1f - BackdropEdit.MinStrength) * v
    }

private fun strengthToSlider(strength: Float): Float {
    val v = if (strength >= 1f) {
        (strength - 1f) / (BackdropEdit.MaxStrength - 1f)
    } else {
        (strength - 1f) / (1f - BackdropEdit.MinStrength)
    }
    return v.coerceIn(-1f, 1f)
}

/**
 * Where the automatic backdrop takes its colours from, as the colour engine reports it for this cover: the weight each
 * spot of the artwork has (what counts and what is ignored), and for each of the four lights the part of the cover it
 * reads and the spot its colour sits at. A light with no part found uses the main colour of the cover instead.
 */
private class OriginView(
    /** Black with an alpha: the opaque parts are the ones the engine ignores. Covers [area]. */
    val mask: ImageBitmap,
    /** Left, top, right, bottom of the part the engine looked at, as fractions of the whole cover. */
    val area: FloatArray,
    /** Per light: the part of the cover it reads (fractions of the whole cover), or null. */
    val rects: Array<FloatArray?>,
    /** Per light: where its colour sits (fractions of the whole cover), or null. */
    val at: Array<FloatArray?>,
    /** Per light: the colour the backdrop gets. */
    val colours: IntArray,
)

/** The lights, in the order the engine gives them (the same spots as on the Home). */
private val OriginNames = arrayOf("Arriba izq.", "Derecha", "Arriba der.", "Izquierda")

private fun opaque(argb: Int): Color = Color(argb or (0xFF shl 24))

/** A smaller copy of a picture (each new pixel is the average of the old ones it covers). */
private fun shrinkPixels(src: IntArray, w: Int, h: Int, nw: Int, nh: Int): IntArray {
    val out = IntArray(nw * nh)
    for (y in 0 until nh) {
        val y0 = y * h / nh
        val y1 = maxOf(y0 + 1, (y + 1) * h / nh)
        for (x in 0 until nw) {
            val x0 = x * w / nw
            val x1 = maxOf(x0 + 1, (x + 1) * w / nw)
            var r = 0
            var g = 0
            var b = 0
            var n = 0
            for (yy in y0 until y1) {
                for (xx in x0 until x1) {
                    val p = src[yy * w + xx]
                    r += (p shr 16) and 255
                    g += (p shr 8) and 255
                    b += p and 255
                    n++
                }
            }
            out[y * nw + x] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
        }
    }
    return out
}

/** Runs the colour engine on the cover the way the Home does and keeps what it says it looked at. */
private fun buildOrigin(s: CoverSample): OriginView? {
    val scale = 160f / maxOf(s.w, s.h).coerceAtLeast(1)
    val nw: Int
    val nh: Int
    val px: IntArray
    if (scale < 1f) {
        nw = (s.w * scale).toInt().coerceAtLeast(1)
        nh = (s.h * scale).toInt().coerceAtLeast(1)
        px = shrinkPixels(s.pixels, s.w, s.h, nw, nh)
    } else {
        nw = s.w
        nh = s.h
        px = s.pixels
    }
    val explain = EngineExplain()
    val result = CoverColorEngine.analyze(px, nw, nh, explain) ?: return null
    val mw = explain.keptW
    val mh = explain.keptH
    if (mw <= 0 || mh <= 0 || explain.kept.size < mw * mh || result.lights.size < 4) return null
    val alphas = IntArray(mw * mh)
    for (i in alphas.indices) {
        val t = ((explain.kept[i] - 0.02f) / 0.23f).coerceIn(0f, 1f)
        val counts = t * t * (3f - 2f * t)
        val alpha = (0.64f * (1f - counts) * 255f).roundToInt().coerceIn(0, 255)
        alphas[i] = alpha shl 24
    }
    val mask = Bitmap.createBitmap(alphas, mw, mh, Bitmap.Config.ARGB_8888)
    return OriginView(mask.asImageBitmap(), explain.area, explain.lightRect, explain.lightAt, result.lights)
}

/**
 * Markers on the spots the automatic backdrop really reads (one per light, without repeating a colour), so that it can
 * be taken from there and changed. A spot with almost no colour moves to the nearest one that has some.
 */
private fun originMarkers(origin: OriginView, s: CoverSample): List<BackdropMarker> {
    val order = intArrayOf(3, 1, 0, 2)
    val zones = intArrayOf(
        BackdropPaint.ZoneTop,
        BackdropPaint.ZoneRight,
        BackdropPaint.ZoneTop,
        BackdropPaint.ZoneLeft,
    )
    val radius = 0.03f * s.w
    val out = ArrayList<BackdropMarker>()
    for (light in order) {
        val at = origin.at.getOrNull(light) ?: continue
        val rect = origin.rects.getOrNull(light) ?: continue
        var spotX = at[0]
        var spotY = at[1]
        var colour = BackdropPaint.sample(s.pixels, s.w, s.h, spotX * s.w, spotY * s.h, radius)
        if (BackdropPaint.chromaOf(colour) < 0.05f) {
            var bestDistance = Float.MAX_VALUE
            for (j in 0 until 9) {
                for (i in 0 until 9) {
                    val fx = rect[0] + (rect[2] - rect[0]) * (i + 0.5f) / 9f
                    val fy = rect[1] + (rect[3] - rect[1]) * (j + 0.5f) / 9f
                    val c = BackdropPaint.sample(s.pixels, s.w, s.h, fx * s.w, fy * s.h, radius)
                    if (BackdropPaint.chromaOf(c) < 0.05f) continue
                    val d = (fx - at[0]) * (fx - at[0]) + (fy - at[1]) * (fy - at[1])
                    if (d < bestDistance) {
                        bestDistance = d
                        spotX = fx
                        spotY = fy
                        colour = c
                    }
                }
            }
        }
        val zone = zones[light]
        if (out.any { it.zone == zone && BackdropPaint.colourDistance(it.argb, colour) < 0.05f }) continue
        out.add(
            BackdropMarker(
                x = s.cardX(spotX).coerceIn(0.05f, 0.95f),
                y = s.cardY(spotY).coerceIn(0.05f, 0.95f),
                argb = colour,
                zone = zone,
            ),
        )
    }
    return out
}

/**
 * Drawn over the cover: what the automatic backdrop ignores is darkened (outside the part it looks at, and inside it
 * white, black and the colourless), each light has a frame around the part of the cover it reads, and a number on the
 * spot its colour is taken from.
 */
@Composable
private fun OriginOverlay(
    origin: OriginView,
    sample: CoverSample,
    cardSize: IntSize,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    Box(modifier = modifier) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            val dim = Color.Black.copy(alpha = 0.64f)
            val ax0 = sample.cardX(origin.area[0]) * w
            val ay0 = sample.cardY(origin.area[1]) * h
            val ax1 = sample.cardX(origin.area[2]) * w
            val ay1 = sample.cardY(origin.area[3]) * h
            val top = ay0.coerceIn(0f, h)
            val bottom = ay1.coerceIn(0f, h)
            val left = ax0.coerceIn(0f, w)
            val right = ax1.coerceIn(0f, w)
            if (top > 0f) drawRect(dim, Offset(0f, 0f), Size(w, top))
            if (bottom < h) drawRect(dim, Offset(0f, bottom), Size(w, h - bottom))
            if (bottom > top && left > 0f) drawRect(dim, Offset(0f, top), Size(left, bottom - top))
            if (bottom > top && right < w) drawRect(dim, Offset(right, top), Size(w - right, bottom - top))
            val dw = (ax1 - ax0).roundToInt()
            val dh = (ay1 - ay0).roundToInt()
            if (dw > 0 && dh > 0) {
                drawImage(
                    image = origin.mask,
                    dstOffset = IntOffset(ax0.roundToInt(), ay0.roundToInt()),
                    dstSize = IntSize(dw, dh),
                    filterQuality = FilterQuality.Low,
                )
            }
            val corner = CornerRadius(8.dp.toPx())
            for (i in 0 until 4) {
                val r = origin.rects.getOrNull(i) ?: continue
                val x0 = sample.cardX(r[0]) * w
                val y0 = sample.cardY(r[1]) * h
                val x1 = sample.cardX(r[2]) * w
                val y1 = sample.cardY(r[3]) * h
                if (x1 <= x0 || y1 <= y0) continue
                drawRoundRect(
                    color = Color.White,
                    topLeft = Offset(x0, y0),
                    size = Size(x1 - x0, y1 - y0),
                    cornerRadius = corner,
                    style = Stroke(width = 3.dp.toPx()),
                )
                drawRoundRect(
                    color = opaque(origin.colours[i]),
                    topLeft = Offset(x0, y0),
                    size = Size(x1 - x0, y1 - y0),
                    cornerRadius = corner,
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }
        }
        val badge = 22.dp
        val badgePx = with(density) { badge.toPx() }
        for (i in 0 until 4) {
            val at = origin.at.getOrNull(i) ?: continue
            val colour = origin.colours[i]
            val luma = (
                0.299f * ((colour shr 16) and 255) +
                    0.587f * ((colour shr 8) and 255) +
                    0.114f * (colour and 255)
                ) / 255f
            val cx = sample.cardX(at[0]).coerceIn(0.05f, 0.95f) * cardSize.width
            val cy = sample.cardY(at[1]).coerceIn(0.05f, 0.95f) * cardSize.height
            Box(
                modifier = Modifier
                    .offset { IntOffset((cx - badgePx / 2f).roundToInt(), (cy - badgePx / 2f).roundToInt()) }
                    .size(badge)
                    .background(opaque(colour), CircleShape)
                    .border(2.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "${i + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (luma > 0.6f) Color.Black else Color.White,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Under the cover: which light is which, and what the darkened parts mean. */
@Composable
private fun OriginLegend(origin: OriginView, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (row in 0 until 2) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (col in 0 until 2) {
                    val i = row * 2 + col
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .background(opaque(origin.colours[i]), CircleShape)
                                .border(1.dp, Color.White.copy(alpha = 0.8f), CircleShape),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "${i + 1} · ${OriginNames[i]}" + if (origin.rects[i] == null) " (general)" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.9f),
                            maxLines = 2,
                        )
                    }
                }
            }
        }
        Text(
            text = "Cada luz del fondo toma el color de su recuadro. Lo oscurecido no cuenta: " +
                "blanco, negro o sin color." +
                if (origin.rects.any { it == null }) " «General»: no halló color en ese borde y usa el principal." else "",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
        )
    }
}

/** Changes on top of the automatic backdrop itself (no markers): Brillo, Color and Tono. */
@Composable
private fun TweakPanel(
    tweak: BackdropTweak,
    onChange: (BackdropTweak) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Sobre el automático",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.85f),
                maxLines = 1,
            )
            TextButton(
                onClick = { onChange(BackdropTweak()) },
                enabled = tweak.active,
                contentPadding = PaddingValues(horizontal = 10.dp),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = Color.White,
                    disabledContentColor = Color.White.copy(alpha = 0.35f),
                ),
            ) {
                Text("Restablecer", maxLines = 1)
            }
        }
        AdjustRow("Brillo", tweak.light, signed((tweak.light * 100f).roundToInt())) {
            onChange(tweak.copy(light = it))
        }
        AdjustRow("Color", tweak.color, signed((tweak.color * 100f).roundToInt())) {
            onChange(tweak.copy(color = it))
        }
        AdjustRow("Tono", tweak.tone, signed((tweak.tone * BackdropPaint.toneDegrees(1f)).roundToInt()) + "°") {
            onChange(tweak.copy(tone = it))
        }
    }
}

/** The state of the editor at one moment, for "Deshacer". */
private class EditorSnapshot(
    val markers: List<BackdropMarker>,
    val general: Float,
    val selected: Int,
    val tweak: BackdropTweak,
)

/** Which other seasons get this backdrop (all ticked at first), then it is saved for them and for this cover. */
@Composable
private fun CopySeasonsDialog(
    seasons: List<Pair<Long, String>>,
    onDismiss: () -> Unit,
    onConfirm: (List<Long>) -> Unit,
) {
    val picked = remember { mutableStateMapOf<Long, Boolean>().also { m -> seasons.forEach { m[it.first] = true } } }
    val chosen = seasons.filter { picked[it.first] == true }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Copiar fondo a otras temporadas") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = "Este fondo se guardará también en las marcadas, y después puedes cambiarlo en cada una.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                for ((id, title) in seasons) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { picked[id] = picked[id] != true }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = picked[id] == true, onCheckedChange = { picked[id] = it })
                        Text(
                            text = title,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(chosen.map { it.first }) }, enabled = chosen.isNotEmpty()) {
                Text("Guardar y copiar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}

/** A pill that does something only while it is held down (the finger up gives the state back). */
@Composable
private fun HoldPill(
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onHold: (Boolean) -> Unit,
) {
    val latest by rememberUpdatedState(onHold)
    Box(
        modifier = modifier
            .height(30.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(if (active) Color.White else Color.White.copy(alpha = 0.14f))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        latest(true)
                        tryAwaitRelease()
                        latest(false)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (active) Color.Black else Color.White,
            maxLines = 1,
        )
    }
}

@Composable
private fun PillChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    idle: Color = Color.White.copy(alpha = 0.14f),
    textPadding: PaddingValues = PaddingValues(0.dp),
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(30.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(if (selected) Color.White else idle)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(textPadding),
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) Color.Black else Color.White,
            maxLines = 1,
        )
    }
}

private fun signed(n: Int): String = if (n > 0) "+$n" else "$n"

@Composable
private fun AdjustRow(
    label: String,
    value: Float,
    display: String,
    onChange: (Float) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.width(56.dp),
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.85f),
            maxLines = 1,
        )
        AdjustSlider(value = value, onChange = onChange, modifier = Modifier.weight(1f))
        Text(
            text = display,
            modifier = Modifier.width(48.dp),
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.85f),
            textAlign = TextAlign.End,
            maxLines = 1,
        )
    }
}

/**
 * A slider from -1 to 1 with a mark in the middle (the untouched value). It snaps to the middle when it is close, so
 * going back to "as it is" is easy. Drawn by hand so that it is thin and the rows stay compact.
 */
@Composable
private fun AdjustSlider(
    value: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var widthPx by remember { mutableFloatStateOf(1f) }
    val latest by rememberUpdatedState(onChange)
    val padPx = with(LocalDensity.current) { 12.dp.toPx() }

    fun pick(x: Float) {
        val span = (widthPx - 2f * padPx).coerceAtLeast(1f)
        val v = ((x - padPx) / span * 2f - 1f).coerceIn(-1f, 1f)
        latest(if (abs(v) < 0.04f) 0f else v)
    }

    Box(
        modifier = modifier
            .height(32.dp)
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) {
                detectTapGestures { position -> pick(position.x) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(onDragStart = { position -> pick(position.x) }) { change, _ ->
                    change.consume()
                    pick(change.position.x)
                }
            },
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val pad = 12.dp.toPx()
            val span = size.width - 2f * pad
            val cy = size.height / 2f
            val track = 4.dp.toPx()
            val centreX = pad + span / 2f
            val thumbX = pad + (value.coerceIn(-1f, 1f) + 1f) / 2f * span
            drawRoundRect(
                color = Color.White.copy(alpha = 0.28f),
                topLeft = Offset(pad, cy - track / 2f),
                size = Size(span, track),
                cornerRadius = CornerRadius(track / 2f),
            )
            val from = minOf(centreX, thumbX)
            val to = maxOf(centreX, thumbX)
            if (to - from > 0.5f) {
                drawRoundRect(
                    color = Color.White,
                    topLeft = Offset(from, cy - track / 2f),
                    size = Size(to - from, track),
                    cornerRadius = CornerRadius(track / 2f),
                )
            }
            drawRoundRect(
                color = Color.White.copy(alpha = 0.7f),
                topLeft = Offset(centreX - 1.dp.toPx(), cy - 7.dp.toPx()),
                size = Size(2.dp.toPx(), 14.dp.toPx()),
                cornerRadius = CornerRadius(1.dp.toPx()),
            )
            drawCircle(color = Color.Black.copy(alpha = 0.30f), radius = 11.dp.toPx(), center = Offset(thumbX, cy))
            drawCircle(color = Color.White, radius = 9.dp.toPx(), center = Offset(thumbX, cy))
        }
    }
}
// ANZ <--
