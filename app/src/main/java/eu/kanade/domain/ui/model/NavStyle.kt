package eu.kanade.domain.ui.model

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.home.FeedTab
import eu.kanade.tachiyomi.ui.browse.BrowseTab
import eu.kanade.tachiyomi.ui.history.HistoryTab
import eu.kanade.tachiyomi.ui.library.LibraryTab
import eu.kanade.tachiyomi.ui.more.MoreTab
import eu.kanade.tachiyomi.ui.updates.UpdatesTab
import mihon.feature.airingschedule.AiringScheduleTab
import tachiyomi.i18n.MR

import eu.kanade.domain.ui.UiPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

enum class NavStyle(
    val titleRes: StringResource,
    val moreTab: Tab,
) {
    MOVE_UPDATES_TO_MORE(titleRes = MR.strings.pref_bottom_nav_no_updates, moreTab = UpdatesTab),
    MOVE_HISTORY_TO_MORE(titleRes = MR.strings.pref_bottom_nav_no_history, moreTab = HistoryTab),
    MOVE_SCHEDULE_TO_MORE(titleRes = MR.strings.pref_bottom_nav_no_schedule, moreTab = AiringScheduleTab),
    MOVE_BROWSE_TO_MORE(titleRes = MR.strings.pref_bottom_nav_no_browse, moreTab = BrowseTab),
    SHOW_ALL(titleRes = MR.strings.all, moreTab = HistoryTab),
    ;

    val moreIcon: ImageVector
        @Composable
        get() = when (this) {
            MOVE_UPDATES_TO_MORE -> ImageVector.vectorResource(id = R.drawable.ic_updates_outline_24dp)
            MOVE_HISTORY_TO_MORE -> Icons.Outlined.History
            MOVE_SCHEDULE_TO_MORE -> Icons.Outlined.DateRange
            MOVE_BROWSE_TO_MORE -> Icons.Outlined.Explore
            SHOW_ALL -> Icons.Outlined.History
        }

    fun tabs(): List<Tab> {
        val uiPreferences: UiPreferences = Injekt.get()
        val tabs = mutableListOf<Tab>(
            LibraryTab,
        )
        tabs.addAll(
            listOf(
                UpdatesTab,
                HistoryTab,
                BrowseTab,
                MoreTab,
            )
        )
        if (this != SHOW_ALL) {
            tabs.remove(this.moreTab)
        }
        return tabs
    }
}
