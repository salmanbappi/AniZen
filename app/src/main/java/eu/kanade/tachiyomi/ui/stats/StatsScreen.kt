package eu.kanade.tachiyomi.ui.stats

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.stats.StatsScreenContent
import eu.kanade.presentation.more.stats.StatsScreenState
import eu.kanade.tachiyomi.network.model.ExtensionHealth
import eu.kanade.tachiyomi.ui.main.MainActivity
import kotlinx.coroutines.flow.collectLatest
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen

object StatsScreen : Screen {
    private fun readResolve(): Any = StatsScreen

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val animeScreenModel = rememberScreenModel { StatsScreenModel() }
        val state by animeScreenModel.state.collectAsState()

        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(MR.strings.label_stats),
                    navigateUp = navigator::pop,
                )
            },
            containerColor = MaterialTheme.colorScheme.background,
        ) { contentPadding ->
            if (state is StatsScreenState.Loading) {
                LoadingScreen()
            } else {
                StatsScreenContent(
                    state = state as StatsScreenState.SuccessAnime,
                    paddingValues = contentPadding,
                    onGenerateAiAnalysis = animeScreenModel::generateAiAnalysis,
                    onRegenerateAiAnalysis = animeScreenModel::regenerateAiAnalysis,
                    onClickExtensionReport = {
                        val report = (state as? StatsScreenState.SuccessAnime)?.infrastructure?.healthReport
                        if (report != null) {
                            navigator.push(ExtensionReportScreen(report))
                        }
                    },
                )
            }
        }

        LaunchedEffect(Unit) {
            (context as? MainActivity)?.ready = true
        }
    }
}

data class ExtensionReportScreen(
    private val healthReport: List<ExtensionHealth>,
) : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        Scaffold(
            topBar = {
                AppBar(
                    title = "Infrastructure Health Report",
                    navigateUp = navigator::pop,
                )
            },
            containerColor = MaterialTheme.colorScheme.background,
        ) { contentPadding ->
            eu.kanade.presentation.more.stats.ExtensionReportScreen(
                healthReport = healthReport,
                contentPadding = contentPadding,
            )
        }
    }
}

object InfrastructureScreen : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { InfrastructureScreenModel() }
        val state by screenModel.state.collectAsState()
        val isRefreshing by screenModel.isRefreshing.collectAsState()
        val snackbarHostState = remember { SnackbarHostState() }

        LaunchedEffect(Unit) {
            screenModel.events.collectLatest { event ->
                when (event) {
                    InfrastructureScreenModel.Event.ReportCopied -> {
                        snackbarHostState.showSnackbar("Report copied to clipboard")
                    }
                }
            }
        }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = "Extension Health",
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        ) { contentPadding ->
            eu.kanade.presentation.more.stats.InfrastructureScreen(
                state = state,
                isRefreshing = isRefreshing,
                onRefresh = screenModel::runDiagnostics,
                contentPadding = contentPadding,
                snackbarHostState = snackbarHostState,
            )
        }
    }
}
