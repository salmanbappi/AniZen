package eu.kanade.presentation.history

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.domain.ui.ContainerStyle
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.PanoramaMode
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.PanoramaModeToggle
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.presentation.components.relativeDateText
import eu.kanade.presentation.history.components.HistoryItem
import eu.kanade.tachiyomi.ui.history.HistoryScreenModel
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.ListGroupHeader
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import tachiyomi.presentation.core.util.collectAsState as collectAsStatePref

@Composable
fun HistoryScreen(
    state: HistoryScreenModel.State,
    snackbarHostState: SnackbarHostState,
    onSearchQueryChange: (String?) -> Unit,
    onClickCover: (animeId: Long) -> Unit,
    onClickResume: (animeId: Long, episodeId: Long) -> Unit,
    onDialogChange: (HistoryScreenModel.Dialog?) -> Unit,
    navigateUp: (() -> Unit)?,
    searchQuery: String? = null,
) {
    val uiPreferences = remember { Injekt.get<UiPreferences>() }
    val globalPanorama by uiPreferences.panoramaCover().collectAsStatePref() as State<Boolean>
    val historyMode by uiPreferences.historyPanoramaMode().collectAsStatePref() as State<PanoramaMode>
    val effectivePanorama = remember(globalPanorama, historyMode) { historyMode.resolve(globalPanorama) }

    Scaffold(
        topBar = { scrollBehavior ->
            SearchToolbar(
                titleContent = { AppBarTitle(stringResource(MR.strings.history)) },
                searchQuery = searchQuery,
                onChangeSearchQuery = onSearchQueryChange,
                actions = {
                    val panoramaMode by uiPreferences.historyPanoramaMode().collectAsStatePref() as State<PanoramaMode>
                    PanoramaModeToggle(
                        panoramaMode = panoramaMode,
                        globalPanorama = globalPanorama,
                        onPanoramaModeChange = { next ->
                            uiPreferences.historyPanoramaMode().set(next)
                        },
                    )
                    AppBarActions(
                        persistentListOf<AppBar.AppBarAction>().builder()
                            .apply {
                                add(
                                    AppBar.Action(
                                        title = stringResource(MR.strings.pref_clear_history),
                                        icon = Icons.Outlined.DeleteSweep,
                                        onClick = {
                                            onDialogChange(HistoryScreenModel.Dialog.DeleteAll)
                                        },
                                    ),
                                )
                            }
                            .build(),
                    )
                },
                navigateUp = navigateUp,
                scrollBehavior = scrollBehavior,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { contentPadding ->
        state.list.let {
            if (it == null) {
                LoadingScreen(Modifier.padding(contentPadding))
            } else if (it.isEmpty()) {
                val msg = if (!searchQuery.isNullOrEmpty()) {
                    MR.strings.no_results_found
                } else {
                    MR.strings.information_no_recent_anime
                }
                EmptyScreen(
                    stringRes = msg,
                    modifier = Modifier.padding(contentPadding),
                )
            } else {
                HistoryScreenContent(
                    history = it,
                    contentPadding = contentPadding,
                    onClickCover = { history -> onClickCover(history.animeId) },
                    onClickResume = { history -> onClickResume(history.animeId, history.episodeId) },
                    onClickDelete = { item -> onDialogChange(HistoryScreenModel.Dialog.Delete(item)) },
                    usePanorama = effectivePanorama,
                )
            }
        }
    }
}

@Composable
private fun HistoryScreenContent(
    history: List<HistoryUiModel>,
    contentPadding: PaddingValues,
    onClickCover: (HistoryWithRelations) -> Unit,
    onClickResume: (HistoryWithRelations) -> Unit,
    onClickDelete: (HistoryWithRelations) -> Unit,
    usePanorama: Boolean,
) {
    val uiPreferences = remember { Injekt.get<UiPreferences>() }
    val containerStyles by uiPreferences.containerStyles().collectAsStatePref() as State<Set<String>>
    val useContainer = remember(containerStyles) { eu.kanade.domain.ui.ContainerStyle.HISTORY in containerStyles }

    FastScrollLazyColumn(
        contentPadding = contentPadding,
    ) {
        if (useContainer) {
            items(
                items = history,
                key = { "history-${it.hashCode()}" },
                contentType = {
                    when (it) {
                        is HistoryUiModel.Header -> "history_header"
                        is HistoryUiModel.Item -> "history_item"
                    }
                },
            ) { model ->
                when (model) {
                    is HistoryUiModel.Header -> {
                        ListGroupHeader(
                            modifier = Modifier,
                            text = relativeDateText(model.date),
                        )
                    }
                    is HistoryUiModel.Item -> {
                        Surface(
                            modifier = Modifier
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                                .fillMaxWidth(),
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            tonalElevation = 2.dp,
                        ) {
                            androidx.compose.foundation.layout.Column {
                                HistoryItem(
                                    modifier = Modifier,
                                    history = model.item,
                                    onClickCover = { onClickCover(model.item) },
                                    onClickResume = { onClickResume(model.item) },
                                    onClickDelete = { onClickDelete(model.item) },
                                    usePanorama = usePanorama,
                                )
                            }
                        }
                    }
                }
            }
        } else {
            items(
                items = history,
                key = { "history-${it.hashCode()}" },
                contentType = { "history" },
            ) { model ->
                when (model) {
                    is HistoryUiModel.Header -> {
                        ListGroupHeader(
                            modifier = Modifier,
                            text = relativeDateText(model.date),
                        )
                    }
                    is HistoryUiModel.Item -> {
                        HistoryItem(
                            modifier = Modifier,
                            history = model.item,
                            onClickCover = { onClickCover(model.item) },
                            onClickResume = { onClickResume(model.item) },
                            onClickDelete = { onClickDelete(model.item) },
                            usePanorama = usePanorama,
                        )
                    }
                }
            }
        }
    }
}
