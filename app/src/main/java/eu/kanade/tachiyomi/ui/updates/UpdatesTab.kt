package eu.kanade.tachiyomi.ui.updates

import android.content.Context
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.NavItem
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import androidx.compose.runtime.DisposableEffect
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
import eu.kanade.presentation.updates.UpdateScreen
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.connections.discord.DiscordRPCService
import eu.kanade.tachiyomi.data.connections.discord.DiscordScreen
import eu.kanade.tachiyomi.ui.anime.AnimeScreen
import eu.kanade.tachiyomi.ui.download.DownloadQueueScreen
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import mihon.feature.upcoming.UpcomingScreen
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.injectLazy
import tachiyomi.presentation.core.util.collectAsState as collectAsStatePref
import androidx.compose.runtime.collectAsState
import tachiyomi.core.common.i18n.stringResource as stringResourceContext

data object UpdatesTab : Tab {

    override val options: TabOptions
        @Composable
        get() {
            val uiPreferences = remember { Injekt.get<UiPreferences>() }
            val visibleTabs by uiPreferences.bottomNavTabs().collectAsStatePref()
            val index = remember(visibleTabs) { 
                val i = visibleTabs.indexOf(NavItem.UPDATES.id)
                if (i != -1) i.toUShort() else 4u
            }
            return TabOptions(
                index = index,
                title = stringResource(MR.strings.label_recent_updates),
                icon = rememberAnimatedVectorPainter(AnimatedImageVector.animatedVectorResource(R.drawable.anim_updates_enter), false),
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
        navigator.push(DownloadQueueScreen)
    }

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { UpdatesScreenModel() }
        val state by screenModel.state.collectAsState()
        val fromMore = isTabFromMore(NavItem.UPDATES.id)

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

        suspend fun openEpisode(context: Context, animeId: Long, episodeId: Long, extPlayer: Boolean) {
            MainActivity.startPlayerActivity(context, animeId, episodeId, extPlayer)
        }

        UpdateScreen(
            state = state,
            snackbarHostState = screenModel.snackbarHostState,
            lastUpdated = screenModel.lastUpdated,
            onClickCover = { navigator.push(AnimeScreen(it.update.animeId)) },
            onSelectAll = screenModel::toggleAllSelection,
            onInvertSelection = screenModel::invertSelection,
            onCalendarClicked = { navigator.push(UpcomingScreen()) },
            onUpdateLibrary = screenModel::updateLibrary,
            onToggleExpand = screenModel::toggleExpandedState,
            onDownloadEpisode = screenModel::downloadEpisodes,
            onMultiBookmarkClicked = screenModel::bookmarkUpdates,
            onMultiFillermarkClicked = { updates, filler -> /* Not in model? */ },
            onMultiMarkAsSeenClicked = screenModel::markUpdatesSeen,
            onMultiDeleteClicked = screenModel::showConfirmDeleteEpisodes,
            onUpdateSelected = screenModel::toggleSelection,
            onOpenEpisode = { item, altPlayer ->
                val playerPreferences: PlayerPreferences by injectLazy()
                val extPlayer = playerPreferences.alwaysUseExternalPlayer().get()
                scope.launch {
                    openEpisode(
                        context,
                        item.update.animeId,
                        item.update.episodeId,
                        extPlayer,
                    )
                }
            },
            navigateUp = navigateUp,
        )

        LaunchedEffect(state.selectionMode) {
            HomeScreen.showBottomNav(!state.selectionMode)
        }

        LaunchedEffect(Unit) {
            screenModel.events.collectLatest { event ->
                when (event) {
                    UpdatesScreenModel.Event.InternalError -> {
                        screenModel.snackbarHostState.showSnackbar(context.stringResourceContext(MR.strings.internal_error))
                    }
                    is UpdatesScreenModel.Event.LibraryUpdateTriggered -> {
                        val message = if (event.started) {
                            MR.strings.updating_library
                        } else {
                            MR.strings.update_already_running
                        }
                        screenModel.snackbarHostState.showSnackbar(context.stringResourceContext(message))
                    }
                }
            }
        }

        LaunchedEffect(Unit) {
            DiscordRPCService.setAnimeScreen(context, DiscordScreen.APP)
        }

        DisposableEffect(Unit) {
            screenModel.resetNewUpdatesCount()
            onDispose {}
        }
    }
}
