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
    // the steps that can be undone (newest last), and when the last slider step was recorded
    val history = remember { mutableStateListOf<EditorSnapshot>() }
    var lastPanelStep by remember { mutableLongStateOf(0L) }
    var dragIndex by remember { mutableIntStateOf(-1) }
    var dragging by remember { mutableStateOf(false) }
    var touch by remember { mutableStateOf(Offset.Zero) }
    val scope = rememberCoroutineScope()
    val autoTones = CoverTonesCache.get(anime.id, anime.coverLastModified)

    val previewTones = remember(markers, general) {
        if (markers.isEmpty()) null else BackdropOverrides.tonesOf(BackdropEdit(markers, general))
    }
    val changed = markers.isNotEmpty() && (markers != saved?.markers || general != saved?.strength)

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
        if (last != null && last.markers == markers && last.general == general) return
        history.add(EditorSnapshot(markers, general, selected))
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
                        if (markers.isNotEmpty()) {
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
                    if (!panelOpen) {
                        Text(
                            text = when {
                                markers.isEmpty() -> "Toca la portada para poner marcadores en los colores que quieres " +
                                    "(hasta ${BackdropEdit.MaxMarkers}), o parte del fondo automático."
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
                    if (!panelOpen && markers.isNotEmpty() && seasons.isNotEmpty()) {
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
                        OutlinedButton(
                            onClick = {
                                val s = sample
                                val lights = autoTones?.lights
                                if (s != null && lights != null) {
                                    scope.launch {
                                        pushHistory()
                                        val points = withContext(Dispatchers.Default) {
                                            BackdropPaint.suggest(
                                                s.pixels,
                                                s.w,
                                                s.h,
                                                s.visW,
                                                s.visH,
                                                lights.take(3).map { it.toArgb() }.toIntArray(),
                                                3,
                                            )
                                        }
                                        markers = List(points.size / 2) { n ->
                                            BackdropMarker(
                                                x = points[n * 2],
                                                y = points[n * 2 + 1],
                                                argb = s.colourAt(points[n * 2], points[n * 2 + 1]),
                                            )
                                        }
                                        selected = if (markers.isEmpty()) -1 else 0
                                    }
                                }
                            },
                            enabled = sample != null && autoTones != null,
                            modifier = Modifier.fillMaxWidth(),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color.White,
                                disabledContentColor = Color.White.copy(alpha = 0.35f),
                            ),
                        ) {
                            Text("Partir del fondo automático", maxLines = 1)
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
                                history.clear()
                            },
                            enabled = markers.isNotEmpty() || saved != null,
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
                                if (markers.isNotEmpty()) {
                                    val edit = BackdropEdit(markers, general)
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
            if (showCopy && markers.isNotEmpty()) {
                CopySeasonsDialog(
                    seasons = seasons,
                    onDismiss = { showCopy = false },
                    onConfirm = { ids ->
                        val edit = BackdropEdit(markers, general)
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

/** The state of the editor at one moment, for "Deshacer". */
private class EditorSnapshot(
    val markers: List<BackdropMarker>,
    val general: Float,
    val selected: Int,
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
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(30.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(if (selected) Color.White else Color.White.copy(alpha = 0.14f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
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
