package eu.kanade.presentation.more.settings.screen

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import dev.icerock.moko.resources.StringResource
import eu.kanade.domain.track.model.AutoTrackState
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.widget.BasePreferenceWidget
import eu.kanade.presentation.more.settings.widget.PrefsHorizontalPadding
import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.anilist.AnilistApi
import eu.kanade.tachiyomi.data.track.bangumi.BangumiApi
import eu.kanade.tachiyomi.data.track.myanimelist.MyAnimeListApi
import eu.kanade.tachiyomi.data.track.shikimori.ShikimoriApi
import eu.kanade.tachiyomi.data.track.simkl.SimklApi
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toPersistentMap
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object SettingsTrackingScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_tracking

    @Composable
    override fun RowScope.AppBarAction() {
        val uriHandler = LocalUriHandler.current
        IconButton(onClick = { uriHandler.openUri("https://aniyomi.org/help/guides/tracking/") }) {
            Icon(
                imageVector = Icons.Outlined.HelpOutline,
                contentDescription = stringResource(MR.strings.tracking_guide),
            )
        }
    }

    @Composable
    override fun getPreferences(): List<Preference> {
        val navigator = LocalNavigator.current
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val trackPreferences = remember { Injekt.get<TrackPreferences>() }
        val trackerManager = remember { Injekt.get<TrackerManager>() }
        val sourceManager = remember { Injekt.get<SourceManager>() }
        val autoTrackStatePref = trackPreferences.autoUpdateTrackOnMarkRead()
        val isAnilistLoggedIn by trackerManager.aniList.isLoggedInFlow.collectAsState(trackerManager.aniList.isLoggedIn)
        val isMyanimelistLoggedIn by trackerManager.myAnimeList.isLoggedInFlow.collectAsState(trackerManager.myAnimeList.isLoggedIn)
        val isSimklLoggedIn by trackerManager.simkl.isLoggedInFlow.collectAsState(trackerManager.simkl.isLoggedIn)
        val isTraktLoggedIn by trackerManager.trakt.isLoggedInFlow.collectAsState(trackerManager.trakt.isLoggedIn)

        var dialog by remember { mutableStateOf<Any?>(null) }
        dialog?.run {
            when (this) {
                is LoginDialog -> {
                    TrackingLoginDialog(
                        tracker = tracker,
                        uNameStringRes = uNameStringRes,
                        onDismissRequest = { dialog = null },
                    )
                }
                is LogoutDialog -> {
                    TrackingLogoutDialog(
                        tracker = tracker,
                        onDismissRequest = { dialog = null },
                    )
                }
                is ApiKeyDialog -> {
                    TrackingApiKeyDialog(
                        tracker = tracker,
                        onDismissRequest = { dialog = null },
                    )
                }
            }
        }

        val enhancedTrackers = trackerManager.trackers
            .filter { it is EnhancedTracker }
            .partition { service ->
                val acceptedAnimeSources = (service as EnhancedTracker).getAcceptedSources()
                sourceManager.getCatalogueSources().any { it::class.qualifiedName in acceptedAnimeSources }
            }

        var enhancedTrackerInfo = stringResource(MR.strings.enhanced_tracking_info)
        if (enhancedTrackers.second.isNotEmpty()) {
            val missingSourcesInfo = stringResource(
                MR.strings.enhanced_services_not_installed,
                enhancedTrackers.second.joinToString { it.name },
            )
            enhancedTrackerInfo += "\n\n$missingSourcesInfo"
        }

        return listOf(
            Preference.PreferenceItem.SwitchPreference(
                pref = trackPreferences.autoUpdateTrack(),
                title = stringResource(MR.strings.pref_auto_update_manga_sync),
            ),
            Preference.PreferenceItem.SwitchPreference(
                pref = trackPreferences.autoSyncFromTrackers(),
                title = stringResource(MR.strings.pref_auto_sync_from_trackers),
            ),
            Preference.PreferenceItem.SwitchPreference(
                pref = trackPreferences.trackOnAddingToLibrary(),
                title = stringResource(MR.strings.pref_track_on_add_library),
            ),
            Preference.PreferenceItem.SwitchPreference(
                pref = trackPreferences.showNextEpisodeAiringTime(),
                title = stringResource(MR.strings.pref_show_next_episode_airing_time),
            ),
            Preference.PreferenceItem.ListPreference(
                pref = trackPreferences.autoUpdateTrackOnMarkRead(),
                title = stringResource(MR.strings.pref_auto_update_manga_on_mark_read),
                entries = AutoTrackState.entries
                    .associateWith { stringResource(it.titleRes) }
                    .toPersistentMap(),
            ),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.services),
                preferenceItems = (
                    listOf(
                        Preference.PreferenceItem.TrackerPreference(
                            title = trackerManager.myAnimeList.name,
                            tracker = trackerManager.myAnimeList,
                            login = {
                                context.openInBrowser(
                                    MyAnimeListApi.authUrl(),
                                    forceDefaultBrowser = true,
                                )
                            },
                            logout = { dialog = LogoutDialog(trackerManager.myAnimeList) },
                        ),
                    ) + (
                        if (isMyanimelistLoggedIn) {
                            listOf(
                                Preference.PreferenceItem.CustomPreference(
                                    title = stringResource(MR.strings.pref_import_from_myanimelist),
                                ) {
                                    BasePreferenceWidget(
                                        subcomponent = {
                                            OutlinedButton(
                                                onClick = {
                                                    navigator?.push(eu.kanade.tachiyomi.ui.trackerimport.TrackerImportScreen(1L))
                                                },
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = PrefsHorizontalPadding),
                                                shape = CircleShape,
                                                border = BorderStroke(
                                                    width = 1.dp,
                                                    color = MaterialTheme.colorScheme.primary,
                                                ),
                                                colors = ButtonDefaults.outlinedButtonColors(
                                                    contentColor = MaterialTheme.colorScheme.primary,
                                                ),
                                            ) {
                                                Text(
                                                    text = stringResource(MR.strings.pref_import_from_myanimelist),
                                                    style = MaterialTheme.typography.bodyLarge,
                                                )
                                            }
                                        },
                                    )
                                },
                            )
                        } else {
                            emptyList()
                        }
                        ) + listOf(
                        Preference.PreferenceItem.TrackerPreference(
                            title = trackerManager.aniList.name,
                            tracker = trackerManager.aniList,
                            login = {
                                context.openInBrowser(
                                    AnilistApi.authUrl(),
                                    forceDefaultBrowser = true,
                                )
                            },
                            logout = { dialog = LogoutDialog(trackerManager.aniList) },
                        ),
                    ) + (
                        if (isAnilistLoggedIn) {
                            listOf(
                                Preference.PreferenceItem.CustomPreference(
                                    title = stringResource(MR.strings.pref_import_from_anilist),
                                ) {
                                    BasePreferenceWidget(
                                        subcomponent = {
                                            OutlinedButton(
                                                onClick = {
                                                    navigator?.push(eu.kanade.tachiyomi.ui.trackerimport.TrackerImportScreen(trackerManager.aniList.id))
                                                },
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = PrefsHorizontalPadding),
                                                shape = CircleShape,
                                                border = BorderStroke(
                                                    width = 1.dp,
                                                    color = MaterialTheme.colorScheme.primary,
                                                ),
                                                colors = ButtonDefaults.outlinedButtonColors(
                                                    contentColor = MaterialTheme.colorScheme.primary,
                                                ),
                                            ) {
                                                Text(
                                                    text = stringResource(MR.strings.pref_import_from_anilist),
                                                    style = MaterialTheme.typography.bodyLarge,
                                                )
                                            }
                                        },
                                    )
                                },
                            )
                        } else {
                            emptyList()
                        }
                        ) + listOf(
                        Preference.PreferenceItem.TrackerPreference(
                            title = trackerManager.kitsu.name,
                            tracker = trackerManager.kitsu,
                            login = { dialog = LoginDialog(trackerManager.kitsu, MR.strings.email) },
                            logout = { dialog = LogoutDialog(trackerManager.kitsu) },
                        ),
                        Preference.PreferenceItem.TrackerPreference(
                            title = trackerManager.shikimori.name,
                            tracker = trackerManager.shikimori,
                            login = {
                                context.openInBrowser(
                                    ShikimoriApi.authUrl(),
                                    forceDefaultBrowser = true,
                                )
                            },
                            logout = { dialog = LogoutDialog(trackerManager.shikimori) },
                        ),
                        Preference.PreferenceItem.TrackerPreference(
                            title = trackerManager.simkl.name,
                            tracker = trackerManager.simkl,
                            login = {
                                context.openInBrowser(
                                    SimklApi.authUrl(),
                                    forceDefaultBrowser = true,
                                )
                            },
                            logout = { dialog = LogoutDialog(trackerManager.simkl) },
                        ),
                    ) + (
                        if (isSimklLoggedIn) {
                            listOf(
                                Preference.PreferenceItem.CustomPreference(
                                    title = stringResource(MR.strings.pref_import_from_simkl),
                                ) {
                                    BasePreferenceWidget(
                                        subcomponent = {
                                            OutlinedButton(
                                                onClick = {
                                                    navigator?.push(eu.kanade.tachiyomi.ui.trackerimport.TrackerImportScreen(trackerManager.simkl.id))
                                                },
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = PrefsHorizontalPadding),
                                                shape = CircleShape,
                                                border = BorderStroke(
                                                    width = 1.dp,
                                                    color = MaterialTheme.colorScheme.primary,
                                                ),
                                                colors = ButtonDefaults.outlinedButtonColors(
                                                    contentColor = MaterialTheme.colorScheme.primary,
                                                ),
                                            ) {
                                                Text(
                                                    text = stringResource(MR.strings.pref_import_from_simkl),
                                                    style = MaterialTheme.typography.bodyLarge,
                                                )
                                            }
                                        },
                                    )
                                },
                            )
                        } else {
                            emptyList()
                        }
                        ) + listOf(
                        Preference.PreferenceItem.TrackerPreference(
                            title = trackerManager.trakt.name,
                            tracker = trackerManager.trakt,
                            login = {
                                context.openInBrowser(
                                    eu.kanade.tachiyomi.data.track.trakt.TraktApi.authUrl(),
                                    forceDefaultBrowser = true,
                                )
                            },
                            logout = { dialog = LogoutDialog(trackerManager.trakt) },
                        ),
                    ) + (
                        if (isTraktLoggedIn) {
                            listOf(
                                Preference.PreferenceItem.CustomPreference(
                                    title = stringResource(MR.strings.pref_import_from_trakt),
                                ) {
                                    BasePreferenceWidget(
                                        subcomponent = {
                                            OutlinedButton(
                                                onClick = {
                                                    navigator?.push(eu.kanade.tachiyomi.ui.trackerimport.TrackerImportScreen(trackerManager.trakt.id))
                                                },
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = PrefsHorizontalPadding),
                                                shape = CircleShape,
                                                border = BorderStroke(
                                                    width = 1.dp,
                                                    color = MaterialTheme.colorScheme.primary,
                                                ),
                                                colors = ButtonDefaults.outlinedButtonColors(
                                                    contentColor = MaterialTheme.colorScheme.primary,
                                                ),
                                            ) {
                                                Text(
                                                    text = stringResource(MR.strings.pref_import_from_trakt),
                                                    style = MaterialTheme.typography.bodyLarge,
                                                )
                                            }
                                        },
                                    )
                                },
                            )
                        } else {
                            emptyList()
                        }
                        ) + listOf(
                        Preference.PreferenceItem.TrackerPreference(
                            title = trackerManager.tmdb.name,
                            tracker = trackerManager.tmdb,
                            login = {
                                val currentApiKey = trackPreferences.trackApiKey(trackerManager.tmdb).get()
                                if (currentApiKey.isBlank()) {
                                    dialog = ApiKeyDialog(trackerManager.tmdb)
                                } else {
                                    scope.launchIO {
                                        try {
                                            val authUrl = trackerManager.tmdb.getAuthUrl()
                                            context.openInBrowser(
                                                authUrl,
                                                forceDefaultBrowser = true,
                                            )
                                        } catch (e: Exception) {
                                            withUIContext {
                                                context.toast(e.message ?: "Error logging in")
                                            }
                                        }
                                    }
                                }
                            },
                            logout = { dialog = LogoutDialog(trackerManager.tmdb) },
                        ),
                        Preference.PreferenceItem.TrackerPreference(
                            title = trackerManager.bangumi.name,
                            tracker = trackerManager.bangumi,
                            login = {
                                context.openInBrowser(
                                    BangumiApi.authUrl(),
                                    forceDefaultBrowser = true,
                                )
                            },
                            logout = { dialog = LogoutDialog(trackerManager.bangumi) },
                        ),
                        Preference.PreferenceItem.InfoPreference(stringResource(MR.strings.tracking_info)),
                    )
                    ).toImmutableList(),
            ),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.enhanced_services),
                preferenceItems =
                (
                    enhancedTrackers.first
                        .map { service ->
                            Preference.PreferenceItem.TrackerPreference(
                                title = service.name,
                                tracker = service,
                                login = { (service as EnhancedTracker).loginNoop() },
                                logout = service::logout,
                            )
                        } + listOf(Preference.PreferenceItem.InfoPreference(enhancedTrackerInfo))
                    ).toImmutableList(),
            ),
        )
    }

    @Composable
    private fun TrackingLoginDialog(
        tracker: Tracker,
        uNameStringRes: StringResource,
        onDismissRequest: () -> Unit,
    ) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        var username by remember { mutableStateOf(TextFieldValue(tracker.getUsername())) }
        var password by remember { mutableStateOf(TextFieldValue(tracker.getPassword())) }
        var processing by remember { mutableStateOf(false) }
        var inputError by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = onDismissRequest,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(MR.strings.login_title, tracker.name),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismissRequest) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = stringResource(MR.strings.action_close),
                        )
                    }
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = username,
                        onValueChange = { username = it },
                        label = { Text(text = stringResource(uNameStringRes)) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        singleLine = true,
                        isError = inputError && !processing,
                    )

                    var hidePassword by remember { mutableStateOf(true) }
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(text = stringResource(MR.strings.password)) },
                        trailingIcon = {
                            IconButton(onClick = { hidePassword = !hidePassword }) {
                                Icon(
                                    imageVector = if (hidePassword) {
                                        Icons.Filled.Visibility
                                    } else {
                                        Icons.Filled.VisibilityOff
                                    },
                                    contentDescription = null,
                                )
                            }
                        },
                        visualTransformation = if (hidePassword) {
                            PasswordVisualTransformation()
                        } else {
                            VisualTransformation.None
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        singleLine = true,
                        isError = inputError && !processing,
                    )
                }
            },
            confirmButton = {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !processing && username.text.isNotBlank() && password.text.isNotBlank(),
                    onClick = {
                        scope.launchIO {
                            processing = true
                            val result = checkLogin(
                                context = context,
                                tracker = tracker,
                                username = username.text,
                                password = password.text,
                            )
                            inputError = !result
                            if (result) onDismissRequest()
                            processing = false
                        }
                    },
                ) {
                    val id = if (processing) MR.strings.loading else MR.strings.login
                    Text(text = stringResource(id))
                }
            },
        )
    }

    private suspend fun checkLogin(
        context: Context,
        tracker: Tracker,
        username: String,
        password: String,
    ): Boolean {
        return try {
            tracker.login(username, password)
            withUIContext { context.toast(MR.strings.login_success) }
            true
        } catch (e: Throwable) {
            tracker.logout()
            withUIContext { context.toast(e.message.toString()) }
            false
        }
    }

    @Composable
    private fun TrackingLogoutDialog(
        tracker: Tracker,
        onDismissRequest: () -> Unit,
    ) {
        val context = LocalContext.current
        AlertDialog(
            onDismissRequest = onDismissRequest,
            title = {
                Text(
                    text = stringResource(MR.strings.logout_title, tracker.name),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall)) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = onDismissRequest,
                    ) {
                        Text(text = stringResource(MR.strings.action_cancel))
                    }
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            tracker.logout()
                            onDismissRequest()
                            context.toast(MR.strings.logout_success)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) {
                        Text(text = stringResource(MR.strings.logout))
                    }
                }
            },
        )
    }
}

