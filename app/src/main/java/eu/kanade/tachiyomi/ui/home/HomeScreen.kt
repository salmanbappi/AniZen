package eu.kanade.tachiyomi.ui.home

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.graphics.ExperimentalAnimationGraphicsApi
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastFilter
import androidx.compose.ui.util.fastForEach
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabNavigator
import eu.kanade.core.preference.asState
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.NavBehavior
import eu.kanade.domain.ui.model.NavLabelVisibility
import eu.kanade.domain.ui.model.NavItem
import eu.kanade.domain.ui.model.NavAction
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.util.Tab
import eu.kanade.presentation.util.isTabletUi
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.connections.discord.DiscordRPCService
import eu.kanade.tachiyomi.data.connections.discord.DiscordScreen
import eu.kanade.tachiyomi.ui.anime.AnimeScreen
import eu.kanade.tachiyomi.ui.browse.BrowseTab
import eu.kanade.tachiyomi.ui.download.DownloadQueueScreen
import eu.kanade.tachiyomi.ui.history.HistoryTab
import eu.kanade.tachiyomi.ui.library.LibraryTab
import eu.kanade.tachiyomi.ui.more.MoreTab
import eu.kanade.tachiyomi.ui.updates.UpdatesTab
import tachiyomi.presentation.core.i18n.stringResource
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import soup.compose.material.motion.animation.materialFadeThroughIn
import soup.compose.material.motion.animation.materialFadeThroughOut
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.NavigationBar
import tachiyomi.presentation.core.components.material.NavigationRail
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.util.collectAsState as collectAsStatePref
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

object HomeScreen : Screen() {

    private val librarySearchEvent = Channel<String>()
    private val openTabEvent = Channel<HomeTab>()
    private val showBottomNavEvent = Channel<Boolean>()

    private const val TAB_NAVIGATOR_KEY = "HomeTabs"

    private val uiPreferences: UiPreferences by injectLazy()
    private val defaultTab = uiPreferences.startScreen().get().tab.let { 
        if (it.isEnabled()) it else LibraryTab
    }

