package eu.kanade.tachiyomi.ui.browse.extension

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.browse.ExtensionScreen
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.NsfwIcon
import eu.kanade.presentation.components.TabContent
import eu.kanade.presentation.more.settings.screen.browse.ExtensionReposScreen
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.ui.browse.extension.details.ExtensionDetailsScreen
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import eu.kanade.tachiyomi.util.system.isPackageInstalled
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.map
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun extensionsTab(
    extensionsScreenModel: ExtensionsScreenModel,
): TabContent {
    val navigator = LocalNavigator.currentOrThrow
    val context = LocalContext.current

    val updates by extensionsScreenModel.state.map { it.updates }.collectAsStateWithLifecycle(0)
    val actionFilter = stringResource(MR.strings.action_filter)
    val labelExtensionRepos = stringResource(MR.strings.label_extension_repos)

    return remember(updates, actionFilter, labelExtensionRepos) {
        TabContent(
            titleRes = MR.strings.label_extensions,
            badgeNumber = updates.takeIf { it > 0 },
            searchEnabled = true,
            actions = persistentListOf(
                AppBar.Action(
                    title = "NSFW Only",
                    onClick = { extensionsScreenModel.toggleNsfwOnly() },
                    iconContent = {
                        val nsfwOnly by extensionsScreenModel.state.map { it.nsfwOnly }.collectAsStateWithLifecycle(false)
                        NsfwIcon(
                            color = if (nsfwOnly) MaterialTheme.colorScheme.primary else LocalContentColor.current
                        )
                    }
                ),
                AppBar.OverflowAction(
                    title = actionFilter,
                    onClick = {
                        navigator.push(
                            ExtensionFilterScreen(),
                        )
                    },
                ),
                AppBar.OverflowAction(
                    title = labelExtensionRepos,
                    onClick = { navigator.push(ExtensionReposScreen()) },
                ),
            ),
            content = { contentPadding, _ ->
                val state by extensionsScreenModel.state.collectAsStateWithLifecycle()
                var privateExtensionToUninstall by remember { mutableStateOf<Extension?>(null) }

                val onLongClickItem: (Extension) -> Unit = remember(context, extensionsScreenModel) {
                    { extension ->
                        when (extension) {
                            is Extension.Available -> extensionsScreenModel.installExtension(extension)
                            else -> {
                                if (context.isPackageInstalled(extension.pkgName)) {
                                    extensionsScreenModel.uninstallExtension(extension)
                                } else {
                                    privateExtensionToUninstall = extension
                                }
                            }
                        }
                    }
                }

                val onOpenWebView: (Extension.Available) -> Unit = remember(navigator) {
                    { extension ->
                        extension.sources.getOrNull(0)?.let {
                            navigator.push(
                                WebViewScreen(
                                    url = it.baseUrl,
                                    initialTitle = it.name,
                                    sourceId = it.id,
                                ),
                            )
                        }
                    }
                }

                val onOpenExtension: (Extension.Installed) -> Unit = remember(navigator) {
                    { navigator.push(ExtensionDetailsScreen(it.pkgName)) }
                }

                val onTrustExtension: (Extension.Untrusted) -> Unit = remember(extensionsScreenModel) {
                    { extensionsScreenModel.trustExtension(it) }
                }

                val onUninstallExtension: (Extension) -> Unit = remember(extensionsScreenModel) {
                    { extensionsScreenModel.uninstallExtension(it) }
                }

                ExtensionScreen(
                    state = state,
                    contentPadding = contentPadding,
                    searchQuery = state.searchQuery,
                    onLongClickItem = onLongClickItem,
                    onClickItemCancel = extensionsScreenModel::cancelInstallUpdateExtension,
                    onClickUpdateAll = extensionsScreenModel::updateAllExtensions,
                    onOpenWebView = onOpenWebView,
                    onInstallExtension = extensionsScreenModel::installExtension,
                    onOpenExtension = onOpenExtension,
                    onTrustExtension = onTrustExtension,
                    onUninstallExtension = onUninstallExtension,
                    onUpdateExtension = extensionsScreenModel::updateExtension,
                    onRefresh = extensionsScreenModel::findAvailableExtensions,
                )

                privateExtensionToUninstall?.let { extension ->
                    ExtensionUninstallConfirmation(
                        extensionName = extension.name,
                        onClickConfirm = {
                            extensionsScreenModel.uninstallExtension(extension)
                        },
                        onDismissRequest = {
                            privateExtensionToUninstall = null
                        },
                    )
                }
            },
        )
    }
}

@Composable
private fun ExtensionUninstallConfirmation(
    extensionName: String,
    onClickConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        title = {
            Text(text = stringResource(MR.strings.ext_confirm_remove))
        },
        text = {
            Text(text = stringResource(MR.strings.remove_private_extension_message, extensionName))
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onClickConfirm()
                    onDismissRequest()
                },
            ) {
                Text(text = stringResource(MR.strings.ext_remove))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        onDismissRequest = onDismissRequest,
    )
}
