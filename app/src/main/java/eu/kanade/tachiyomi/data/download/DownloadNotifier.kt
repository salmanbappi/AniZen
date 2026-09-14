package eu.kanade.tachiyomi.data.download

import android.app.PendingIntent
import android.content.Context
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.notification.NotificationHandler
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.lang.chop
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notificationManager
import eu.kanade.tachiyomi.util.system.notify
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import uy.kohesive.injekt.injectLazy
import java.util.regex.Pattern

/**
 * DownloadNotifier is used to show notifications when downloading one or multiple chapters.
 *
 * @param context context of application
 */
internal class DownloadNotifier(private val context: Context) {

    private val preferences: SecurityPreferences by injectLazy()

    private val progressNotificationBuilder by lazy {
        context.notificationBuilder(Notifications.CHANNEL_DOWNLOADER_PROGRESS) {
            setLargeIcon(BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher))
            setAutoCancel(false)
            setOnlyAlertOnce(true)
        }
    }

    private val errorNotificationBuilder by lazy {
        context.notificationBuilder(Notifications.CHANNEL_DOWNLOADER_ERROR) {
            setAutoCancel(false)
        }
    }

    /**
     * Status of download. Used for correct notification icon.
     */
    private var isDownloading = false

    private var lastNotificationTime = 0L

    /**
     * Shows a notification from this builder.
     *
     * @param id the id of the notification.
     */
    private fun NotificationCompat.Builder.show(id: Int) {
        context.notify(id, build())
    }

    private fun getNotificationId(download: Download): Int {
        return Notifications.ID_DOWNLOAD_EPISODE_PROGRESS + download.episode.id.toInt()
    }

    /**
     * Dismiss the downloader's notification. Downloader error notifications use a different id, so
     * those can only be dismissed by the user.
     */
    fun dismissProgress() {
        context.notificationManager.cancel(Notifications.ID_DOWNLOAD_EPISODE_PROGRESS)
    }

    fun dismissProgress(download: Download) {
        context.notificationManager.cancel(getNotificationId(download))
    }

    fun dismissAll() {
        context.notificationManager.cancel(Notifications.ID_DOWNLOAD_EPISODE_PROGRESS)
        // Since we cannot easily list active IDs, we clear the whole progress channel via summary
        // And reset downloading flag
        isDownloading = false
    }

    /**
     * Called when download progress changes.
     *
     * @param download download object containing download information.
     */
    fun onProgressChange(download: Download) {
        if (download.status == Download.State.DOWNLOADED) {
            dismissProgress(download)
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastNotificationTime < 500 && download.status == Download.State.DOWNLOADING && download.progress < 100) {
            return
        }
        lastNotificationTime = now

        val notificationId = getNotificationId(download)

        with(context.notificationBuilder(Notifications.CHANNEL_DOWNLOADER_PROGRESS)) {
            setLargeIcon(BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher))
            setAutoCancel(false)
            setOnlyAlertOnce(true)
            setSmallIcon(android.R.drawable.stat_sys_download)
            setGroup(Notifications.GROUP_DOWNLOADER)

            // Open download manager when clicked
            setContentIntent(
                NotificationHandler.openAnimeDownloadManagerPendingActivity(context),
            )

            // Actions
            addAction(
                R.drawable.ic_pause_24dp,
                context.stringResource(MR.strings.action_pause),
                NotificationReceiver.pauseAnimeDownloadsPendingBroadcast(context),
            )
            addAction(
                R.drawable.ic_book_24dp,
                context.stringResource(MR.strings.action_show_anime),
                NotificationReceiver.openAnimeEntryPendingActivity(context, download.anime.id),
            )

            val downloadingProgressText = if (download.progress <= 0 && download.downloadedSize.isEmpty() && download.status == Download.State.DOWNLOADING) {
                context.stringResource(MR.strings.update_check_notification_download_in_progress)
            } else {
                // 1DM+ Style Rich Notification
                val size = download.downloadedSize
                val speed = download.speed
                val eta = download.eta
                val progress = if (download.progress > 0) "${download.progress}%" else "0%"

                buildString {
                    val statePrefix = when (download.status) {
                        Download.State.MERGING -> "Merging: "
                        Download.State.DECRYPTING -> "Decrypting: "
                        Download.State.FINALIZING -> "Finalizing: "
                        else -> ""
                    }
                    append(statePrefix)
                    append(progress)

                    if (download.status == Download.State.DOWNLOADING) {
                        if (size.isNotEmpty()) append(" | ").append(size)
                        if (speed.isNotEmpty()) append(" | ").append(speed)
                        if (eta.isNotEmpty()) append(" | ETA: ").append(eta)
                    }
                }
            }

            if (preferences.hideNotificationContent().get()) {
                setContentTitle(downloadingProgressText)
                setContentText(null)
            } else {
                val title = download.anime.title.chop(15)
                val quotedTitle = Pattern.quote(title)
                val episode = download.episode.name.replaceFirst(
                    "$quotedTitle[\\s]*[-]*[\\s]*".toRegex(RegexOption.IGNORE_CASE),
                    "",
                )
                setContentTitle("$title - $episode".chop(30))
                setContentText(downloadingProgressText)
            }

            if (download.progress <= 0) {
                setProgress(100, 0, true)
            } else {
                setProgress(100, download.progress, false)
            }
            setOngoing(true)

            show(notificationId)
        }

        // Show summary notification
        showSummaryNotification()
    }

    private fun showSummaryNotification() {
        val summary = context.notificationBuilder(Notifications.CHANNEL_DOWNLOADER_PROGRESS) {
            setSmallIcon(android.R.drawable.stat_sys_download)
            setContentTitle(context.stringResource(MR.strings.download_notifier_downloader_title))
            setContentText(context.stringResource(MR.strings.update_check_notification_download_in_progress))
            setGroup(Notifications.GROUP_DOWNLOADER)
            setGroupSummary(true)
            setAutoCancel(false)
            setOngoing(true)
            setOnlyAlertOnce(true)
        }
        summary.show(Notifications.ID_DOWNLOAD_EPISODE_PROGRESS)
    }

    /**
     * Show notification when download is paused.
     */
    fun onPaused() {
        with(context.notificationBuilder(Notifications.CHANNEL_DOWNLOADER_PROGRESS)) {
            setContentTitle(context.stringResource(MR.strings.download_paused))
            setContentText(context.stringResource(MR.strings.download_notifier_download_paused_episodes))
            setSmallIcon(R.drawable.ic_pause_24dp)
            setProgress(0, 0, false)
            setOngoing(false)
            setGroup(Notifications.GROUP_DOWNLOADER)
            setGroupSummary(true)
            clearActions()
            // Open download manager when clicked
            setContentIntent(NotificationHandler.openAnimeDownloadManagerPendingActivity(context))
            // Resume action
            addAction(
                R.drawable.ic_play_arrow_24dp,
                context.stringResource(MR.strings.action_resume),
                NotificationReceiver.resumeAnimeDownloadsPendingBroadcast(context),
            )
            // Clear action
            addAction(
                R.drawable.ic_close_24dp,
                context.stringResource(MR.strings.action_cancel_all),
                NotificationReceiver.clearAnimeDownloadsPendingBroadcast(context),
            )

            show(Notifications.ID_DOWNLOAD_EPISODE_PROGRESS)
        }

        // Reset initial values
        isDownloading = false
    }

    /**
     *  Resets the state once downloads are completed.
     */
    fun onComplete() {
        dismissProgress()

        // Reset states to default
        isDownloading = false
    }

    /**
     * Called when the downloader receives a warning.
     *
     * @param reason the text to show.
     * @param timeout duration after which to automatically dismiss the notification.
     * @param animeId the id of the entry being warned about
     */
    fun onWarning(reason: String, timeout: Long? = null, contentIntent: PendingIntent? = null, animeId: Long? = null) {
        with(errorNotificationBuilder) {
            setContentTitle(context.stringResource(MR.strings.download_notifier_downloader_title))
            setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            setSmallIcon(R.drawable.ic_warning_white_24dp)
            setAutoCancel(true)
            clearActions()
            setContentIntent(NotificationHandler.openAnimeDownloadManagerPendingActivity(context))
            if (animeId != null) {
                addAction(
                    R.drawable.ic_book_24dp,
                    context.stringResource(MR.strings.action_show_anime),
                    NotificationReceiver.openAnimeEntryPendingActivity(context, animeId),
                )
            }
            setProgress(0, 0, false)
            timeout?.let { setTimeoutAfter(it) }
            contentIntent?.let { setContentIntent(it) }

            show(Notifications.ID_DOWNLOAD_EPISODE_ERROR)
        }

        // Reset download information
        isDownloading = false
    }

    /**
     * Called when the downloader receives an error. It's shown as a separate notification to avoid
     * being overwritten.
     *
     * @param error string containing error information.
     * @param episode string containing episode title.
     * @param animeId the id of the entry that the error occurred on
     */
    fun onError(error: String? = null, episode: String? = null, animeTitle: String? = null, animeId: Long? = null) {
        // Create notification
        with(errorNotificationBuilder) {
            setContentTitle(
                animeTitle?.plus(": $episode") ?: context.stringResource(
                    MR.strings.download_notifier_downloader_title,
                ),
            )
            setContentText(error ?: context.stringResource(MR.strings.download_notifier_unknown_error))
            setSmallIcon(R.drawable.ic_warning_white_24dp)
            clearActions()
            setContentIntent(NotificationHandler.openAnimeDownloadManagerPendingActivity(context))
            if (animeId != null) {
                addAction(
                    R.drawable.ic_book_24dp,
                    context.stringResource(MR.strings.action_show_anime),
                    NotificationReceiver.openAnimeEntryPendingActivity(context, animeId),
                )
            }
            setProgress(0, 0, false)

            show(Notifications.ID_DOWNLOAD_EPISODE_ERROR)
        }

        // Reset download information
        isDownloading = false
    }
}