private data class LoginDialog(
    val tracker: Tracker,
    val uNameStringRes: StringResource,
)

private data class LogoutDialog(
    val tracker: Tracker,
)

private data class ApiKeyDialog(
    val tracker: Tracker,
)

@Composable
private fun TrackingApiKeyDialog(
    tracker: Tracker,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val trackPreferences = remember { Injekt.get<TrackPreferences>() }
    val networkHelper = remember { Injekt.get<eu.kanade.tachiyomi.network.NetworkHelper>() }
    val scope = rememberCoroutineScope()

    var apiKey by remember { mutableStateOf(TextFieldValue(trackPreferences.trackApiKey(tracker).get())) }
    var processing by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "TMDB API Key",
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismissRequest) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(MR.strings.action_close),
                    )
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text(text = "API Key") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !processing && apiKey.text.isNotBlank(),
                onClick = {
                    scope.launchIO {
                        processing = true
                        try {
                            // Validate API key by requesting /3/configuration
                            val url = "https://api.themoviedb.org/3/configuration?api_key=${apiKey.text}"
                            val req = okhttp3.Request.Builder().url(url).get().build()
                            val resp = networkHelper.client.newCall(req).execute()
                            val ok = try {
                                resp.use { r -> r.isSuccessful && r.body.string().contains("images") }
                            } catch (_: Exception) {
                                false
                            }

                            if (ok) {
                                trackPreferences.trackApiKey(tracker).set(apiKey.text)
                                withUIContext {
                                    onDismissRequest()
                                    context.toast(MR.strings.login_success)
                                }
                            } else {
                                withUIContext { context.toast("Invalid TMDB API Key") }
                            }
                        } catch (_: Exception) {
                            withUIContext { context.toast("Error validating key") }
                        } finally {
                            processing = false
                        }
                    }
                },
            ) {
                val label = if (processing) "Saving..." else "Save"
                Text(text = label)
            }
        },
    )
}
