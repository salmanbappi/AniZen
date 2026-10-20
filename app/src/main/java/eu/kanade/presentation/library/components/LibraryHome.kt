package eu.kanade.presentation.library.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Shadow
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.State
import android.content.res.Configuration
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.anime.components.AnimeCover
import eu.kanade.tachiyomi.ui.library.LibraryDisplayItem
import eu.kanade.tachiyomi.ui.library.LibraryItem
import eu.kanade.tachiyomi.util.system.CoverColorObserver
import kotlin.math.absoluteValue
import kotlin.random.Random
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.delay
import tachiyomi.domain.anime.model.asAnimeCover
import tachiyomi.domain.library.model.LibraryAnime
import tachiyomi.domain.library.model.LibraryDisplayMode

// ANZ -->
/*
 * "Home" style for the library (v2): an immersive poster hero with the info laid over the artwork
 * and tinted with the cover's own colour, a "continue watching" row, a ranked top, genre tiles,
 * Netflix/Crunchyroll style shelves and a modern poster grid.
 *
 * It only replaces the classic grids; search results and the List display mode still use the
 * classic layouts (see [shouldUseLibraryHome]).
 */

/** Whether the library page should render the new home layout instead of the classic grid. */
internal fun shouldUseLibraryHome(searchQuery: String?, displayMode: LibraryDisplayMode): Boolean {
    // Search results use the same modern cards (see the search section of [LibraryHome]);
    // only the List display mode keeps the classic layout.
    return displayMode != LibraryDisplayMode.List
}

private enum class LibraryHomeTab(val label: String) {
    Home("Inicio"),
    All("Todos"),
    Watching("En curso"),
    NotStarted("Sin empezar"),
    Finished("Completados"),
}

private val EdgePadding = 16.dp
private val CardGap = 12.dp
private val CardWidth = 120.dp
private val ContinueWidth = 296.dp
private val ContinuePoster = 64.dp
private val ContinueRadius = 20.dp
private val PosterRadius = 14.dp
private val TileRadius = 18.dp
private val ShelfSpacing = 28.dp
private val BottomSpace = 28.dp
private val HeroRadius = 26.dp
private val HeroSpacing = 10.dp
private val HeroPeek = 22.dp
private val HeroMaxWidth = 380.dp
private val HeroMinHeight = 300.dp
private val GenreTileWidth = 156.dp
private val GenreTileHeight = 92.dp
private val RankZone = 52.dp
private val RankFont = 112.sp
private val ToolbarFadeDistance = 240.dp

/** Changes every time the app process starts, so the hero carousel is different on every launch. */
private val SessionSeed: Long = System.nanoTime()

private const val HeroHeightRatio = 1.45f
private const val HeroScreenFraction = 0.6f
private const val HeroAutoAdvanceMs = 5000L
private const val AmbientStrength = 0.55f
private const val AmbientFallback = 0.14f
private const val SelectedScale = 0.95f
private const val ColumnsPortrait = 3
private const val ColumnsLandscape = 6
private const val HeroCount = 5
private const val MaxShelfItems = 20
private const val MinTopItems = 3
private const val MinGenreShelfItems = 3
private const val MinGenreTileItems = 2
private const val MaxGenreShelves = 2
private const val MaxGenreTiles = 12
private const val RankStroke = 6f

private val GenreColors = listOf(
    Color(0xFF7C3AED),
    Color(0xFFDB2777),
    Color(0xFFEA580C),
    Color(0xFF0D9488),
    Color(0xFF2563EB),
    Color(0xFF65A30D),
    Color(0xFFC026D3),
    Color(0xFFDC2626),
)

/**
 * Colour of the hero's ambient tint, shared with the toolbar so both blend into one surface.
 * [fade] goes from 0 (top of the screen) to 1 (scrolled away).
 */
internal object LibraryHomeAmbient {
    var color by mutableStateOf<Color?>(null)
    var fade by mutableFloatStateOf(0f)

    /** Toolbar height in px, reported by the toolbar itself. */
    var barHeight by mutableFloatStateOf(0f)

    /** How far the transparent toolbar has slid up: 0 = fully shown, -barHeight = hidden. */
    var barOffset by mutableFloatStateOf(0f)
    private var owner: Any? = null

    /** Follows the finger: scrolling down hides the bar, scrolling up brings it straight back. */
    fun slideBar(delta: Float) {
        barOffset = (barOffset + delta).coerceIn(-barHeight, 0f)
    }

    fun showBar() {
        barOffset = 0f
    }

    fun publish(owner: Any, value: Color) {
        this.owner = owner
        color = value
    }

    fun release(owner: Any) {
        if (this.owner === owner) {
            this.owner = null
            color = null
            fade = 0f
            barOffset = 0f
        }
    }
}

@Immutable
private class HomeActions(
    val onClick: (LibraryAnime) -> Unit,
    val onLongClick: (LibraryAnime) -> Unit,
    val onPlay: ((LibraryAnime) -> Unit)?,
    val onFolderClick: ((LibraryDisplayItem.Folder) -> Unit)?,
    val onFolderLongClick: ((LibraryDisplayItem.Folder) -> Unit)?,
    val onOpenTab: (LibraryHomeTab) -> Unit,
    val onOpenGenre: (String) -> Unit,
    val onShuffle: () -> Unit,
)

@Immutable
private class GenreShelf(val name: String, val entries: ImmutableList<LibraryItem>)

@Immutable
private class GenreTile(val name: String, val count: Int, val cover: LibraryItem)

@Immutable
private class Shelves(
    val heroes: ImmutableList<LibraryItem>,
    val watching: ImmutableList<LibraryItem>,
    val notStarted: ImmutableList<LibraryItem>,
    val recent: ImmutableList<LibraryItem>,
    val top: ImmutableList<LibraryItem>,
    val finished: ImmutableList<LibraryItem>,
    val genres: ImmutableList<GenreShelf>,
    val tiles: ImmutableList<GenreTile>,
)

private sealed interface GridRow {
    class Title(val name: String) : GridRow
    class Cells(val cells: List<LibraryDisplayItem>) : GridRow
}

private fun LibraryAnime.isWatching(): Boolean = hasStarted && unseenCount > 0

