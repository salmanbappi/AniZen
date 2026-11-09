package eu.kanade.tachiyomi.ui.more

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.NewUpdateScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.util.system.openInBrowser

// ANZ -->
class NewUpdateScreen(
    private val versionName: String,
    private val changelogInfo: String,
    private val releaseLink: String,
    private val downloadLink: String,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val changelogInfoNoChecksum = remember {
            changelogInfo.replace("""---(\R|.)*Checksums(\R|.)*""".toRegex(), "")
        }
        val screenModel = rememberScreenModel {
            NewUpdateScreenModel(
                changelogInfo = changelogInfoNoChecksum,
                downloadLink = downloadLink,
            )
        }

        val state by screenModel.state.collectAsState()

        NewUpdateScreen(
            versionName = versionName,
            stage = state.stage,
            downloadProgress = { state.downloadProgress },
            changelogInfo = state.changelogInfo,
            onOpenInBrowser = { context.openInBrowser(releaseLink) },
            onAcceptUpdate = {
                when (state.stage) {
                    NewUpdateScreenModel.Stage.Available, NewUpdateScreenModel.Stage.Failed -> screenModel.startDownload()
                    NewUpdateScreenModel.Stage.Downloaded -> screenModel.installUpdate()
                    else -> Unit
                }
            },
            onRejectUpdate = navigator::pop,
            onSkipUpdate = {
                screenModel.skipUpdate(versionName)
                navigator.pop()
            },
        )
    }
}
// ANZ <--
