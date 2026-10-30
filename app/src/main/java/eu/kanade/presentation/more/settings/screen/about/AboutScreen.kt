package eu.kanade.presentation.more.settings.screen.about

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.LogoHeader
import eu.kanade.presentation.more.components.MoreItem
import eu.kanade.presentation.more.components.MoreSection
import eu.kanade.presentation.util.LocalBackPress
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.updater.AppUpdateChecker
import eu.kanade.tachiyomi.ui.more.ComingUpdatesScreen
import eu.kanade.tachiyomi.ui.more.NewUpdateScreen
import eu.kanade.tachiyomi.ui.more.WhatsNewScreen
import eu.kanade.tachiyomi.util.CrashLogUtil
import eu.kanade.tachiyomi.util.lang.toDateTimestampString
import eu.kanade.tachiyomi.util.system.copyToClipboard
import eu.kanade.tachiyomi.util.system.isDebugBuildType
import eu.kanade.tachiyomi.util.system.isPreviewBuildType
import eu.kanade.tachiyomi.util.system.isReleaseBuildType
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.icons.CustomIcons
import tachiyomi.presentation.core.icons.Discord
import tachiyomi.presentation.core.icons.Github
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

object AboutScreen : Screen() {
    private fun readResolve(): Any = AboutScreen