private fun LibraryAnime.isFinished(): Boolean = hasStarted && unseenCount <= 0 && totalEpisodes > 0

private fun flattenAnime(items: List<LibraryDisplayItem>): List<LibraryItem> {
    return items.flatMap { item ->
        when (item) {
            is LibraryDisplayItem.Anime -> listOf(item.libraryItem)
            is LibraryDisplayItem.Folder -> item.items
            is LibraryDisplayItem.Header -> emptyList()
        }
    }
}

/** Deterministic hash of (id, seed): the same pair always sorts the same, whatever the list order. */
private fun scramble(id: Long, seed: Long): Long {
    var x = id xor seed
    x = (x xor (x ushr 33)) * -49064778989728563L
    x = (x xor (x ushr 33)) * -4265267296055464877L
    return x xor (x ushr 33)
}

private fun buildShelves(all: List<LibraryItem>, seed: Long): Shelves {
    val watching = all
        .filter { it.libraryAnime.isWatching() }
        .sortedByDescending { it.libraryAnime.lastSeen }
    val notStarted = all.filter { !it.libraryAnime.hasStarted }
    val recent = all.sortedByDescending { it.libraryAnime.anime.dateAdded }
    val top = all
        .filter { it.libraryAnime.seenCount > 0 }
        .sortedByDescending { it.libraryAnime.seenCount }
    val finished = all.filter { it.libraryAnime.isFinished() }

    // Completely random picks from the whole library (new ones on every app launch).
    val heroes = all.sortedBy { scramble(it.libraryAnime.id, seed) }.take(HeroCount)

    val byGenre = LinkedHashMap<String, MutableList<LibraryItem>>()
    val names = HashMap<String, String>()
    all.forEach { item ->
        item.libraryAnime.anime.genre.orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .forEach { genre ->
                val key = genre.lowercase()
                names.putIfAbsent(key, genre.replaceFirstChar { it.uppercase() })
                byGenre.getOrPut(key) { ArrayList() }.add(item)
            }
    }
    val eligible = byGenre.entries
        .filter { it.value.size >= MinGenreTileItems && (it.value.size < all.size || all.size <= MinGenreShelfItems) }
        .sortedByDescending { it.value.size }

    val genres = eligible
        .filter { it.value.size >= MinGenreShelfItems }
        .take(MaxGenreShelves)
        .map { GenreShelf(names[it.key].orEmpty(), it.value.take(MaxShelfItems).toImmutableList()) }

    // Every tile gets a different cover when the library allows it.
    val usedCovers = HashSet<Long>()
    val tiles = eligible
        .take(MaxGenreTiles)
        .map { entry ->
            val pick = entry.value.firstOrNull { usedCovers.add(it.libraryAnime.id) } ?: entry.value.first()
            GenreTile(name = names[entry.key].orEmpty(), count = entry.value.size, cover = pick)
        }

    return Shelves(
        heroes = heroes.toImmutableList(),
        watching = watching.take(MaxShelfItems).toImmutableList(),
        notStarted = notStarted.take(MaxShelfItems).toImmutableList(),
        recent = recent.take(MaxShelfItems).toImmutableList(),
        top = top.take(HeroCount).toImmutableList(),
        finished = finished.take(MaxShelfItems).toImmutableList(),
        genres = genres.toImmutableList(),
        tiles = tiles.toImmutableList(),
    )
}

private fun buildGridRows(
    tab: LibraryHomeTab,
    genre: String?,
    items: List<LibraryDisplayItem>,
    flat: List<LibraryItem>,
    columns: Int,
): List<GridRow> {
    val cells: List<LibraryDisplayItem> = if (genre != null) {
        flat
            .filter { item -> item.libraryAnime.anime.genre.orEmpty().any { it.trim().equals(genre, ignoreCase = true) } }
            .map { LibraryDisplayItem.Anime(it) }
    } else {
        when (tab) {
            LibraryHomeTab.Home -> emptyList()
            LibraryHomeTab.All -> items
            LibraryHomeTab.Watching -> flat
                .filter { it.libraryAnime.isWatching() }
                .sortedByDescending { it.libraryAnime.lastSeen }
                .map { LibraryDisplayItem.Anime(it) }
            LibraryHomeTab.NotStarted -> flat
                .filter { !it.libraryAnime.hasStarted }
                .map { LibraryDisplayItem.Anime(it) }
            LibraryHomeTab.Finished -> flat
                .filter { it.libraryAnime.isFinished() }
                .map { LibraryDisplayItem.Anime(it) }
        }
    }

    val rows = ArrayList<GridRow>()
    var current = ArrayList<LibraryDisplayItem>()
    fun flush() {
        if (current.isNotEmpty()) {
            rows.add(GridRow.Cells(current))
            current = ArrayList()
        }
    }
    for (cell in cells) {
        if (cell is LibraryDisplayItem.Header) {
            flush()
            rows.add(GridRow.Title(cell.name))
        } else {
            current.add(cell)
            if (current.size == columns) flush()
        }
    }
    flush()
    return rows
}

private fun emptyMessage(tab: LibraryHomeTab): String = when (tab) {
    LibraryHomeTab.Watching -> "No tienes series en curso. Empieza una desde Inicio."
    LibraryHomeTab.NotStarted -> "Ya empezaste todas tus series."
    LibraryHomeTab.Finished -> "Todavía no terminaste ninguna serie."
    LibraryHomeTab.Home, LibraryHomeTab.All -> "No hay series para mostrar."
}

private fun tabTitle(tab: LibraryHomeTab): String = when (tab) {
    LibraryHomeTab.Home, LibraryHomeTab.All -> "Toda la biblioteca"
    LibraryHomeTab.Watching -> "En curso"
    LibraryHomeTab.NotStarted -> "Sin empezar"
    LibraryHomeTab.Finished -> "Completados"
}

private fun progressOf(entry: LibraryAnime): Float {
    if (entry.totalEpisodes <= 0) return 0f
    return (entry.seenCount.toFloat() / entry.totalEpisodes).coerceIn(0f, 1f)
}

