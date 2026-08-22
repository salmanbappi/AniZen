package eu.kanade.presentation.more

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.CallToAction
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.GetApp
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LocalLibrary
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.VideoSettings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import eu.kanade.domain.ai.AiPreferences
import eu.kanade.domain.ui.model.NavItem
import eu.kanade.presentation.more.settings.screen.ai.AiAssistantScreen
import eu.kanade.presentation.more.settings.screen.NavigationSettingsScreen
import eu.kanade.presentation.more.settings.widget.SwitchPreferenceWidget
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.more.DownloadQueueState
import eu.kanade.tachiyomi.ui.stats.InfrastructureScreen
import kotlinx.coroutines.launch
import tachiyomi.core.common.Constants
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

import eu.kanade.domain.ui.ContainerStyle
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.more.components.MoreItem
import eu.kanade.presentation.more.components.MoreSection

@Composable
fun MoreScreen(
    downloadQueueStateProvider: () -> DownloadQueueState,
    downloadedOnly: Boolean,
    onDownloadedOnlyChange: (Boolean) -> Unit,
    incognitoMode: Boolean,
    onIncognitoModeChange: (Boolean) -> Unit,
    isFDroid: Boolean,
    hiddenTabs: List<NavItem>,
    onClickDownloadQueue: () -> Unit,
    onClickCategories: () -> Unit,
    onClickStats: () -> Unit,
    onClickLibraryUpdateErrors: () -> Unit,
    onClickDataAndStorage: () -> Unit,
    onClickPlayerSettings: () -> Unit,
    onClickSettings: () -> Unit,
    onClickAbout: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val navigator = LocalNavigator.currentOrThrow
    val scope = rememberCoroutineScope()
    val aiPreferences = remember { Injekt.get<AiPreferences>() }

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier.windowInsetsPadding(
                    WindowInsets.systemBars.only(
                        WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                    ),
                ),
            ) {
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { contentPadding ->
        ScrollbarLazyColumn(
            modifier = Modifier.fillMaxSize().padding(contentPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                LogoHeader()
            }

            item {
                MoreSection(title = "Preferences") {
                    SwitchPreferenceWidget(
                        title = stringResource(MR.strings.label_downloaded_only),
                        subtitle = stringResource(MR.strings.downloaded_only_summary),
                        icon = Icons.Outlined.CloudOff,
                        checked = downloadedOnly,
                        onCheckedChanged = onDownloadedOnlyChange,
                    )
                    SwitchPreferenceWidget(
                        title = stringResource(MR.strings.pref_incognito_mode),
                        subtitle = stringResource(MR.strings.pref_incognito_mode_summary),
                        icon = ImageVector.vectorResource(R.drawable.ic_glasses_24dp),
                        checked = incognitoMode,
                        onCheckedChanged = onIncognitoModeChange,
                    )
                }
            }

            item {
                MoreSection(title = "Library") {
                    val downloadQueueState = downloadQueueStateProvider()
                    MoreItem(
                        title = stringResource(MR.strings.label_download_queue),
                        subtitle = when (downloadQueueState) {
                            DownloadQueueState.Stopped -> null
                            is DownloadQueueState.Paused -> {
                                val pending = downloadQueueState.pending
                                if (pending == 0) {
                                    stringResource(MR.strings.paused)
                                } else {
                                    "${stringResource(MR.strings.paused)} • ${
                                        pluralStringResource(
                                            MR.plurals.download_queue_summary,
                                            count = pending,
                                            pending,
                                        )
                                    }"
                                }
                            }
                            is DownloadQueueState.Downloading -> {
                                val pending = downloadQueueState.pending
                                pluralStringResource(
                                    MR.plurals.download_queue_summary,
                                    count = pending,
                                    pending,
                                )
                            }
                        },
                        icon = Icons.Outlined.GetApp,
                        onClick = onClickDownloadQueue
                    )
                    MoreItem(
                        title = stringResource(MR.strings.general_categories),
                        icon = Icons.AutoMirrored.Outlined.Label,
                        onClick = onClickCategories
                    )
                    MoreItem(
                        title = stringResource(MR.strings.label_stats),
                        icon = Icons.Outlined.QueryStats,
                        onClick = onClickStats
                    )
                    MoreItem(
                        title = stringResource(SYMR.strings.option_label_library_update_errors),
                        icon = Icons.Outlined.Info,
                        onClick = onClickLibraryUpdateErrors
                    )
                }
            }

            item {
                MoreSection(title = "System") {
                    MoreItem(
                        title = "Extension Health",
                        subtitle = "Real-time telemetry and source status",
                        icon = Icons.Outlined.MonitorHeart,
                        onClick = { navigator.push(InfrastructureScreen) }
                    )

                    val enableAi by aiPreferences.enableAi().collectAsState()
                    val enableAiAssistant by aiPreferences.enableAiAssistant().collectAsState()
                    if (enableAi && enableAiAssistant) {
                        MoreItem(
                            title = "App Diagnostics",
                            subtitle = "Automated troubleshooting and AI insights",
                            icon = Icons.Default.Terminal,
                            onClick = { navigator.push(AiAssistantScreen()) }
                        )
                    }
                }
            }

            item {
                MoreSection(title = "General") {
                    hiddenTabs.forEach { navItem ->
                        MoreItem(
                            title = stringResource(navItem.titleRes),
                            icon = navItem.iconVector,
                            iconPainter = if (navItem.iconVector == null) painterResource(navItem.staticIconRes) else null,
                            onClick = {
                                scope.launch {
                                    val homeTab = when (navItem) {
                                        NavItem.LIBRARY -> HomeScreen.HomeTab.AnimeLib()
                                        NavItem.FEED -> HomeScreen.HomeTab.Feed
                                        NavItem.UPDATES -> HomeScreen.HomeTab.Updates
                                        NavItem.HISTORY -> HomeScreen.HomeTab.History
                                        NavItem.SCHEDULE -> HomeScreen.HomeTab.Schedule
                                        NavItem.BROWSE -> HomeScreen.HomeTab.Browse()
                                        NavItem.MORE -> HomeScreen.HomeTab.More(false)
                                    }
                                    HomeScreen.openTab(homeTab)
                                }
                            }
                        )
                    }
                    MoreItem(
                        title = stringResource(MR.strings.pref_bottom_nav_settings),
                        icon = Icons.Outlined.CallToAction,
                        onClick = { navigator.push(NavigationSettingsScreen(null)) }
                    )
                    MoreItem(
                        title = stringResource(MR.strings.label_data_storage),
                        icon = Icons.Outlined.Storage,
                        onClick = onClickDataAndStorage
                    )
                    MoreItem(
                        title = stringResource(MR.strings.label_settings),
                        icon = Icons.Outlined.Settings,
                        onClick = onClickSettings
                    )
                    MoreItem(
                        title = stringResource(MR.strings.label_player_settings),
                        icon = Icons.Outlined.VideoSettings,
                        onClick = onClickPlayerSettings
                    )
                }
            }

            item {
                MoreSection(title = "Support") {
                    MoreItem(
                        title = stringResource(MR.strings.pref_category_about),
                        icon = Icons.Outlined.Info,
                        onClick = onClickAbout
                    )
                    MoreItem(
                        title = stringResource(MR.strings.label_help),
                        icon = Icons.AutoMirrored.Outlined.HelpOutline,
                        onClick = { uriHandler.openUri(Constants.URL_HELP) }
                    )
                    MoreItem(
                        title = stringResource(MR.strings.label_sponsor_me),
                        icon = Icons.Outlined.Favorite,
                        onClick = { uriHandler.openUri("https://www.patreon.com/10625779/join") }
                    )
                }
            }
        }
    }
}


