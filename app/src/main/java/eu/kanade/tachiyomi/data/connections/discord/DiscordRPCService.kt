// AM (DISCORD) -->

// Taken from Animiru. Thank you Quickdev for permission!

package eu.kanade.tachiyomi.data.connections.discord

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.compose.ui.util.fastAny
import eu.kanade.domain.connections.service.ConnectionsPreferences
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.connections.ConnectionsManager
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import eu.kanade.tachiyomi.util.system.notificationBuilder
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.serialization.json.Json
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category.Companion.UNCATEGORIZED_ID
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import kotlin.math.ceil
import kotlin.math.floor

class DiscordRPCService : Service() {

    private val connectionsManager: ConnectionsManager by injectLazy()

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCreate() {
        super.onCreate()
        val token = connectionsPreferences.connectionsToken(connectionsManager.discord).get()
        val status = when (connectionsPreferences.discordRPCStatus().get()) {
            -1 -> "dnd"
            0 -> "idle"
            else -> "online"
        }
        rpc = if (token.isNotBlank()) DiscordRPC(token, status) else null
        if (rpc != null) {
            launchIO {
                try {
                    setAnimeScreen(this@DiscordRPCService, lastUsedScreen)
                } catch (e: Exception) {
                    Log.e(TAG, "Error setting screen: ${e.message}", e)
                }
            }
            notification(this)
        } else {
            stopSelf()
            connectionsPreferences.enableDiscordRPC().set(false)
        }
    }
    override fun onDestroy() {
        NotificationReceiver.dismissNotification(this, Notifications.ID_DISCORD_RPC)
        rpc?.closeRPC() // Check for null before closing
        rpc = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    private fun notification(context: Context) {
        val appName = context.getString(R.string.app_name) // Get app name once
        val builder = context.notificationBuilder(Notifications.CHANNEL_DISCORD_RPC) {
            setLargeIcon(BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher))
            setSmallIcon(R.drawable.ic_discord_24dp)
            setContentText(context.resources.getString(R.string.pref_discord_rpc))
            setAutoCancel(false)
            setOngoing(true)
            setUsesChronometer(true)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                Notifications.ID_DISCORD_RPC,
                builder.build(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(Notifications.ID_DISCORD_RPC, builder.build())
        }
    }

    companion object {

        private val connectionsPreferences: ConnectionsPreferences by injectLazy()

        private var rpc: DiscordRPC? = null // Consider making private

        private val handler = Handler(Looper.getMainLooper())
        private val playerPreferences: PlayerPreferences by injectLazy()

        fun start(context: Context) {
            handler.removeCallbacksAndMessages(null)
            if (rpc == null && connectionsPreferences.enableDiscordRPC().get()) {
                since = System.currentTimeMillis()
                context.startService(Intent(context, DiscordRPCService::class.java))
            }
        }

        fun stop(context: Context, delay: Long = 30000L) {
            handler.postDelayed(
                { context.stopService(Intent(context, DiscordRPCService::class.java)) },
                delay,
            )
        }

        private var since = 0L // Consider making private

        internal var lastUsedScreen = DiscordScreen.APP // Consider making private
            set(value) {
                field = if ((
                        value == DiscordScreen.VIDEO ||
                            value == DiscordScreen.MANGA
                        ) ||
                    value == DiscordScreen.WEBVIEW
                ) {
                    field
                } else {
                    value
                }
            }
        private const val MP_PREFIX = "mp:"
        private const val EXTERNAL_PREFIX = "external/"
        private val json = Json {
            encodeDefaults = true
            allowStructuredMapKeys = true
            ignoreUnknownKeys = true
        }
        private const val TAG = "DiscordRPCService"

        private var lastCustomDetails: String? = null
        private var lastCustomState: String? = null
        private var lastPlayerData: PlayerData? = null
        private var isIncognitoActive: Boolean = false

        private fun isIncognitoModeActive(incognitoMode: Boolean = false): Boolean {
            val basePreferences: eu.kanade.domain.base.BasePreferences by injectLazy()
            val isGlobalIncognito = basePreferences.incognitoMode().get()
            val isDiscordIncognito = connectionsPreferences.discordRPCIncognito().get()
            return isGlobalIncognito || isDiscordIncognito || incognitoMode
        }

        internal suspend fun setAnimeScreen(
            context: Context,
            discordScreen: DiscordScreen,
            customDetails: String? = null,
            customState: String? = null,
            playerData: PlayerData = PlayerData(),
            smallImageUri: String? = null,
        ) {
            if (isIncognitoModeActive(playerData.incognitoMode)) {
                if (!isIncognitoActive) {
                    isIncognitoActive = true
                    rpc?.updateRPC(null)
                }
                return
            }
            isIncognitoActive = false

            if (discordScreen == lastUsedScreen &&
                customDetails == lastCustomDetails &&
                customState == lastCustomState &&
                playerData == lastPlayerData
            ) {
                return
            }

            lastUsedScreen = discordScreen
            lastCustomDetails = customDetails
            lastCustomState = customState
            lastPlayerData = playerData

            if (rpc == null) return

            if (discordScreen == DiscordScreen.VIDEO) {
                updateDiscordRPC(
                    context = context,
                    playerData = playerData,
                    discordScreen = discordScreen,
                    customDetails = customDetails,
                    customState = customState,
                    largeImageUri = playerData.thumbnailUrl,
                    smallImageUri = smallImageUri ?: DiscordScreen.APP.imageUrl,
                )
            } else {
                withIOContext {
                    val rpcExternalAsset = getRPCExternalAsset()
                    val appLogoUri = getDiscordThumbnail(rpcExternalAsset, DiscordScreen.APP.imageUrl, false)

                    updateDiscordRPC(
                        context = context,
                        playerData = playerData,
                        discordScreen = discordScreen,
                        customDetails = customDetails,
                        customState = customState,
                        largeImageUri = appLogoUri,
                        smallImageUri = null,
                    )
                }
            }
        }

        private suspend fun updateDiscordRPC(
            context: Context,
            playerData: PlayerData,
            discordScreen: DiscordScreen,
            sinceTime: Long = since,
            customDetails: String? = null,
            customState: String? = null,
            largeImageUri: String? = null,
            smallImageUri: String? = null,
        ) {
            val appName = context.getString(R.string.app_name)

            val customMessage = connectionsPreferences.discordCustomMessage().get()
            val showProgress = connectionsPreferences.discordShowProgress().get()
            val showTimestamp = connectionsPreferences.discordShowTimestamp().get()
            val showButtons = connectionsPreferences.discordShowButtons().get()
            val showDownloadButton = connectionsPreferences.discordShowDownloadButton().get()
            val showDiscordButton = connectionsPreferences.discordShowDiscordButton().get()

            val name = when (discordScreen) {
                DiscordScreen.VIDEO -> playerData.animeTitle ?: appName
                else -> appName
            }

            val details = when {
                customDetails != null -> customDetails
                customMessage.isNotBlank() -> customMessage
                discordScreen == DiscordScreen.VIDEO -> playerData.animeTitle ?: appName
                else -> context.getString(discordScreen.details)
            }

            val state = when {
                customState != null -> customState
                discordScreen == DiscordScreen.VIDEO && showProgress -> playerData.episodeNumber
                else -> null
            }

            val finalLargeImage = largeImageUri ?: playerData.thumbnailUrl ?: DiscordScreen.APP.imageUrl
            val finalSmallImage = if (discordScreen == DiscordScreen.VIDEO) {
                smallImageUri ?: DiscordScreen.APP.imageUrl
            } else {
                null
            }

            val timestamps = if (showTimestamp && !playerData.isPaused && discordScreen == DiscordScreen.VIDEO) {
                Activity.Timestamps(
                    start = playerData.startTimestamp ?: since,
                    end = playerData.endTimestamp,
                )
            } else {
                null
            }

            val buttons = if (showButtons) {
                buildList {
                    if (showDownloadButton) add(DOWNLOAD_BUTTON_LABEL)
                    if (showDiscordButton) add(DISCORD_BUTTON_LABEL)
                }.takeIf { it.isNotEmpty() }
            } else {
                null
            }

            val metadata = buttons?.let {
                Activity.Metadata(
                    buttonUrls = buildList {
                        if (showDownloadButton) add(DOWNLOAD_BUTTON_URL)
                        if (showDiscordButton) add(DISCORD_BUTTON_URL)
                    },
                )
            }

            rpc?.updateRPC(
                activity = Activity(
                    applicationId = RICH_PRESENCE_APPLICATION_ID,
                    name = name,
                    details = details,
                    state = state,
                    type = 3,
                    timestamps = timestamps,
                    assets = Activity.Assets(
                        largeImage = finalLargeImage.fixDiscordImage(),
                        smallImage = finalSmallImage?.fixDiscordImage(),
                        smallText = context.getString(DiscordScreen.APP.text),
                    ),
                    buttons = buttons,
                    metadata = metadata,
                ),
                since = sinceTime,
            )
        }

        internal suspend fun setMangaScreen(
            context: Context,
            discordScreen: DiscordScreen,
            readerData: ReaderData = ReaderData(),
        ) {
            if (discordScreen == DiscordScreen.MANGA) {
                lastUsedScreen = discordScreen // Update last used screen
                if (rpc == null) return
                updateDiscordRPC(context, readerData, discordScreen)
            } else {
                setAnimeScreen(context, discordScreen)
            }
        }

        private suspend fun updateDiscordRPC(
            context: Context,
            readerData: ReaderData,
            discordScreen: DiscordScreen,
            sinceTime: Long = since,
        ) {
            val appName = context.getString(R.string.app_name)
            val name = readerData.mangaTitle ?: appName
            val details = readerData.mangaTitle ?: context.getString(discordScreen.details)
            val state = readerData.chapterNumber ?: context.getString(discordScreen.text)
            val imageUrl = readerData.thumbnailUrl ?: discordScreen.imageUrl

            val showButtons = connectionsPreferences.discordShowButtons().get()
            val showDownloadButton = connectionsPreferences.discordShowDownloadButton().get()
            val showDiscordButton = connectionsPreferences.discordShowDiscordButton().get()

            val buttons = if (showButtons) {
                buildList {
                    if (showDownloadButton) add(DOWNLOAD_BUTTON_LABEL)
                    if (showDiscordButton) add(DISCORD_BUTTON_LABEL)
                }.takeIf { it.isNotEmpty() }
            } else {
                null
            }

            val metadata = buttons?.let {
                Activity.Metadata(
                    buttonUrls = buildList {
                        if (showDownloadButton) add(DOWNLOAD_BUTTON_URL)
                        if (showDiscordButton) add(DISCORD_BUTTON_URL)
                    },
                )
            }

            rpc?.updateRPC(
                activity = Activity(
                    applicationId = RICH_PRESENCE_APPLICATION_ID,
                    name = name,
                    details = details,
                    state = state,
                    type = 3,
                    timestamps = Activity.Timestamps(start = sinceTime),
                    assets = Activity.Assets(
                        largeImage = imageUrl.fixDiscordImage(),
                        smallImage = DiscordScreen.APP.imageUrl.fixDiscordImage(),
                        smallText = context.getString(DiscordScreen.APP.text),
                    ),
                    buttons = buttons,
                    metadata = metadata,
                ),
                since = since,
            )
        }

        private fun String.fixDiscordImage(): String {
            if (this.startsWith(MP_PREFIX)) return this
            if (this.startsWith(EXTERNAL_PREFIX)) return "$MP_PREFIX$this"
            if (this.startsWith("https://")) return "${MP_PREFIX}${EXTERNAL_PREFIX}https/${this.removePrefix("https://")}"
            if (this.startsWith("http://")) return "${MP_PREFIX}${EXTERNAL_PREFIX}http/${this.removePrefix("http://")}"
            return "$MP_PREFIX$this"
        }

        @Suppress("SwallowedException", "TooGenericExceptionCaught")
        internal suspend fun setAnimeDetailsActivity(
            context: Context,
            animeTitle: String?,
            thumbnailUrl: String?,
            animeId: Long? = null,
        ) {
            if (rpc == null || animeTitle == null) return
            try {
                val categories = if (animeId != null) getCategories(animeId) else emptyList()
                val discordIncognito = isIncognito(categories, false)

                val displayTitle = animeTitle.takeUnless { discordIncognito }
                val displayThumbnail = if (discordIncognito) null else thumbnailUrl

                withIOContext {
                    val rpcExternalAsset = getRPCExternalAsset()
                    val animeThumbnail = getDiscordThumbnail(rpcExternalAsset, displayThumbnail, discordIncognito)
                    val appLogoUri = getDiscordThumbnail(rpcExternalAsset, DiscordScreen.APP.imageUrl, false)

                    setAnimeScreen(
                        context = context,
                        discordScreen = DiscordScreen.DETAILS,
                        playerData = PlayerData(
                            animeTitle = displayTitle,
                            thumbnailUrl = animeThumbnail,
                        ),
                        smallImageUri = appLogoUri,
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting anime details activity: ${e.message}", e)
            }
        }

        @Suppress("SwallowedException", "TooGenericExceptionCaught", "CyclomaticComplexMethod")
        internal suspend fun setPlayerActivity(
            context: Context,
            playerData: PlayerData = PlayerData(),
        ) {
            if (rpc == null || playerData.thumbnailUrl == null || playerData.animeId == null) return

            try { // Wrap in try-catch
                val categories = getCategories(playerData.animeId)
                val discordIncognito = isIncognito(categories, playerData.incognitoMode)

                val animeTitle = playerData.animeTitle.takeUnless { discordIncognito }
                val episodeNumber = getFormattedEpisodeNumber(playerData, discordIncognito)
                val (startTime, end) = getTimestamps(playerData)

                withIOContext {
                    val rpcExternalAsset = getRPCExternalAsset() // Get RPCExternalAsset
                    val animeThumbnail =
                        getDiscordThumbnail(rpcExternalAsset, playerData.thumbnailUrl, discordIncognito)
                    val appLogoUri =
                        getDiscordThumbnail(rpcExternalAsset, DiscordScreen.APP.imageUrl, false)

                    setAnimeScreen(
                        context = context,
                        discordScreen = DiscordScreen.VIDEO,
                        playerData = PlayerData(
                            animeTitle = animeTitle,
                            episodeNumber = episodeNumber,
                            thumbnailUrl = animeThumbnail,
                            startTimestamp = startTime,
                            endTimestamp = end,
                            isPaused = playerData.isPaused,
                        ),
                        smallImageUri = appLogoUri,
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting player activity: ${e.message}", e)
            }
        }

        @Suppress("SwallowedException", "TooGenericExceptionCaught", "CyclomaticComplexMethod")
        internal suspend fun setReaderActivity(
            context: Context,
            readerData: ReaderData = ReaderData(),
        ) {
            if (rpc == null || readerData.thumbnailUrl == null || readerData.mangaId == null) return
            try {
                val categories = getCategories(readerData.mangaId)
                val discordIncognito = isIncognito(categories, readerData.incognitoMode)

                val mangaTitle = readerData.mangaTitle.takeUnless { discordIncognito }
                val chapterNumber = getFormattedChapterNumber(readerData, discordIncognito)

                withIOContext {
                    val rpcExternalAsset = getRPCExternalAsset() // Get rpcExternalAsset
                    val mangaThumbnail =
                        getDiscordThumbnail(rpcExternalAsset, readerData.thumbnailUrl, discordIncognito)

                    setMangaScreen(
                        context = context,
                        discordScreen = DiscordScreen.MANGA,
                        readerData = ReaderData(
                            mangaTitle = mangaTitle,
                            chapterNumber = chapterNumber,
                            thumbnailUrl = mangaThumbnail,
                        ),
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting reader activity: ${e.message}", e)
            }
        }

        // Helper functions

        private suspend fun getCategories(id: Long?): List<String> {
            return Injekt.get<GetCategories>()
                .await(id!!)
                .map { it.id.toString() }
                .run { ifEmpty { plus(UNCATEGORIZED_ID.toString()) } }
        }

        private fun isIncognito(categories: List<String>, incognitoMode: Boolean): Boolean {
            val discordIncognitoMode = connectionsPreferences.discordRPCIncognito().get()
            val incognitoCategories = connectionsPreferences.discordRPCIncognitoCategories().get()
            val incognitoCategory = categories.fastAny { it in incognitoCategories }
            return discordIncognitoMode || incognitoMode || incognitoCategory
        }

        private fun getFormattedEpisodeNumber(playerData: PlayerData, discordIncognito: Boolean): String? {
            return playerData.episodeNumber?.takeUnless { discordIncognito }
        }
        private fun getFormattedChapterNumber(readerData: ReaderData, discordIncognito: Boolean): String? {
            val chapterNumber = readerData.chapterNumber
            val chapterProgress = readerData.chapterProgress
            return chapterNumber?.let {
                when {
                    discordIncognito -> null
                    connectionsPreferences.useChapterTitles().get() ->
                        "$it (${chapterProgress.first}/${chapterProgress.second})"

                    ceil(it.toDouble()) == floor(it.toDouble()) -> "Chapter ${it.toInt()}" + " " +
                        "(${chapterProgress.first}/${chapterProgress.second})"

                    else -> "Chapter $it (${chapterProgress.first}/${chapterProgress.second}"
                }
            }
        }

        private fun getTimestamps(playerData: PlayerData): Pair<Long?, Long?> {
            val startTime = playerData.startTimestamp ?: System.currentTimeMillis()
            val end = playerData.endTimestamp
            return Pair(startTime, end)
        }

        private suspend fun getRPCExternalAsset(): RPCExternalAsset {
            val connectionsManager: ConnectionsManager by injectLazy()
            val networkService: NetworkHelper by injectLazy()
            val client = networkService.client
            return RPCExternalAsset(
                applicationId = RICH_PRESENCE_APPLICATION_ID,
                token = connectionsPreferences.connectionsToken(connectionsManager.discord).get(),
                client = client,
                json = json,
            )
        }
        private suspend fun getDiscordThumbnail(
            rpcExternalAsset: RPCExternalAsset,
            thumbnailUrl: String?,
            incognito: Boolean,
        ): String? {
            if (incognito || thumbnailUrl == null) return null

            return try {
                rpcExternalAsset.getDiscordUri(thumbnailUrl)
            } catch (e: Exception) {
                Log.e(TAG, "Error getting Discord URI: ${e.message}", e)
                null
            }
        }
    }
}
// <-- AM (DISCORD)
