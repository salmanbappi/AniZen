package eu.kanade.tachiyomi.ui.history

import androidx.activity.compose.BackHandler
import android.content.Context
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.NavItem
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.history.HistoryScreen
import eu.kanade.presentation.history.components.HistoryDeleteAllDialog
import eu.kanade.presentation.history.components.HistoryDeleteDialog
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.connections.discord.DiscordRPCService
import eu.kanade.tachiyomi.data.connections.discord.DiscordScreen
import eu.kanade.tachiyomi.ui.anime.AnimeScreen
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import tachiyomi.core.common.i18n.stringResource as stringResourceContext
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.injectLazy
import tachiyomi.presentation.core.util.collectAsState as collectAsStatePref
import androidx.compose.runtime.collectAsState

data object HistoryTab : Tab {

    private val resumeLastEpisodeSeenEvent = Channel<Unit>()

    override val options: TabOptions
        @Composable
        get() {
            val uiPreferences = remember { Injekt.get<UiPreferences>() }
            val visibleTabs by uiPreferences.bottomNavTabs().collectAsStatePref()
            val index = remember(visibleTabs) { 
                val i = visibleTabs.indexOf(NavItem.HISTORY.id)
                if (i != -1) i.toUShort() else 5u
            }
            return TabOptions(
                index = index,
                title = stringResource(MR.strings.history),
                icon = rememberAnimatedVectorPainter(AnimatedImageVector.animatedVectorResource(R.drawable.anim_history_enter), false),
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
        resumeLastEpisodeSeenEvent.send(Unit)
    }

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val fromMore = isTabFromMore(NavItem.HISTORY.id)
        val snackbarHostState = remember { SnackbarHostState() }

        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { HistoryScreenModel() }
        val state by screenModel.state.collectAsState()

        val scope = rememberCoroutineScope()
        val navigateUp: (() -> Unit)? = if (fromMore) {
            {
                if (navigator.lastItem == HomeScreen) {
                    scope.launch { HomeScreen.openTab(HomeScreen.HomeTab.AnimeLib()) }
                } else {
                    navigator.pop()
                }
            }
        } else {
            null
        }

        suspend fun openEpisode(context: Context, animeId: Long, episodeId: Long) {
            val playerPreferences: PlayerPreferences by injectLazy()
            val extPlayer = playerPreferences.alwaysUseExternalPlayer().get()
            MainActivity.startPlayerActivity(
                context,
                animeId,
                episodeId,
                extPlayer,
            )
        }

        HistoryScreen(
            state = state,
            searchQuery = state.searchQuery,
            snackbarHostState = snackbarHostState,
            onSearchQueryChange = screenModel::search,
            onClickCover = { navigator.push(AnimeScreen(it)) },
            onClickResume = { animeId, episodeId -> screenModel.getNextEpisodeForAnime(animeId, episodeId) },
            onDialogChange = screenModel::setDialog,
            navigateUp = navigateUp,
        )

        BackHandler(enabled = state.searchQuery != null) {
            screenModel.search(null)
        }

        state.dialog?.let { dialog ->
            when (dialog) {
                is HistoryScreenModel.Dialog.Delete -> {
                    HistoryDeleteDialog(
                        onDismissRequest = { screenModel.setDialog(null) },
                        onDelete = { all ->
                            if (all) {
                                screenModel.removeAllFromHistory(dialog.history.animeId)
                            } else {
                                screenModel.removeFromHistory(dialog.history)
                            }
                            screenModel.setDialog(null)
                        },
                    )
                }
                is HistoryScreenModel.Dialog.DeleteAll -> {
                    HistoryDeleteAllDialog(
                        onDismissRequest = { screenModel.setDialog(null) },
                        onDelete = {
                            screenModel.removeAllHistory()
                            screenModel.setDialog(null)
                        },
                    )
                }
            }
        }

        LaunchedEffect(Unit) {
            resumeLastEpisodeSeenEvent.receiveAsFlow().collectLatest {
                val episode = screenModel.getNextEpisode()
                if (episode != null) {
                    openEpisode(context, episode.animeId, episode.id)
                } else {
                    snackbarHostState.showSnackbar(context.stringResourceContext(MR.strings.no_next_episode))
                }
            }
        }

        LaunchedEffect(Unit) {
            screenModel.events.collectLatest { event ->
                when (event) {
                    HistoryScreenModel.Event.InternalError -> {
                        snackbarHostState.showSnackbar(context.stringResourceContext(MR.strings.internal_error))
                    }
                    is HistoryScreenModel.Event.OpenEpisode -> {
                        val episode = event.episode
                        if (episode != null) {
                            openEpisode(context, episode.animeId, episode.id)
                        } else {
                            snackbarHostState.showSnackbar(context.stringResourceContext(MR.strings.no_next_episode))
                        }
                    }
                    HistoryScreenModel.Event.HistoryCleared -> {
                        snackbarHostState.showSnackbar(context.stringResourceContext(MR.strings.clear_history_completed))
                    }
                }
            }
        }

        LaunchedEffect(Unit) {
            DiscordRPCService.setAnimeScreen(context, DiscordScreen.APP)
        }
    }
}
