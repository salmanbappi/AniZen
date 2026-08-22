package eu.kanade.presentation.more.settings.screen

import android.util.Log
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import sh.calvin.reorderable.*
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.NavBehavior
import eu.kanade.domain.ui.model.NavConfig
import eu.kanade.domain.ui.model.NavConfigSerializer
import eu.kanade.domain.ui.model.NavConfigValidator
import eu.kanade.domain.ui.model.NavItem
import eu.kanade.domain.ui.model.NavLabelVisibility
import eu.kanade.domain.ui.model.NavPresets
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.more.settings.widget.ListPreferenceWidget
import eu.kanade.presentation.more.settings.widget.PreferenceGroupHeader
import eu.kanade.presentation.more.settings.widget.SwitchPreferenceWidget
import eu.kanade.presentation.util.LocalBackPress
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.home.ActionTrace
import eu.kanade.tachiyomi.ui.home.NavActionExecutor
import eu.kanade.tachiyomi.util.system.copyToClipboard
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.plus
import tachiyomi.presentation.core.util.collectAsState as collectAsStatePref
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class NavigationSettingsScreen(
    private val initialLayoutData: String? = null,
) : Screen() {

    @Composable
    override fun Content() {
        val uiPreferences = remember { Injekt.get<UiPreferences>() }
        val backPress = LocalBackPress.current
        val navigator = LocalNavigator.currentOrThrow

        val bottomNavTabs by uiPreferences.bottomNavTabs().collectAsStatePref()
        val bottomNavHiddenTabs by uiPreferences.bottomNavHiddenTabs().collectAsStatePref()
        val behaviorMap by uiPreferences.bottomNavBehaviors().collectAsStatePref()
        val navLabelVisibility by uiPreferences.navLabelVisibility().collectAsStatePref()
        val hideOnScroll by uiPreferences.hideBottomBarOnScroll().collectAsStatePref()

        val haptic = LocalHapticFeedback.current
        val context = LocalContext.current
        
        var showImportDialog by remember { mutableStateOf(initialLayoutData != null) }
        var importInput by remember { mutableStateOf(initialLayoutData ?: "") }

        if (showImportDialog) {
            AlertDialog(
                onDismissRequest = { showImportDialog = false },
                title = { Text("Import Layout") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Paste a layout string to apply a shared navigation configuration.")
                        TextField(
                            value = importInput,
                            onValueChange = { importInput = it },
                            placeholder = { Text("v1|library,updates...") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        val preview = NavConfigSerializer.deserialize(importInput)
                        if (preview != null) {
                            Text("Preview:", fontWeight = FontWeight.Bold)
                            Text(preview.visibleTabs.joinToString(" → "))
                        } else if (importInput.isNotBlank()) {
                            Text("Invalid format or ID", color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val config = NavConfigSerializer.deserialize(importInput)
                            if (config != null) {
                                uiPreferences.updateNavConfig(config)
                                showImportDialog = false
                                importInput = ""
                                context.toast("Layout applied successfully")
                            }
                        },
                        enabled = NavConfigSerializer.deserialize(importInput) != null
                    ) {
                        Text("Apply")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showImportDialog = false }) {
                        Text(stringResource(MR.strings.action_cancel))
                    }
                }
            )
        }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.pref_bottom_nav_settings),
                    navigateUp = { backPress?.invoke() },
                    actions = {
                        AppBarActions(
                            actions = persistentListOf<AppBar.AppBarAction>(
                                AppBar.Action(
                                    title = "Browse Gallery",
                                    icon = Icons.Outlined.Dashboard,
                                    onClick = { navigator.push(NavigationGalleryScreen()) },
                                ),                                AppBar.OverflowAction(
                                    title = "Copy Layout String",
                                    onClick = {
                                        val config = NavConfig(
                                            visibleTabs = bottomNavTabs.toImmutableList(),
                                            hiddenTabs = bottomNavHiddenTabs.toImmutableList(),
                                            behaviorMap = behaviorMap
                                        )
                                        val serialized = NavConfigSerializer.serialize(config)
                                        context.copyToClipboard("AniZen Layout", serialized)
                                        context.toast("Layout string copied to clipboard")
                                    }
                                ),
                                AppBar.OverflowAction(
                                    title = "Default Preset",
                                    onClick = { 
                                        Log.d("AniZenNav", "Preset applied: Default")
                                        uiPreferences.updateNavConfig(NavPresets.DEFAULT) 
                                    },
                                ),
                                AppBar.OverflowAction(
                                    title = "Minimal Preset",
                                    onClick = { 
                                        Log.d("AniZenNav", "Preset applied: Minimal")
                                        uiPreferences.updateNavConfig(NavPresets.MINIMAL) 
                                    },
                                ),
                                AppBar.OverflowAction(
                                    title = "Power Preset",
                                    onClick = { 
                                        Log.d("AniZenNav", "Preset applied: Power")
                                        uiPreferences.updateNavConfig(NavPresets.POWER) 
                                    },
                                ),
                                AppBar.OverflowAction(
                                    title = "Import Layout String",
                                    onClick = { showImportDialog = true }
                                ),
                                AppBar.Action(
                                    title = stringResource(MR.strings.pref_bottom_nav_reset_layout),
                                    icon = Icons.Outlined.RestartAlt,
                                    onClick = {
                                        Log.d("AniZenNav", "Layout Reset triggered")
                                        uiPreferences.updateNavConfig(NavPresets.DEFAULT)
                                        uiPreferences.bottomNavBehaviors().delete()
                                    },
                                ),
                            ),
                        )
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { paddingValues ->
            val lazyListState = rememberLazyListState()
            
            val visibleItems: List<NavItem> = remember(bottomNavTabs) {
                bottomNavTabs.mapNotNull { NavItem.fromId(it) }
            }
            val hiddenItems: List<NavItem> = remember(bottomNavHiddenTabs) {
                bottomNavHiddenTabs.mapNotNull { NavItem.fromId(it) }
            }

            val onMoveToHidden = { item: NavItem ->
                Log.d("AniZenNav", "Hiding tab: ${item.id}")
                val newVisible = visibleItems.filter { it != item }.map { n -> n.id }.toImmutableList()
                val newHidden = (hiddenItems.map { n -> n.id } + item.id).distinct().toImmutableList()
                uiPreferences.updateNavConfig(NavConfig(visibleTabs = newVisible, hiddenTabs = newHidden, behaviorMap = behaviorMap))
            }

            val onMoveToVisible = { item: NavItem ->
                if (visibleItems.size < NavConfigValidator.MAX_BOTTOM_TABS) {
                    Log.d("AniZenNav", "Showing tab: ${item.id}")
                    val newHidden = hiddenItems.filter { it != item }.map { n -> n.id }.toImmutableList()
                    val newVisible = (visibleItems.map { n -> n.id } + item.id).distinct().toImmutableList()
                    uiPreferences.updateNavConfig(NavConfig(visibleTabs = newVisible, hiddenTabs = newHidden, behaviorMap = behaviorMap))
                } else {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    context.toast(MR.strings.pref_bottom_nav_max_tabs_reached)
                }
            }

            val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
                val visibleIndexFrom = visibleItems.indexOfFirst { "visible-${it.id}" == from.key }
                val visibleIndexTo = visibleItems.indexOfFirst { "visible-${it.id}" == to.key }
                
                if (visibleIndexFrom != -1 && visibleIndexTo != -1) {
                    val newList = visibleItems.toMutableList().apply {
                        add(visibleIndexTo, removeAt(visibleIndexFrom))
                    }
                    uiPreferences.updateNavConfig(NavConfig(visibleTabs = newList.map { n -> n.id }.toImmutableList(), hiddenTabs = bottomNavHiddenTabs.toImmutableList(), behaviorMap = behaviorMap))
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = lazyListState,
                contentPadding = paddingValues + PaddingValues(0.dp, 16.dp),
            ) {
                item {
                    PreferenceGroupHeader(title = stringResource(MR.strings.pref_behavior))
                    ListPreferenceWidget(
                        value = navLabelVisibility,
                        title = stringResource(MR.strings.pref_bottom_nav_style),
                        subtitle = stringResource(navLabelVisibility.titleRes),
                        icon = null,
                        entries = NavLabelVisibility.entries
                            .associateWith { stringResource(it.titleRes) }
                            .toImmutableMap(),
                        onValueChange = { uiPreferences.navLabelVisibility().set(it) },
                    )
                    SwitchPreferenceWidget(
                        title = "Always show nav bar",
                        subtitle = "Disable hiding the navigation bar when scrolling down",
                        checked = !hideOnScroll,
                        onCheckedChanged = { uiPreferences.hideBottomBarOnScroll().set(!it) },
                        icon = null
                    )
                    val hideTabsCompletely by uiPreferences.hideTabsCompletely().collectAsStatePref()
                    SwitchPreferenceWidget(
                        title = "Hide hidden tabs from More",
                        subtitle = "When enabled, tabs dragged to 'Hidden Tabs' will completely disappear from the app instead of moving into the More menu.",
                        checked = hideTabsCompletely,
                        onCheckedChanged = { uiPreferences.hideTabsCompletely().set(it) },
                        icon = null
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    PreferenceGroupHeader(title = "Feed Settings")
                    val showFeedInBrowse by uiPreferences.showFeedInBrowse().collectAsStatePref()
                    SwitchPreferenceWidget(
                        title = stringResource(MR.strings.pref_show_feed_in_browse),
                        subtitle = stringResource(MR.strings.pref_show_feed_in_browse_summary),
                        checked = showFeedInBrowse,
                        onCheckedChanged = { uiPreferences.showFeedInBrowse().set(it) },
                        icon = null
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    PreferenceGroupHeader(title = "Adaptive Navigation (Beta)")
                    val adaptiveNavEnabled by uiPreferences.adaptiveNavEnabled().collectAsStatePref()
                    SwitchPreferenceWidget(
                        title = "Enable Adaptive Navigation",
                        subtitle = "Allow the app to suggest layout changes based on context.",
                        checked = adaptiveNavEnabled,
                        onCheckedChanged = { uiPreferences.adaptiveNavEnabled().set(it) },
                        icon = null
                    )
                    if (adaptiveNavEnabled) {
                        val connectivityRule by uiPreferences.adaptiveConnectivityRule().collectAsStatePref()
                        SwitchPreferenceWidget(
                            title = "Offline Suggestions",
                            subtitle = "Suggest offline layouts when internet connection is lost.",
                            checked = connectivityRule,
                            onCheckedChanged = { uiPreferences.adaptiveConnectivityRule().set(it) },
                            icon = null
                        )
                        val timeRule by uiPreferences.adaptiveTimeRule().collectAsStatePref()
                        SwitchPreferenceWidget(
                            title = "Late-Night Suggestions",
                            subtitle = "Simplify navigation during late hours.",
                            checked = timeRule,
                            onCheckedChanged = { uiPreferences.adaptiveTimeRule().set(it) },
                            icon = null
                        )
                        if (timeRule) {
                            val formatHour = { h: Int -> 
                                when (h) {
                                    0 -> "12 AM"
                                    12 -> "12 PM"
                                    in 1..11 -> "$h AM"
                                    else -> "${h - 12} PM"
                                }
                            }
                            val hoursMap = (0..23).associateWith { formatHour(it) }.toImmutableMap()
                            
                            val startHour by uiPreferences.adaptiveTimeRuleStart().collectAsStatePref()
                            ListPreferenceWidget(
                                value = startHour,
                                title = "Start time",
                                subtitle = formatHour(startHour),
                                icon = null,
                                entries = hoursMap,
                                onValueChange = { uiPreferences.adaptiveTimeRuleStart().set(it) }
                            )
                            val endHour by uiPreferences.adaptiveTimeRuleEnd().collectAsStatePref()
                            ListPreferenceWidget(
                                value = endHour,
                                title = "End time",
                                subtitle = formatHour(endHour),
                                icon = null,
                                entries = hoursMap,
                                onValueChange = { uiPreferences.adaptiveTimeRuleEnd().set(it) }
                            )
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    PreferenceGroupHeader(title = "Privacy & Diagnostics")
                    val telemetryEnabled by uiPreferences.adaptiveTelemetryEnabled().collectAsStatePref()
                    SwitchPreferenceWidget(
                        title = "Local Usage Logs",
                        subtitle = "Logs interactions locally for navigation optimization. Data never leaves your device.",
                        checked = telemetryEnabled,
                        onCheckedChanged = { uiPreferences.adaptiveTelemetryEnabled().set(it) },
                        icon = null
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    PreferenceGroupHeader(title = stringResource(MR.strings.pref_bottom_nav_visible_tabs))
                    if (visibleItems.isEmpty()) {
                        Text(
                            text = "No visible tabs",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                items(
                    items = visibleItems,
                    key = { i -> "visible-${i.id}" }
                ) { item ->
                    val isRequired = NavConfigValidator.REQUIRED_TABS.contains(item.id)
                    val index = visibleItems.indexOf(item)
                    ReorderableItem(state = reorderableState, key = "visible-${item.id}") { isDragging ->
                        val elevation = animateDpAsState(if (isDragging) 8.dp else 0.dp)
                        Surface(
                            shadowElevation = elevation.value,
                        ) {
                            NavigationSettingsItem(
                                item = item,
                                isVisible = true,
                                onToggle = { if (!isRequired) onMoveToHidden(item) },
                                onReorder = null,
                                canMoveUp = index > 0,
                                canMoveDown = index < visibleItems.size - 1,
                                onMoveUp = {
                                    val newList = visibleItems.toMutableList().apply {
                                        add(index - 1, removeAt(index))
                                    }
                                    uiPreferences.updateNavConfig(NavConfig(visibleTabs = newList.map { n -> n.id }.toImmutableList(), hiddenTabs = bottomNavHiddenTabs.toImmutableList(), behaviorMap = behaviorMap))
                                },
                                onMoveDown = {
                                    val newList = visibleItems.toMutableList().apply {
                                        add(index + 1, removeAt(index))
                                    }
                                    uiPreferences.updateNavConfig(NavConfig(visibleTabs = newList.map { n -> n.id }.toImmutableList(), hiddenTabs = bottomNavHiddenTabs.toImmutableList(), behaviorMap = behaviorMap))
                                },
                                toggleEnabled = !isRequired,
                                reorderableScope = this
                            )
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    PreferenceGroupHeader(title = stringResource(MR.strings.pref_bottom_nav_hidden_tabs))
                    if (hiddenItems.isEmpty()) {
                        Text(
                            text = "No hidden tabs",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                items(
                    items = hiddenItems,
                    key = { i -> "hidden-${i.id}" }
                ) { item ->
                    NavigationSettingsItem(
                        item = item,
                        isVisible = false,
                        onToggle = { onMoveToVisible(item) },
                        onReorder = null,
                        canMoveUp = false,
                        canMoveDown = false,
                        onMoveUp = {},
                        onMoveDown = {}
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(32.dp))
                    PreferenceGroupHeader(title = "Telemetry Debug (Dev Only)")
                }
                
                items(
                    items = NavActionExecutor.getHistory(),
                    key = { it.timestamp }
                ) { trace: ActionTrace ->
                    Text(
                        text = "[${trace.timestamp % 10000}] ${trace.tabId ?: "Global"} -> ${trace.actionName} (${trace.result})",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }

    @Composable
    private fun NavigationSettingsItem(
        item: NavItem,
        isVisible: Boolean,
        onToggle: () -> Unit,
        onReorder: ((Int, Int) -> Unit)?,
        canMoveUp: Boolean,
        canMoveDown: Boolean,
        onMoveUp: () -> Unit,
        onMoveDown: () -> Unit,
        toggleEnabled: Boolean = true,
        reorderableScope: ReorderableCollectionItemScope? = null,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (item.iconVector != null) {
                    Icon(
                        imageVector = item.iconVector!!,
                        contentDescription = null,
                        modifier = Modifier.padding(start = 8.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                } else {
                    val iconPainter = rememberTabIcon(item)
                    Icon(
                        painter = iconPainter,
                        contentDescription = null,
                        modifier = Modifier.padding(start = 8.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = stringResource(item.titleRes),
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 16.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                
                if (isVisible && reorderableScope != null) {
                    IconButton(
                        onClick = {},
                        modifier = with(reorderableScope) {
                            Modifier.draggableHandle(
                                onDragStarted = {
                                    Log.d("AniZenNav", "Drag started: ${item.id}")
                                },
                            )
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DragHandle,
                            contentDescription = "Reorder",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                IconButton(onClick = onToggle, enabled = toggleEnabled) {
                    Icon(
                        imageVector = if (isVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (isVisible) "Hide" else "Show",
                        tint = if (toggleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }

    @Composable
    private fun rememberTabIcon(item: NavItem): androidx.compose.ui.graphics.painter.Painter {
        return if (item == NavItem.FEED || item == NavItem.SCHEDULE) {
            painterResource(item.staticIconRes)
        } else {
            rememberAnimatedVectorPainter(
                AnimatedImageVector.animatedVectorResource(item.iconRes),
                false,
            )
        }
    }
}
