package eu.kanade.presentation.browse.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.PanoramaMode
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.components.RadioMenuItem
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.Source
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.source.localanime.LocalAnimeSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import tachiyomi.presentation.core.util.collectAsState as collectAsStatePref

@Composable
fun BrowseSourceToolbar(
    searchQuery: String?,
    onSearchQueryChange: (String?) -> Unit,
    source: Source?,
    displayMode: LibraryDisplayMode?,
    onDisplayModeChange: (LibraryDisplayMode) -> Unit,
    navigateUp: () -> Unit,
    onWebViewClick: () -> Unit,
    onHelpClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onSearch: (String) -> Unit,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    onUnselectAll: () -> Unit = {},
    onSelectAll: () -> Unit = {},
    onInvertSelection: () -> Unit = {},
    selectedCount: Int = 0,
    subtitle: String? = null,
    searchEnabled: Boolean = true,
    showMore: Boolean = true,
) {
    // Avoid capturing unstable source in actions lambda
    val title = source?.name
    val isLocalSource = source is LocalAnimeSource
    val isConfigurableSource = source is ConfigurableSource

    val mode = displayMode ?: LibraryDisplayMode.default

    var selectingDisplayMode by remember { mutableStateOf(false) }

    SearchToolbar(
        navigateUp = if (selectedCount > 0) onUnselectAll else navigateUp,
        titleContent = {
            if (selectedCount > 0) {
                Text(text = selectedCount.toString())
            } else {
                AppBarTitle(title, subtitle = subtitle)
            }
        },
        searchQuery = searchQuery,
        onChangeSearchQuery = onSearchQueryChange,
        onSearch = onSearch,
        onClickCloseSearch = navigateUp,
        searchEnabled = searchEnabled,
        actions = {
            if (selectedCount > 0) {
                AppBarActions(
                    actions = persistentListOf(
                        AppBar.Action(
                            title = stringResource(MR.strings.action_select_all),
                            icon = Icons.Outlined.SelectAll,
                            onClick = onSelectAll,
                        ),
                        AppBar.Action(
                            title = stringResource(MR.strings.action_select_inverse),
                            icon = Icons.Outlined.FlipToBack,
                            onClick = onInvertSelection,
                        ),
                    ),
                )
            } else {
                AppBarActions(
                    actions = persistentListOf<AppBar.AppBarAction>().builder()
                        .apply {
                            add(
                                AppBar.Action(
                                    title = stringResource(MR.strings.action_display_mode),
                                    icon = if (mode == LibraryDisplayMode.List) {
                                        Icons.AutoMirrored.Filled.ViewList
                                    } else {
                                        Icons.Filled.ViewModule
                                    },
                                    onClick = { selectingDisplayMode = true },
                                ),
                            )
                            if (showMore) {
                                if (isLocalSource) {
                                    add(
                                        AppBar.OverflowAction(
                                            title = stringResource(MR.strings.label_help),
                                            onClick = onHelpClick,
                                        ),
                                    )
                                } else {
                                    add(
                                        AppBar.OverflowAction(
                                            title = stringResource(MR.strings.action_open_in_web_view),
                                            onClick = onWebViewClick,
                                        ),
                                    )
                                }
                                if (isConfigurableSource) {
                                    add(
                                        AppBar.OverflowAction(
                                            title = stringResource(MR.strings.action_settings),
                                            onClick = onSettingsClick,
                                        ),
                                    )
                                }
                            }
                        }
                        .build(),
                )
            }

            DropdownMenu(
                expanded = selectingDisplayMode,
                onDismissRequest = { selectingDisplayMode = false },
            ) {
                val uiPreferences = remember { Injekt.get<UiPreferences>() }
                val panoramaMode by uiPreferences.browsePanoramaMode().collectAsStatePref()

                RadioMenuItem(
                    text = { Text(text = stringResource(MR.strings.action_display_comfortable_grid)) },
                    isChecked = mode == LibraryDisplayMode.ComfortableGrid,
                ) {
                    selectingDisplayMode = false
                    onDisplayModeChange(LibraryDisplayMode.ComfortableGrid)
                }
                RadioMenuItem(
                    text = { Text(text = stringResource(MR.strings.action_display_grid)) },
                    isChecked = mode == LibraryDisplayMode.CompactGrid,
                ) {
                    selectingDisplayMode = false
                    onDisplayModeChange(LibraryDisplayMode.CompactGrid)
                }
                RadioMenuItem(
                    text = { Text(text = stringResource(MR.strings.action_display_list)) },
                    isChecked = mode == LibraryDisplayMode.List,
                ) {
                    selectingDisplayMode = false
                    onDisplayModeChange(LibraryDisplayMode.List)
                }

                androidx.compose.material3.HorizontalDivider()

                tachiyomi.presentation.core.components.HeadingItem(MR.strings.pref_panorama_cover)

                PanoramaMode.entries.forEach { panoMode ->
                    RadioMenuItem(
                        text = { Text(text = stringResource(panoMode.getLabelRes())) },
                        isChecked = panoramaMode == panoMode,
                    ) {
                        selectingDisplayMode = false
                        uiPreferences.browsePanoramaMode().set(panoMode)
                    }
                }
            }
        },
        scrollBehavior = scrollBehavior,
    )
}
