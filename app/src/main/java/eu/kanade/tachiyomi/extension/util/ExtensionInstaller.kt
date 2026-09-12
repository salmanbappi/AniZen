package eu.kanade.tachiyomi.extension.util

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.NotificationHandler
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.installer.Installer
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.isPackageInstalled
import eu.kanade.tachiyomi.util.system.notify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import logcat.LogPriority
import okhttp3.OkHttpClient
import okhttp3.Request
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * The installer which installs, updates and uninstalls the extensions.
 *
 * @param context The application context.
 */
internal class ExtensionInstaller(
    private val context: Context,
) {

    private val scope = CoroutineScope(Dispatchers.IO)
    private val activeJobs = mutableMapOf<String, Job>()
    private val activeSteps = mutableMapOf<Long, MutableStateFlow<InstallStep>>()
    private val extensionInstaller = Injekt.get<BasePreferences>().extensionInstaller()
    private val httpClient: OkHttpClient = Injekt.get<NetworkHelper>().client

    /**
     * Adds the given extension to the downloads queue and returns an observable containing its
     * step in the installation process.
     *
     * @param url The url of the apk.
     * @param extension The extension to install.
     */
    fun downloadAndInstall(url: String, extension: Extension): Flow<InstallStep> {
        val downloadId = extension.pkgName.hashCode().toLong()
        logcat { "downloadAndInstall: ${extension.pkgName}, id: $downloadId, url: $url" }
        
        cancelInstall(extension.pkgName)
        
        val step = MutableStateFlow(InstallStep.Pending)
        activeSteps[downloadId] = step

        val job = scope.launch {
            val tmpFile = File(context.cacheDir, "extension_${extension.pkgName}.apk")
            try {
                step.value = InstallStep.Downloading
                val request = Request.Builder().url(url).build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw Exception("Failed to download extension")
                    }
                    response.body.byteStream().use { input ->
                        tmpFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }

                step.value = InstallStep.Installing
                installApk(downloadId, tmpFile)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    // Canceled
                } else {
                    logcat(LogPriority.ERROR, e)
                    step.value = InstallStep.Error
                }
            }
        }

        activeJobs[extension.pkgName] = job

        return step.asStateFlow()
            .onCompletion {
                activeJobs.remove(extension.pkgName)
                activeSteps.remove(downloadId)
                job.cancel()
            }
    }

    /**
     * Starts an intent to install the extension at the given uri.
     *
     * @param tempFile The file of the extension to install.
     */
    fun installApk(downloadId: Long, tempFile: File) {
        when (val installer = extensionInstaller.get()) {
            BasePreferences.ExtensionInstaller.LEGACY -> {
                val intent = Intent(context, ExtensionInstallActivity::class.java)
                    .setDataAndType(tempFile.getUriCompat(context), APK_MIME)
                    .putExtra(EXTRA_DOWNLOAD_ID, downloadId)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)

                context.startActivity(intent)
            }
            BasePreferences.ExtensionInstaller.PRIVATE -> {
                val extensionManager = Injekt.get<ExtensionManager>()
                try {
                    if (ExtensionLoader.installPrivateExtensionFile(context, tempFile)) {
                        extensionManager.updateInstallStep(downloadId, InstallStep.Installed)
                    } else {
                        extensionManager.updateInstallStep(downloadId, InstallStep.Error)
                    }
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Failed to read downloaded extension file." }
                    extensionManager.updateInstallStep(downloadId, InstallStep.Error)
                }

                tempFile.delete()
            }
            else -> {
                val intent = ExtensionInstallService.getIntent(
                    context,
                    downloadId,
                    tempFile.getUriCompat(context),
                    installer,
                )
                ContextCompat.startForegroundService(context, intent)
            }
        }
    }

    /**
     * Shows a notification to prompt the user to install the extension manually if needed.
     */
    fun promptInstall(name: String, pkgName: String, downloadId: Long, tempFile: File) {
        val uri = tempFile.getUriCompat(context)
        val installIntent = NotificationHandler.installApkPendingActivity(context, uri)
        val notificationId = Notifications.ID_EXTENSION_INSTALLER + downloadId.toInt()

        context.notify(
            notificationId,
            Notifications.CHANNEL_EXTENSIONS_UPDATE,
        ) {
            setContentTitle(name)
            setContentText(context.stringResource(MR.strings.update_check_notification_download_complete))
            setSmallIcon(android.R.drawable.stat_sys_download_done)
            setOnlyAlertOnce(false)
            setProgress(0, 0, false)
            setContentIntent(installIntent)

            clearActions()
            addAction(
                R.drawable.ic_system_update_alt_white_24dp,
                context.stringResource(MR.strings.action_install),
                installIntent,
            )
            addAction(
                R.drawable.ic_close_24dp,
                context.stringResource(MR.strings.action_cancel),
                NotificationReceiver.dismissNotificationPendingBroadcast(context, notificationId),
            )
        }
    }

    /**
     * Cancels extension install and remove from download manager and installer.
     */
    fun cancelInstall(pkgName: String) {
        val downloadId = pkgName.hashCode().toLong()
        updateInstallStep(downloadId, InstallStep.Idle)
        activeJobs.remove(pkgName)?.cancel()
        Installer.cancelInstallQueue(context, downloadId)
    }

    /**
     * Starts an intent to uninstall the extension by the given package name.
     *
     * @param pkgName The package name of the extension to uninstall
     */
    fun uninstallApk(pkgName: String) {
        if (context.isPackageInstalled(pkgName)) {
            @Suppress("DEPRECATION")
            val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE, "package:$pkgName".toUri())
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } else {
            ExtensionLoader.uninstallPrivateExtension(context, pkgName)
            ExtensionInstallReceiver.notifyRemoved(context, pkgName)
        }
    }

    /**
     * Sets the step of the installation of an extension.
     *
     * @param downloadId The id of the download.
     * @param step New install step.
     */
    fun updateInstallStep(downloadId: Long, step: InstallStep) {
        activeSteps[downloadId]?.let { it.value = step }
    }

    /**
     * Cancels the installation notification for the given package name.
     *
     * @param pkgName The package name of the extension.
     */
    fun dismissInstallNotification(pkgName: String) {
        val notificationId = Notifications.ID_EXTENSION_INSTALLER + pkgName.hashCode()
        val manager = ContextCompat.getSystemService(context, android.app.NotificationManager::class.java)
        manager?.cancel(notificationId)
    }

    companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
        const val EXTRA_DOWNLOAD_ID = "ExtensionInstaller.extra.DOWNLOAD_ID"
    }
}