private fun progressLabel(entry: LibraryAnime): String {
    val left = entry.unseenCount
    return if (left > 0) {
        "${entry.seenCount} de ${entry.totalEpisodes} vistos · faltan $left"
    } else {
        "${entry.seenCount} de ${entry.totalEpisodes} episodios vistos"
    }
}

// ANZ -->
private const val NewWindowMs = 7L * 24 * 60 * 60 * 1000

/** "Nuevo" only means something when the series really was added in the last 7 days. */
private fun LibraryAnime.isRecentlyAdded(): Boolean {
    val added = anime.dateAdded
    if (added <= 0L) return false
    val millis = if (added < 100_000_000_000L) added * 1000 else added
    return System.currentTimeMillis() - millis in 0..NewWindowMs
}

private val GenreNames = mapOf(
    "action" to "Acción",
    "adventure" to "Aventura",
    "comedy" to "Comedia",
    "fantasy" to "Fantasía",
    "horror" to "Terror",
    "mystery" to "Misterio",
    "psychological" to "Psicológico",
    "sci-fi" to "Ciencia ficción",
    "sci fi" to "Ciencia ficción",
    "science fiction" to "Ciencia ficción",
    "slice of life" to "Vida cotidiana",
    "sports" to "Deportes",
    "supernatural" to "Sobrenatural",
    "thriller" to "Suspenso",
    "suspense" to "Suspenso",
    "music" to "Música",
    "school" to "Escolar",
    "historical" to "Histórico",
    "military" to "Militar",
    "magic" to "Magia",
    "martial arts" to "Artes marciales",
    "super power" to "Superpoderes",
    "vampire" to "Vampiros",
    "demons" to "Demonios",
    "game" to "Juegos",
    "parody" to "Parodia",
    "space" to "Espacio",
    "kids" to "Infantil",
    "police" to "Policíaco",
    "gourmet" to "Gastronomía",
    "award winning" to "Premiada",
)

/** Display only: filtering keeps using the original genre name. */
private fun genreLabel(name: String): String = GenreNames[name.trim().lowercase()] ?: name
// ANZ <--

@Composable
internal fun LibraryHome(
    items: ImmutableList<LibraryDisplayItem>,
    contentPadding: PaddingValues,
    selection: ImmutableList<LibraryAnime>,
    onClick: (LibraryAnime) -> Unit,
    onLongClick: (LibraryAnime) -> Unit,
    onClickContinueWatching: ((LibraryAnime) -> Unit)?,
    onFolderClick: ((LibraryDisplayItem.Folder) -> Unit)?,
    onFolderLongClick: ((LibraryDisplayItem.Folder) -> Unit)?,
    modifier: Modifier = Modifier,
    searchQuery: String? = null,
    onGlobalSearchClicked: (() -> Unit)? = null,
) {
    val searching = !searchQuery.isNullOrEmpty()
    val flat = remember(items) { flattenAnime(items) }
    var heroSeed by rememberSaveable { mutableStateOf(SessionSeed) }
    val shelves = remember(flat, heroSeed) { buildShelves(flat, heroSeed) }
    val selectedIds = remember(selection) { selection.map { it.id }.toSet() }
    val counts = remember(flat) {
        mapOf(
            LibraryHomeTab.All to flat.size,
            LibraryHomeTab.Watching to flat.count { it.libraryAnime.isWatching() },
            LibraryHomeTab.NotStarted to flat.count { !it.libraryAnime.hasStarted },
            LibraryHomeTab.Finished to flat.count { it.libraryAnime.isFinished() },
        )
    }

    val episodesLeft = remember(flat) { flat.sumOf { it.libraryAnime.unseenCount.coerceAtLeast(0L) } }

    var tabIndex by rememberSaveable { mutableStateOf(0) }
    var genre by rememberSaveable { mutableStateOf<String?>(null) }
    val tab = LibraryHomeTab.entries[tabIndex.coerceIn(0, LibraryHomeTab.entries.lastIndex)]

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val columns = if (isLandscape) ColumnsLandscape else ColumnsPortrait

    val heroPager = rememberPagerState { shelves.heroes.size }
    val listState = rememberLazyListState()
    val fadePx = with(LocalDensity.current) { ToolbarFadeDistance.toPx() }
    val ambientOwner = remember { Any() }
    DisposableEffect(ambientOwner) { onDispose { LibraryHomeAmbient.release(ambientOwner) } }
    LaunchedEffect(listState, fadePx) {
        snapshotFlow {
            if (listState.firstVisibleItemIndex > 0) {
                1f
            } else {
                (listState.firstVisibleItemScrollOffset / fadePx).coerceIn(0f, 1f)
            }
        }.collect { LibraryHomeAmbient.fade = it }
    }
    // Back at the very top the toolbar is always fully shown again (tab changes jump to the top).
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 }
            .collect { atTop -> if (atTop) LibraryHomeAmbient.showBar() }
    }
    // Only listens: never consumes the scroll, it just tells the toolbar which way the page moves.
    val barConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                LibraryHomeAmbient.slideBar(available.y)
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(heroSeed) { heroPager.scrollToPage(0) }
    val gridRows = remember(tab, genre, items, flat, columns) { buildGridRows(tab, genre, items, flat, columns) }
    val gridCount = remember(gridRows) { gridRows.sumOf { (it as? GridRow.Cells)?.cells?.size ?: 0 } }
    // Search results: one flat grid of the same cards, without headers or folders.
    val searchRows = remember(searching, flat, columns) {
        if (searching) {
            buildGridRows(
                LibraryHomeTab.All,
                null,
                flat.map { LibraryDisplayItem.Anime(it) },
                flat,
                columns,
            )
        } else {
            emptyList()
        }
    }

    val actions = remember(onClick, onLongClick, onClickContinueWatching, onFolderClick, onFolderLongClick) {
        HomeActions(
            onClick = onClick,
            onLongClick = onLongClick,
            onPlay = onClickContinueWatching,
            onFolderClick = onFolderClick,
            onFolderLongClick = onFolderLongClick,
            onOpenTab = { target ->
                genre = null
                tabIndex = target.ordinal
            },
            onOpenGenre = { name ->
                genre = name
                tabIndex = LibraryHomeTab.All.ordinal
            },
            onShuffle = { heroSeed = Random.nextLong() },
        )
    }

    BackHandler(enabled = !searching && tab != LibraryHomeTab.Home) {
        actions.onOpenTab(LibraryHomeTab.Home)
    }

    LaunchedEffect(tab, genre, searchQuery) { listState.scrollToItem(0) }

    // The page runs under the transparent toolbar: its tint starts at the very top of the screen and
    // this inset keeps the first row clear of the icons.
    val topInset = contentPadding.calculateTopPadding()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(barConnection),
        state = listState,
        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + BottomSpace),
    ) {
        if (searching) {
            searchSection(
                query = searchQuery.orEmpty(),
                rows = searchRows,
                columns = columns,
                selectedIds = selectedIds,
                actions = actions,
                onGlobalSearch = onGlobalSearchClicked,
                topInset = topInset,
            )
        } else {
            item(key = "top", contentType = "top") {
                HomeTop(
                    tab = tab,
                    counts = counts,
                    episodesLeft = episodesLeft,
                    ambientOwner = ambientOwner,
                    heroes = shelves.heroes,
                    pagerState = heroPager,
                    selectedIds = selectedIds,
                    actions = actions,
                    topInset = topInset,
                )
            }
            if (tab == LibraryHomeTab.Home) {
                homeShelves(shelves = shelves, total = flat.size, selectedIds = selectedIds, actions = actions)
            } else {
                gridSection(
                    tab = tab,
                    genre = genre,
                    count = gridCount,
                    rows = gridRows,
                    columns = columns,
                    selectedIds = selectedIds,
                    actions = actions,
                )
            }
        }
    }
}

