/*
 * Copyright 2024 Abdallah Mehiz
 * https://github.com/abdallahmehiz/mpvKt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Code is a mix between PlayerActivity from mpvKt and the former
 * PlayerActivity from Aniyomi.
 */

package eu.kanade.tachiyomi.ui.player

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.theme.DynamicTachiyomiTheme
import eu.kanade.tachiyomi.animesource.model.HttpServer
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.util.system.CoverColorObserver
import tachiyomi.domain.anime.model.asAnimeCover
import tachiyomi.presentation.core.util.collectAsState as collectAsStatePref


import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Rational
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.media.AudioAttributesCompat
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import animiru.feature.mpvfiles.MpvConfig
import animiru.feature.mpvfiles.MpvConfig.Companion.MPV_DIR
import com.hippo.unifile.UniFile
import eu.kanade.domain.connections.service.ConnectionsPreferences
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.tachiyomi.animesource.model.ChapterType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SerializableHoster.Companion.serialize
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.data.connections.discord.DiscordRPCService
import eu.kanade.tachiyomi.data.connections.discord.PlayerData
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.torrentServer.service.TorrentServerService
import eu.kanade.tachiyomi.source.isNsfw
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.torrentServer.TorrentServerApi
import eu.kanade.tachiyomi.torrentServer.TorrentServerUtils
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.player.controls.PlayerControls
import eu.kanade.tachiyomi.ui.player.settings.AdvancedPlayerPreferences
import eu.kanade.tachiyomi.ui.player.settings.AudioPreferences
import eu.kanade.tachiyomi.ui.player.settings.GesturePreferences
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import eu.kanade.tachiyomi.ui.player.settings.SubtitlePreferences
import eu.kanade.tachiyomi.ui.player.utils.ChapterUtils.Companion.getStringRes
import eu.kanade.tachiyomi.util.system.powerManager
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import `is`.xyz.mpv.MPV
import `is`.xyz.mpv.MPVNode
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.launchUI
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR

import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Calendar
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.time.Duration.Companion.milliseconds

class PlayerActivity : BaseActivity() {
    // ANZ -->
    private var httpServer: HttpServer? = null
    internal val viewModel by viewModels<PlayerViewModel>()
    internal val player by lazy { AniyomiMPVView(this, null) }
    // ANZ <--
    private val mpv by lazy { viewModel.mpv }
    private val playerObserver by lazy { PlayerObserver(this) }
    private val windowInsetsController by lazy { WindowCompat.getInsetsController(window, window.decorView) }
    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private val inputMethodManager by lazy { getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager }

    private var mediaSession: MediaSession? = null
    private val gesturePreferences: GesturePreferences = Injekt.get()
    private val playerPreferences: PlayerPreferences = Injekt.get()
    private val audioPreferences: AudioPreferences = Injekt.get()
    private val advancedPlayerPreferences: AdvancedPlayerPreferences = Injekt.get()
    private val subtitlePreferences: SubtitlePreferences = Injekt.get()

    // ANK -->
    private val mpvConfig: MpvConfig = Injekt.get()
    // ANK <--

    // Cast -->
    val castManager: CastManager by lazy { CastManager(this, Injekt.get()) }
    // <-- Cast

    // AM (CONNECTIONS) -->
    private val connectionsPreferences: ConnectionsPreferences = Injekt.get()
    // <-- AM (CONNECTIONS)

    private var audioFocusRequest: AudioFocusRequestCompat? = null
    private var restoreAudioFocus: () -> Unit = {}

    private var pipRect: Rect? = null
    val isPipSupportedAndEnabled by lazy {
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
            playerPreferences.enablePip().get()
    }

    private var pipReceiver: BroadcastReceiver? = null

    // ANZ -->
    /**
     * True from the moment PiP is entered until the activity is resumed again.
     *
     * A dismissed PiP window and one expanded back to full screen both produce
     * `onPictureInPictureModeChanged(false)`, and the ordering of that callback relative to
     * `onStop()` is not guaranteed — on some devices `isInPictureInPictureMode` still reports
     * `true` inside `onStop()`. The only reliable discriminator is *what happens next*: expanding
     * resumes the activity, dismissing stops it. So this flag is cleared in `onResume()` and
     * consulted in `onStop()`; a stop that still has it set left PiP without resuming, i.e. the
     * user dismissed the window.
     */
    private var wasInPictureInPictureMode = false
    // ANZ <--

