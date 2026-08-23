package eu.kanade.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.icerock.moko.resources.StringResource
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import eu.kanade.domain.ui.UiPreferences
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.TabText
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState as collectAsStatePref
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
fun TabbedScreen(
    titleRes: StringResource?,
    tabs: ImmutableList<TabContent>,
    modifier: Modifier = Modifier,
    state: PagerState = rememberPagerState { tabs.size },
    scrollable: Boolean = false,
    searchQuery: String? = null,
    onChangeSearchQuery: (String?) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val uiPreferences = remember { Injekt.get<UiPreferences>() }
    val hazeEnabled by uiPreferences.hazeEnabled().collectAsStatePref()

    Scaffold(
        topBar = {
            val tab = tabs.getOrNull(state.currentPage) ?: tabs.getOrNull(0)
            val currentTitleRes = titleRes ?: tab?.titleRes
            if (currentTitleRes != null) {
                val searchEnabled = tab?.searchEnabled ?: false

                SearchToolbar(
                    titleContent = {
                        AppBarTitle(
                            stringResource(currentTitleRes),
                            modifier = modifier,
                            null,
                            tab?.numberTitle ?: 0,
                        )
                    },
                    searchEnabled = searchEnabled,
                    searchQuery = if (searchEnabled) searchQuery else null,
                    onChangeSearchQuery = onChangeSearchQuery,
                    actions = { AppBarActions(tab?.actions ?: persistentListOf()) },
                    navigateUp = tab?.navigateUp,
                )
            }
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        contentWindowInsets = WindowInsets(0),
        hazeEnabled = hazeEnabled,
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            FlexibleTabRow(
                scrollable = scrollable,
                selectedTabIndex = state.currentPage.coerceIn(0, maxOf(0, tabs.lastIndex)),
                tabsCount = tabs.size,
            ) {
                tabs.forEachIndexed { index, tab ->
                    Tab(
                        selected = state.currentPage == index,
                        onClick = { scope.launch { state.animateScrollToPage(index) } },
                        text = {
                            TabText(
                                text = stringResource(tab.titleRes),
                                modifier = Modifier.padding(horizontal = 8.dp),
                                badgeCount = tab.badgeNumber,
                            )
                        },
                        unselectedContentColor = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            HorizontalPager(
                modifier = Modifier.fillMaxSize(),
                state = state,
                verticalAlignment = Alignment.Top,
                beyondViewportPageCount = 1,
            ) { page ->
                tabs[page].content(
                    PaddingValues(bottom = contentPadding.calculateBottomPadding()),
                    snackbarHostState,
                )
            }
        }
    }
}

data class TabContent(
    val titleRes: StringResource,
    val badgeNumber: Int? = null,
    val searchEnabled: Boolean = false,
    val actions: ImmutableList<AppBar.AppBarAction> = persistentListOf(),
    val content: @Composable (contentPadding: PaddingValues, snackbarHostState: SnackbarHostState) -> Unit,
    val numberTitle: Int = 0,
    val cancelAction: () -> Unit = {},
    val navigateUp: (() -> Unit)? = null,
)

@Composable
private fun FlexibleTabRow(
    scrollable: Boolean,
    selectedTabIndex: Int,
    tabsCount: Int,
    block: @Composable () -> Unit,
) {
    return if (scrollable || tabsCount > 3) {
        ScrollableTabRow(
            selectedTabIndex = selectedTabIndex,
            edgePadding = 13.dp,
            modifier = Modifier.zIndex(1f),
        ) {
            block()
        }
    } else {
        SecondaryTabRow(
            selectedTabIndex = selectedTabIndex,
            modifier = Modifier.zIndex(1f),
        ) {
            block()
        }
    }
}
