package eu.kanade.presentation.library.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.kanade.presentation.anime.components.AnimeCover
import eu.kanade.tachiyomi.ui.library.LibraryItem
import kotlinx.collections.immutable.ImmutableList
import tachiyomi.domain.anime.model.asAnimeCover

// ANZ --> "Editar fondo": per-cover editor of the carousel backdrop (step 1: the window itself)

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

@Composable
private fun CoverBackdropEditor(
    item: LibraryItem,
    onClose: () -> Unit,
) {
    val anime = item.libraryAnime.anime
    val cover = remember(anime.id, anime.thumbnailUrl, anime.coverLastModified) { anime.asAnimeCover() }
    var coverBottom by remember { mutableFloatStateOf(0f) }
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
            // The same backdrop the carousel has, painted with this cover's colours.
            EditorBackdrop(
                item = item,
                coverBottom = { coverBottom },
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
                            .onGloballyPositioned { coords ->
                                coverBottom = coords.positionInRoot().y + coords.size.height
                            },
                    ) {
                        AnimeCover.Book(
                            data = cover,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RectangleShape,
                            measureRatio = false,
                            ratio = CardRatio,
                        )
                    }
                }
                Text(
                    text = "Así se ve ahora el fondo de esta portada (automático). " +
                        "En los próximos pasos podrás poner marcadores para elegir los colores.",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 28.dp, vertical = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
// ANZ <--
