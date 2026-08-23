package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.outlined.CallToAction
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.GetApp
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.icerock.moko.resources.StringResource
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.more.components.MoreItem
import eu.kanade.presentation.more.components.MoreSection
import eu.kanade.presentation.more.settings.screen.about.AboutScreen
import eu.kanade.presentation.more.settings.screen.NavigationSettingsScreen
import eu.kanade.presentation.util.LocalBackPress
import eu.kanade.presentation.util.Screen
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState as collectAsStatePref
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import cafe.adriel.voyager.core.screen.Screen as VoyagerScreen

object SettingsMainScreen : Screen() {
    @Composable
    override fun Content() {
        Content(twoPane = false)
    }

    @Composable
    private fun getPalerSurface(): Color {
        val surface = MaterialTheme.colorScheme.surface
        val dark = isSystemInDarkTheme()
        return remember(surface, dark) {
            val arr = FloatArray(3)
            ColorUtils.colorToHSL(surface.toArgb(), arr)
            arr[2] = if (dark) {
                arr[2] - 0.05f
            } else {
                arr[2] + 0.02f
            }.coerceIn(0f, 1f)
            Color.hsl(arr[0], arr[1], arr[2])
        }
    }

    @Composable
    fun Content(twoPane: Boolean) {
        val navigator = LocalNavigator.currentOrThrow
        val backPress = LocalBackPress.currentOrThrow
        val containerColor = if (twoPane) getPalerSurface() else MaterialTheme.colorScheme.background
        val topBarState = rememberTopAppBarState()

        val groupedItems = remember {
            mapOf(
                "User Interface" to listOf(
                    Item(
                        titleRes = MR.strings.pref_category_appearance,
                        subtitleRes = MR.strings.pref_appearance_summary,
                        icon = Icons.Outlined.Palette,
                        screen = SettingsAppearanceScreen,
                    ),
                    Item(
                        titleRes = MR.strings.pref_category_library,
                        subtitleRes = MR.strings.pref_library_summary,
                        icon = Icons.Outlined.CollectionsBookmark,
                        screen = SettingsLibraryScreen,
                    ),
                ),
                "Core Features" to listOf(
                    Item(
                        titleRes = MR.strings.pref_category_schedule,
                        subtitleRes = MR.strings.pref_category_schedule_summary,
                        icon = Icons.Outlined.DateRange,
                        screen = SettingsScheduleScreen,
                    ),
                    Item(
                        titleRes = MR.strings.pref_category_downloads,
                        subtitleRes = MR.strings.pref_downloads_summary,
                        icon = Icons.Outlined.GetApp,
                        screen = SettingsDownloadScreen,
                    ),
                    Item(
                        titleRes = MR.strings.pref_category_tracking,
                        subtitleRes = MR.strings.pref_tracking_summary,
                        icon = Icons.Outlined.Sync,
                        screen = SettingsTrackingScreen,
                    ),
                    Item(
                        titleRes = MR.strings.pref_category_ai,
                        subtitleRes = MR.strings.pref_ai_summary,
                        icon = Icons.Default.AutoAwesome,
                        screen = SettingsAiScreen,
                    ),
                ),
                "Connections" to listOf(
                    Item(
                        titleRes = KMR.strings.pref_category_connections,
                        subtitleRes = KMR.strings.pref_connections_summary,
                        icon = Icons.Outlined.Link,
                        screen = SettingsConnectionsScreen,
                    ),
                    Item(
                        titleRes = MR.strings.browse,
                        subtitleRes = MR.strings.pref_browse_summary,
                        icon = Icons.Outlined.Explore,
                        screen = SettingsBrowseScreen,
                    ),
                ),
                "System" to listOf(
                    Item(
                        titleRes = MR.strings.label_data_storage,
                        subtitleRes = MR.strings.pref_backup_summary,
                        icon = Icons.Outlined.Storage,
                        screen = SettingsDataScreen,
                    ),
                    Item(
                        titleRes = MR.strings.pref_category_security,
                        subtitleRes = MR.strings.pref_security_summary,
                        icon = Icons.Outlined.Security,
                        screen = SettingsSecurityScreen,
                    ),
                    Item(
                        titleRes = MR.strings.pref_category_advanced,
                        subtitleRes = MR.strings.pref_advanced_summary,
                        icon = Icons.Outlined.Code,
                        screen = SettingsAdvancedScreen,
                    ),
                ),
                "About" to listOf(
                    Item(
                        titleRes = MR.strings.pref_category_about,
                        formatSubtitle = {
                            "${stringResource(MR.strings.app_name)} ${AboutScreen.getVersionName(
                                withBuildDate = false,
                            )}"
                        },
                        icon = Icons.Outlined.Info,
                        screen = AboutScreen,
                    ),
                )
            )
        }

        val uiPreferences = remember { Injekt.get<UiPreferences>() }
        val hazeEnabled by uiPreferences.hazeEnabled().collectAsStatePref()

        Scaffold(
            topBarScrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(topBarState),
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.label_settings),
                    navigateUp = backPress::invoke,
                    actions = {
                        AppBarActions(
                            persistentListOf(
                                AppBar.Action(
                                    title = stringResource(MR.strings.action_search),
                                    icon = Icons.Outlined.Search,
                                    onClick = { navigator.navigate(SettingsSearchScreen(), twoPane) },
                                ),
                            ),
                        )
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
            containerColor = containerColor,
            hazeEnabled = hazeEnabled,
            content = { contentPadding ->
                val state = rememberLazyListState()

                LazyColumn(
                    state = state,
                    contentPadding = PaddingValues(16.dp),
                    modifier = Modifier.padding(top = contentPadding.calculateTopPadding()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    groupedItems.forEach { (category, items) ->
                        item(key = "settings-category-$category") {
                            MoreSection(title = category) {
                                items.forEach { item ->
                                    val onClick = remember(item.screen, twoPane, navigator) {
                                        { navigator.navigate(item.screen, twoPane) }
                                    }
                                    MoreItem(
                                        title = stringResource(item.titleRes),
                                        subtitle = item.formatSubtitle(),
                                        icon = item.icon,
                                        onClick = onClick,
                                    )
                                }
                            }
                        }
                    }
                }
            },
        )
    }

    private fun Navigator.navigate(screen: VoyagerScreen, twoPane: Boolean) {
        if (lastItem.key == screen.key) return
        if (twoPane) replace(screen) else push(screen)
    }

    @androidx.compose.runtime.Immutable
    data class Item(
        val titleRes: StringResource,
        val subtitleRes: StringResource? = null,
        val formatSubtitle: @Composable () -> String? = { subtitleRes?.let { stringResource(it) } },
        val icon: ImageVector,
        val screen: VoyagerScreen,
    )
}
