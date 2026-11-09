package eu.kanade.presentation.library.components

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
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
import kotlinx.coroutines.withContext
import tachiyomi.domain.anime.model.asAnimeCover
import kotlin.math.roundToInt

// ANZ --> "Editar fondo": per-cover editor of the carousel backdrop.
// Step 2: one marker. Touch the cover to put it, slide to move it (a magnifier shows the exact colour), Save keeps it
// for this cover, "Volver a automático" removes it.

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
            CoverBackdropEditor(item = frozen, onClose = { BackdropEditor.hide() })
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

    // what is saved for this anime, and the marker as it is right now
    var saved by remember { mutableStateOf(BackdropOverrides.editOf(anime.id)) }
    var edit by remember { mutableStateOf(saved) }
    var dragging by remember { mutableStateOf(false) }
    var touch by remember { mutableStateOf(Offset.Zero) }

    val previewTones = remember(edit) { edit?.let { BackdropOverrides.tonesOf(it) } }

    fun place(position: Offset, size: IntSize) {
        val s = sample ?: return
        if (size.width <= 0 || size.height <= 0) return
        val nx = (position.x / size.width).coerceIn(0f, 1f)
        val ny = (position.y / size.height).coerceIn(0f, 1f)
        edit = BackdropEdit(nx, ny, s.colourAt(nx, ny))
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
                forcedTones = previewTones,
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
                        // Touch anywhere to put the marker there; slide to move it.
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .pointerInput(Unit) {
                                    detectTapGestures(onTap = { position -> place(position, size) })
                                }
                                .pointerInput(Unit) {
                                    detectDragGestures(
                                        onDragStart = { position ->
                                            dragging = true
                                            touch = position
                                            place(position, size)
                                        },
                                        onDragEnd = { dragging = false },
                                        onDragCancel = { dragging = false },
                                    ) { change, _ ->
                                        change.consume()
                                        touch = change.position
                                        place(change.position, size)
                                    }
                                },
                        )
                        val marker = edit
                        if (marker != null && cardSize.width > 0) {
                            val markerSize = 34.dp
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
                                    .border(3.dp, Color.White, CircleShape)
                                    .padding(5.dp)
                                    .background(Color(marker.argb), CircleShape),
                            )
                        }
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = when {
                            edit == null -> "Toca la portada para poner un marcador en el color que quieres " +
                                "para el fondo. Después desliza el dedo para afinarlo."
                            edit !== saved -> "Mueve el marcador hasta el color que te guste y pulsa Guardar."
                            else -> "Este es el fondo guardado para esta portada."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                BackdropOverrides.clear(anime.id)
                                saved = null
                                edit = null
                            },
                            enabled = edit != null || saved != null,
                            modifier = Modifier.weight(1f),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color.White,
                                disabledContentColor = Color.White.copy(alpha = 0.35f),
                            ),
                        ) {
                            Text("Volver a automático", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Button(
                            onClick = {
                                val current = edit
                                if (current != null) {
                                    BackdropOverrides.save(anime.id, current)
                                    saved = current
                                    onClose()
                                }
                            },
                            enabled = edit != null && edit !== saved,
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
            // The magnifier: while the finger is down it shows the spot under it, close up.
            val s = sample
            val e = edit
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
// ANZ <--