    private val noisyReceiver = object : BroadcastReceiver() {
        var initialized = false
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                viewModel.pause()
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    companion object {
        fun newIntent(
            context: Context,
            animeId: Long?,
            episodeId: Long?,
            hostList: List<Hoster>? = null,
            hostIndex: Int? = null,
            vidIndex: Int? = null,
        ): Intent {
            return Intent(context, PlayerActivity::class.java).apply {
                putExtra("animeId", animeId)
                putExtra("episodeId", episodeId)
                hostIndex?.let { putExtra("hostIndex", it) }
                vidIndex?.let { putExtra("vidIndex", it) }
                hostList?.let { putExtra("hostList", it.serialize()) }
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }

        // ANK -->
        /** Upper bound on how long player startup blocks on a pending mpv config copy. */
        private const val MPV_COPY_AWAIT_TIMEOUT_MS = 5_000L
        // ANK <--

        // ANK -->
        /** mpv option names start alphanumeric and hold nothing but these; anything else is not one. */
        private val MPV_OPTION_NAME_REGEX = Regex("^[a-zA-Z0-9][a-zA-Z0-9_-]*$")

        /** A value made up of only these needs none of mpv's escaping mechanisms. */
        private val MPV_PLAIN_OPTION_VALUE_REGEX = Regex("^[a-zA-Z0-9_.:/+-]*$")
        // ANK <--
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        val animeId = intent.extras?.getLong("animeId") ?: -1
        val episodeId = intent.extras?.getLong("episodeId") ?: -1
        val hostList = intent.extras?.getString("hostList") ?: ""
        val hostIndex = intent.extras?.getInt("hostIndex") ?: -1
        val vidIndex = intent.extras?.getInt("vidIndex") ?: -1
        if (animeId == -1L || episodeId == -1L) {
            finish()
            return
        }
        NotificationReceiver.dismissNotification(
            this,
            animeId.hashCode(),
            Notifications.ID_NEW_EPISODES,
        )

        viewModel.saveCurrentEpisodeWatchingProgress()

        lifecycleScope.launchNonCancellable {
            viewModel.updateIsLoadingEpisode(true)
            viewModel.updateIsLoadingHosters(true)

            val initResult = viewModel.init(animeId, episodeId, hostList, hostIndex, vidIndex)
            if (!initResult.second.getOrDefault(false)) {
                val exception = initResult.second.exceptionOrNull() ?: IllegalStateException(
                    "Unknown error",
                )
                withUIContext {
                    setInitialEpisodeError(exception)
                }
            }

            viewModel.updateIsLoadingHosters(false)

            // ANZ -->
            viewModel.loadHosters(
                source = viewModel.currentSource.value!!,
                hosterList = initResult.first.hosterList ?: emptyList(),
                hosterIndex = initResult.first.videoIndex.first,
                videoIndex = initResult.first.videoIndex.second,
            )
            // ANZ <--
        }

        setIntent(intent)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        registerSecureActivity(this)
        super.onCreate(savedInstanceState)

        // ANK -->
        // Registers before setupPlayerMPV() so MpvConfig stops wiping the config directory for as
        // long as this player lives.
        mpvConfig.onPlayerCreated()
        // ANK <--

        setupPlayerMPV()
        setupPlayerAudio()
        setupMediaSession()
        setupPlayerOrientation()



        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            runOnUiThread {
                toast(throwable.message)
            }
            logcat(LogPriority.ERROR, throwable)
            finish()
        }

        viewModel.eventFlow
            .onEach { event ->
                when (event) {
                    is PlayerViewModel.Event.SavedImage -> {
                        onSaveImageResult(event.result)
                    }
                    is PlayerViewModel.Event.ShareImage -> {
                        onShareImageResult(event.uri, event.seconds)
                    }
                    is PlayerViewModel.Event.SetCoverResult -> {
                        onSetAsArtResult(event.result, event.artType)
                    }
                    is PlayerViewModel.Event.ShowToast -> {
                        showToast(stringResource(event.stringResource))
                    }
                    is PlayerViewModel.Event.ShowToastString -> {
                        showToast(event.string)
                    }
                    is PlayerViewModel.Event.ChangeEpisode -> {
                        changeEpisode(event.episodeId, event.autoPlay)
                    }
                    is PlayerViewModel.Event.SetVideo -> {
                        setVideo(event.video)
                    }
                    is PlayerViewModel.Event.SetStatusBar -> {
                        if (event.show) {
                            windowInsetsController.show(WindowInsetsCompat.Type.statusBars())
                        } else {
                            windowInsetsController.hide(WindowInsetsCompat.Type.statusBars())
                        }
                    }
                    is PlayerViewModel.Event.SetBrightness -> {
                        window.attributes = window.attributes.apply {
                            screenBrightness = event.brightness
                        }
                    }
                    is PlayerViewModel.Event.ChangeVideoAspect -> {
                        // ANZ -->
                        changeVideoAspect(event.aspect, event.showUpdate)
                        // ANZ <--
                    }
                    PlayerViewModel.Event.CycleRotations -> {
                        cycleRotations()
                    }
                    is PlayerViewModel.Event.SetKeyboard -> {
                        if (event.show) {
                            forceShowSoftwareKeyboard()
                        } else {
                            forceHideSoftwareKeyboard()
                        }
                    }
                    PlayerViewModel.Event.ToggleKeyboard -> {
                        toggleShowSoftwareKeyboard()
                    }
                    // ANK -->
                    is PlayerViewModel.Event.SetVideoLoadError -> {
                        setInitialEpisodeError(event.error)
                    }
                    // ANK <--
                }
            }
            .launchIn(lifecycleScope)

        // AM (DISCORD) -->
        viewModel.viewModelScope.launchIO {
            updateDiscordRPC(exitingPlayer = false)
        }
        // <-- AM (DISCORD)

        // Cast -->
        castManager
        // <-- Cast

        setContent {
            TachiyomiTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    AndroidView(
                        factory = { player },
                        modifier = Modifier.onGloballyPositioned {
                            pipRect = run {
                                val boundsInWindow = it.boundsInWindow()
                                Rect(
                                    boundsInWindow.left.toInt(),
                                    boundsInWindow.top.toInt(),
                                    boundsInWindow.right.toInt(),
                                    boundsInWindow.bottom.toInt(),
                                )
                            }
                        },
                    )
                    PlayerControls(
                        viewModel = viewModel,
                        castManager = castManager, // Pass the castManager instance
                        // ANZ -->
                        onBackPress = { backPressed() },
                        // ANZ <--
                    )
                }
            }
        }

