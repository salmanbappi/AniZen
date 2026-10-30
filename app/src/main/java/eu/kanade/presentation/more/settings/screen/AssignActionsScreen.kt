package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.NavAction
import eu.kanade.domain.ui.model.NavBehavior
import eu.kanade.domain.ui.model.NavConfig
import eu.kanade.domain.ui.model.NavItem
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.settings.widget.ListPreferenceWidget
import eu.kanade.presentation.more.settings.widget.PreferenceGroupHeader
import eu.kanade.presentation.util.LocalBackPress
import eu.kanade.presentation.util.Screen
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.plus
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import tachiyomi.presentation.core.util.collectAsState as collectAsStatePref

class AssignActionsScreen : Screen() {

    @Composable
    override fun Content() {
        val uiPreferences = remember { Injekt.get<UiPreferences>() }
        val backPress = LocalBackPress.current
        val bottomNavTabs by uiPreferences.bottomNavTabs().collectAsStatePref()
        val bottomNavHiddenTabs by uiPreferences.bottomNavHiddenTabs().collectAsStatePref()
        val behaviorMap by uiPreferences.bottomNavBehaviors().collectAsStatePref()

        // Sliced state for gesture mapping
        val visibleItems: List<NavItem> = remember(bottomNavTabs) {
            bottomNavTabs.mapNotNull { NavItem.fromId(it) }
        }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = "Assign Actions",
                    navigateUp = { backPress?.invoke() },
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { paddingValues ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 0.dp,
                    top = paddingValues.calculateTopPadding() + 16.dp,
                    end = 0.dp,
                    bottom = paddingValues.calculateBottomPadding() + 16.dp,
                ),
            ) {
                item {
                    Text(
                        text = "Customize Long Press and Double Tap actions for your bottom bar tabs.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    )
                }

                items(visibleItems, key = { it.id }) { item ->
                    GestureMappingSection(
                        item = item,
                        behavior = behaviorMap[item.id] ?: NavBehavior(),
                        uiPreferences = uiPreferences,
                        visibleTabs = bottomNavTabs.toImmutableList(),
                        hiddenTabs = bottomNavHiddenTabs.toImmutableList(),
                        behaviorMap = behaviorMap,
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }
            }
        }
    }

    @Composable
    private fun GestureMappingSection(
        item: NavItem,
        behavior: NavBehavior,
        uiPreferences: UiPreferences,
        visibleTabs: ImmutableList<String>,
        hiddenTabs: ImmutableList<String>,
        behaviorMap: ImmutableMap<String, NavBehavior>,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            PreferenceGroupHeader(title = stringResource(item.titleRes))

            // Long Press Mapping
            ListPreferenceWidget(
                value = behavior.onLongClick,
                title = "Long Press",
                subtitle = if (behavior.onLongClick.isDangerous) "⚠️ Dangerous Action" else "Choose action for long press",
                icon = null,
                entries = NavAction.ALL.associateWith {
                    val name = it.javaClass.simpleName
                    if (it.isDangerous) "$name ⚠️" else name
                }.toImmutableMap(),
                onValueChange = { newAction ->
                    val newBehaviorMap = behaviorMap.toMutableMap()
                    newBehaviorMap[item.id] = behavior.copy(onLongClick = newAction)
                    uiPreferences.updateNavConfig(
                        NavConfig(
                            visibleTabs = visibleTabs,
                            hiddenTabs = hiddenTabs,
                            behaviorMap = newBehaviorMap.toImmutableMap(),
                        ),
                    )
                },
            )

            // Double Tap Mapping
            ListPreferenceWidget(
                value = behavior.onDoubleTap,
                title = "Double Tap",
                subtitle = if (behavior.onDoubleTap.cooldownMs > 1000) "Cooldown: ${behavior.onDoubleTap.cooldownMs / 1000}s" else "Choose action for double tap",
                icon = null,
                entries = NavAction.ALL.associateWith { it.javaClass.simpleName }.toImmutableMap(),
                onValueChange = { newAction ->
                    val newBehaviorMap = behaviorMap.toMutableMap()
                    newBehaviorMap[item.id] = behavior.copy(onDoubleTap = newAction)
                    uiPreferences.updateNavConfig(
                        NavConfig(
                            visibleTabs = visibleTabs,
                            hiddenTabs = hiddenTabs,
                            behaviorMap = newBehaviorMap.toImmutableMap(),
                        ),
                    )
                },
            )
        }
    }
}
