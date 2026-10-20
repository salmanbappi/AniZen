package eu.kanade.tachiyomi.ui.home

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
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
import mihon.feature.airingschedule.AiringScheduleTab
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

        val behaviorMap by uiPreferences.bottomNavBehaviors().collectAsStatePref()
        val executor = remember(context, scope, navigator) { NavActionExecutor(context, scope, navigator) }

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
                    // ANZ --> the blur frosted the whole bottom slot as one grey rectangle: off here
                    hazeEnabled = false,
                    // ANZ <--
                    startBar = {
                        if (isTabletUi()) {
                            NavigationRail(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            ) {
                                for (navItem in visibleNavItems) {
                                    key(navItem.id) {
                                        HomeNavigationRailItem(tabNavigator, navItem, navLabelVisibility, adaptiveDecision, updatesCount, extensionUpdatesCount, behaviorMap, executor)
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
                                // ANZ -->
                                FloatingNavBar {
                                    for (navItem in visibleNavItems) {
                                        key(navItem.id) {
                                            HomeNavigationBarItem(this, tabNavigator, navItem, navLabelVisibility, adaptiveDecision, updatesCount, extensionUpdatesCount, behaviorMap, executor)
                                        }
                                    }
                                }
                                // ANZ <--
                            }
                        }
                    },
                    contentWindowInsets = WindowInsets(0),
                ) { contentPadding ->
                    // ANZ -->
                    // The library runs under the floating bar down to the very bottom edge (so there is no
                    // block behind the dock or the system buttons) and adds the bar height as its own bottom
                    // space; the other tabs keep the classic bottom padding.
                    val layoutDirection = LocalLayoutDirection.current
                    val barHeight = contentPadding.calculateBottomPadding()
                    // ANZ <--
                    Box(
                        modifier = Modifier
                            // ANZ -->
                            .padding(
                                start = contentPadding.calculateStartPadding(layoutDirection),
                                top = contentPadding.calculateTopPadding(),
                                end = contentPadding.calculateEndPadding(layoutDirection),
                            )
                            // ANZ <--
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
                                tabNavigator.saveableState(key = "tab-${it.key}", it) {
                                    // ANZ -->
                                    val underBar = it is LibraryTab
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(bottom = if (underBar) 0.dp else barHeight),
                                    ) {
                                        CompositionLocalProvider(
                                            LocalHomeBarInset provides (if (underBar) barHeight else 0.dp),
                                        ) {
                                            it.Content()
                                        }
                                        if (underBar) {
                                            // Soft fade of the page colour behind the icons and the system buttons:
                                            // no box, no blur, it just melts into the background.
                                            val fadeColor = MaterialTheme.colorScheme.background
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.BottomCenter)
                                                    .fillMaxWidth()
                                                    .height(barHeight + 44.dp)
                                                    .background(
                                                        Brush.verticalGradient(
                                                            0f to Color.Transparent,
                                                            0.5f to fadeColor.copy(alpha = 0.72f),
                                                            1f to fadeColor.copy(alpha = 0.96f),
                                                        ),
                                                    ),
                                            )
                                        }
                                    }
                                    // ANZ <--
                                }
                            }
                        }

                        // Explainability Layer (Smart Suggestions)
                        adaptiveDecision?.let { decision ->
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = barHeight)
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
                            is HomeTab.Schedule -> AiringScheduleTab
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
        behaviorMap: Map<String, NavBehavior>,
        executor: NavActionExecutor,
    ) {
        val tab = navItem.tab
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val behavior = remember(behaviorMap, navItem.id) { behaviorMap[navItem.id] ?: NavBehavior() }

        val selected = tabNavigator.current.key == tab.key
        val haptic = LocalHapticFeedback.current
        
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

        // ANZ -->
        with(rowScope) {
            FloatingNavItem(
                isSelected = selected,
                title = title,
                showLabel = navLabelVisibility != NavLabelVisibility.NEVER,
                onClick = onClick,
                onLongClick = if (behavior.onLongClick != NavAction.Default) onLongClick else null,
                onDoubleClick = if (behavior.onDoubleTap != NavAction.Default) onDoubleClick else null,
                icon = {
                    NavigationIconItem(
                        navItem,
                        adaptiveDecision,
                        updatesCount,
                        extensionUpdatesCount,
                        selectedTint = MaterialTheme.colorScheme.primary,
                    )
                },
            )
        }
        // ANZ <--
    }

    @Composable
    private fun HomeNavigationRailItem(
        tabNavigator: TabNavigator,
        navItem: NavItem,
        navLabelVisibility: NavLabelVisibility,
        adaptiveDecision: AdaptiveDecision?,
        updatesCount: Int,
        extensionUpdatesCount: Int,
        behaviorMap: Map<String, NavBehavior>,
        executor: NavActionExecutor,
    ) {
        val tab = navItem.tab
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val behavior = remember(behaviorMap, navItem.id) { behaviorMap[navItem.id] ?: NavBehavior() }

        val selected = tabNavigator.current.key == tab.key
        val haptic = LocalHapticFeedback.current

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
        selectedTint: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        val tab = navItem.tab
        val tabNavigator = LocalTabNavigator.current
        val animatedTransitions = remember { uiPreferences.animatedTransitions().get() }
        val selected = tabNavigator.current.key == tab.key
        val scaleState = animateFloatAsState(
            targetValue = if (selected && animatedTransitions) 1.15f else 1f,
            animationSpec = tween(
                durationMillis = 200,
                easing = androidx.compose.animation.core.FastOutSlowInEasing
            ),
            label = "iconScale",
        )

        BadgedBox(
            modifier = Modifier.graphicsLayer {
                scaleX = scaleState.value
                scaleY = scaleState.value
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
            val animResId = remember(tab::class) {
                when {
                    LibraryTab::class.isInstance(tab) -> R.drawable.anim_library_enter
                    UpdatesTab::class.isInstance(tab) -> R.drawable.anim_updates_enter
                    HistoryTab::class.isInstance(tab) -> R.drawable.anim_history_enter
                    BrowseTab::class.isInstance(tab) -> R.drawable.anim_browse_enter
                    MoreTab::class.isInstance(tab) -> R.drawable.anim_more_enter
                    else -> null
                }
            }
            val animVector = animResId?.let { AnimatedImageVector.animatedVectorResource(it) }
            val animatedPainter = animVector?.let { rememberAnimatedVectorPainter(it, selected) }
            val iconPainter = when {
                navItem.iconVector != null -> null
                animatedPainter != null -> animatedPainter
                FeedTab::class.isInstance(tab) -> painterResource(R.drawable.ic_dynamic_feed_24dp)
                AiringScheduleTab::class.isInstance(tab) -> painterResource(R.drawable.ic_progress_clock_24dp)
                else -> painterResource(R.drawable.ic_browse_filled_24dp)
            }

            if (navItem.iconVector != null) {
                Icon(
                    imageVector = navItem.iconVector!!,
                    contentDescription = stringResource(navItem.titleRes),
                    tint = if (selected) selectedTint else LocalContentColor.current,
                )
            } else {
                Icon(
                    painter = iconPainter!!,
                    contentDescription = stringResource(navItem.titleRes),
                    tint = if (selected) selectedTint else LocalContentColor.current,
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
        data object Schedule : HomeTab
        data class Browse(val toExtensions: Boolean = false, val anime: Boolean = false) : HomeTab
        data class More(val toDownloads: Boolean) : HomeTab
    }
}

// ANZ -->
/** Height of the floating bottom bar for the tabs that run underneath it (the library); 0 for the rest. */
val LocalHomeBarInset = compositionLocalOf { 0.dp }
// ANZ <--

// ANZ -->
/*
 * Floating bottom bar with no container at all: every tab is just an icon floating over the page, and
 * only the selected one gets a soft tinted pill that also shows its label.
 */
@Composable
private fun FloatingNavBar(content: @Composable RowScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(NavigationBarDefaults.windowInsets)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides colors.onSurfaceVariant) {
            Row(
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .selectableGroup(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun RowScope.FloatingNavItem(
    isSelected: Boolean,
    title: String,
    showLabel: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    onDoubleClick: (() -> Unit)?,
    icon: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f),
        label = "navPress",
    )
    val grow by animateFloatAsState(
        targetValue = if (isSelected && showLabel) 2.3f else 1f,
        animationSpec = spring(dampingRatio = 0.85f, stiffness = 400f),
        label = "navGrow",
    )
    val pill by animateColorAsState(
        targetValue = colors.primary.copy(alpha = if (isSelected) 0.18f else 0f),
        label = "navPill",
    )

    Row(
        modifier = Modifier
            .weight(grow.coerceAtLeast(0.1f))
            .height(48.dp)
            .graphicsLayer {
                scaleX = press
                scaleY = press
            }
            .clip(CircleShape)
            .background(pill)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
                onDoubleClick = onDoubleClick,
            )
            .semantics {
                role = Role.Tab
                selected = isSelected
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        icon()
        AnimatedVisibility(
            visible = isSelected && showLabel,
            enter = fadeIn() + expandHorizontally(),
            exit = fadeOut() + shrinkHorizontally(),
        ) {
            Text(
                text = title,
                modifier = Modifier.padding(start = 8.dp, end = 4.dp),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = colors.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
// ANZ <--
