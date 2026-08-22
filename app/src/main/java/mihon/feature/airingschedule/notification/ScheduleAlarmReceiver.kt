package mihon.feature.airingschedule.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.toBitmap
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.util.system.notify
import tachiyomi.core.common.Constants
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Fires the actual system notification once an alarm scheduled by [ScheduleNotifications]
 * reaches its trigger time. Styled to match the app's own notification look (accent color,
 * app icon, cover art as the large icon) rather than a bare system default notification.
 */
class ScheduleAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val mediaId = intent.getIntExtra(EXTRA_MEDIA_ID, -1)
        val episode = intent.getIntExtra(EXTRA_EPISODE, -1)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: return
        val coverUrl = intent.getStringExtra(EXTRA_COVER_URL)
        if (mediaId == -1 || episode == -1) return

        // Remove the alarm key from the scheduled set first, before the async notification work.
        // All key-set mutations go through ScheduleNotifications so they are serialized.
        ScheduleNotifications.removeAlarmKey(mediaId, episode)

        // Cover-art loading is async (network/disk); use goAsync() so the receiver's process
        // isn't killed before the notification is actually posted. goAsync() only grants a
        // short (~10s) execution window before the OS may kill the process, so the cover
        // fetch is capped well under that and the notification is always posted — with or
        // without the cover — inside a try/finally so pendingResult.finish() is never skipped.
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        val titleEnglish = intent.getStringExtra(EXTRA_TITLE_ENGLISH)
        val titleRomaji = intent.getStringExtra(EXTRA_TITLE_ROMAJI)
        val titleNative = intent.getStringExtra(EXTRA_TITLE_NATIVE)
        val titleCandidates = listOfNotNull(title, titleEnglish, titleRomaji, titleNative)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                var matchedAnimeId: Long? = null
                val shouldSkipNotification = runCatching {
                    val getLibraryAnime = Injekt.get<tachiyomi.domain.anime.interactor.GetLibraryAnime>()
                    val getEpisodesByAnimeId = Injekt.get<tachiyomi.domain.episode.interactor.GetEpisodesByAnimeId>()
                    val libraryAnime = getLibraryAnime.await()
                    val matchedAnime = libraryAnime.firstOrNull { lib ->
                        mihon.feature.airingschedule.util.ScheduleTitleMatcher.matchesAny(lib.anime.title, titleCandidates)
                    }
                    if (matchedAnime != null) {
                        matchedAnimeId = matchedAnime.anime.id
                        val episodes = getEpisodesByAnimeId.await(matchedAnime.anime.id)
                        episodes.any { ep ->
                            ep.episodeNumber == episode.toDouble() &&
                                (ep.seen || ep.dateFetch > 0L)
                        }
                    } else {
                        false
                    }
                }.getOrDefault(false)

                if (!shouldSkipNotification) {
                    val coverBitmap = coverUrl?.let {
                        withTimeoutOrNull(COVER_LOAD_TIMEOUT_MS) { loadCoverBitmap(appContext, it) }
                    }
                    postNotification(appContext, mediaId, episode, title, coverBitmap, matchedAnimeId)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun loadCoverBitmap(context: Context, url: String): Bitmap? = runCatching {
        val density = context.resources.displayMetrics.density
        val targetPx = (64 * density).toInt()
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(targetPx, targetPx)
            .build()
        context.imageLoader.execute(request).image
            ?.asDrawable(context.resources)
            ?.toBitmap()
    }.getOrNull()

    private fun postNotification(
        context: Context,
        mediaId: Int,
        episode: Int,
        title: String,
        coverBitmap: Bitmap?,
        matchedAnimeId: Long?,
    ) {
        val contentIntent = if (matchedAnimeId != null) {
            Intent(context, MainActivity::class.java).apply {
                action = Constants.SHORTCUT_ANIME
                putExtra(Constants.ANIME_EXTRA, matchedAnimeId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        } else {
            Intent(context, MainActivity::class.java).apply {
                action = MainActivity.INTENT_ANIMESEARCH
                putExtra(MainActivity.INTENT_SEARCH_QUERY, title)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        }
        val pendingContentIntent = android.app.PendingIntent.getActivity(
            context,
            ScheduleNotifications.requestCode(mediaId, episode),
            contentIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )

        val contentText = context.stringResource(MR.strings.notification_episode_aired, episode)
        val notificationId = ScheduleNotifications.notificationId(mediaId, episode)

        runCatching {
            context.notify(notificationId, Notifications.CHANNEL_AIRING_SCHEDULE) {
                setSmallIcon(R.drawable.ic_splash_logo)
                setContentTitle(title)
                setContentText(contentText)
                setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
                coverBitmap?.let { setLargeIcon(it) }
                setPriority(NotificationCompat.PRIORITY_HIGH)
                setCategory(NotificationCompat.CATEGORY_REMINDER)
                setAutoCancel(true)
                setContentIntent(pendingContentIntent)
            }
        }
    }

    companion object {
        const val EXTRA_MEDIA_ID = "media_id"
        const val EXTRA_EPISODE = "episode"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TITLE_ENGLISH = "title_english"
        const val EXTRA_TITLE_ROMAJI = "title_romaji"
        const val EXTRA_TITLE_NATIVE = "title_native"
        const val EXTRA_COVER_URL = "cover_url"
        private const val COVER_LOAD_TIMEOUT_MS = 6_000L
    }
}
