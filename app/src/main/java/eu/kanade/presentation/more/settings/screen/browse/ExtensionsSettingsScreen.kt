// ANZ -->
package eu.kanade.presentation.more.settings.screen.browse

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.ui.browse.extension.details.ExtensionDetailsScreen
import eu.kanade.presentation.browse.ExtensionScreen
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.ui.browse.extension.ExtensionsScreenModel
import eu.kanade.tachiyomi.util.system.openInBrowser
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState as collectAsStatePref
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class ExtensionsSettingsScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val screenModel = rememberScreenModel { ExtensionsScreenModel() }
        val state by screenModel.state.collectAsState()
        val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
        val uiPreferences = remember { Injekt.get<UiPreferences>() }
        val hazeEnabled by uiPreferences.hazeEnabled().collectAsStatePref()

        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(MR.strings.label_extensions),
                    navigateUp = navigator::pop,
                    actions = {
                        AppBarActions(
                            persistentListOf(
                                AppBar.Action(
                                    title = stringResource(MR.strings.label_extension_repos),
                                    icon = Icons.Outlined.Settings,
                                    onClick = { navigator.push(ExtensionReposScreen()) },
                                ),
                            ),
                        )
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
            hazeEnabled = hazeEnabled,
        ) { contentPadding ->
            ExtensionScreen(
                state = state,
                contentPadding = contentPadding,
                searchQuery = state.searchQuery,
                onLongClickItem = { extension ->
                    when (extension) {
                        is Extension.Available -> screenModel.installExtension(extension)
                        is Extension.Installed -> navigator.push(ExtensionDetailsScreen(extension.pkgName))
                        is Extension.Untrusted -> screenModel.uninstallExtension(extension)
                    }
                },
                onClickItemCancel = { extension ->
                    screenModel.cancelInstallUpdateExtension(extension)
                },
                onOpenWebView = { extension ->
                    extension.sources.firstOrNull()?.let { source ->
                        context.openInBrowser(source.baseUrl)
                    }
                },
                onInstallExtension = { extension ->
                    screenModel.installExtension(extension)
                },
                onUninstallExtension = { extension ->
                    screenModel.uninstallExtension(extension)
                },
                onUpdateExtension = { extension ->
                    screenModel.updateExtension(extension)
                },
                onTrustExtension = { extension ->
                    screenModel.trustExtension(extension)
                },
                onOpenExtension = { extension ->
                    navigator.push(ExtensionDetailsScreen(extension.pkgName))
                },
                onClickUpdateAll = {
                    screenModel.updateAllExtensions()
                },
                onRefresh = {
                    screenModel.findAvailableExtensions()
                },
            )
        }
    }
}
// ANZ <--
