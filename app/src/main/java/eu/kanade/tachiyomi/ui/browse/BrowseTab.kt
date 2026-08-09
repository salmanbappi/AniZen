package eu.kanade.tachiyomi.ui.browse

import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Panorama
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.PanoramaMode
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.TabbedScreen
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.data.connections.discord.DiscordRPCService
import eu.kanade.tachiyomi.data.connections.discord.DiscordScreen
import eu.kanade.tachiyomi.ui.browse.extension.ExtensionsScreenModel
import eu.kanade.tachiyomi.ui.browse.extension.extensionsTab
import eu.kanade.tachiyomi.ui.browse.migration.sources.migrateSourceTab
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import eu.kanade.tachiyomi.ui.browse.source.sourcesTab
import eu.kanade.tachiyomi.ui.home.FeedManageScreen
import eu.kanade.tachiyomi.ui.home.FeedTab
import eu.kanade.tachiyomi.ui.main.MainActivity
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState as collectAsStatePref
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import androidx.compose.runtime.collectAsState as collectAsStateFlow

data object BrowseTab : Tab {

    override val options: TabOptions
        @Composable
        get() {
            return TabOptions(
                index = 3u,
                title = stringResource(MR.strings.browse),
                icon = null, // Handled in HomeScreen
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
        navigator.push(GlobalSearchScreen())
    }

    private val switchToExtensionTabChannel = kotlinx.coroutines.channels.Channel<Unit>(1, kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)

    fun showExtension() {
        switchToExtensionTabChannel.trySend(Unit)
    }

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val uiPreferences = remember { Injekt.get<UiPreferences>() }
        val showFeedInBrowse by uiPreferences.showFeedInBrowse().collectAsStatePref()

        // Hoisted for extensions tab's search bar
        val extensionsScreenModel = rememberScreenModel { ExtensionsScreenModel() }
        val animeExtensionsState by extensionsScreenModel.state.collectAsStateFlow()

        val sourcesTab = sourcesTab()
        val extensionsTab = extensionsTab(extensionsScreenModel)
        val migrateSourceTab = migrateSourceTab()

        val globalPanorama by uiPreferences.panoramaCover().collectAsStatePref() as State<Boolean>
        val feedMode by uiPreferences.feedPanoramaMode().collectAsStatePref() as State<PanoramaMode>
        val effectivePanorama = remember(globalPanorama, feedMode) { feedMode.resolve(globalPanorama) }

        val tabs = remember(showFeedInBrowse, sourcesTab, extensionsTab, migrateSourceTab, feedMode, effectivePanorama) {
            buildList {
                add(sourcesTab)
                if (showFeedInBrowse) {
                    add(
                        eu.kanade.presentation.components.TabContent(
                            titleRes = SYMR.strings.feed,
                            searchEnabled = false,
                            actions = persistentListOf(
                                AppBar.Action(
                                    title = "Toggle Panorama",
                                    icon = Icons.Outlined.Panorama,
                                    onClick = {
                                        val next = when (feedMode) {
                                            PanoramaMode.FOLLOW_GLOBAL -> PanoramaMode.FORCE_ON
                                            PanoramaMode.FORCE_ON -> PanoramaMode.FORCE_OFF
                                            PanoramaMode.FORCE_OFF -> PanoramaMode.FOLLOW_GLOBAL
                                        }
                                        uiPreferences.feedPanoramaMode().set(next)
                                    },
                                ),
                                AppBar.Action(
                                    title = "Edit Feed",
                                    icon = Icons.Outlined.Settings,
                                    onClick = { 
                                        navigator.push(FeedManageScreen())
                                    },
                                ),
                            ),
                            content = { contentPadding, _ -> 
                                FeedTab.Content(contentPadding, effectivePanorama)
                            }
                        )
                    )
                }
                add(extensionsTab)
                add(migrateSourceTab)
            }.toPersistentList()
        }

        val state = rememberPagerState { tabs.size }

        TabbedScreen(
            titleRes = MR.strings.browse,
            tabs = tabs,
            state = state,
            searchQuery = animeExtensionsState.searchQuery,
            onChangeSearchQuery = extensionsScreenModel::search,
            scrollable = false,
        )
        LaunchedEffect(state, showFeedInBrowse) {
            switchToExtensionTabChannel.receiveAsFlow()
                .collectLatest { 
                    val targetPage = if (showFeedInBrowse) 2 else 1
                    state.scrollToPage(targetPage) 
                }
        }

        LaunchedEffect(Unit) {
            (context as? MainActivity)?.ready = true
            // AM (DISCORD) -->
            DiscordRPCService.setAnimeScreen(context, DiscordScreen.BROWSE)
            // <-- AM (DISCORD)
        }
    }
}