// region Sections

private fun LazyListScope.homeShelves(
    shelves: Shelves,
    total: Int,
    selectedIds: Set<Long>,
    actions: HomeActions,
) {
    if (shelves.watching.isNotEmpty()) {
        item(key = "shelf-watching", contentType = "continue") {
            ContinueShelf(
                entries = shelves.watching,
                selectedIds = selectedIds,
                actions = actions,
                onSeeAll = { actions.onOpenTab(LibraryHomeTab.Watching) },
                modifier = Modifier.padding(top = ShelfSpacing),
            )
        }
    }
    shelf(
        key = "not-started",
        title = "Por empezar",
        subtitle = "Series que aún no has visto",
        entries = shelves.notStarted,
        selectedIds = selectedIds,
        actions = actions,
        onSeeAll = { actions.onOpenTab(LibraryHomeTab.NotStarted) },
    )
    if (shelves.top.size >= MinTopItems) {
        item(key = "shelf-top", contentType = "ranked") {
            RankedShelf(
                title = "Tu top ${shelves.top.size}",
                entries = shelves.top,
                selectedIds = selectedIds,
                actions = actions,
                modifier = Modifier.padding(top = ShelfSpacing),
            )
        }
    }
    if (shelves.tiles.size >= 2) {
        item(key = "shelf-genres", contentType = "genres") {
            GenreTilesShelf(
                tiles = shelves.tiles,
                actions = actions,
                modifier = Modifier.padding(top = ShelfSpacing),
            )
        }
    }
    shelf(
        key = "recent",
        title = "Recién agregados",
        subtitle = "Lo último de tu colección",
        entries = shelves.recent,
        selectedIds = selectedIds,
        actions = actions,
        onSeeAll = { actions.onOpenTab(LibraryHomeTab.All) },
    )
    shelves.genres.forEach { genre ->
        shelf(
            key = "genre-${genre.name}",
            title = genreLabel(genre.name),
            subtitle = "Tus series de este género",
            entries = genre.entries,
            selectedIds = selectedIds,
            actions = actions,
            onSeeAll = { actions.onOpenGenre(genre.name) },
        )
    }
    shelf(
        key = "finished",
        title = "Completados",
        subtitle = "Ya las terminaste",
        entries = shelves.finished,
        selectedIds = selectedIds,
        actions = actions,
        onSeeAll = { actions.onOpenTab(LibraryHomeTab.Finished) },
    )
    item(key = "see-all", contentType = "footer") {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = ShelfSpacing + 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            FilledTonalButton(onClick = { actions.onOpenTab(LibraryHomeTab.All) }) {
                Text("Ver toda la biblioteca ($total)")
            }
        }
    }
}

private fun LazyListScope.shelf(
    key: String,
    title: String,
    entries: ImmutableList<LibraryItem>,
    selectedIds: Set<Long>,
    actions: HomeActions,
    subtitle: String? = null,
    onSeeAll: (() -> Unit)? = null,
) {
    if (entries.isEmpty()) return
    item(key = "shelf-$key", contentType = "shelf") {
        Shelf(
            title = title,
            entries = entries,
            selectedIds = selectedIds,
            actions = actions,
            modifier = Modifier.padding(top = ShelfSpacing),
            subtitle = subtitle,
            onSeeAll = onSeeAll,
        )
    }
}

