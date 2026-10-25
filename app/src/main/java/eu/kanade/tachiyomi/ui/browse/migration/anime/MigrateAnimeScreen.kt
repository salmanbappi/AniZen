package eu.kanade.tachiyomi.ui.browse.migration.anime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.browse.MigrateAnimeScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.anime.AnimeScreen
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.flow.collectLatest
import mihon.feature.migration.config.MigrationConfigScreen
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen

data class MigrateAnimeScreen(
    private val sourceIds: List<Long>,
) : Screen() {

    constructor(sourceId: Long) : this(listOf(sourceId))

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { MigrateAnimeScreenModel(sourceIds) }

        val state by screenModel.state.collectAsState()

        if (state.isLoading) {
            LoadingScreen()
            return
        }

        val title = if (state.sources.size == 1) {
            state.sources.first().name
        } else {
            stringResource(MR.strings.label_migration)
        }

        MigrateAnimeScreen(
            navigateUp = navigator::pop,
            title = title,
            state = state,
            onClickItem = { navigator.push(MigrationConfigScreen(it.id)) },
            onClickCover = { navigator.push(AnimeScreen(it.id)) },
            onAnimeSelected = screenModel::toggleSelection,
            onSelectAll = screenModel::toggleAllSelection,
            onInvertSelection = screenModel::invertSelection,
            onMultiMigrateClicked = {
                screenModel.clearSelection()
                navigator.push(MigrationConfigScreen(it.map { it.id }))
            },
        )

        LaunchedEffect(Unit) {
            screenModel.events.collectLatest { event ->
                when (event) {
                    MigrationAnimeEvent.FailedFetchingFavorites -> {
                        context.toast(MR.strings.internal_error)
                    }
                }
            }
        }
    }
}
