package eu.kanade.presentation.anime

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PeopleAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.domain.anime.model.downloadedFilter
import eu.kanade.domain.anime.model.forceDownloaded
import eu.kanade.presentation.components.TabbedDialog
import eu.kanade.presentation.components.TabbedDialogPaddings
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.anime.model.Anime
import tachiyomi.i18n.MR
import tachiyomi.i18n.ank.AMR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.LabeledCheckbox
import tachiyomi.presentation.core.components.RadioItem
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.components.SortItem
import tachiyomi.presentation.core.components.TriStateItem
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.theme.active
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Locale

@Composable
fun EpisodeSettingsDialog(
    onDismissRequest: () -> Unit,
    anime: Anime? = null,
    onDownloadFilterChanged: (TriState) -> Unit,
    onUnseenFilterChanged: (TriState) -> Unit,
    onBookmarkedFilterChanged: (TriState) -> Unit,
    // AM (FILLERMARK) -->
    onFillermarkedFilterChanged: (TriState) -> Unit,
    // <-- AM (FILLERMARK)
    onSortModeChanged: (Long) -> Unit,
    onDisplayModeChanged: (Long) -> Unit,
    onShowPreviewsEnabled: (Long) -> Unit,
    onShowSummariesEnabled: (Long) -> Unit,
    // ANZ -->
    onSetThumbnailSize: (Int) -> Unit = {}, // ANZ
    // ANZ <--
    onSetAsDefault: (applyToExistingAnime: Boolean) -> Unit,
    scanlatorFilterActive: Boolean,
    onScanlatorFilterClicked: () -> Unit,
) {
    var showSetAsDefaultDialog by rememberSaveable { mutableStateOf(false) }
    if (showSetAsDefaultDialog) {
        SetAsDefaultDialog(
            onDismissRequest = { showSetAsDefaultDialog = false },
            onConfirmed = onSetAsDefault,
        )
    }

    TabbedDialog(
        onDismissRequest = onDismissRequest,
        tabTitles = persistentListOf(
            stringResource(MR.strings.action_filter),
            stringResource(MR.strings.action_sort),
            stringResource(MR.strings.action_display),
        ),
        tabOverflowMenuContent = { closeMenu ->
            DropdownMenuItem(
                text = { Text(stringResource(MR.strings.set_chapter_settings_as_default)) },
                onClick = {
                    showSetAsDefaultDialog = true
                    closeMenu()
                },
            )
        },
    ) { page ->
        Column(
            modifier = Modifier
                .padding(vertical = TabbedDialogPaddings.Vertical)
                .verticalScroll(rememberScrollState()),
        ) {
            when (page) {
                0 -> {
                    FilterPage(
                        downloadFilter = anime?.downloadedFilter ?: TriState.DISABLED,
                        onDownloadFilterChanged = onDownloadFilterChanged
                            .takeUnless { anime?.forceDownloaded() == true },
                        unseenFilter = anime?.unseenFilter ?: TriState.DISABLED,
                        onUnseenFilterChanged = onUnseenFilterChanged,
                        bookmarkedFilter = anime?.bookmarkedFilter ?: TriState.DISABLED,
                        onBookmarkedFilterChanged = onBookmarkedFilterChanged,
                        // AM (FILLERMARK) -->
                        fillermarkedFilter = anime?.fillermarkedFilter ?: TriState.DISABLED,
                        onFillermarkedFilterChanged = onFillermarkedFilterChanged,
                        // <-- AM (FILLERMARK)
                        scanlatorFilterActive = scanlatorFilterActive,
                        onScanlatorFilterClicked = onScanlatorFilterClicked,
                    )
                }
                1 -> {
                    SortPage(
                        sortingMode = anime?.sorting ?: 0,
                        sortDescending = anime?.sortDescending() ?: false,
                        onItemSelected = onSortModeChanged,
                    )
                }
                2 -> {
                    DisplayPage(
                        anime = anime,
                        displayMode = anime?.displayMode ?: Anime.EPISODE_DISPLAY_NAME,
                        onDisplayModeChanged = onDisplayModeChanged,
                        showPreviews = anime?.showPreviews() ?: true,
                        onShowPreviewsEnabled = onShowPreviewsEnabled,
                        showSummaries = anime?.showSummaries() ?: true,
                        onShowSummariesEnabled = onShowSummariesEnabled,
                        // ANZ -->
                        thumbnailSize = anime?.episodeThumbnailSize ?: 4,
                        onSetThumbnailSize = onSetThumbnailSize,
                        // ANZ <--
                    )
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.FilterPage(
    downloadFilter: TriState,
    onDownloadFilterChanged: ((TriState) -> Unit)?,
    unseenFilter: TriState,
    onUnseenFilterChanged: (TriState) -> Unit,
    bookmarkedFilter: TriState,
    onBookmarkedFilterChanged: (TriState) -> Unit,
    // AM (FILLERMARK) -->
    fillermarkedFilter: TriState,
    onFillermarkedFilterChanged: (TriState) -> Unit,
    // <-- AM (FILLERMARK)
    scanlatorFilterActive: Boolean,
    onScanlatorFilterClicked: () -> Unit,
) {
    TriStateItem(
        label = stringResource(MR.strings.label_downloaded),
        state = downloadFilter,
        onClick = onDownloadFilterChanged,
    )
    TriStateItem(
        label = stringResource(MR.strings.action_filter_unseen),
        state = unseenFilter,
        onClick = onUnseenFilterChanged,
    )
    TriStateItem(
        label = stringResource(MR.strings.action_filter_bookmarked),
        state = bookmarkedFilter,
        onClick = onBookmarkedFilterChanged,
    )
    // AM (FILLERMARK) -->
    TriStateItem(
        label = stringResource(AMR.strings.action_filter_fillermarked),
        state = fillermarkedFilter,
        onClick = onFillermarkedFilterChanged,
    )
    // <-- AM (FILLERMARK)

    ScanlatorFilterItem(
        active = scanlatorFilterActive,
        onClick = onScanlatorFilterClicked,
    )
}

@Composable
fun ScanlatorFilterItem(
    active: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clickable(onClick = onClick)
            .fillMaxWidth()
            .padding(horizontal = TabbedDialogPaddings.Horizontal, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.PeopleAlt,
            contentDescription = null,
            tint = if (active) {
                MaterialTheme.colorScheme.active
            } else {
                LocalContentColor.current
            },
        )
        Text(
            text = stringResource(MR.strings.scanlator),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ColumnScope.SortPage(
    sortingMode: Long,
    sortDescending: Boolean,
    onItemSelected: (Long) -> Unit,
) {
    listOf(
        MR.strings.sort_by_source to Anime.EPISODE_SORTING_SOURCE,
        MR.strings.sort_by_episode_number to Anime.EPISODE_SORTING_NUMBER,
        MR.strings.sort_by_upload_date to Anime.EPISODE_SORTING_UPLOAD_DATE,
        MR.strings.action_sort_alpha to Anime.EPISODE_SORTING_ALPHABET,
    ).map { (titleRes, mode) ->
        SortItem(
            label = stringResource(titleRes),
            sortDescending = sortDescending.takeIf { sortingMode == mode },
            onClick = { onItemSelected(mode) },
        )
    }
}

@Composable
private fun ColumnScope.DisplayPage(
    anime: Anime?,
    displayMode: Long,
    onDisplayModeChanged: (Long) -> Unit,
    showPreviews: Boolean,
    onShowPreviewsEnabled: (Long) -> Unit,
    showSummaries: Boolean,
    onShowSummariesEnabled: (Long) -> Unit,
    // ANZ -->
    thumbnailSize: Int,
    onSetThumbnailSize: (Int) -> Unit,
    // ANZ <--
) {
    listOf(
        MR.strings.show_title to Anime.EPISODE_DISPLAY_NAME,
        MR.strings.show_episode_number to Anime.EPISODE_DISPLAY_NUMBER,
    ).map { (titleRes, mode) ->
        RadioItem(
            label = stringResource(titleRes),
            selected = displayMode == mode,
            onClick = { onDisplayModeChanged(mode) },
        )
    }

    androidx.compose.material3.HorizontalDivider(
        modifier = Modifier.padding(vertical = 8.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
    )

    val showPreviewsFlag = if (showPreviews) Anime.EPISODE_SHOW_NOT_PREVIEWS else Anime.EPISODE_SHOW_PREVIEWS
    CheckboxItem(
        label = stringResource(MR.strings.pref_show_episode_thumbnail),
        checked = showPreviews,
        onClick = { onShowPreviewsEnabled(showPreviewsFlag) },
    )

    val showSummariesFlag = if (showSummaries) Anime.EPISODE_SHOW_NOT_SUMMARIES else Anime.EPISODE_SHOW_SUMMARIES
    CheckboxItem(
        label = stringResource(MR.strings.pref_show_episode_summary),
        checked = showSummaries,
        onClick = { onShowSummariesEnabled(showSummariesFlag) },
    )

    // ANZ -->
    SliderItem( // ANZ
        value = thumbnailSize,
        valueRange = 0 until 10,
        label = stringResource(AMR.strings.episode_thumbnail_size),
        valueString = "${String.format(Locale.US, "%.1f", (thumbnailSize + 1) / 5.0)}x",
        onChange = { onSetThumbnailSize(it) },
        pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    )
    // ANZ <--

    val libraryPreferences: tachiyomi.domain.library.service.LibraryPreferences = Injekt.get()
    if (anime != null) {
        androidx.compose.material3.HorizontalDivider(
            modifier = Modifier.padding(vertical = 8.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
        )

        Text(
            text = "Season grouping",
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        listOf(
            "Default (Use app setting)" to Anime.EPISODE_SEASON_GROUP_DEFAULT,
            "Disabled" to Anime.EPISODE_SEASON_GROUP_OFF,
            "Headers" to Anime.EPISODE_SEASON_GROUP_ON,
            "Tabs" to Anime.EPISODE_SEASON_GROUP_TABS,
        ).map { (label, flag) ->
            RadioItem(
                label = label,
                selected = (anime.episodeFlags and Anime.EPISODE_SEASON_GROUP_MASK) == flag,
                onClick = {
                    onDisplayModeChanged(flag or 0x10000000L) // Use a high bit to indicate season grouping change
                },
            )
        }
    }
}

@Composable
private fun SetAsDefaultDialog(
    onDismissRequest: () -> Unit,
    onConfirmed: (optionalChecked: Boolean) -> Unit,
) {
    var optionalChecked by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(MR.strings.episode_settings)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(text = stringResource(MR.strings.confirm_set_chapter_settings))

                LabeledCheckbox(
                    label = stringResource(MR.strings.also_set_episode_settings_for_library),
                    checked = optionalChecked,
                    onCheckedChange = { optionalChecked = it },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirmed(optionalChecked)
                    onDismissRequest()
                },
            ) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
    )
}
