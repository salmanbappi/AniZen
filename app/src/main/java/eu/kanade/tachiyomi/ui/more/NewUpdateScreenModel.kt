package eu.kanade.tachiyomi.ui.more

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.extension.util.ExtensionInstaller
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.ProgressListener
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.newCachelessCallWithProgress
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.storage.saveTo
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

// ANZ -->
class NewUpdateScreenModel(
    changelogInfo: String,
    private val downloadLink: String,
    private val context: Application = Injekt.get(),
    private val network: NetworkHelper = Injekt.get(),
    private val preferences: BasePreferences = Injekt.get(),
) : StateScreenModel<NewUpdateScreenModel.State>(State(changelogInfo = changelogInfo)) {

    private val apkFile: File
        get() = File(context.externalCacheDir ?: context.cacheDir, "update.apk")

    private var downloadJob: Job? = null

    fun startDownload() {
        if (downloadJob?.isActive == true) return

        downloadJob = screenModelScope.launch {
            mutableState.update { it.copy(downloadProgress = 0, stage = Stage.Downloading) }
            try {
                if (apkFile.exists()) {
                    apkFile.delete()
                }
                withContext(Dispatchers.IO) { downloadApk() }
                mutableState.update { it.copy(downloadProgress = 100, stage = Stage.Downloaded) }
                installUpdate()
            } catch (e: CancellationException) {
                apkFile.delete()
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e)
                apkFile.delete()
                mutableState.update { it.copy(stage = Stage.Failed) }
            }
        }
    }

    private suspend fun downloadApk() {
        val progressListener = object : ProgressListener {
            var savedProgress = 0
            var lastTick = 0L

            override fun update(bytesRead: Long, contentLength: Long, done: Boolean) {
                val progress = if (contentLength > 0) {
                    (100 * (bytesRead.toFloat() / contentLength)).toInt()
                } else {
                    -1
                }
                val currentTime = System.currentTimeMillis()
                if ((progress > savedProgress && currentTime - 200 > lastTick) || done) {
                    savedProgress = progress
                    lastTick = currentTime
                    mutableState.update { it.copy(downloadProgress = progress) }
                }
            }
        }

        val response = network.client.newCachelessCallWithProgress(GET(downloadLink), progressListener).awaitSuccess()
        response.use { res ->
            res.body.source().saveTo(apkFile)
        }
    }

    fun skipUpdate(versionName: String) {
        downloadJob?.cancel()
        preferences.skippedAppUpdate().set(versionName)
    }

    fun installUpdate() {
        try {
            if (!apkFile.exists()) {
                logcat(LogPriority.ERROR) { "APK file does not exist for install: ${apkFile.absolutePath}" }
                mutableState.update { it.copy(stage = Stage.Failed) }
                return
            }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkFile.getUriCompat(context), ExtensionInstaller.APK_MIME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            context.toast(e.message)
        }
    }

    @Immutable
    data class State(
        val changelogInfo: String,
        val downloadProgress: Int = 0,
        val stage: Stage = Stage.Available,
    )

    enum class Stage {
        Available,
        Downloading,
        Downloaded,
        Failed,
    }
}
// ANZ <--