    @Composable
    override fun Content() {
        val navLabelVisibility by uiPreferences.navLabelVisibility().collectAsStatePref()
        val hideOnScroll by uiPreferences.hideBottomBarOnScroll().collectAsStatePref()
        val bottomNavTabs by uiPreferences.bottomNavTabs().collectAsStatePref()
        val animatedTransitions by uiPreferences.animatedTransitions().collectAsStatePref()
        val hazeEnabled by uiPreferences.hazeEnabled().collectAsStatePref()
        val tabFadeDuration = remember(animatedTransitions) { if (animatedTransitions) 200 else 0 }

        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val context = LocalContext.current

        val screenModel = rememberScreenModel { HomeScreenModel(context) }
        val adaptiveEngine = screenModel.adaptiveEngine
        val adaptiveDecision by adaptiveEngine.currentDecision.collectAsState()
        val updatesCount by screenModel.updatesCount.collectAsState()
        val extensionUpdatesCount by screenModel.extensionUpdatesCount.collectAsState()

        val activity = context as? ComponentActivity
        val preferences = Injekt.get<PreferenceStore>()

        var bottomNavVisible by rememberSaveable { mutableStateOf(true) }

        val nestedScrollConnection = remember(hideOnScroll) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (hideOnScroll && available.y < -10f && bottomNavVisible) {
                        bottomNavVisible = false
                    } else if (hideOnScroll && available.y > 10f && !bottomNavVisible) {
                        bottomNavVisible = true
                    }
                    return Offset.Zero
                }
            }
        }

        TabNavigator(
            tab = defaultTab,
            key = TAB_NAVIGATOR_KEY,
        ) { tabNavigator ->
            val visibleNavItems: List<NavItem> = remember(bottomNavTabs) {
                bottomNavTabs.mapNotNull { id -> NavItem.fromId(id) }.filter { it.tab.isEnabled() }
            }
            val isCurrentTabVisible = remember(visibleNavItems, tabNavigator.current) {
                visibleNavItems.any { it.tab::class == tabNavigator.current::class }
            }

            // Provide usable navigator to content screen
            CompositionLocalProvider(LocalNavigator provides navigator) {
                Scaffold(
                    modifier = Modifier.nestedScroll(nestedScrollConnection),
                    hazeEnabled = hazeEnabled,
                    startBar = {
                        if (isTabletUi()) {
                            NavigationRail(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            ) {
                                for (navItem in visibleNavItems) {
                                    key(navItem.id) {
                                        HomeNavigationRailItem(tabNavigator, navItem, navLabelVisibility, adaptiveDecision, updatesCount, extensionUpdatesCount)
                                    }
                                }
                            }
                        }
                    },
                    bottomBar = {
                        if (!isTabletUi()) {
                            LaunchedEffect(Unit) {
                                showBottomNavEvent.receiveAsFlow().collectLatest { bottomNavVisible = it }
                            }

                            AnimatedVisibility(
                                visible = bottomNavVisible && isCurrentTabVisible,
                                enter = slideInVertically { it } + expandVertically(),
                                exit = slideOutVertically { it } + shrinkVertically(),
                            ) {
                                NavigationBar(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                ) {
                                    for (navItem in visibleNavItems) {
                                        key(navItem.id) {
                                            HomeNavigationBarItem(this, tabNavigator, navItem, navLabelVisibility, adaptiveDecision, updatesCount, extensionUpdatesCount)
                                        }
                                    }
                                }
                            }
                        }
                    },
                    contentWindowInsets = WindowInsets(0),
                ) { contentPadding ->
                    Box(
                        modifier = Modifier
                            .padding(contentPadding)
                            .consumeWindowInsets(contentPadding)
                            .fillMaxSize(),
                    ) {
                        AnimatedContent(
                            targetState = tabNavigator.current,
                            transitionSpec = {
                                materialFadeThroughIn(
                                    durationMillis = tabFadeDuration,
                                ) togetherWith
                                    materialFadeThroughOut(
                                        durationMillis = tabFadeDuration,
                                    )
                            },
                            label = "tabContent",
                            contentKey = { it.key },
                        ) {
                            key(it.key) {
                                tabNavigator.saveableState(key = "currentTab", it) {
                                    it.Content()
                                }
                            }
                        }

                        // Explainability Layer (Smart Suggestions)
                        adaptiveDecision?.let { decision ->
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(16.dp)
                                    .fillMaxWidth(),
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                tonalElevation = 4.dp
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(text = "Smart Suggestion", style = MaterialTheme.typography.labelSmall)
                                        Text(text = decision.reason, style = MaterialTheme.typography.bodyMedium)
                                    }
                                    TextButton(
                                        onClick = { adaptiveEngine.dismissDecision() },
                                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
                                    ) {
                                        Text("Dismiss")
                                    }
                                    Button(
                                        onClick = { adaptiveEngine.applyDecision(decision) },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.primary,
                                            contentColor = MaterialTheme.colorScheme.onPrimary
                                        )
                                    ) {
                                        Text("Apply")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            val goToStartScreen = remember(tabNavigator, defaultTab) {
                { tabNavigator.current = defaultTab }
            }
            BackHandler(
                enabled = tabNavigator.current != defaultTab,
                onBack = goToStartScreen,
            )
            LaunchedEffect(Unit) {
                launch {
                    librarySearchEvent.receiveAsFlow().collectLatest {
                        goToStartScreen()
                        LibraryTab.search(it)
                    }
                }
                launch {
                    openTabEvent.receiveAsFlow().collectLatest {
                        tabNavigator.current = when (it) {
                            is HomeTab.AnimeLib -> LibraryTab
                            is HomeTab.Feed -> FeedTab
                            is HomeTab.Updates -> UpdatesTab
                            is HomeTab.History -> HistoryTab
                            is HomeTab.Browse -> {
                                if (it.toExtensions) {
                                    BrowseTab.showExtension()
                                }
                                BrowseTab
                            }
                            is HomeTab.More -> MoreTab
                        }

                        if (it is HomeTab.AnimeLib && it.animeIdToOpen != null) {
                            navigator.push(AnimeScreen(it.animeIdToOpen))
                        }
                        if (it is HomeTab.More && it.toDownloads) {
                            navigator.push(DownloadQueueScreen)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun RowScope.HomeNavigationBarItem(
        rowScope: RowScope,
        tabNavigator: TabNavigator,
        navItem: NavItem,
        navLabelVisibility: NavLabelVisibility,
        adaptiveDecision: AdaptiveDecision?,
        updatesCount: Int,
        extensionUpdatesCount: Int,
    ) {
        val tab = navItem.tab
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        val behaviorMap by uiPreferences.bottomNavBehaviors().collectAsStatePref()
        val behavior = remember(behaviorMap, navItem.id) { behaviorMap[navItem.id] ?: NavBehavior() }

        val selected = tabNavigator.current.key == tab.key
        val haptic = LocalHapticFeedback.current
        val executor = remember(context, scope, navigator) { NavActionExecutor(context, scope, navigator) }
        
        val title = stringResource(navItem.titleRes)

        val onClick: () -> Unit = remember(selected, navItem.id, tabNavigator, tab, scope, navigator, executor) {
            {
                if (!selected) {
                    executor.logClick(navItem.id)
                    tabNavigator.current = tab
                } else {
                    scope.launch { tab.onReselect(navigator) }
                }
                Unit
            }
        }

        val onLongClick: () -> Unit = remember(behavior, navItem.id, haptic, executor) {
            {
                if (behavior.onLongClick != NavAction.Default) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    executor.execute(behavior.onLongClick, navItem.id)
                }
            }
        }

        val onDoubleClick: () -> Unit = remember(behavior, navItem.id, haptic, executor) {
            {
                if (behavior.onDoubleTap != NavAction.Default) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    executor.execute(behavior.onDoubleTap, navItem.id)
                }
            }
        }

        val label: @Composable (() -> Unit)? = remember(navLabelVisibility, title) {
            if (navLabelVisibility != NavLabelVisibility.NEVER) {
                {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else null
        }

        with(rowScope) {
            NavigationBarItem(
                selected = selected,
                onClick = onClick,
                modifier = if (
                    behavior.onLongClick != NavAction.Default ||
                    behavior.onDoubleTap != NavAction.Default
                ) {
                    Modifier.combinedClickable(
                        onLongClick = onLongClick,
                        onDoubleClick = onDoubleClick,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
                icon = { NavigationIconItem(navItem, adaptiveDecision, updatesCount, extensionUpdatesCount) },
                label = label,
                alwaysShowLabel = navLabelVisibility == NavLabelVisibility.ALWAYS,
            )
        }
    }

    @Composable
    private fun HomeNavigationRailItem(
        tabNavigator: TabNavigator,
        navItem: NavItem,
        navLabelVisibility: NavLabelVisibility,
        adaptiveDecision: AdaptiveDecision?,
        updatesCount: Int,
        extensionUpdatesCount: Int,
    ) {
        val tab = navItem.tab
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        
        val behaviorMap by uiPreferences.bottomNavBehaviors().collectAsStatePref()
        val behavior = remember(behaviorMap, navItem.id) { behaviorMap[navItem.id] ?: NavBehavior() }

        val selected = tabNavigator.current.key == tab.key
        val haptic = LocalHapticFeedback.current
        val executor = remember(context, scope, navigator) { NavActionExecutor(context, scope, navigator) }

        val title = stringResource(navItem.titleRes)

        val onClick: () -> Unit = remember(selected, navItem.id, tabNavigator, tab, scope, navigator, executor) {
            {
                if (!selected) {
                    tabNavigator.current = tab
                } else {
                    scope.launch { tab.onReselect(navigator) }
                }
                Unit
            }
        }

        val onLongClick: () -> Unit = remember(behavior, navItem.id, haptic, executor) {
            {
                if (behavior.onLongClick != NavAction.Default) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    executor.execute(behavior.onLongClick, navItem.id)
                }
            }
        }

        val onDoubleClick: () -> Unit = remember(behavior, navItem.id, haptic, executor) {
            {
                if (behavior.onDoubleTap != NavAction.Default) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    executor.execute(behavior.onDoubleTap, navItem.id)
                }
            }
        }

        val combinedClick: () -> Unit = remember(selected, navItem.id, tabNavigator, tab, scope, navigator, executor) {
            {
                if (!selected) {
                    executor.logClick(navItem.id)
                    tabNavigator.current = tab
                } else {
                    scope.launch { tab.onReselect(navigator) }
                }
                Unit
            }
        }

        val label: @Composable (() -> Unit)? = remember(navLabelVisibility, title) {
            if (navLabelVisibility != NavLabelVisibility.NEVER) {
                {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else null
        }

        NavigationRailItem(
            selected = selected,
            onClick = onClick,
            modifier = if (
                behavior.onLongClick != NavAction.Default ||
                    behavior.onDoubleTap != NavAction.Default
            ) {
                Modifier.combinedClickable(
                    onLongClick = onLongClick,
                    onDoubleClick = onDoubleClick,
                    onClick = combinedClick,
                )
            } else {
                Modifier
            },
            icon = { NavigationIconItem(navItem, adaptiveDecision, updatesCount, extensionUpdatesCount) },
            label = label,
            alwaysShowLabel = navLabelVisibility == NavLabelVisibility.ALWAYS,
        )
    }

    @OptIn(ExperimentalAnimationGraphicsApi::class)
    @Composable
    private fun NavigationIconItem(
        navItem: NavItem,
        adaptiveDecision: AdaptiveDecision?,
        updatesCount: Int,
        extensionUpdatesCount: Int,
    ) {
        val tab = navItem.tab
        val tabNavigator = LocalTabNavigator.current
        val animatedTransitions by uiPreferences.animatedTransitions().collectAsStatePref()
        val selected = tabNavigator.current.key == tab.key
        val scale by animateFloatAsState(
            targetValue = if (selected && animatedTransitions) 1.15f else 1f,
            animationSpec = tween(
                durationMillis = 200,
                easing = androidx.compose.animation.core.FastOutSlowInEasing
            ),
            label = "iconScale",
        )

        BadgedBox(
            modifier = Modifier.graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
            badge = {
                when {
                    UpdatesTab::class.isInstance(tab) -> {
                        if (updatesCount > 0) {
                            Badge {
                                val desc = pluralStringResource(
                                    MR.plurals.notification_chapters_generic,
                                    count = updatesCount,
                                    updatesCount,
                                )
                                Text(
                                    text = if (updatesCount > 99) "99+" else updatesCount.toString(),
                                    modifier = Modifier.semantics { contentDescription = desc },
                                )
                            }
                        }
                    }
                    BrowseTab::class.isInstance(tab) -> {
                        if (extensionUpdatesCount > 0) {
                            Badge {
                                val desc = pluralStringResource(
                                    MR.plurals.update_check_notification_ext_updates,
                                    count = extensionUpdatesCount,
                                    extensionUpdatesCount,
                                )
                                Text(
                                    text = if (extensionUpdatesCount > 99) "99+" else extensionUpdatesCount.toString(),
                                    modifier = Modifier.semantics { contentDescription = desc },
                                )
                            }
                        }
                    }
                }
            },
        ) {
            val iconPainter = when {
                navItem.iconVector != null -> null
                LibraryTab::class.isInstance(tab) -> {
                    rememberAnimatedVectorPainter(
                        AnimatedImageVector.animatedVectorResource(R.drawable.anim_library_enter),
                        selected
                    )
                }
                UpdatesTab::class.isInstance(tab) -> {
                    rememberAnimatedVectorPainter(
                        AnimatedImageVector.animatedVectorResource(R.drawable.anim_updates_enter),
                        selected
                    )
                }
                HistoryTab::class.isInstance(tab) -> {
                    rememberAnimatedVectorPainter(
                        AnimatedImageVector.animatedVectorResource(R.drawable.anim_history_enter),
                        selected
                    )
                }
                BrowseTab::class.isInstance(tab) -> {
                    rememberAnimatedVectorPainter(
                        AnimatedImageVector.animatedVectorResource(R.drawable.anim_browse_enter),
                        selected
                    )
                }
                MoreTab::class.isInstance(tab) -> {
                    rememberAnimatedVectorPainter(
                        AnimatedImageVector.animatedVectorResource(R.drawable.anim_more_enter),
                        selected
                    )
                }
                FeedTab::class.isInstance(tab) -> {
                    painterResource(R.drawable.ic_dynamic_feed_24dp)
                }
                else -> painterResource(R.drawable.ic_browse_filled_24dp)
            }

            if (navItem.iconVector != null) {
                Icon(
                    imageVector = navItem.iconVector!!,
                    contentDescription = stringResource(navItem.titleRes),
                    tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else LocalContentColor.current,
                )
            } else {
                Icon(
                    painter = iconPainter!!,
                    contentDescription = stringResource(navItem.titleRes),
                    tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else LocalContentColor.current,
                )
            }
        }
    }

    suspend fun search(query: String) {
        librarySearchEvent.send(query)
    }

    suspend fun openTab(tab: HomeTab) {
        openTabEvent.send(tab)
    }

    suspend fun showBottomNav(show: Boolean) {
        showBottomNavEvent.send(show)
    }

    sealed interface HomeTab {
        data class AnimeLib(val animeIdToOpen: Long? = null) : HomeTab
        data object Feed : HomeTab
        data object Updates : HomeTab
        data object History : HomeTab
        data class Browse(val toExtensions: Boolean = false, val anime: Boolean = false) : HomeTab
        data class More(val toDownloads: Boolean) : HomeTab
    }
}
