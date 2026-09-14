package eu.kanade.presentation.more.settings.screen

import android.app.Activity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.ui.ContainerStyle
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.domain.ui.model.StartScreen
import eu.kanade.domain.ui.model.TabletUiMode
import eu.kanade.domain.ui.model.ThemeMode
import eu.kanade.domain.ui.model.setAppCompatDelegateThemeMode
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.screen.appearance.AppCustomThemeColorPickerScreen
import eu.kanade.presentation.more.settings.screen.appearance.AppLanguageScreen
import eu.kanade.presentation.more.settings.widget.AppThemeModePreferenceWidget
import eu.kanade.presentation.more.settings.widget.AppThemePreferenceWidget
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableMap
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.LocalDate

object SettingsAppearanceScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_appearance

    @Composable
    override fun getPreferences(): List<Preference> {
        val uiPreferences = remember { Injekt.get<UiPreferences>() }

        return listOf(
            getThemeGroup(uiPreferences = uiPreferences),
            getLayoutNavigationGroup(uiPreferences = uiPreferences),
            getVisualCustomizationGroup(uiPreferences = uiPreferences),
        )
    }

    @Composable
    @Suppress("SpreadOperator")
    private fun getThemeGroup(
        uiPreferences: UiPreferences,
    ): Preference.PreferenceGroup {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow

        val themeModePref = uiPreferences.themeMode()
        val themeMode by themeModePref.collectAsState()

        val appThemePref = uiPreferences.appTheme()
        val appTheme by appThemePref.collectAsState()

        val amoledPref = uiPreferences.themeDarkAmoled()
        val amoled by amoledPref.collectAsState()

        val customPreferenceItem = if (appTheme == AppTheme.CUSTOM) {
            listOf(
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_custom_color),
                    subtitle = stringResource(MR.strings.custom_color_description),
                    onClick = { navigator.push(AppCustomThemeColorPickerScreen()) },
                ),
            )
        } else {
            emptyList()
        }

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_theme),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.CustomPreference(
                    title = stringResource(MR.strings.pref_app_theme),
                ) {
                    Column {
                        AppThemeModePreferenceWidget(
                            value = themeMode,
                            onItemClick = {
                                themeModePref.set(it)
                                setAppCompatDelegateThemeMode(it)
                            },
                        )

                        AppThemePreferenceWidget(
                            value = appTheme,
                            amoled = amoled,
                            onItemClick = { appThemePref.set(it) },
                        )
                    }
                },
                *customPreferenceItem.toTypedArray(),
                Preference.PreferenceItem.SwitchPreference(
                    pref = amoledPref,
                    title = stringResource(MR.strings.pref_dark_theme_pure_black),
                    enabled = themeMode != ThemeMode.LIGHT,
                    onValueChanged = {
                        (context as? Activity)?.let { ActivityCompat.recreate(it) }
                        true
                    },
                ),
            ),
        )
    }

    @Composable
    private fun getLayoutNavigationGroup(
        uiPreferences: UiPreferences,
    ): Preference.PreferenceGroup {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow

        val now = remember { LocalDate.now() }

        val dateFormat by uiPreferences.dateFormat().collectAsState()
        val formattedNow = remember(dateFormat) {
            UiPreferences.dateFormat(dateFormat).format(now)
        }

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_layout_navigation),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_app_language),
                    onClick = { navigator.push(AppLanguageScreen()) },
                ),
                Preference.PreferenceItem.ListPreference(
                    pref = uiPreferences.tabletUiMode(),
                    title = stringResource(MR.strings.pref_tablet_ui_mode),
                    entries = TabletUiMode.entries
                        .associateWith { stringResource(it.titleRes) }
                        .toImmutableMap(),
                    onValueChanged = {
                        context.toast(MR.strings.requires_app_restart)
                        true
                    },
                ),
                Preference.PreferenceItem.ListPreference(
                    pref = uiPreferences.startScreen(),
                    title = stringResource(MR.strings.pref_start_screen),
                    entries = StartScreen.entries
                        .associateWith { it.titleRes }
                        .mapValues { stringResource(it.value) }
                        .toImmutableMap(),
                    onValueChanged = {
                        context.toast(MR.strings.requires_app_restart)
                        true
                    },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_bottom_nav_settings),
                    onClick = { navigator.push(NavigationSettingsScreen(null)) },
                ),
                Preference.PreferenceItem.ListPreference(
                    pref = uiPreferences.dateFormat(),
                    title = stringResource(MR.strings.pref_date_format),
                    entries = DateFormats
                        .associateWith {
                            val formattedDate = UiPreferences.dateFormat(it).format(now)
                            "${it.ifEmpty { stringResource(MR.strings.label_default) }} ($formattedDate)"
                        }
                        .toImmutableMap(),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    pref = uiPreferences.relativeTime(),
                    title = stringResource(MR.strings.pref_relative_format),
                    subtitle = stringResource(
                        MR.strings.pref_relative_format_summary,
                        stringResource(MR.strings.relative_time_today),
                        formattedNow,
                    ),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    pref = uiPreferences.animatedTransitions(),
                    title = stringResource(MR.strings.pref_animated_transitions),
                    subtitle = stringResource(MR.strings.pref_animated_transitions_summary),
                ),
            ),
        )
    }

    @Composable
    private fun getVisualCustomizationGroup(
        uiPreferences: UiPreferences,
    ): Preference.PreferenceGroup {
        val dynamicAnimeTheme by uiPreferences.dynamicAnimeTheme().collectAsState()

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_visual_customization),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.SwitchPreference(
                    pref = uiPreferences.dynamicAnimeTheme(),
                    title = "Dynamic Anime Theme",
                    subtitle = "Adapts app colors to the current anime cover",
                ),
                Preference.PreferenceItem.SwitchPreference(
                    pref = uiPreferences.dynamicPlayerTheme(),
                    title = "Dynamic Player Theme",
                    subtitle = "Adapts player colors to the current anime cover",
                    enabled = dynamicAnimeTheme,
                ),
                Preference.PreferenceItem.SwitchPreference(
                    pref = uiPreferences.panoramaCover(),
                    title = stringResource(MR.strings.pref_panorama_cover),
                    subtitle = stringResource(MR.strings.pref_panorama_cover_summary),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    pref = uiPreferences.autoExpandAnimeDescription(),
                    title = "Auto-expand details",
                    subtitle = "Expand anime description by default",
                ),
                Preference.PreferenceItem.SwitchPreference(
                    pref = uiPreferences.hazeEnabled(),
                    title = "Glassmorphism (Haze)",
                    subtitle = "Applies blur effect to Top Bar and Bottom Bar (Can cause lag)",
                ),
                Preference.PreferenceItem.MultiSelectListPreference(
                    pref = uiPreferences.containerStyles(),
                    title = "Container Style",
                    subtitle = "Enable rounded containers for selected screens",
                    entries = mapOf(
                        ContainerStyle.LIBRARY to "Library",
                        ContainerStyle.UPDATES to "Updates",
                        ContainerStyle.HISTORY to "History",
                        ContainerStyle.SETTINGS to "Settings",
                        ContainerStyle.BROWSE to "Browse (Sources/Extensions)",
                    ).toImmutableMap(),
                ),
            ),
        )
    }
}

private val DateFormats = listOf(
    "", // Default
    "MM/dd/yy",
    "dd/MM/yy",
    "yyyy-MM-dd",
    "dd MMM yyyy",
    "MMM dd, yyyy",
)