        // ANK -->
        // Migrate system back gesture handling to OnBackPressedDispatcher
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = backPressed()
            },
        )
        // ANK <--

        onNewIntent(this.intent)
    }

    override fun onDestroy() {
        player.isExiting = true

        // ANZ -->
        val serverToStop = httpServer
        httpServer = null
        if (serverToStop != null) {
            lifecycleScope.launchIO {
                runCatching { serverToStop.stop() }
            }
        }
        // ANZ <--

        audioFocusRequest?.let {
            AudioManagerCompat.abandonAudioFocusRequest(audioManager, it)
        }
        audioFocusRequest = null

        mediaSession?.let {
            it.isActive = false
            it.release()
        }

        if (noisyReceiver.initialized) {
            unregisterReceiver(noisyReceiver)
            noisyReceiver.initialized = false
        }

        mpv.removeLogObserver(playerObserver)
        mpv.removeObserver(playerObserver)
        // ANK -->
        // `mpv` is owned by the retained PlayerViewModel and must survive activity
        // recreation (e.g. config changes), so it's closed in onCleared() instead of here.
        // mpv.close()
        // ANK <--
        castManager.cleanup()

        // ANK -->
        mpvConfig.onPlayerDestroyed()
        // ANK <--

        // AM (DISCORD) -->
        updateDiscordRPC(exitingPlayer = true)
        // <-- AM (DISCORD)

        // ANZ -->
        pipReceiver?.let {
            runCatching { unregisterReceiver(it) }
            pipReceiver = null
        }
        runCatching { viewModel.pause() }
        // ANZ <--

        super.onDestroy()
    }

    override fun onPause() {
        viewModel.saveCurrentEpisodeWatchingProgress()

        // Maintain active Cast session
        castManager.maintainCastSessionBackground()

        // AM (DISCORD) -->
        updateDiscordRPC(exitingPlayer = true)
        // <-- AM (DISCORD)

        if (isInPictureInPictureMode) {
            super.onPause()
            return
        }

        player.isExiting = true

        // ANZ -->
        val serverToStop = httpServer
        httpServer = null
        if (serverToStop != null) {
            lifecycleScope.launchIO {
                runCatching { serverToStop.stop() }
            }
        }
        // ANZ <--
        // ANZ -->
        if (isFinishing) {
            viewModel.deletePendingEpisodes()
        }
        viewModel.pause()
        // ANZ <--

        super.onPause()
    }

    override fun onStop() {
        window.attributes.screenBrightness.let {
            if (playerPreferences.rememberPlayerBrightness().get() && it != -1f) {
                playerPreferences.playerBrightnessValue().set(it)
            }
        }

        // ANZ -->
        // Either signal means we are leaving a PiP window: the latched flag covers devices that
        // report the exit callback before onStop(), and isInPictureInPictureMode covers devices
        // that report it after (or auto-enter without latching). A screen-off stop is excluded by
        // isInteractive so that locking the phone does not kill the player.
        if ((wasInPictureInPictureMode || isInPictureInPictureMode) && powerManager.isInteractive) {
            // Left PiP without resuming, so the user dismissed the window: stop playback and tear
            // the player task down.
            player.isExiting = true
            viewModel.saveCurrentEpisodeWatchingProgress()
            viewModel.deletePendingEpisodes()
            viewModel.pause()
            finishAndRemoveTask()
        } else if (isFinishing) {
            player.isExiting = true
            viewModel.saveCurrentEpisodeWatchingProgress()
            viewModel.deletePendingEpisodes()
            val serverToStop = httpServer
            httpServer = null
            if (serverToStop != null) {
                lifecycleScope.launchIO {
                    runCatching { serverToStop.stop() }
                }
            }
            viewModel.pause()
        } else if (!isInPictureInPictureMode || !powerManager.isInteractive) {
            viewModel.saveCurrentEpisodeWatchingProgress()
            viewModel.pause()
        }
        // ANZ <--

        super.onStop()
    }

    override fun onUserLeaveHint() {
        if (isPipSupportedAndEnabled && viewModel.paused.value == false && playerPreferences.pipOnExit().get()) {
            enterPictureInPictureMode()
        }
        super.onUserLeaveHint()
    }

    // ANK -->
    private fun backPressed() {
        // ANK <--
        if (isPipSupportedAndEnabled && viewModel.paused.value == false && playerPreferences.pipOnExit().get()) {
            if (viewModel.sheetShown.value == Sheets.None &&
                viewModel.panelShown.value == Panels.None &&
                viewModel.dialogShown.value == Dialogs.None
            ) {
                enterPictureInPictureMode()
            }
            // ANK -->
            return
        }

        // Default behavior: finish the activity
        // ANZ -->
        if (isTaskRoot) {
            finishAndRemoveTask()
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                },
            )
            return
        }
        // ANZ <--
        finish()
        // ANK <--
    }

    override fun onStart() {
        super.onStart()
        setPictureInPictureParams(createPipParams())
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LOW_PROFILE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        windowInsetsController.hide(WindowInsetsCompat.Type.navigationBars())
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = if (playerPreferences.playerFullscreen().get()) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
            }
        }

        if (playerPreferences.rememberPlayerBrightness().get()) {
            playerPreferences.playerBrightnessValue().get().let {
                if (it != -1f) viewModel.changeBrightnessTo(it)
            }
        }

        // AM (DISCORD) -->
        updateDiscordRPC(exitingPlayer = false)
        // <-- AM (DISCORD)

        castManager.apply {
            // Register session listener cast
            registerSessionListener()

            // Update current status of cast
            if (castState.value == CastManager.CastState.CONNECTED) {
                updateCastState(CastManager.CastState.CONNECTED)
            }
            // Synchronize initial status with viewmodel
            viewModel.isCasting.value = castState.value == CastManager.CastState.CONNECTED
        }
    }

    private fun UniFile.writeText(text: String) {
        this.openOutputStream().use {
            it.write(text.toByteArray())
        }
    }

    private fun setupPlayerMPV() {
        val mpvDir = UniFile.fromFile(applicationContext.filesDir)?.createDirectory(MPV_DIR)
            ?: run {
                logcat(LogPriority.ERROR) { "Failed to create MPV directory: $MPV_DIR in ${applicationContext.filesDir}" }
                return
            }

        val mpvConfFile = mpvDir.createFile("mpv.conf")!!
        advancedPlayerPreferences.mpvConf().get().let { mpvConfFile.writeText(it) }
        val mpvInputFile = mpvDir.createFile("input.conf")!!
        advancedPlayerPreferences.mpvInput().get().let { mpvInputFile.writeText(it) }

        // ANK -->
        // mpv reads scripts/, script-opts/ and shaders/ during init, so it must not start while
        // MpvConfig is midway through deleting and rewriting them.
        val copied = runBlocking {
            withTimeoutOrNull(MPV_COPY_AWAIT_TIMEOUT_MS.milliseconds) { mpvConfig.awaitCopy() } != null
        }
        if (!copied) {
            logcat(LogPriority.WARN) { "Timed out waiting for the mpv config copy; initializing anyway" }
        }
        // ANK <--

        player.init(mpv)

        val showBlackBars = if (subtitlePreferences.subtitleBlackBars().get()) "yes" else "no"
        mpv.setOptionString("sub-ass-force-margins", showBlackBars)
        mpv.setOptionString("sub-use-margins", showBlackBars)
        mpv.addLogObserver(playerObserver)
        mpv.addObserver(playerObserver)
    }

    private fun setupPlayerAudio() {
        with(audioPreferences) {
            audioChannels().get().let { mpv.setPropertyString(it.property, it.value) }

            val request = AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN).also {
                it.setAudioAttributes(
                    AudioAttributesCompat.Builder().setUsage(AudioAttributesCompat.USAGE_MEDIA)
                        .setContentType(AudioAttributesCompat.CONTENT_TYPE_MUSIC).build(),
                )
                it.setOnAudioFocusChangeListener(audioFocusChangeListener)
            }.build()
            AudioManagerCompat.requestAudioFocus(audioManager, request).let {
                if (it == AudioManager.AUDIOFOCUS_REQUEST_FAILED) return@let
                audioFocusRequest = request
            }
        }
    }

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener {
        when (it) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            -> {
                val oldRestore = restoreAudioFocus
                val wasPlayerPaused = viewModel.paused.value ?: false
                viewModel.pause()
                restoreAudioFocus = {
                    oldRestore()
                    if (!wasPlayerPaused) viewModel.unpause()
                }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                mpv.command("multiply", "volume", "0.5")
                restoreAudioFocus = {
                    mpv.command("multiply", "volume", "2")
                }
            }

            AudioManager.AUDIOFOCUS_GAIN -> {
                restoreAudioFocus()
                restoreAudioFocus = {}
            }

            AudioManager.AUDIOFOCUS_REQUEST_FAILED -> {
                logcat(LogPriority.DEBUG) { "didn't get audio focus" }
            }
        }
    }

    override fun onResume() {
        // ANZ -->
        // Resuming means the PiP window was expanded back to full screen, not dismissed.
        wasInPictureInPictureMode = false
        // ANZ <--

        // Reconnect cast if it was active
        castManager.apply {
            reconnect()
            registerSessionListener()
        }

        // AM (DISCORD) -->
        updateDiscordRPC(exitingPlayer = false)
        // <-- AM (DISCORD)

        if (!player.isExiting) {
            super.onResume()
            return
        }

        player.isExiting = false
        super.onResume()

        viewModel.currentVolume.update {
            audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).also {
                if (it < viewModel.maxVolume) viewModel.changeMPVVolumeTo(100)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        if (!isInPictureInPictureMode) {
            // ANZ -->
            // Restoring instead of applying the stored VideoAspect keeps a custom aspect ratio
            // alive across rotation; applying the preset cleared `video-aspect-override`.
            viewModel.restoreAspectRatio()
            // ANZ <--
        } else {
            viewModel.hideControls()
        }
        super.onConfigurationChanged(newConfig)
    }

    fun showToast(message: String) {
        runOnUiThread { toast(message) }
    }

    // A bunch of observers

    @Suppress("unused")
    internal fun onObserverEvent(property: String, value: Long) {
        if (player.isExiting) return
    }

    @Suppress("unused")
    internal fun onObserverEvent(property: String) {
        if (player.isExiting) return
    }

    internal fun onObserverEvent(property: String, value: Boolean) {
        if (player.isExiting) return
        when (property) {
            "pause" if value -> {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

                // ANK -->
                updateDiscordRPC(exitingPlayer = false)
                // ANK <--
            }
            "pause" -> {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

                // ANK -->
                updateDiscordRPC(exitingPlayer = false)
                // ANK <--
            }
            "eof-reached" -> endFile(value)
        }
    }

    internal fun onObserverEvent(property: String, value: String) {
        if (player.isExiting) return
        when (property.substringBeforeLast("/")) {
            "user-data/aniyomi" -> viewModel.handleLuaInvocation(property, value)
        }
    }

    @Suppress("unused")
    internal fun onObserverEvent(property: String, value: Double) {
        if (player.isExiting) return
        when (property) {
            "video-params/aspect" -> {
                if (isPipSupportedAndEnabled) createPipParams()
                // ANZ -->
                // Video params are known at this point (fresh file, file switch or rotation),
                // which is the reliable moment to re-apply a remembered custom aspect ratio.
                viewModel.restoreAspectRatio()
                // ANZ <--
            }
        }
    }

    @Suppress("unused")
    internal fun onObserverEvent(property: String, value: MPVNode) {
        if (player.isExiting) return
    }

    internal fun event(eventId: Int, node: MPVNode) {
        if (player.isExiting) return
        when (eventId) {
            MPV.mpvEvent.MPV_EVENT_FILE_LOADED -> {
                viewModel.viewModelScope.launchIO { fileLoaded() }
            }
            MPV.mpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
                player.isExiting = false
                // ANZ -->
                viewModel.restoreAspectRatio()
                // ANZ <--
            }
            MPV.mpvEvent.MPV_EVENT_END_FILE -> {
                val errorNode = node.asMap()?.get("file_error") ?: return
                var errorMessage = errorNode.asString() ?: "Error: File ended"

                val httpError = playerObserver.httpError
                if (!httpError.isNullOrEmpty()) {
                    errorMessage += ": $httpError"
                    playerObserver.httpError = null
                }

                logcat(LogPriority.ERROR) { errorMessage }
                showToast(errorMessage)

                viewModel.setCurrentVideoError()

                if (playerPreferences.switchOnFailure().get()) {
                    if (!viewModel.loadBestVideo()) {
                        finish()
                    }
                } else {
                    viewModel.setIsStopped(true)
                }
            }
        }
    }

    // ANZ -->
    fun createPipParams(isPaused: Boolean? = null): PictureInPictureParams {
    // ANZ <--
        val builder = PictureInPictureParams.Builder()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val anime = viewModel.currentAnime.value
            val episode = viewModel.currentEpisode.value

            if (anime != null && episode != null) {
                builder.setTitle(anime.title).setSubtitle(episode.name)
            }
        }
        // ANZ -->
        val pausedState = isPaused ?: (viewModel.paused.value ?: true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val autoEnter = playerPreferences.pipOnExit().get()
            builder.setAutoEnterEnabled(!pausedState && autoEnter)
            builder.setSeamlessResizeEnabled(!pausedState && autoEnter)
        }
        builder.setActions(
            createPipActions(
                context = this,
                isPaused = pausedState,
                replaceWithPrevious = playerPreferences.pipReplaceWithPrevious().get(),
                playlistCount = viewModel.currentPlaylist.value.size,
                playlistPosition = viewModel.getCurrentEpisodeIndex(),
            ),
        )
        // ANZ <--
        builder.setSourceRectHint(pipRect)
        // ANZ -->
        if (mpv.isInitialized) {
            mpv.getPropertyInt("video-params/h")?.let { height ->
                player.getVideoOutAspect()?.let { aspect ->
                    val width = height * aspect
                    val rational = Rational(height, width.toInt()).toFloat()
                    if (rational in 0.42..2.38) builder.setAspectRatio(Rational(width.toInt(), height))
                }
            }
        }
        // ANZ <--
        return builder.build()
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        // ANZ -->
        // Latch that we entered PiP. Whether leaving it was a dismissal or an expand is decided in
        // onStop()/onResume(), because this callback's ordering against them is not guaranteed.
        if (isInPictureInPictureMode) wasInPictureInPictureMode = true
        // ANZ <--
        if (!isInPictureInPictureMode) {
            pipReceiver?.let {
                unregisterReceiver(pipReceiver)
                pipReceiver = null
            }
        } else {
            setPictureInPictureParams(createPipParams())
            viewModel.hideControls()
            viewModel.hideSeekBar()
            viewModel.isBrightnessSliderShown.update { false }
            viewModel.isVolumeSliderShown.update { false }
            viewModel.sheetShown.update { Sheets.None }
            pipReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent == null || intent.action != PIP_INTENTS_FILTER) return
                    when (intent.getIntExtra(PIP_INTENT_ACTION, 0)) {
                        // ANZ -->
                        PIP_PAUSE -> {
                            viewModel.pause()
                            setPictureInPictureParams(createPipParams(isPaused = true))
                        }
                        PIP_PLAY -> {
                            viewModel.unpause()
                            setPictureInPictureParams(createPipParams(isPaused = false))
                        }
                        // ANZ <--
                        PIP_NEXT -> viewModel.changeEpisode(false)
                        PIP_PREVIOUS -> viewModel.changeEpisode(true)
                        PIP_SKIP -> viewModel.seekBy(10)
                    }
                    // ANZ -->
                    // Remote action update was already dispatched above for pause/play;
                    // refresh once more for track changes or seek actions if needed.
                    if (intent.getIntExtra(PIP_INTENT_ACTION, 0) !in listOf(PIP_PAUSE, PIP_PLAY)) {
                        setPictureInPictureParams(createPipParams())
                    }
                    // ANZ <--
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(pipReceiver, IntentFilter(PIP_INTENTS_FILTER), RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(pipReceiver, IntentFilter(PIP_INTENTS_FILTER))
            }
        }

        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
    }

    private fun setupPlayerOrientation() {
        if (player.isExiting) return
        // ANZ -->
        val target = when (playerPreferences.defaultPlayerOrientationType().get()) {
            PlayerOrientation.Free -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
            PlayerOrientation.Video -> if ((player.getVideoOutAspect() ?: 0.0) > 1.0) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }

            PlayerOrientation.Portrait -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            PlayerOrientation.ReversePortrait -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            PlayerOrientation.SensorPortrait -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            PlayerOrientation.Landscape -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            PlayerOrientation.ReverseLandscape -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            PlayerOrientation.SensorLandscape -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        if (requestedOrientation != target) {
            requestedOrientation = target
        }
        // ANZ <--
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                viewModel.changeVolumeBy(1)
                viewModel.displayVolumeSlider()
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                viewModel.changeVolumeBy(-1)
                viewModel.displayVolumeSlider()
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> viewModel.handleLeftDoubleTap()
            KeyEvent.KEYCODE_DPAD_RIGHT -> viewModel.handleRightDoubleTap()
            KeyEvent.KEYCODE_SPACE -> viewModel.pauseUnpause()
            KeyEvent.KEYCODE_MEDIA_STOP -> finishAndRemoveTask()

            KeyEvent.KEYCODE_MEDIA_REWIND -> viewModel.handleLeftDoubleTap()
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> viewModel.handleRightDoubleTap()

            // other keys should be bound by the user in input.conf ig
            else -> {
                // ANZ -->
                if (!isTextInputActive()) event?.let { player.onKey(it) }
                // ANZ <--
                super.onKeyDown(keyCode, event)
            }
        }
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        // ANZ -->
        if (!isTextInputActive() && player.onKey(event!!)) return true
        // ANZ <--
        return super.onKeyUp(keyCode, event)
    }

    private fun setupMediaSession() {
        val previousAction = gesturePreferences.mediaPreviousGesture().get()
        val playAction = gesturePreferences.mediaPlayPauseGesture().get()
        val nextAction = gesturePreferences.mediaNextGesture().get()

        mediaSession = MediaSession(this, "PlayerActivity").apply {
            setCallback(
                object : MediaSession.Callback() {
                    override fun onPlay() {
                        when (playAction) {
                            SingleActionGesture.None -> {}
                            SingleActionGesture.Seek -> {}
                            SingleActionGesture.PlayPause -> {
                                super.onPlay()
                                viewModel.unpause()
                                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            }
                            SingleActionGesture.Custom -> {
                                mpv.command("keypress", CustomKeyCodes.MediaPlay.keyCode)
                            }

                            SingleActionGesture.Switch -> {}
                        }
                    }

                    override fun onPause() {
                        // Cast -->
                        castManager.apply {
                            // Release resources only if not in PIP
                            if (!isInPictureInPictureMode) {
                                unregisterSessionListener()
                            }

                            // If you are transmitting, keep an active session
                            if (castState.value == CastManager.CastState.CONNECTED) {
                                maintainCastSessionBackground()
                            }
                        }
                        //
                        when (playAction) {
                            SingleActionGesture.None -> {}
                            SingleActionGesture.Seek -> {}
                            SingleActionGesture.PlayPause -> {
                                super.onPause()
                                viewModel.pause()
                                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            }
                            SingleActionGesture.Custom -> {
                                mpv.command("keypress", CustomKeyCodes.MediaPlay.keyCode)
                            }

                            SingleActionGesture.Switch -> {}
                        }
                    }

                    override fun onSkipToPrevious() {
                        when (previousAction) {
                            SingleActionGesture.None -> {}
                            SingleActionGesture.Seek -> {
                                viewModel.leftSeek()
                            }
                            SingleActionGesture.PlayPause -> {
                                viewModel.pauseUnpause()
                            }
                            SingleActionGesture.Custom -> {
                                mpv.command("keypress", CustomKeyCodes.MediaPrevious.keyCode)
                            }

                            SingleActionGesture.Switch -> viewModel.changeEpisode(true)
                        }
                    }

                    override fun onSkipToNext() {
                        when (nextAction) {
                            SingleActionGesture.None -> {}
                            SingleActionGesture.Seek -> {
                                viewModel.rightSeek()
                            }
                            SingleActionGesture.PlayPause -> {
                                viewModel.pauseUnpause()
                            }
                            SingleActionGesture.Custom -> {
                                mpv.command("keypress", CustomKeyCodes.MediaNext.keyCode)
                            }

                            SingleActionGesture.Switch -> viewModel.changeEpisode(false)
                        }
                    }

                    override fun onStop() {
                        super.onStop()
                        isActive = false
                        this@PlayerActivity.onStop()
                    }
                },
            )
            setPlaybackState(
                PlaybackState.Builder()
                    .setActions(
                        PlaybackState.ACTION_PLAY or
                            PlaybackState.ACTION_PAUSE or
                            PlaybackState.ACTION_STOP or
                            PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                            PlaybackState.ACTION_SKIP_TO_NEXT,
                    )
                    .build(),
            )
            isActive = true
        }

        val filter = IntentFilter().apply { addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY) }
        registerReceiver(noisyReceiver, filter)
        noisyReceiver.initialized = true
    }

    // ==== END MPVKT ====

    override fun onSaveInstanceState(outState: Bundle) {
        if (!isChangingConfigurations) {
            viewModel.onSaveInstanceStateNonConfigurationChange()
        }
        super.onSaveInstanceState(outState)
    }

    /**
     * Switches to the episode based on [episodeId],
     * @param episodeId id of the episode to switch the player to
     * @param autoPlay whether the episode is switching due to auto play
     */
    internal fun changeEpisode(episodeId: Long?, autoPlay: Boolean = false) {
        viewModel.sheetShown.update { _ -> Sheets.None }
        viewModel.panelShown.update { _ -> Panels.None }
        viewModel.pause()
        viewModel.clearTracks()
        viewModel.isLoading.update { _ -> true }
        viewModel.resetHosterState()

        // ANZ -->
        lifecycleScope.launchIO {
            viewModel.updateIsLoadingEpisode(true)
            viewModel.updateIsLoadingHosters(true)
            viewModel.cancelHosterVideoLinksJob()

            val pipEpisodeToasts = playerPreferences.pipEpisodeToasts().get()
            val switchMethod = viewModel.loadEpisode(episodeId)

            viewModel.updateIsLoadingHosters(false)

            when (switchMethod) {
                null -> {
                    if (viewModel.currentAnime.value != null && !autoPlay) {
                        launchUI { toast(MR.strings.no_next_episode) }
                    }
                    viewModel.isLoading.update { _ -> false }
                }

                else -> {
                    if (switchMethod.hosterList != null) {
                        when {
                            switchMethod.hosterList.isEmpty() -> withUIContext {
                                setInitialEpisodeError(
                                    PlayerViewModel.ExceptionWithStringResource(
                                        "Hoster list is empty",
                                        MR.strings.no_hosters,
                                    ),
                                )
                            }
                            else -> {
                                viewModel.loadHosters(
                                    source = switchMethod.source,
                                    hosterList = switchMethod.hosterList,
                                    hosterIndex = -1,
                                    videoIndex = -1,
                                )
                            }
                        }
                    } else {
                        logcat(LogPriority.ERROR) { "Error getting links" }
                    }

                    if (isInPictureInPictureMode && pipEpisodeToasts) {
                        launchUI { toast(switchMethod.episodeTitle) }
                    }
                }
            }
        }
        // ANZ <--
    }

    fun setVideo(video: Video?, position: Long? = null) {
        if (player.isExiting) return
        if (video == null) return

        viewModel.setIsStopped(false)
        setHttpOptions(video)

        if (viewModel.isLoadingEpisode.value) {
            viewModel.currentEpisode.value?.let { episode ->
                val preservePos = playerPreferences.preserveWatchingPosition().get()
                val resumePosition = position
                    ?: if (episode.seen && !preservePos) {
                        0L
                    } else {
                        episode.last_second_seen
                    }
                mpv.command("set", "start", "${resumePosition / 1000F}")
            }
        } else {
            viewModel.pos.value?.let {
                mpv.command("set", "start", "$it")
            }
        }

        // ANZ -->
        val serverToStop = httpServer
        httpServer = null
        if (serverToStop != null) {
            lifecycleScope.launchIO {
                runCatching { serverToStop.stop() }
            }
        }
        // ANZ <--

        if (video.videoUrl.startsWith(TorrentServerUtils.hostUrl) ||
            video.videoUrl.startsWith("magnet") ||
            video.videoUrl.endsWith(".torrent")
        ) {
            launchIO {
                TorrentServerService.start()
                TorrentServerService.wait(10)
                // ANK -->
                torrentLinkHandler(video.videoUrl, video.videoTitle, video.mpvArgs)
                // ANK <--
            }
        } else {
            // ANZ -->
            val httpSource = viewModel.currentSource.value as? AnimeHttpSource
            var videoUrl: String = video.videoUrl
            if (video.usesHttpServer() && httpSource != null) {
                val port = try {
                    httpServer = httpSource.createHttpServer()
                    httpServer?.start()
                    httpServer?.listeningPort ?: 0
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Failed to start http server" }
                    return
                }
                val newVideo = video.copyHttpServer(port)
                videoUrl = newVideo.videoUrl
                viewModel.updateVideo(newVideo)
            }
            loadFile(parseVideoUrl(videoUrl)!!, video.mpvArgs)
            // ANZ <--
        }

        // AM (DISCORD) -->
        updateDiscordRPC(exitingPlayer = false)
        // <-- AM (DISCORD)
    }

    // ANK -->
    /**
     * Issues a `loadfile` for [url], appending the per-file options that have to apply no matter
     * which branch started the load. Keeping this in one place is what stops the torrent path from
     * inheriting the previous file's `sid`/`aid`.
     */
    private fun loadFile(url: String, mpvArgs: List<Pair<String, String>> = emptyList()) {
        // We handle selecting these in the viewmodel
        val forcedOptions = listOf(
            Pair("sid", "no"),
            Pair("aid", "no"),
        )

        mpv.command(
            "loadfile",
            url,
            "replace",
            "0",
            formatMpvOptions(mpvArgs + forcedOptions),
        )
    }

    /**
     * Formats [options] for the `options` argument of `loadfile`.
     *
     * mpv parses that argument as its own `key=value` list and never hands it to a shell, so the
     * FFmpeg sanitizers must not be reused here: they reject values mpv accepts (`$`, `(`, `\`, or
     * anything starting with `-`) and they *throw*, which would tear down the event collector that
     * calls [setVideo] -- or crash the app outright from [torrentLinkHandler]'s coroutine.
     *
     * Any value the list syntax itself would otherwise eat -- one holding a `,`, a quote, or
     * whitespace -- is emitted with mpv's `%<bytes>%<value>` escaping. Quoting cannot do the job:
     * mpv's quoted form ends at the first `"` and has no escape for a literal one, so a value
     * containing a quote used to produce an unparsable list and lose every option in it. Option
     * names are validated rather than escaped, since a name mpv could not accept is a mistake in
     * the extension either way.
     */
    private fun formatMpvOptions(options: List<Pair<String, String>>): String {
        val (valid, invalid) = options.partition { (option, _) -> MPV_OPTION_NAME_REGEX.matches(option) }

        invalid.forEach { (option, _) ->
            logcat(LogPriority.WARN) { "Ignoring mpv option with unusable name: $option" }
        }

        return valid.joinToString(",") { (option, value) ->
            if (MPV_PLAIN_OPTION_VALUE_REGEX.matches(value)) {
                "$option=$value"
            } else {
                "$option=%${value.toByteArray().size}%$value"
            }
        }
    }
    // ANK <--

    private fun torrentLinkHandler(
        videoUrl: String,
        quality: String,
        // ANK -->
        mpvArgs: List<Pair<String, String>> = emptyList(),
        // ANK <--
    ) {
        var index = 0

        // check if link is from localSource
        if (videoUrl.startsWith("content://")) {
            val videoInputStream = applicationContext.contentResolver.openInputStream(videoUrl.toUri())
            val torrent = TorrentServerApi.uploadTorrent(videoInputStream!!, quality, "", "", false)
            val torrentUrl = TorrentServerUtils.getTorrentPlayLink(torrent, 0)
            // ANK -->
            loadFile(torrentUrl, mpvArgs)
            // ANK <--
            return
        }

        // check if link is from magnet, in that check if index is present
        if (videoUrl.startsWith("magnet")) {
            if (videoUrl.contains("index=")) {
                index = try {
                    videoUrl.substringAfter("index=").toInt()
                } catch (_: NumberFormatException) {
                    0
                }
            }
        }

        val currentTorrent = TorrentServerApi.addTorrent(videoUrl, quality, "", "", false)
        val videoTorrentUrl = TorrentServerUtils.getTorrentPlayLink(currentTorrent, index)
        // ANK -->
        loadFile(videoTorrentUrl, mpvArgs)
        // ANK <--
    }

    /**
     * Called from the presenter if the initial load couldn't load the videos of the episode. In
     * this case the activity is closed and a toast is shown to the user.
     */
    private fun setInitialEpisodeError(error: Throwable) {
        if (error is PlayerViewModel.ExceptionWithStringResource) {
            toast(error.stringResource)
        } else {
            toast(error.message)
        }
        logcat(LogPriority.ERROR, error)
        finish()
    }

    private fun parseVideoUrl(videoUrl: String?): String? {
        return videoUrl?.toUri()?.resolveUri(this)
            ?: videoUrl
    }

    private fun setHttpOptions(video: Video) {
        if (viewModel.isEpisodeOnline() != true) return
        val source = viewModel.currentSource.value as? HttpSource ?: return

        val headers = (video.headers ?: source.headers)
            .toMultimap()
            .mapValues { it.value.firstOrNull() ?: "" }
            .toMutableMap()

        val httpHeaderString = headers.map {
            it.key + ": " + it.value.replace(",", "\\,")
        }.joinToString(",")

        mpv.setOptionString("http-header-fields", httpHeaderString)

        // need to fix the cache
        // MPVLib.setOptionString("cache-on-disk", "yes")
        // val cacheDir = File(applicationContext.filesDir, "media").path
        // MPVLib.setOptionString("cache-dir", cacheDir)
    }

    fun onTrackLoadedFailure(url: String) {
        viewModel.onTrackLoadedFailure(url)
    }

    /**
     * Called from the presenter when a screenshot is ready to be shared. It shows Android's
     * default sharing tool.
     */
    private fun onShareImageResult(uri: Uri, seconds: String) {
        val anime = viewModel.currentAnime.value ?: return
        val episode = viewModel.currentEpisode.value ?: return

        val intent = uri.toShareIntent(
            context = applicationContext,
            message = stringResource(MR.strings.share_screenshot_info, anime.title, episode.name, seconds),
        )
        startActivity(intent)
    }

    /**
     * Called from the presenter when a screenshot is saved or fails. It shows a message
     * or logs the event depending on the [result].
     */
    private fun onSaveImageResult(result: PlayerViewModel.SaveImageResult) {
        when (result) {
            is PlayerViewModel.SaveImageResult.Success -> {
                toast(MR.strings.picture_saved)
            }
            is PlayerViewModel.SaveImageResult.Error -> {
                logcat(LogPriority.ERROR, result.error)
            }
        }
    }

    /**
     * Called from the presenter when a screenshot is set as art or fails.
     * It shows a different message depending on the [result].
     */
    private fun onSetAsArtResult(result: SetAsCover, artType: ArtType) {
        toast(
            when (result) {
                SetAsCover.Success ->
                    when (artType) {
                        ArtType.Cover -> MR.strings.cover_updated
                        ArtType.Background -> MR.strings.cover_updated
                        ArtType.Thumbnail -> MR.strings.cover_updated
                    }
                SetAsCover.AddToLibraryFirst -> MR.strings.notification_first_add_to_library
                SetAsCover.Error -> MR.strings.notification_cover_update_failed
            },
        )
    }

    // ANZ -->
    private fun changeVideoAspect(aspect: VideoAspect, showUpdate: Boolean = true) {
    // ANZ <--
        var ratio = -1.0
        val pan: Double
        when (aspect) {
            VideoAspect.Crop -> {
                pan = 1.0
            }

            VideoAspect.Fit -> {
                pan = 0.0
                mpv.setPropertyDouble("panscan", 0.0)
            }

            VideoAspect.Stretch -> {
                val dm = DisplayMetrics()
                windowManager.defaultDisplay.getRealMetrics(dm)
                ratio = dm.widthPixels / dm.heightPixels.toDouble()
                pan = 0.0
            }
        }
        // ANZ -->
        viewModel.setAspect(aspect, pan, ratio, showUpdate)
        // ANZ <--
    }

    private fun cycleRotations() {
        requestedOrientation = when (requestedOrientation) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            -> {
                playerPreferences.defaultPlayerOrientationType().set(PlayerOrientation.SensorPortrait)
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }

            else -> {
                playerPreferences.defaultPlayerOrientationType().set(PlayerOrientation.SensorLandscape)
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        }
    }

    private fun toggleShowSoftwareKeyboard() {
        if (inputMethodManager.isActive) {
            forceHideSoftwareKeyboard()
        } else {
            forceShowSoftwareKeyboard()
        }
    }

    private fun forceShowSoftwareKeyboard() {
        inputMethodManager.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
    }

    private fun forceHideSoftwareKeyboard() {
        inputMethodManager.toggleSoftInput(InputMethodManager.SHOW_IMPLICIT, 0)
    }

    private fun fileLoaded() {
        if (player.isExiting) return

        setMpvOptions()
        setMpvMediaTitle()
        // ANZ -->
        runOnUiThread { setupPlayerOrientation() }
        // ANZ <--
        setupChapters()
        viewModel.checkFileLoaded()
        // ANZ -->
        viewModel.restoreAspectRatio()
        // ANZ <--

        // aniSkip stuff
        viewModel.viewModelScope.launchIO {
            if (viewModel.introSkipEnabled && playerPreferences.aniSkipEnabled().get() &&
                !(playerPreferences.disableAniSkipOnChapters().get() && viewModel.getChapterCount() > 0)
            ) {
                viewModel.aniSkipResponse(viewModel.duration.value)?.let {
                    viewModel.addTimestamps(it)
                }
            }
        }
    }

    private fun setMpvOptions() {
        if (player.isExiting) return
        val video = viewModel.currentVideo.value ?: return

        // Only check for `MPV_ARGS_TAG` on downloaded videos
        if (listOf("file", "content", "data").none { video.videoUrl.startsWith(it) }) {
            return
        }

        try {
            val metadata = mpv.getPropertyString("metadata")?.let {
                Json.decodeFromString<Map<String, String>>(it)
            } ?: return

            val opts = metadata[Video.MPV_ARGS_TAG]
                ?.split(";")
                ?.map { it.split("=", limit = 2) }
                ?: return

            opts.forEach { parts ->
                // AY -->
                if (parts.size == 2) {
                    val (option, value) = parts
                    // <-- AY
                    mpv.setPropertyString(option, value)
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to read video metadata" }
        }
    }

    private fun setupChapters() {
        if (player.isExiting) return

        val timestamps = viewModel.currentVideo.value?.timestamps?.takeIf { it.isNotEmpty() }
            ?.map { timestamp ->
                if (timestamp.name.isEmpty() && timestamp.type != ChapterType.Other) {
                    timestamp.copy(
                        name = timestamp.type.getStringRes()?.let(::stringResource) ?: "",
                    )
                } else {
                    timestamp
                }
            }
            ?: return

        viewModel.addTimestamps(timestamps)
    }

    private fun setMpvMediaTitle() {
        if (player.isExiting) return
        val anime = viewModel.currentAnime.value ?: return
        val episode = viewModel.currentEpisode.value ?: return

        // Write to mpv table
        mpv.setPropertyString("user-data/current-anime/episode-title", episode.name)

        val epNumber = episode.episode_number.let { number ->
            if (ceil(number) == floor(number)) number.toInt() else number
        }.toString().padStart(2, '0')

        val title = stringResource(
            MR.strings.mpv_media_title,
            anime.title,
            epNumber,
            episode.name,
        )

        mpv.setPropertyString("force-media-title", title)
    }

    private fun endFile(eofReached: Boolean) {
        if (eofReached && playerPreferences.autoplayEnabled().get()) {
            viewModel.changeEpisode(previous = false, autoPlay = true)
        }
    }

    // AM (DISCORD) -->
    private fun updateDiscordRPC(exitingPlayer: Boolean) {
        if (!connectionsPreferences.enableDiscordRPC().get()) return

        lifecycleScope.launchIO {
            try {
                if (!exitingPlayer) {
                    val anime = viewModel.currentAnime.value ?: return@launchIO
                    val episode = viewModel.currentEpisode.value ?: return@launchIO
                    val isPaused = viewModel.paused.value == true

                    val (startTime, endTime) = if (isPaused) {
                        Pair(0L, 0L)
                    } else {
                        val currentPosition = (viewModel.pos.value ?: 0).toLong() * 1000
                        val duration = (viewModel.duration.value ?: 1440).toLong() * 1000

                        val startTimestamp = Calendar.getInstance().apply {
                            timeInMillis = System.currentTimeMillis() - currentPosition
                        }
                        val endTimestamp = Calendar.getInstance().apply {
                            timeInMillis = startTimestamp.timeInMillis
                            add(Calendar.MILLISECOND, duration.toInt())
                        }
                        Pair(startTimestamp.timeInMillis, endTimestamp.timeInMillis)
                    }

                    val formattedEpisodeNumber = if (connectionsPreferences.useChapterTitles().get()) {
                        episode.name
                    } else {
                        episode.episode_number.toString()
                    }

                    DiscordRPCService.setPlayerActivity(
                        context = this@PlayerActivity,
                        PlayerData(
                            incognitoMode = viewModel.currentSource.value?.isNsfw() == true || viewModel.incognitoMode,
                            animeId = anime.id,
                            animeTitle = anime.ogTitle,
                            thumbnailUrl = anime.thumbnailUrl ?: anime.ogThumbnailUrl,
                            episodeNumber = formattedEpisodeNumber,
                            startTimestamp = startTime,
                            endTimestamp = endTime,
                            isPaused = isPaused,
                        ),
                    )
                } else {
                    val lastUsedScreen = DiscordRPCService.lastUsedScreen
                    DiscordRPCService.setAnimeScreen(this@PlayerActivity, lastUsedScreen)
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR) { "Error updating Discord RPC: ${e.message}" }
            }
        }
    }

    // ANZ -->
    fun setCustomVideoAspect(ratio: Double, label: String) {
        viewModel.setCustomVideoAspect(ratio, label)
    }

    /**
     * True while a text field owns the input connection, such as the Width/Height inputs of the
     * aspect ratio sheet.
     *
     * Key events must not reach mpv in that state. mpv's default bindings map the digits 1-0 to
     * contrast, brightness, gamma, saturation and volume, and [AniyomiMPVView.onKey] consumes every
     * key it maps, so a typed digit would both change a video setting and never reach the field.
     */
    private fun isTextInputActive(): Boolean = inputMethodManager.isActive

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!isTextInputActive() && player.onKey(event)) return true
        return super.dispatchKeyEvent(event)
    }
    // ANZ <--
}