    @Composable
    override fun Content() {
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        val uriHandler = LocalUriHandler.current
        val handleBack = LocalBackPress.current
        val navigator = LocalNavigator.currentOrThrow
        var isCheckingUpdates by remember { mutableStateOf(false) }

        // KMK -->
        var isCheckingWhatsNew by remember { mutableStateOf(false) }
        var isCheckingWhatsComing by remember { mutableStateOf(false) }
        // KMK <--

        // SY -->
        var showWhatsNewDialog by remember { mutableStateOf(false) }
        // SY <--

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.pref_category_about),
                    navigateUp = if (handleBack != null) handleBack::invoke else null,
                    scrollBehavior = scrollBehavior,
                )
            },
            containerColor = MaterialTheme.colorScheme.background,
        ) { contentPadding ->
            ScrollbarLazyColumn(
                contentPadding = PaddingValues(16.dp),
                modifier = Modifier.padding(top = contentPadding.calculateTopPadding()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    LogoHeader()
                }

                item {
                    MoreSection(title = "App Information") {
                        MoreItem(
                            title = stringResource(MR.strings.version),
                            subtitle = getVersionName(withBuildDate = true),
                            onClick = {
                                val deviceInfo = CrashLogUtil(context).getDebugInfo()
                                context.copyToClipboard("Debug information", deviceInfo)
                            },
                        )

                        MoreItem(
                            title = stringResource(MR.strings.check_for_updates),
                            subtitle = if (isCheckingUpdates) "Checking..." else null,
                            onClick = {
                                if (!isCheckingUpdates) {
                                    scope.launch {
                                        isCheckingUpdates = true

                                        checkVersion(
                                            context = context,
                                            onAvailableUpdate = { result ->
                                                val updateScreen = NewUpdateScreen(
                                                    versionName = result.release.version,
                                                    changelogInfo = result.release.info,
                                                    releaseLink = result.release.releaseLink,
                                                    downloadLink = result.release.getDownloadLink(),
                                                )
                                                navigator.push(updateScreen)
                                            },
                                            onFinish = {
                                                isCheckingUpdates = false
                                            },
                                        )
                                    }
                                }
                            },
                        )

                        // KMK -->
                        MoreItem(
                            title = stringResource(MR.strings.whats_new),
                            subtitle = if (isCheckingWhatsNew) "Checking..." else null,
                            onClick = {
                                if (!isCheckingWhatsNew) {
                                    scope.launch {
                                        isCheckingWhatsNew = true

                                        getReleaseNotes(
                                            context = context,
                                            onAvailableUpdate = { result ->
                                                val whatsNewScreen = WhatsNewScreen(
                                                    currentVersion = BuildConfig.VERSION_NAME,
                                                    versionName = result.release.version,
                                                    changelogInfo = result.release.info,
                                                    releaseLink = result.release.releaseLink,
                                                )
                                                navigator.push(whatsNewScreen)
                                            },
                                            onFinish = {
                                                isCheckingWhatsNew = false
                                            },
                                        )
                                    }
                                }
                            },
                        )

                        if (isReleaseBuildType || isDebugBuildType) {
                            MoreItem(
                                title = stringResource(KMR.strings.whats_coming),
                                subtitle = if (isCheckingWhatsComing) "Checking..." else null,
                                onClick = {
                                    if (!isCheckingWhatsComing) {
                                        scope.launch {
                                            isCheckingWhatsComing = true

                                            checkVersion(
                                                context = context,
                                                onAvailableUpdate = { result ->
                                                    val updateScreen = ComingUpdatesScreen(
                                                        versionName = result.release.version,
                                                        changelogInfo = result.release.info,
                                                        releaseLink = result.release.releaseLink,
                                                        downloadLink = result.release.getDownloadLink(),
                                                    )
                                                    navigator.push(updateScreen)
                                                },
                                                onFinish = {
                                                    isCheckingWhatsComing = false
                                                },
                                                peekIntoPreview = true,
                                            )
                                        }
                                    }
                                },
                            )
                        }
                        // KMK <--
                    }
                }

                item {
                    MoreSection(title = "Community") {
                        MoreItem(
                            title = stringResource(MR.strings.help_translate),
                            onClick = {
                                uriHandler.openUri(
                                    "https://crowdin.com/project/komikku/" +
                                        "invite?h=f922abd4193e77309b084a08c74b89872112170",
                                )
                            },
                        )

                        MoreItem(
                            title = stringResource(MR.strings.licenses),
                            onClick = { navigator.push(OpenSourceLicensesScreen()) },
                        )

                        MoreItem(
                            title = stringResource(MR.strings.privacy_policy),
                            onClick = { uriHandler.openUri("https://mihon.app/privacy/") },
                        )
                    }
                }

                item {
                    MoreSection(title = "Links") {
                        MoreItem(
                            title = "Discord",
                            subtitle = "Join our community",
                            icon = CustomIcons.Discord,
                            iconTint = Color(0xFF5865F2),
                            onClick = { uriHandler.openUri("https://discord.gg/J2wmZqEJnS") },
                        )
                        MoreItem(
                            title = "GitHub",
                            subtitle = "Source code",
                            icon = CustomIcons.Github,
                            iconTint = MaterialTheme.colorScheme.onSurface,
                            onClick = { uriHandler.openUri("https://github.com/salmanbappi/AniZen") },
                        )
                    }
                }
            }
        }

        // SY -->
        // KMK: Unused now
        if (showWhatsNewDialog) {
            WhatsNewDialog(onDismissRequest = { showWhatsNewDialog = false })
        }
        // SY <--
    }

    /**
     * Checks version and shows a user prompt if an update is available.
     */
    private suspend fun checkVersion(
        context: Context,
        onAvailableUpdate: (GetApplicationRelease.Result.NewUpdate) -> Unit,
        onFinish: () -> Unit,
        // KMK -->
        peekIntoPreview: Boolean = false,
        // KMK <--
    ) {
        val updateChecker = AppUpdateChecker(
            // KMK -->
            peekIntoPreview = peekIntoPreview,
            // KMK <--
        )
        withUIContext {
            try {
                when (val result = withIOContext { updateChecker.checkForUpdate(context, forceCheck = true) }) {
                    is GetApplicationRelease.Result.NewUpdate -> {
                        onAvailableUpdate(result)
                    }
                    is GetApplicationRelease.Result.NoNewUpdate -> {
                        context.toast(MR.strings.update_check_no_new_updates)
                    }
                    is GetApplicationRelease.Result.OsTooOld -> {
                        context.toast(MR.strings.update_check_eol)
                    }
                    else -> {}
                }
            } catch (e: Exception) {
                context.toast(e.message)
                logcat(LogPriority.ERROR, e)
            } finally {
                onFinish()
            }
        }
    }

    // KMK -->
    private suspend fun getReleaseNotes(
        context: Context,
        onAvailableUpdate: (GetApplicationRelease.Result.NewUpdate) -> Unit,
        onFinish: () -> Unit,
    ) {
        val updateChecker = AppUpdateChecker()
        withUIContext {
            try {
                when (val result = withIOContext { updateChecker.getReleaseNotes(context) }) {
                    is GetApplicationRelease.Result.NewUpdate -> {
                        onAvailableUpdate(result)
                    }
                    else -> {}
                }
            } catch (e: Exception) {
                context.toast(e.message)
                logcat(LogPriority.ERROR, e)
            } finally {
                onFinish()
            }
        }
    }
    // KMK <--

    fun getVersionName(withBuildDate: Boolean): String {
        return when {
            isDebugBuildType -> {
                "Debug ${BuildConfig.COMMIT_SHA}".let {
                    if (withBuildDate) {
                        "$it (${getFormattedBuildTime()})"
                    } else {
                        it
                    }
                }
            }
            isPreviewBuildType -> {
                "Beta r${BuildConfig.COMMIT_COUNT}".let {
                    if (withBuildDate) {
                        "$it (${BuildConfig.COMMIT_SHA}, ${getFormattedBuildTime()})"
                    } else {
                        "$it (${BuildConfig.COMMIT_SHA})"
                    }
                }
            }
            else -> {
                "Stable ${BuildConfig.VERSION_NAME}".let {
                    if (withBuildDate) {
                        "$it (${getFormattedBuildTime()})"
                    } else {
                        it
                    }
                }
            }
        }
    }

    internal fun getFormattedBuildTime(): String {
        return try {
            LocalDateTime.ofInstant(
                Instant.parse(BuildConfig.BUILD_TIME),
                ZoneId.systemDefault(),
            )
                .toDateTimestampString(
                    UiPreferences.dateFormat(
                        Injekt.get<UiPreferences>().dateFormat().get(),
                    ),
                )
        } catch (e: Exception) {
            BuildConfig.BUILD_TIME
        }
    }
}
