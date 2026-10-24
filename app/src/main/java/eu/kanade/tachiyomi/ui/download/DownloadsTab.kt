// ANZ -->
package eu.kanade.tachiyomi.ui.download

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

data object DownloadsTab : Tab {

    override val options: TabOptions
        @Composable
        get() {
            val title = stringResource(MR.strings.label_download_queue)
            return TabOptions(
                index = 3u,
                title = title,
                icon = painterResource(R.drawable.ic_download_item_24dp),
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
        // Tab reselection behavior
    }

    @Composable
    override fun Content() {
        DownloadQueueScreen.Content()
    }
}
// ANZ <--