private fun LazyListScope.gridSection(
    tab: LibraryHomeTab,
    genre: String?,
    count: Int,
    rows: List<GridRow>,
    columns: Int,
    selectedIds: Set<Long>,
    actions: HomeActions,
) {
    val scope = "${tab.name}-${genre.orEmpty()}"
    item(key = "grid-header-$scope", contentType = "grid-header") {
        GridHeader(
            title = genre?.let { genreLabel(it) } ?: tabTitle(tab),
            count = count,
            onBack = { actions.onOpenTab(LibraryHomeTab.Home) },
            onClear = if (genre != null) ({ actions.onOpenTab(LibraryHomeTab.All) }) else null,
        )
    }
    if (rows.isEmpty()) {
        item(key = "empty-$scope", contentType = "empty") {
            Text(
                text = emptyMessage(tab),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = EdgePadding, vertical = ShelfSpacing),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    itemsIndexed(
        items = rows,
        key = { index, _ -> "grid-$scope-$index" },
        contentType = { _, row -> if (row is GridRow.Title) "grid-title" else "grid-cells" },
    ) { _, row ->
        when (row) {
            is GridRow.Title -> Text(
                text = row.name,
                modifier = Modifier.padding(horizontal = EdgePadding, vertical = 12.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            is GridRow.Cells -> GridRowContent(
                cells = row.cells,
                columns = columns,
                selectedIds = selectedIds,
                showProgress = tab == LibraryHomeTab.Watching && genre == null,
                actions = actions,
            )
        }
    }
}

private fun LazyListScope.searchSection(
    query: String,
    rows: List<GridRow>,
    columns: Int,
    selectedIds: Set<Long>,
    actions: HomeActions,
    onGlobalSearch: (() -> Unit)?,
    topInset: Dp,
) {
    val count = rows.sumOf { (it as? GridRow.Cells)?.cells?.size ?: 0 }
    item(key = "search-header", contentType = "search-header") {
        SearchHeader(query = query, count = count, onGlobalSearch = onGlobalSearch, topInset = topInset)
    }
    itemsIndexed(
        items = rows,
        key = { index, _ -> "search-$index" },
        contentType = { _, _ -> "grid-cells" },
    ) { _, row ->
        if (row is GridRow.Cells) {
            GridRowContent(
                cells = row.cells,
                columns = columns,
                selectedIds = selectedIds,
                showProgress = false,
                actions = actions,
            )
        }
    }
}

/** Result count + the "search everywhere" pill, over the same soft tint the toolbar uses. */
@Composable
private fun SearchHeader(query: String, count: Int, onGlobalSearch: (() -> Unit)?, topInset: Dp) {
    val colors = MaterialTheme.colorScheme
    val ambient = LibraryHomeAmbient.color ?: colors.background
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(ambient, colors.background)))
            .padding(top = topInset)
            .padding(horizontal = EdgePadding)
            .padding(top = 18.dp, bottom = 16.dp),
    ) {
        Text(
            text = if (count == 1) "1 resultado" else "$count resultados",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.3).sp,
        )
        Text(
            text = "en tu biblioteca para «$query»",
            modifier = Modifier.padding(top = 2.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onBackground.copy(alpha = 0.65f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (onGlobalSearch != null) {
            GlobalSearchItem(
                searchQuery = query,
                onClick = onGlobalSearch,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
            )
        }
    }
}

// endregion

// region Top block (tabs + hero)

@Composable
private fun HomeTop(
    tab: LibraryHomeTab,
    counts: Map<LibraryHomeTab, Int>,
    episodesLeft: Long,
    ambientOwner: Any,
    heroes: ImmutableList<LibraryItem>,
    pagerState: PagerState,
    selectedIds: Set<Long>,
    actions: HomeActions,
    topInset: Dp,
    modifier: Modifier = Modifier,
) {
    val vibrant by CoverColorObserver.vibrantColors.collectAsState()
    val background = MaterialTheme.colorScheme.background
    val accent = MaterialTheme.colorScheme.primary

    val current = if (tab == LibraryHomeTab.Home) heroes.getOrNull(pagerState.currentPage) else null
    val seed = current?.let { vibrant[it.libraryAnime.id] }?.let { Color(it) }
    val ambientTarget = if (seed != null) {
        lerp(background, seed, AmbientStrength)
    } else {
        lerp(background, accent, AmbientFallback)
    }
    val ambient by animateColorAsState(targetValue = ambientTarget, label = "libraryAmbient")
    SideEffect { LibraryHomeAmbient.publish(ambientOwner, ambient) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(ambient, background)))
            .padding(top = topInset),
    ) {
        if (tab == LibraryHomeTab.Home) {
            HomeGreeting(onShuffle = actions.onShuffle)
            Spacer(Modifier.height(14.dp))
        } else {
            Spacer(Modifier.height(12.dp))
        }
        if (current != null) {
            Hero(
                heroes = heroes,
                pagerState = pagerState,
                selectedIds = selectedIds,
                actions = actions,
            )
        }
    }
}

private fun timeGreeting(): String {
    val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    return when {
        hour < 6 -> "Buenas noches"
        hour < 12 -> "Buenos días"
        hour < 19 -> "Buenas tardes"
        else -> "Buenas noches"
    }
}

@Composable
private fun HomeGreeting(
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val greeting = remember { timeGreeting() }
    val onBackground = MaterialTheme.colorScheme.onBackground
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = EdgePadding, end = EdgePadding, top = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = greeting,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "¿Qué vemos hoy?",
                modifier = Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = onBackground.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier
                .padding(start = 12.dp)
                .size(40.dp)
                .clip(CircleShape)
                .background(onBackground.copy(alpha = 0.12f))
                .clickable(role = Role.Button, onClick = onShuffle),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Shuffle,
                contentDescription = "Mezclar destacados",
                tint = onBackground,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun Hero(
    heroes: ImmutableList<LibraryItem>,
    pagerState: PagerState,
    selectedIds: Set<Long>,
    actions: HomeActions,
) {
    val screenHeight = LocalConfiguration.current.screenHeightDp

    // Auto advance, restarted after every page change; it waits while the user is dragging.
    LaunchedEffect(pagerState.currentPage, heroes.size) {
        if (heroes.size > 1) {
            delay(HeroAutoAdvanceMs)
            while (pagerState.isScrollInProgress) delay(500)
            pagerState.animateScrollToPage(
                page = (pagerState.currentPage + 1) % heroes.size,
                animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
            )
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val cardWidth = minOf(maxWidth - HeroPeek * 2, HeroMaxWidth)
        val cardHeight = maxOf(
            minOf(cardWidth * HeroHeightRatio, (screenHeight * HeroScreenFraction).dp),
            HeroMinHeight,
        )
        val sidePadding = (maxWidth - cardWidth) / 2
        val ratio = cardWidth.value / cardHeight.value

        // Quicker snap after a swipe (the stock spring felt sluggish).
        val fling = PagerDefaults.flingBehavior(
            state = pagerState,
            pagerSnapDistance = PagerSnapDistance.atMost(1),
            snapAnimationSpec = spring(dampingRatio = 0.9f, stiffness = 900f),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            HorizontalPager(
                state = pagerState,
                contentPadding = PaddingValues(horizontal = sidePadding),
                pageSpacing = HeroSpacing,
                flingBehavior = fling,
                key = { index -> heroes.getOrNull(index)?.libraryAnime?.id ?: index },
            ) { page ->
                val item = heroes.getOrNull(page) ?: return@HorizontalPager
                HeroCard(
                    item = item,
                    ratio = ratio,
                    isSelected = item.libraryAnime.id in selectedIds,
                    actions = actions,
                    pageOffset = { (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction },
                    modifier = Modifier.graphicsLayer {
                        val distance = ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction)
                            .absoluteValue
                            .coerceIn(0f, 1f)
                        val scale = 1f - 0.07f * distance
                        scaleX = scale
                        scaleY = scale
                        alpha = 1f - 0.5f * distance
                    },
                )
            }
            Spacer(Modifier.height(14.dp))
            PagerDots(pagerState = pagerState, count = heroes.size)
            Spacer(Modifier.height(4.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HeroCard(
    item: LibraryItem,
    ratio: Float,
    isSelected: Boolean,
    actions: HomeActions,
    pageOffset: () -> Float,
    modifier: Modifier = Modifier,
) {
    val entry = item.libraryAnime
    val anime = entry.anime
    val cover = remember(anime.id, anime.thumbnailUrl, anime.coverLastModified) { anime.asAnimeCover() }
    val shape = RoundedCornerShape(HeroRadius)
    val (pressSource, pressScale) = rememberPress()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = pressScale.value
                scaleY = pressScale.value
            }
            .clip(shape)
            .then(
                if (isSelected) {
                    Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape)
                } else {
                    Modifier.border(1.dp, Color.White.copy(alpha = 0.14f), shape)
                },
            )
            .combinedClickable(
                interactionSource = pressSource,
                indication = null,
                onClick = { actions.onClick(entry) },
                onLongClick = { actions.onLongClick(entry) },
            ),
    ) {
        // The hero cover is the one that seeds the ambient colour of the whole screen. The poster
        // fills the card (small crop) and the info sits over a dark fade at the bottom.
        // Parallax: while swiping, the artwork drifts slower than its frame (and zooms a little so
        // no edge shows). At rest it is untouched.
        AnimeCover.Book(
            data = cover,
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    val offset = pageOffset().coerceIn(-1f, 1f)
                    val zoom = 1f + 0.24f * offset.absoluteValue
                    scaleX = zoom
                    scaleY = zoom
                    translationX = offset * size.width * 0.12f
                },
            shape = RectangleShape,
            shouldExtractColor = true,
            measureRatio = false,
            ratio = ratio,
        )
        // Light fade: only the lower part darkens, the upper half is pure artwork.
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.45f to Color.Transparent,
                        0.72f to Color.Black.copy(alpha = 0.72f),
                        1f to Color.Black.copy(alpha = 0.95f),
                    ),
                ),
        )
        if (isSelected) {
            SelectedMark(modifier = Modifier.align(Alignment.TopEnd))
        }
        HeroInfo(
            item = item,
            actions = actions,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .graphicsLayer {
                    // Title and button settle in as the card reaches the centre.
                    val distance = pageOffset().absoluteValue.coerceIn(0f, 1f)
                    alpha = (1f - 1.6f * distance).coerceIn(0f, 1f)
                    translationY = 28.dp.toPx() * distance
                },
        )
    }
}

@Composable
private fun HeroInfo(item: LibraryItem, actions: HomeActions, modifier: Modifier = Modifier) {
    val entry = item.libraryAnime
    val anime = entry.anime
    val play = actions.onPlay
    val canPlay = play != null && entry.unseenCount > 0
    val label = when {
        !canPlay -> "Ver detalles"
        entry.hasStarted -> "Continuar viendo"
        else -> "Empezar a ver"
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
    ) {
        Text(
            text = anime.title,
            style = MaterialTheme.typography.headlineSmall.copy(
                shadow = Shadow(
                    color = Color.Black.copy(alpha = 0.55f),
                    offset = Offset(0f, 2f),
                    blurRadius = 14f,
                ),
            ),
            fontWeight = FontWeight.ExtraBold,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (entry.hasStarted && !entry.isFinished() && entry.totalEpisodes > 0) {
            SlimProgress(
                progress = progressOf(entry),
                track = Color.White.copy(alpha = 0.25f),
                fill = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        // One single action: the whole card also opens the details.
        Button(
            onClick = {
                if (canPlay && play != null) play(entry) else actions.onClick(entry)
            },
            modifier = Modifier
                .padding(top = 14.dp)
                .fillMaxWidth()
                .height(48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White,
                contentColor = Color.Black,
            ),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 0.dp),
        ) {
            if (canPlay) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(text = label, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun PagerDots(pagerState: PagerState, count: Int, modifier: Modifier = Modifier) {
    if (count > 1) {
        val onBackground = MaterialTheme.colorScheme.onBackground
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(count) { index ->
                val selected = pagerState.currentPage == index
                val width by animateDpAsState(targetValue = if (selected) 18.dp else 6.dp, label = "heroDot")
                Box(
                    modifier = Modifier
                        .height(6.dp)
                        .width(width)
                        .clip(CircleShape)
                        .background(onBackground.copy(alpha = if (selected) 0.9f else 0.3f)),
                )
            }
        }
    }
}

// endregion

// region Shelves

@Composable
private fun Shelf(
    title: String,
    entries: ImmutableList<LibraryItem>,
    selectedIds: Set<Long>,
    actions: HomeActions,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onSeeAll: (() -> Unit)? = null,
) {
    Column(modifier = modifier) {
        ShelfHeader(title = title, subtitle = subtitle, onSeeAll = onSeeAll)
        LazyRow(
            contentPadding = PaddingValues(horizontal = EdgePadding),
            horizontalArrangement = Arrangement.spacedBy(CardGap),
        ) {
            items(entries, key = { it.libraryAnime.id }) { item ->
                val entry = item.libraryAnime
                PosterCard(
                    item = item,
                    isSelected = entry.id in selectedIds,
                    onClick = { actions.onClick(entry) },
                    onLongClick = { actions.onLongClick(entry) },
                    modifier = Modifier.width(CardWidth),
                )
            }
        }
    }
}

@Composable
private fun ShelfHeader(title: String, onSeeAll: (() -> Unit)?, subtitle: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = EdgePadding)
            .padding(bottom = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .size(width = 4.dp, height = 18.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onSeeAll != null) {
            Box(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                    .clickable(role = Role.Button, onClick = onSeeAll),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Ver todo",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

@Composable
private fun ContinueShelf(
    entries: ImmutableList<LibraryItem>,
    selectedIds: Set<Long>,
    actions: HomeActions,
    onSeeAll: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        ShelfHeader(title = "Continuar viendo", subtitle = "Retoma donde lo dejaste", onSeeAll = onSeeAll)
        LazyRow(
            contentPadding = PaddingValues(horizontal = EdgePadding),
            horizontalArrangement = Arrangement.spacedBy(CardGap),
        ) {
            items(entries, key = { it.libraryAnime.id }) { item ->
                val entry = item.libraryAnime
                val play = actions.onPlay
                ContinueCard(
                    item = item,
                    isSelected = entry.id in selectedIds,
                    onClick = { actions.onClick(entry) },
                    onLongClick = { actions.onLongClick(entry) },
                    onPlay = if (play != null) ({ play(entry) }) else null,
                    modifier = Modifier.width(ContinueWidth),
                )
            }
        }
    }
}

@Composable
private fun RankedShelf(
    title: String,
    entries: ImmutableList<LibraryItem>,
    selectedIds: Set<Long>,
    actions: HomeActions,
    modifier: Modifier = Modifier,
) {
    val numeral = TextStyle(
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
        fontSize = RankFont,
        fontWeight = FontWeight.Black,
        lineHeight = RankFont,
        drawStyle = Stroke(width = RankStroke, join = StrokeJoin.Round),
    )
    Column(modifier = modifier) {
        ShelfHeader(title = title, subtitle = "Lo que más has visto", onSeeAll = null)
        LazyRow(
            contentPadding = PaddingValues(horizontal = EdgePadding),
            horizontalArrangement = Arrangement.spacedBy(CardGap),
        ) {
            itemsIndexed(entries, key = { _, item -> item.libraryAnime.id }) { index, item ->
                val entry = item.libraryAnime
                Box(modifier = Modifier.width(RankZone + CardWidth)) {
                    Text(
                        text = (index + 1).toString(),
                        modifier = Modifier.align(Alignment.BottomStart),
                        style = numeral,
                    )
                    PosterCard(
                        item = item,
                        isSelected = entry.id in selectedIds,
                        onClick = { actions.onClick(entry) },
                        onLongClick = { actions.onLongClick(entry) },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .width(CardWidth),
                        showTitle = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun GenreTilesShelf(
    tiles: ImmutableList<GenreTile>,
    actions: HomeActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        ShelfHeader(title = "Explorar por género", subtitle = "Toca uno para ver todo", onSeeAll = null)
        LazyRow(
            contentPadding = PaddingValues(horizontal = EdgePadding),
            horizontalArrangement = Arrangement.spacedBy(CardGap),
        ) {
            itemsIndexed(tiles, key = { _, tile -> tile.name }) { index, tile ->
                GenreTileCard(tile = tile, index = index, onClick = { actions.onOpenGenre(tile.name) })
            }
        }
    }
}

// endregion

// region Cards

@Composable
private fun GenreTileCard(tile: GenreTile, index: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val anime = tile.cover.libraryAnime.anime
    val cover = remember(anime.id, anime.thumbnailUrl, anime.coverLastModified) { anime.asAnimeCover() }
    // By position, so neighbouring tiles never share a colour.
    val tint = GenreColors[index % GenreColors.size]
    val shape = RoundedCornerShape(TileRadius)
    val (pressSource, pressScale) = rememberPress()

    Box(
        modifier = modifier
            .width(GenreTileWidth)
            .height(GenreTileHeight)
            .graphicsLayer {
                scaleX = pressScale.value
                scaleY = pressScale.value
            }
            .clip(shape)
            .border(1.dp, Color.White.copy(alpha = 0.12f), shape)
            .clickable(
                interactionSource = pressSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
    ) {
        AnimeCover.Book(
            data = cover,
            modifier = Modifier.fillMaxWidth(),
            shape = RectangleShape,
            shouldExtractColor = false,
            measureRatio = false,
            ratio = GenreTileWidth.value / GenreTileHeight.value,
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(tint.copy(alpha = 0.88f), tint.copy(alpha = 0.40f)),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.55f),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(12.dp),
        ) {
            Text(
                text = genreLabel(tile.name),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${tile.count} series",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.8f),
                maxLines = 1,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContinueCard(
    item: LibraryItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPlay: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val entry = item.libraryAnime
    val anime = entry.anime
    val cover = remember(anime.id, anime.thumbnailUrl, anime.coverLastModified) { anime.asAnimeCover() }
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(ContinueRadius)
    val (pressSource, pressScale) = rememberPress()

    Row(
        modifier = modifier
            .graphicsLayer {
                val scale = (if (isSelected) SelectedScale else 1f) * pressScale.value
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(colors.surfaceContainerHigh)
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) colors.primary else colors.onBackground.copy(alpha = 0.08f),
                shape = shape,
            )
            .combinedClickable(
                interactionSource = pressSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimeCover.Book(
            data = cover,
            modifier = Modifier.width(ContinuePoster),
            shape = RoundedCornerShape(12.dp),
            shouldExtractColor = false,
            measureRatio = false,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(
                text = anime.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.totalEpisodes > 0) {
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${entry.seenCount} de ${entry.totalEpisodes} vistos",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onBackground.copy(alpha = 0.65f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${(progressOf(entry) * 100).toInt()}%",
                        modifier = Modifier.padding(start = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.primary,
                        maxLines = 1,
                    )
                }
                SlimProgress(
                    progress = progressOf(entry),
                    track = colors.onBackground.copy(alpha = 0.15f),
                    fill = colors.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        if (onPlay != null) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(colors.primary)
                    .clickable(role = Role.Button, onClick = onPlay),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "Continuar viendo",
                    tint = colors.onPrimary,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PosterCard(
    item: LibraryItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    showProgress: Boolean = false,
    showTitle: Boolean = true,
    titleLines: Int = 2,
) {
    val entry = item.libraryAnime
    val anime = entry.anime
    val cover = remember(anime.id, anime.thumbnailUrl, anime.coverLastModified) { anime.asAnimeCover() }
    val shape = RoundedCornerShape(PosterRadius)
    val colors = MaterialTheme.colorScheme
    val (pressSource, pressScale) = rememberPress()

    Column(
        modifier = modifier
            .graphicsLayer {
                val scale = (if (isSelected) SelectedScale else 1f) * pressScale.value
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .combinedClickable(
                interactionSource = pressSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .border(
                    width = if (isSelected) 2.dp else 1.dp,
                    color = if (isSelected) colors.primary else colors.onBackground.copy(alpha = 0.08f),
                    shape = shape,
                ),
        ) {
            AnimeCover.Book(
                data = cover,
                modifier = Modifier.fillMaxWidth(),
                shape = RectangleShape,
                shouldExtractColor = false,
                measureRatio = false,
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.White.copy(alpha = 0.10f),
                            0.30f to Color.Transparent,
                        ),
                    ),
            )
            if (entry.unseenCount > 0) {
                UnseenTag(count = entry.unseenCount, modifier = Modifier.align(Alignment.TopStart))
            } else if (entry.isFinished()) {
                FinishedTag(modifier = Modifier.align(Alignment.TopStart))
            }
            if (!entry.hasStarted && entry.isRecentlyAdded()) {
                NewTag(modifier = Modifier.align(Alignment.BottomStart))
            }
            if (showProgress && entry.hasStarted && entry.totalEpisodes > 0) {
                SlimProgress(
                    progress = progressOf(entry),
                    track = Color.Black.copy(alpha = 0.55f),
                    fill = colors.primary,
                    height = 4.dp,
                    rounded = false,
                    modifier = Modifier.align(Alignment.BottomStart),
                )
            }
            if (isSelected) {
                SelectedMark(modifier = Modifier.align(Alignment.TopEnd))
            }
        }
        if (showTitle) {
            Text(
                text = anime.title,
                modifier = Modifier.padding(start = 2.dp, end = 2.dp, top = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                minLines = titleLines,
                maxLines = titleLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// endregion

// region Grid ("Todos", "En curso", ...)

@Composable
private fun GridHeader(title: String, count: Int, onBack: () -> Unit, onClear: (() -> Unit)?) {
    val onBackground = MaterialTheme.colorScheme.onBackground
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = EdgePadding)
            .padding(top = 4.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .padding(end = 12.dp)
                .size(40.dp)
                .clip(CircleShape)
                .background(onBackground.copy(alpha = 0.12f))
                .clickable(role = Role.Button, onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Volver al inicio",
                tint = onBackground,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "$count series",
                style = MaterialTheme.typography.labelLarge,
                color = onBackground.copy(alpha = 0.6f),
            )
        }
        if (onClear != null) {
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(onBackground.copy(alpha = 0.12f))
                    .clickable(role = Role.Button, onClick = onClear)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "Quitar filtro", style = MaterialTheme.typography.labelLarge)
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = null,
                    tint = onBackground,
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun GridRowContent(
    cells: List<LibraryDisplayItem>,
    columns: Int,
    selectedIds: Set<Long>,
    showProgress: Boolean,
    actions: HomeActions,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = EdgePadding)
            .padding(bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(CardGap),
    ) {
        cells.forEach { cell ->
            Box(modifier = Modifier.weight(1f)) {
                when (cell) {
                    is LibraryDisplayItem.Anime -> {
                        val entry = cell.libraryItem.libraryAnime
                        PosterCard(
                            item = cell.libraryItem,
                            isSelected = entry.id in selectedIds,
                            onClick = { actions.onClick(entry) },
                            onLongClick = { actions.onLongClick(entry) },
                            modifier = Modifier.fillMaxWidth(),
                            showProgress = showProgress,
                            titleLines = 2,
                        )
                    }
                    is LibraryDisplayItem.Folder -> {
                        FolderGridItem(
                            folder = cell,
                            displayMode = LibraryDisplayMode.ComfortableGrid,
                            onClick = { actions.onFolderClick?.invoke(cell) },
                            onLongClick = { actions.onFolderLongClick?.invoke(cell) },
                            usePanorama = false,
                        )
                    }
                    is LibraryDisplayItem.Header -> Unit
                }
            }
        }
        repeat(columns - cells.size) {
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

// endregion

// region Small pieces

/** Springy "pressed" feedback shared by every card: returns the interaction source and the scale. */
@Composable
private fun rememberPress(): Pair<MutableInteractionSource, State<Float>> {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale = animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f),
        label = "press",
    )
    return source to scale
}

@Composable
private fun NewTag(modifier: Modifier = Modifier) {
    Text(
        text = "NUEVO",
        modifier = modifier
            .padding(6.dp)
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        color = MaterialTheme.colorScheme.onPrimary,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.6.sp,
        maxLines = 1,
    )
}

@Composable
private fun UnseenTag(count: Long, modifier: Modifier = Modifier) {
    Text(
        text = count.toString(),
        modifier = modifier
            .padding(6.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
            .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(8.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
        color = Color.White,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun FinishedTag(modifier: Modifier = Modifier) {
    Icon(
        imageVector = Icons.Filled.Check,
        contentDescription = "Completado",
        tint = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .padding(6.dp)
            .size(22.dp)
            .background(Color.Black.copy(alpha = 0.55f), CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)
            .padding(4.dp),
    )
}

@Composable
private fun SlimProgress(
    progress: Float,
    track: Color,
    fill: Color,
    modifier: Modifier = Modifier,
    height: Dp = 5.dp,
    rounded: Boolean = true,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .then(if (rounded) Modifier.clip(CircleShape) else Modifier)
            .background(track),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction = progress)
                .background(fill),
        )
    }
}

@Composable
private fun SelectedMark(modifier: Modifier = Modifier) {
    Icon(
        imageVector = Icons.Filled.CheckCircle,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .padding(6.dp)
            .size(22.dp)
            .background(MaterialTheme.colorScheme.surface, CircleShape),
    )
}

// endregion
// ANZ <--
