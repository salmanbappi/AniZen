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

/**
 * Code is a mix between PlayerViewModel from mpvKt and the former
 * PlayerViewModel from Aniyomi.
 */

package eu.kanade.tachiyomi.ui.player

import android.app.Application
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.Immutable
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import dev.icerock.moko.resources.StringResource
import eu.kanade.domain.anime.interactor.SetAnimeViewerFlags
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.episode.model.toDbEpisode
import eu.kanade.domain.track.interactor.TrackEpisode
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.more.settings.screen.player.custombutton.CustomButtonFetchState
import eu.kanade.presentation.more.settings.screen.player.custombutton.getButtons
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.ChapterType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SerializableHoster.Companion.toHosterList
import eu.kanade.tachiyomi.animesource.model.TimeStamp
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.data.database.models.Episode
import eu.kanade.tachiyomi.data.database.models.toDomainEpisode
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.saver.Image
import eu.kanade.tachiyomi.data.saver.ImageSaver
import eu.kanade.tachiyomi.data.saver.Location
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.anilist.Anilist
import eu.kanade.tachiyomi.data.track.myanimelist.MyAnimeList
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.player.controls.components.IndexedSegment
import eu.kanade.tachiyomi.ui.player.controls.components.sheets.HosterState
import eu.kanade.tachiyomi.ui.player.controls.components.sheets.getChangedAt
import eu.kanade.tachiyomi.ui.player.loader.EpisodeLoader
import eu.kanade.tachiyomi.ui.player.loader.HosterLoader
import eu.kanade.tachiyomi.ui.player.resolveUri
import eu.kanade.tachiyomi.ui.player.settings.AudioPreferences
import eu.kanade.tachiyomi.ui.player.settings.DecoderPreferences
import eu.kanade.tachiyomi.ui.player.settings.GesturePreferences
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import eu.kanade.tachiyomi.ui.player.settings.SubtitlePreferences
import eu.kanade.tachiyomi.ui.player.utils.AniSkipApi
import eu.kanade.tachiyomi.ui.player.utils.ChapterUtils.Companion.getStringRes
import eu.kanade.tachiyomi.ui.player.utils.DefaultStreamPreferenceStore
import eu.kanade.tachiyomi.ui.player.utils.DefaultStreamSelector
import eu.kanade.tachiyomi.ui.player.utils.TrackSelect
import eu.kanade.tachiyomi.ui.reader.SaveImageNotifier
import eu.kanade.tachiyomi.util.editCover
import eu.kanade.tachiyomi.util.episode.filterDownloadedEpisodes
import eu.kanade.tachiyomi.util.lang.byteSize
import eu.kanade.tachiyomi.util.lang.takeBytes
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.storage.cacheImageDir
import eu.kanade.tachiyomi.util.system.DeviceTierManager
import eu.kanade.tachiyomi.util.system.isConnectedToWifi
import eu.kanade.tachiyomi.util.system.toast
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.Utils
import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import eu.kanade.tachiyomi.animesource.model.ThumbnailInfo
import eu.kanade.tachiyomi.animesource.model.TileInfo
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.toLong
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.anime.interactor.GetAnime
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.custombuttons.interactor.GetCustomButtons
import tachiyomi.domain.custombuttons.model.CustomButton
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.episode.interactor.GetEpisodesByAnimeId
import tachiyomi.domain.episode.interactor.UpdateEpisode
import tachiyomi.domain.episode.model.EpisodeUpdate
import tachiyomi.domain.episode.service.getEpisodeSort
import tachiyomi.domain.history.interactor.GetNextEpisodes
import tachiyomi.domain.history.interactor.LogActivity
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.model.ActivityLog
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.source.service.SourceManager
import eu.kanade.tachiyomi.util.episode.EpisodeSeasonUtils
import eu.kanade.tachiyomi.data.filler.AnimeFillerListFetcher
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.i18n.MR
import tachiyomi.i18n.ank.AMR
import tachiyomi.source.localanime.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.InputStream
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

class PlayerViewModelProviderFactory(
    private val activity: PlayerActivity,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        return PlayerViewModel(activity, extras.createSavedStateHandle()) as T
    }
}

class PlayerViewModel @JvmOverloads constructor(
    private val activity: PlayerActivity,
    private val savedState: SavedStateHandle,
    private val sourceManager: SourceManager = Injekt.get(),
    private val downloadManager: DownloadManager = Injekt.get(),
    private val networkHelper: NetworkHelper = Injekt.get(),
    private val imageSaver: ImageSaver = Injekt.get(),
    private val downloadPreferences: DownloadPreferences = Injekt.get(),
    private val trackPreferences: TrackPreferences = Injekt.get(),
    private val trackEpisode: TrackEpisode = Injekt.get(),
    private val getAnime: GetAnime = Injekt.get(),
    private val getNextEpisodes: GetNextEpisodes = Injekt.get(),
    private val getEpisodesByAnimeId: GetEpisodesByAnimeId = Injekt.get(),
    private val getAnimeCategories: GetCategories = Injekt.get(),
    private val getTracks: GetTracks = Injekt.get(),
    private val upsertHistory: UpsertHistory = Injekt.get(),
    private val updateEpisode: UpdateEpisode = Injekt.get(),
    private val logActivity: LogActivity = Injekt.get(),
    private val setAnimeViewerFlags: SetAnimeViewerFlags = Injekt.get(),
    internal val playerPreferences: PlayerPreferences = Injekt.get(),
    internal val gesturePreferences: GesturePreferences = Injekt.get(),
    private val decoderPreferences: DecoderPreferences = Injekt.get(),
    private val audioPreferences: AudioPreferences = Injekt.get(),
    private val subtitlePreferences: SubtitlePreferences = Injekt.get(),
    private val basePreferences: BasePreferences = Injekt.get(),
    private val getCustomButtons: GetCustomButtons = Injekt.get(),
    private val trackSelect: TrackSelect = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    uiPreferences: UiPreferences = Injekt.get(),
    private val animeFillerListFetcher: AnimeFillerListFetcher = AnimeFillerListFetcher(),
) : ViewModel() {

    private val _currentPlaylist = MutableStateFlow<List<Episode>>(emptyList())
    val currentPlaylist = _currentPlaylist.asStateFlow()

    private val _hasPreviousEpisode = MutableStateFlow(false)
    val hasPreviousEpisode = _hasPreviousEpisode.asStateFlow()

    private val _hasNextEpisode = MutableStateFlow(false)
    val hasNextEpisode = _hasNextEpisode.asStateFlow()

    private val _currentEpisode = MutableStateFlow<Episode?>(null)
    val currentEpisode = _currentEpisode.asStateFlow()

    private val _currentAnime = MutableStateFlow<Anime?>(null)
    val currentAnime = _currentAnime.asStateFlow()

    private val _currentSource = MutableStateFlow<Source?>(null)
    val currentSource = _currentSource.asStateFlow()

    private val _isEpisodeOnline = MutableStateFlow(false)
    val isEpisodeOnline = _isEpisodeOnline.asStateFlow()

    private val _isLoadingEpisode = MutableStateFlow(false)
    val isLoadingEpisode = _isLoadingEpisode.asStateFlow()

    private val _currentDecoder = MutableStateFlow(Decoder.Auto)
    val currentDecoder = _currentDecoder.asStateFlow()

    val mediaTitle = MutableStateFlow("")
    val animeTitle = MutableStateFlow("")

    val isLoading = MutableStateFlow(true)
    val pausedForCache = MutableStateFlow(false)
    val coreIdle = MutableStateFlow(false)
    private val _isStopped = MutableStateFlow(false)
    val isStopped = _isStopped.asStateFlow()

    val playbackSpeed = MutableStateFlow(playerPreferences.playerSpeed().get())
    val isLongPressing = MutableStateFlow(false)

    private val _subtitleTracks = MutableStateFlow<List<VideoTrack>>(emptyList())
    val subtitleTracks = _subtitleTracks.asStateFlow()
    private val _selectedSubtitles = MutableStateFlow(Pair(-1, -1))
    val selectedSubtitles = _selectedSubtitles.asStateFlow()

    private val _audioTracks = MutableStateFlow<List<VideoTrack>>(emptyList())
    val audioTracks = _audioTracks.asStateFlow()
    private val _selectedAudio = MutableStateFlow(-1)
    val selectedAudio = _selectedAudio.asStateFlow()

    val isLoadingTracks = MutableStateFlow(true)
    val isCasting = MutableStateFlow(false)

    private val _hosterList = MutableStateFlow<List<Hoster>>(emptyList())
    val hosterList = _hosterList.asStateFlow()
    private val _isLoadingHosters = MutableStateFlow(true)
    val isLoadingHosters = _isLoadingHosters.asStateFlow()
    private val _hosterState = MutableStateFlow<List<HosterState>>(emptyList())
    val hosterState = _hosterState.asStateFlow()
    private val _hosterExpandedList = MutableStateFlow<List<Boolean>>(emptyList())
    val hosterExpandedList = _hosterExpandedList.asStateFlow()
    private val _selectedHosterVideoIndex = MutableStateFlow(Pair(-1, -1))
    val selectedHosterVideoIndex = _selectedHosterVideoIndex.asStateFlow()
    private val _currentVideo = MutableStateFlow<Video?>(null)
    val currentVideo = _currentVideo.asStateFlow()

    fun updateVideo(video: Video) {
        _currentVideo.update { video }
    }

    private val _chapters = MutableStateFlow<List<IndexedSegment>>(emptyList())
    val chapters = _chapters.asStateFlow()
    private val _currentChapter = MutableStateFlow<IndexedSegment?>(null)
    val currentChapter = _currentChapter.asStateFlow()
    private val _skipIntroText = MutableStateFlow<String?>(null)
    val skipIntroText = _skipIntroText.asStateFlow()

    private val _pos = MutableStateFlow(0f)
    val pos = _pos.asStateFlow()

    private val _seekPosition = MutableStateFlow(0f)
    val seekPosition = _seekPosition.asStateFlow()

    private val _thumbnailImage = MutableStateFlow<ImageBitmap?>(null)
    val thumbnailImage = _thumbnailImage.asStateFlow()

    private val thumbnailInfo = MutableStateFlow<ThumbnailInfo?>(null)
    val hasThumbnails = thumbnailInfo.map { it != null }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val thumbnailTileCache =
        object : LinkedHashMap<Int, Bitmap>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Bitmap>?) = size > 15
        }
    private var thumbnailFetchJob: Job? = null

    private var lastScrubSeekTime = 0L

    private var castProgressJob: Job? = null

    val duration = MutableStateFlow(0f)

    private val _readAhead = MutableStateFlow(0f)
    val readAhead = _readAhead.asStateFlow()

    private val _paused = MutableStateFlow(false)
    val paused = _paused.asStateFlow()

    // False because the video shouldn't start paused
    private val _pausedState = MutableStateFlow<Boolean?>(false)
    val pausedState = _pausedState.asStateFlow()

    private val _controlsShown = MutableStateFlow(!playerPreferences.hideControls().get())
    val controlsShown = _controlsShown.asStateFlow()
    private val _seekBarShown = MutableStateFlow(!playerPreferences.hideControls().get())
    val seekBarShown = _seekBarShown.asStateFlow()
    private val _areControlsLocked = MutableStateFlow(false)
    val areControlsLocked = _areControlsLocked.asStateFlow()

    val playerUpdate = MutableStateFlow<PlayerUpdates>(PlayerUpdates.None)
    val isBrightnessSliderShown = MutableStateFlow(false)
    val isVolumeSliderShown = MutableStateFlow(false)
    val currentBrightness = MutableStateFlow(
        runCatching {
            Settings.System.getFloat(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
                .normalize(0f, 255f, 0f, 1f)
        }.getOrElse { 0f },
    )
    val currentVolume = MutableStateFlow(activity.audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    val currentMPVVolume = MutableStateFlow(100)
    var volumeBoostCap: Int = audioPreferences.volumeBoostCap().get()

    // Pair(startingPosition, seekAmount)
    val gestureSeekAmount = MutableStateFlow<Pair<Int, Int>?>(null)

    val sheetShown = MutableStateFlow(Sheets.None)
    val panelShown = MutableStateFlow(Panels.None)
    val dialogShown = MutableStateFlow<Dialogs>(Dialogs.None)

    private val _dismissSheet = MutableStateFlow(false)
    val dismissSheet = _dismissSheet.asStateFlow()

    private val _seekText = MutableStateFlow<String?>(null)
    val seekText = _seekText.asStateFlow()
    private val _doubleTapSeekAmount = MutableStateFlow(0)
    val doubleTapSeekAmount = _doubleTapSeekAmount.asStateFlow()
    private val _isSeekingForwards = MutableStateFlow(false)
    val isSeekingForwards = _isSeekingForwards.asStateFlow()

    val videoZoom = MutableStateFlow(0f)
    val videoPanX = MutableStateFlow(0f)
    val videoPanY = MutableStateFlow(0f)

    private val _videoAspectOverride = MutableStateFlow<Double?>(null)
    val videoAspectOverride = _videoAspectOverride.asStateFlow()

    val isSeekingUI = MutableStateFlow(false)
    private var pendingSeekTarget: Float? = null
    private var pendingSeekTimeMs: Long = 0L

    private var hasTriggeredWatching = false
    private var timerJob: Job? = null
    private val _remainingTime = MutableStateFlow(0)
    val remainingTime = _remainingTime.asStateFlow()

    val cachePath: String = activity.cacheDir.path

    private val _customButtons = MutableStateFlow<CustomButtonFetchState>(CustomButtonFetchState.Loading)
    val customButtons = _customButtons.asStateFlow()

    private val _primaryButtonTitle = MutableStateFlow("")
    val primaryButtonTitle = _primaryButtonTitle.asStateFlow()

    private val _primaryButton = MutableStateFlow<CustomButton?>(null)
    val primaryButton = _primaryButton.asStateFlow()

    private var fillerEpisodes: Set<Float> = emptySet()

    init {
        viewModelScope.launchIO {
            try {
                val buttons = getCustomButtons.getAll()
                _customButtons.update { _ -> CustomButtonFetchState.Success(buttons.toImmutableList()) }
                buttons.firstOrNull { it.isFavorite }?.let {
                    _primaryButton.update { _ -> it }
                    // If the button text is not empty, it has been set buy a lua script in which
                    // case we don't want to override it
                    if (_primaryButtonTitle.value.isEmpty()) {
                        setPrimaryCustomButtonTitle(it)
                    }
                }
                activity.setupCustomButtons(buttons)
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e)
                _customButtons.update { _ -> CustomButtonFetchState.Error(e.message ?: "Unable to fetch buttons") }
            }
        }
        viewModelScope.launchIO {
            try {
                currentAnime.collect { anime ->
                    if (anime != null && fillerEpisodes.isEmpty()) {
                        fillerEpisodes = animeFillerListFetcher.getFillerEpisodes(anime.title)
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    /**
     * Starts a sleep timer/cancels the current timer if [seconds] is less than 1.
     */
    fun startTimer(seconds: Int) {
        timerJob?.cancel()
        _remainingTime.value = seconds
        if (seconds < 1) return
        timerJob = viewModelScope.launch {
            for (time in seconds downTo 0) {
                _remainingTime.value = time
                delay(1000)
            }
            pause()
            withUIContext { Injekt.get<Application>().toast(MR.strings.toast_sleep_timer_ended) }
        }
    }

    fun isEpisodeOnline(): Boolean? {
        val anime = currentAnime.value ?: return null
        val episode = currentEpisode.value ?: return null
        val source = currentSource.value ?: return null
        return source is HttpSource &&
            !EpisodeLoader.isDownload(
                episode.toDomainEpisode()!!,
                anime,
            )
    }

    fun updateIsLoadingEpisode(value: Boolean) {
        _isLoadingEpisode.update { _ -> value }
    }

    fun setIsStopped(value: Boolean) {
        _isStopped.update { _ -> value }
    }

    private fun updateEpisodeList(episodeList: List<Episode>) {
        _currentPlaylist.update { _ -> filterEpisodeList(episodeList) }
    }

    fun getDecoder() {
        _currentDecoder.update { getDecoderFromValue(activity.player.hwdecActive) }
    }

    fun updateDecoder(decoder: Decoder) {
        MPVLib.setPropertyString("hwdec", decoder.value)
    }

    val getTrackLanguage: (Int) -> String = {
        if (it != -1) {
            MPVLib.getPropertyString("track-list/$it/lang") ?: ""
        } else {
            activity.stringResource(MR.strings.off)
        }
    }
    val getTrackTitle: (Int) -> String = {
        if (it != -1) {
            MPVLib.getPropertyString("track-list/$it/title") ?: ""
        } else {
            activity.stringResource(MR.strings.off)
        }
    }
    val getTrackMPVId: (Int) -> Int? = {
        if (it != -1) {
            MPVLib.getPropertyInt("track-list/$it/id")
        } else {
            -1
        }
    }
    val getTrackType: (Int) -> String? = {
        MPVLib.getPropertyString("track-list/$it/type")
    }

    fun clearTracks() {
        _subtitleTracks.update { emptyList() }
        _audioTracks.update { emptyList() }
    }

    private var trackLoadingJob: Job? = null
    fun loadTracks() {
        trackLoadingJob?.cancel()
        trackLoadingJob = viewModelScope.launch {
            val possibleTrackTypes = listOf("audio", "sub")
            val subTracks = mutableListOf<VideoTrack>()
            val audioTracks = mutableListOf<VideoTrack>(
                VideoTrack.Internal(-1, activity.stringResource(MR.strings.off), null),
            )
            try {
                val tracksCount = MPVLib.getPropertyInt("track-list/count") ?: 0
                // Collect all MPV track names and IDs — Animiru matches externals by URL stored as name
                val mpvSubNameToId = mutableMapOf<String, Int>()
                val mpvAudioNameToId = mutableMapOf<String, Int>()

                val externalSubUrls = currentVideo.value?.subtitleTracks?.flatMap { sub ->
                    val resolvedUrl = _subtitleTracks.value
                        .filterIsInstance<VideoTrack.External>()
                        .find { it.url == sub.url }?.resolvedUrl
                    listOfNotNull(sub.url, resolvedUrl)
                }?.toSet().orEmpty()

                val externalAudioUrls = currentVideo.value?.audioTracks?.flatMap { audio ->
                    val resolvedUrl = _audioTracks.value
                        .filterIsInstance<VideoTrack.External>()
                        .find { it.url == audio.url }?.resolvedUrl
                    listOfNotNull(audio.url, resolvedUrl)
                }?.toSet().orEmpty()

                for (i in 0 until tracksCount) {
                    val type = getTrackType(i)
                    if (!possibleTrackTypes.contains(type) || type == null) continue
                    val title = getTrackTitle(i)
                    val mpvId = getTrackMPVId(i) ?: continue

                    when (type) {
                        "sub" -> {
                            mpvSubNameToId[title] = mpvId
                            // Only add as Internal if it's not an external URL track
                            if (!title.startsWith("http") && !title.startsWith("content://") &&
                                !title.startsWith("file://") && !title.startsWith("ftp://") &&
                                !title.startsWith("fd://") && !externalSubUrls.contains(title)
                            ) {
                                subTracks.add(VideoTrack.Internal(mpvId, title, getTrackLanguage(i)))
                            }
                        }
                        "audio" -> {
                            mpvAudioNameToId[title] = mpvId
                            if (!title.startsWith("http") && !title.startsWith("content://") &&
                                !title.startsWith("file://") && !title.startsWith("ftp://") &&
                                !title.startsWith("fd://") && !externalAudioUrls.contains(title)
                            ) {
                                audioTracks.add(VideoTrack.Internal(mpvId, title, getTrackLanguage(i)))
                            }
                        }
                        else -> error("Unrecognized track type")
                    }
                }


                val videoFilename = DiskUtil.buildValidFilename(currentEpisode.value?.name ?: "")
                currentVideo.value?.subtitleTracks?.forEachIndexed { index, sub ->
                    val cleanLang = if (sub.url.startsWith("content://") || sub.url.startsWith("file://")) {
                        sub.lang.removePrefix(videoFilename).trimStart('.')
                    } else {
                        sub.lang
                    }
                    val finalLang = cleanLang.ifEmpty { sub.lang }
                    val resolvedUrl = _subtitleTracks.value
                        .filterIsInstance<VideoTrack.External>()
                        .find { it.index == index }?.resolvedUrl
                    // Match by URL — Animiru passes URL as the MPV track title
                    val mpvId = mpvSubNameToId[resolvedUrl] ?: mpvSubNameToId[sub.url]
                    val wasLoading = _subtitleTracks.value
                        .filterIsInstance<VideoTrack.External>()
                        .find { it.index == index }?.isLoading ?: false
                    val wasFailed = _subtitleTracks.value
                        .filterIsInstance<VideoTrack.External>()
                        .find { it.index == index }?.isFailed ?: false
                    subTracks.add(
                        VideoTrack.External(
                            index, finalLang, finalLang, sub.url, mpvId,
                            isAudio = false,
                            isLoading = if (mpvId == null) wasLoading else false,
                            isFailed = if (mpvId != null) false else wasFailed,
                            resolvedUrl = resolvedUrl,
                        )
                    )
                }

                currentVideo.value?.audioTracks?.forEachIndexed { index, audio ->
                    val resolvedUrl = _audioTracks.value
                        .filterIsInstance<VideoTrack.External>()
                        .find { it.index == index }?.resolvedUrl
                    // Match by URL — Animiru passes URL as the MPV track title
                    val mpvId = mpvAudioNameToId[resolvedUrl] ?: mpvAudioNameToId[audio.url]
                    val wasLoading = _audioTracks.value
                        .filterIsInstance<VideoTrack.External>()
                        .find { it.index == index }?.isLoading ?: false
                    val wasFailed = _audioTracks.value
                        .filterIsInstance<VideoTrack.External>()
                        .find { it.index == index }?.isFailed ?: false
                    audioTracks.add(
                        VideoTrack.External(
                            index, audio.lang, audio.lang, audio.url, mpvId,
                            isAudio = true,
                            isLoading = if (mpvId == null) wasLoading else false,
                            isFailed = if (mpvId != null) false else wasFailed,
                            resolvedUrl = resolvedUrl,
                        )
                    )
                }
            } catch (e: NullPointerException) {
                logcat(LogPriority.ERROR) { "Couldn't load tracks, probably cause mpv was destroyed" }
                return@launch
            }

            val oldSubTracks = _subtitleTracks.value
            val oldAudioTracks = _audioTracks.value

            _subtitleTracks.update { subTracks }
            _audioTracks.update { audioTracks }

            // Activate newly loaded external tracks as soon as MPV assigns their ID
            // Also clear the isLoading flag now that the track is ready
            subTracks.filterIsInstance<VideoTrack.External>().forEach { newTrack ->
                if (newTrack.mpvId != null) {
                    val oldTrack = oldSubTracks.find { it is VideoTrack.External && it.index == newTrack.index } as? VideoTrack.External
                    if (oldTrack?.mpvId == null) {
                        // Clear loading state then activate
                        _subtitleTracks.update { list ->
                            list.map { if (it is VideoTrack.External && it.index == newTrack.index) it.copy(isLoading = false) else it }
                        }
                        selectSub(newTrack)
                    }
                }
            }

            audioTracks.filterIsInstance<VideoTrack.External>().forEach { newTrack ->
                if (newTrack.mpvId != null) {
                    val oldTrack = oldAudioTracks.find { it is VideoTrack.External && it.index == newTrack.index } as? VideoTrack.External
                    if (oldTrack?.mpvId == null) {
                        // Clear loading state then activate
                        _audioTracks.update { list ->
                            list.map { if (it is VideoTrack.External && it.index == newTrack.index) it.copy(isLoading = false) else it }
                        }
                        selectAudio(newTrack)
                    }
                }
            }

            if (!isLoadingTracks.value) {
                onFinishLoadingTracks()
            }
        }
    }

    /**
     * When all subtitle/audio tracks are loaded, select the preferred one based on preferences,
     * or select the first one in the list if trackSelect fails.
     */
    fun onFinishLoadingTracks() {
        if (!subtitlePreferences.disableAutoSubtitles().get()) {
            val preferredSubtitle = trackSelect.getPreferredTrackIndex(subtitleTracks.value)
            (preferredSubtitle ?: subtitleTracks.value.firstOrNull())?.let {
                selectSub(it, forcePrimary = true)
            }
        } else {
            activity.player.sid = -1
        }

        val preferredAudio = trackSelect.getPreferredTrackIndex(audioTracks.value, subtitle = false)
        (preferredAudio ?: audioTracks.value.getOrNull(1))?.let {
            selectAudio(it)
        }

        isLoadingTracks.update { _ -> true }
        updateIsLoadingEpisode(false)
        setPausedState()
    }

    @Immutable
    sealed interface VideoTrack {
        val name: String
        val language: String?

        data class Internal(
            val id: Int,
            override val name: String,
            override val language: String?,
        ) : VideoTrack

        data class External(
            val index: Int,
            override val name: String,
            override val language: String?,
            val url: String,
            val mpvId: Int? = null,
            val isAudio: Boolean = false,
            val isLoading: Boolean = false,
            val isFailed: Boolean = false,
            val resolvedUrl: String? = null,
        ) : VideoTrack

        companion object {
            const val TRACK_TITLE_TAG = "animiru_ext"
        }
    }

    fun loadChapters() {
        val chapters = mutableListOf<IndexedSegment>()
        val count = MPVLib.getPropertyInt("chapter-list/count")!!
        for (i in 0 until count) {
            val title = MPVLib.getPropertyString("chapter-list/$i/title")
            val time = MPVLib.getPropertyInt("chapter-list/$i/time")!!
            chapters.add(
                IndexedSegment(
                    name = title,
                    start = time.toFloat(),
                    index = 0,
                ),
            )
        }
        updateChapters(chapters.sortedBy { it.start })
    }

    fun updateChapters(chapters: List<IndexedSegment>) {
        _chapters.update { _ -> chapters }
    }

    fun selectChapter(index: Int) {
        val chapter = chapters.value.getOrNull(index) ?: return
        seekTo(chapter.start.toInt())
    }

    fun updateChapter(index: Long) {
        if (chapters.value.isEmpty() || index == -1L) return
        _currentChapter.update { chapters.value.getOrNull(index.toInt()) ?: return }
    }

    fun addAudio(uri: Uri) {
        val url = uri.toString()
        val isContentUri = url.startsWith("content://")
        if (isContentUri) {
            viewModelScope.launchIO {
                val cacheFile = copyUriToCache(uri)
                if (cacheFile != null) {
                    withUIContext {
                        MPVLib.command(arrayOf("audio-add", cacheFile.absolutePath, "select", cacheFile.name))
                    }
                } else {
                    logcat(LogPriority.ERROR) { "Failed to copy audio to cache" }
                }
            }
        } else {
            val name = uri.path?.let { File(it).name }
            if (name == null) {
                MPVLib.command(arrayOf("audio-add", url, "select"))
            } else {
                MPVLib.command(arrayOf("audio-add", url, "select", name))
            }
        }
    }

    fun selectAudio(track: VideoTrack) {
        if (track is VideoTrack.External && track.mpvId == null) {
            // Mark track as loading immediately so the UI shows a spinner
            _audioTracks.update { list ->
                list.map { if (it is VideoTrack.External && it.index == track.index) it.copy(isLoading = true, isFailed = false) else it }
            }
            // resolveUri does blocking I/O for content:// URIs — must run on IO thread
            viewModelScope.launchIO {
                try {
                    val resolvedUrl = Uri.parse(track.url).resolveUri(activity) ?: track.url
                    _audioTracks.update { list ->
                        list.map { if (it is VideoTrack.External && it.index == track.index) it.copy(resolvedUrl = resolvedUrl) else it }
                    }
                    // Use "select" like Animiru so MPV activates the track immediately after loading
                    // Pass URL as the title so loadTracks() can match it back by name
                    MPVLib.command(arrayOf("audio-add", resolvedUrl, "select", resolvedUrl))
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR) { "Failed to resolve or add audio: ${e.message}" }
                    _audioTracks.update { list ->
                        list.map { if (it is VideoTrack.External && it.index == track.index) it.copy(isLoading = false, isFailed = true) else it }
                    }
                }
            }
            return
        }
        val id = (track as? VideoTrack.Internal)?.id ?: (track as? VideoTrack.External)?.mpvId ?: return
        activity.player.aid = id
    }

    fun updateAudio(id: Int) {
        _selectedAudio.update { id }
    }

    private fun copyUriToCache(uri: Uri): File? {
        val name = uri.getFileName(activity) ?: run {
            val extension = activity.contentResolver.getType(uri)?.let { mime ->
                android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
            } ?: uri.path?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() } ?: "bin"
            "temp_${System.currentTimeMillis()}.$extension"
        }
        val cacheFile = File(activity.cacheDir, name)
        try {
            activity.contentResolver.openInputStream(uri)?.use { input ->
                cacheFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            return if (cacheFile.exists()) cacheFile else null
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "Failed to copy URI to cache: ${e.message}" }
            return null
        }
    }

    fun addSubtitle(uri: Uri) {
        val url = uri.toString()
        val isContentUri = url.startsWith("content://")
        if (isContentUri) {
            viewModelScope.launchIO {
                val cacheFile = copyUriToCache(uri)
                if (cacheFile != null) {
                    withUIContext {
                        MPVLib.command(arrayOf("sub-add", cacheFile.absolutePath, "select", cacheFile.name))
                    }
                } else {
                    logcat(LogPriority.ERROR) { "Failed to copy subtitle to cache" }
                }
            }
        } else {
            val name = uri.path?.let { File(it).name }
            if (name == null) {
                MPVLib.command(arrayOf("sub-add", url, "select"))
            } else {
                MPVLib.command(arrayOf("sub-add", url, "select", name))
            }
        }
    }

    fun selectSub(track: VideoTrack, forcePrimary: Boolean = false) {
        if (track is VideoTrack.External && track.mpvId == null) {
            // Mark track as loading immediately so the UI shows a spinner
            _subtitleTracks.update { list ->
                list.map { if (it is VideoTrack.External && it.index == track.index) it.copy(isLoading = true, isFailed = false) else it }
            }
            // resolveUri does blocking I/O for content:// URIs — must run on IO thread
            viewModelScope.launchIO {
                try {
                    val resolvedUrl = Uri.parse(track.url).resolveUri(activity) ?: track.url
                    _subtitleTracks.update { list ->
                        list.map { if (it is VideoTrack.External && it.index == track.index) it.copy(resolvedUrl = resolvedUrl) else it }
                    }
                    // Use "select" like Animiru so MPV activates the track immediately after loading
                    // Pass URL as the title so loadTracks() can match it back by name
                    MPVLib.command(arrayOf("sub-add", resolvedUrl, "select", resolvedUrl))
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR) { "Failed to resolve or add subtitle: ${e.message}" }
                    _subtitleTracks.update { list ->
                        list.map { if (it is VideoTrack.External && it.index == track.index) it.copy(isLoading = false, isFailed = true) else it }
                    }
                }
            }
            return
        }

        val id = (track as? VideoTrack.Internal)?.id ?: (track as? VideoTrack.External)?.mpvId ?: return

        if (forcePrimary) {
            _selectedSubtitles.update { Pair(id, -1) }
            activity.player.secondarySid = -1
            activity.player.sid = id
            return
        }
        val selectedSubs = selectedSubtitles.value
        _selectedSubtitles.update {
            when (id) {
                selectedSubs.first -> Pair(selectedSubs.second, -1)
                selectedSubs.second -> Pair(selectedSubs.first, -1)
                else -> {
                    if (selectedSubs.first != -1) {
                        Pair(selectedSubs.first, id)
                    } else {
                        Pair(id, -1)
                    }
                }
            }
        }
        val newSecondarySid = _selectedSubtitles.value.second
        if (newSecondarySid >= -1) {
            activity.player.secondarySid = newSecondarySid
        }
        val newSid = _selectedSubtitles.value.first
        if (newSid >= -1) {
            activity.player.sid = newSid
        }
    }

    fun updateSubtitle(sid: Int, secondarySid: Int) {
        _selectedSubtitles.update { Pair(sid, secondarySid) }
    }

    fun handleMpvLogFailure(text: String) {
        _subtitleTracks.update { list ->
            list.map { track ->
                if (track is VideoTrack.External && track.isLoading &&
                    (text.contains(track.url) || (track.resolvedUrl != null && text.contains(track.resolvedUrl)))
                ) {
                    track.copy(isLoading = false, isFailed = true)
                } else {
                    track
                }
            }
        }
        _audioTracks.update { list ->
            list.map { track ->
                if (track is VideoTrack.External && track.isLoading &&
                    (text.contains(track.url) || (track.resolvedUrl != null && text.contains(track.resolvedUrl)))
                ) {
                    track.copy(isLoading = false, isFailed = true)
                } else {
                    track
                }
            }
        }
    }

    fun updatePlayBackPos(pos: Float) {
        val target = pendingSeekTarget
        if (target != null) {
            val elapsed = System.currentTimeMillis() - pendingSeekTimeMs
            if (kotlin.math.abs(pos - target) <= 2.0f || elapsed > 1500L) {
                pendingSeekTarget = null
            } else {
                return
            }
        }

        onSecondReached(pos.toInt(), duration.value.toInt())
        _pos.update { pos }
        
        if (pos > 15f && !hasTriggeredWatching && !incognitoMode) {
            hasTriggeredWatching = true
            val anime = currentAnime.value ?: return
            viewModelScope.launchNonCancellable {
                logActivity.await(anime.source, ActivityLog.TYPE_PLAY, animeId = anime.id)
            }
        }
    }

    fun updateSeekPos(pos: Float) {
        _seekPosition.update { _ -> pos }

        val thumbInfo = thumbnailInfo.value ?: return
        val info = thumbInfo.tileInfo.lastOrNull { it.timeMs <= pos * 1000L }
        if (info != null) {
            val tileBitmap = synchronized(thumbnailTileCache) { thumbnailTileCache[info.imageIndex] }
            if (tileBitmap != null) {
                // Perform crop operation entirely on background thread to avoid main thread jank
                thumbnailFetchJob?.cancel()
                thumbnailFetchJob = viewModelScope.launchIO {
                    try {
                        val thumbnail = Bitmap.createBitmap(tileBitmap, info.x, info.y, info.width, info.height)
                        val imageBitmap = thumbnail.asImageBitmap()
                        _thumbnailImage.update { _ -> imageBitmap }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        logcat(LogPriority.ERROR, e) { "Failed to crop cached thumbnail" }
                    }
                }
            } else {
                thumbnailFetchJob?.cancel()
                thumbnailFetchJob = viewModelScope.launchIO {
                    // 150ms debounce before launching network request
                    delay(150)
                    val source = currentSource.value as? AnimeHttpSource ?: return@launchIO

                    try {
                        val tileUrl = thumbInfo.imageTileUrls[info.imageIndex]
                        val bitmap = source.getImageTile(tileUrl)
                        if (bitmap != null) {
                            synchronized(thumbnailTileCache) {
                                thumbnailTileCache[info.imageIndex] = bitmap
                            }
                            val thumbnail = Bitmap.createBitmap(bitmap, info.x, info.y, info.width, info.height)
                            val imageBitmap = thumbnail.asImageBitmap()
                            _thumbnailImage.update { _ -> imageBitmap }
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        logcat(LogPriority.ERROR, e) { "Failed to fetch thumbnails tiles" }
                    }
                }
            }
        }
    }

    fun updateIsSeeking(value: Boolean) {
        isSeekingUI.update { _ -> value }
        if (!value) {
            _thumbnailImage.update { _ -> null }
        }
    }

    fun scrubSeekTo(position: Int, precise: Boolean = false) {
        val now = System.currentTimeMillis()
        if (now - lastScrubSeekTime > 200L) {
            lastScrubSeekTime = now
            seekTo(position, precise)
        }
    }

    fun updateReadAhead(value: Long) {
        _readAhead.update { value.toFloat() }
    }

    private fun updatePausedState() {
        if (pausedState.value == null) {
            _pausedState.update { _ -> paused.value }
        }
    }

    private fun setPausedState() {
        pausedState.value?.let {
            if (it) {
                pause()
            } else {
                unpause()
            }

            _pausedState.update { _ -> null }
        }
    }

    fun pauseUnpause() {
        if (paused.value) {
            unpause()
        } else {
            pause()
        }
    }

    fun pause() {
        activity.player.paused = true
        _paused.update { true }
        runCatching {
            activity.setPictureInPictureParams(activity.createPipParams())
        }
    }

    fun unpause() {
        activity.player.paused = false
        _paused.update { false }
    }

    private val showStatusBar = playerPreferences.showSystemStatusBar().get()
    fun showControls() {
        if (sheetShown.value != Sheets.None ||
            panelShown.value != Panels.None ||
            dialogShown.value != Dialogs.None
        ) {
            return
        }
        if (showStatusBar) {
            activity.windowInsetsController.show(WindowInsetsCompat.Type.statusBars())
        }
        _controlsShown.update { true }
        _seekBarShown.update { true }
    }

    fun hideControls() {
        activity.windowInsetsController.hide(WindowInsetsCompat.Type.statusBars())
        _controlsShown.update { false }
        _seekBarShown.update { false }
    }

    fun hideSeekBar() {
        _seekBarShown.update { false }
    }

    fun showSeekBar() {
        if (sheetShown.value != Sheets.None) return
        _seekBarShown.update { true }
    }

    fun lockControls() {
        _areControlsLocked.update { true }
    }

    fun unlockControls() {
        _areControlsLocked.update { false }
    }

    fun dismissSheet() {
        _dismissSheet.update { _ -> true }
    }

    fun runExternalDownloader() {
        val anime = currentAnime.value ?: return
        val episode = currentEpisode.value?.toDomainEpisode() ?: return
        val video = currentVideo.value ?: return

        downloadManager.downloadEpisodes(anime, listOf(episode), true, true, video)
    }

    private fun resetDismissSheet() {
        _dismissSheet.update { _ -> false }
    }

    fun showSheet(sheet: Sheets) {
        sheetShown.update { sheet }
        if (sheet == Sheets.None) {
            resetDismissSheet()
            showControls()
        } else {
            hideControls()
            panelShown.update { Panels.None }
            dialogShown.update { Dialogs.None }
        }
    }

    fun showPanel(panel: Panels) {
        panelShown.update { panel }
        if (panel == Panels.None) {
            showControls()
        } else {
            hideControls()
            sheetShown.update { Sheets.None }
            dialogShown.update { Dialogs.None }
        }
    }

    fun showDialog(dialog: Dialogs) {
        dialogShown.update { dialog }
        if (dialog == Dialogs.None) {
            showControls()
        } else {
            hideControls()
            sheetShown.update { Sheets.None }
            panelShown.update { Panels.None }
        }
    }

    fun seekBy(offset: Int, precise: Boolean = false) {
        val current = _pos.value
        val maxDuration = (activity.player.duration ?: 0).toFloat()
        val target = (current + offset).coerceIn(0f, maxDuration)
        pendingSeekTarget = target
        pendingSeekTimeMs = System.currentTimeMillis()
        _pos.update { target }
        MPVLib.command(arrayOf("seek", offset.toString(), if (precise) "relative+exact" else "relative"))
    }

    fun seekTo(position: Int, precise: Boolean = true) {
        if (position !in 0..(activity.player.duration ?: 0)) return
        pendingSeekTarget = position.toFloat()
        pendingSeekTimeMs = System.currentTimeMillis()
        _pos.update { position.toFloat() }
        MPVLib.command(arrayOf("seek", position.toString(), if (precise) "absolute" else "absolute+keyframes"))
    }

    fun changeBrightnessTo(
        brightness: Float,
    ) {
        currentBrightness.update { _ -> brightness.coerceIn(-0.75f, 1f) }
        activity.window.attributes = activity.window.attributes.apply {
            screenBrightness = brightness.coerceIn(0f, 1f)
        }
    }

    fun displayBrightnessSlider() {
        isBrightnessSliderShown.update { true }
    }

    val maxVolume = activity.audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    fun changeVolumeBy(change: Int) {
        val mpvVol = MPVLib.getPropertyInt("volume")
        val sysVol = currentVolume.value

        if (change > 0) { // Increasing
            if (sysVol < maxVolume) {
                changeVolumeTo(sysVol + change)
            } else if (volumeBoostCap > 0) {
                val newBoost = (mpvVol + (change * 5)).coerceAtMost(100 + volumeBoostCap)
                changeMPVVolumeTo(newBoost)
            }
        } else if (change < 0) { // Decreasing
            if (mpvVol > 100) {
                val newBoost = (mpvVol + (change * 5)).coerceAtLeast(100)
                changeMPVVolumeTo(newBoost)
            } else {
                changeVolumeTo(sysVol + change)
            }
        }
    }

    /**
     * Unified volume control for both system volume and MPV boost.
     * @param percent 0 to 100 for system volume, 100 to 100 + volumeBoostCap for boost.
     */
    fun setVolume(percent: Float) {
        val totalMax = 100f + volumeBoostCap
        val clamped = percent.coerceIn(0f, totalMax)
        if (clamped <= 100f) {
            val systemVol = Math.round(clamped / 100f * maxVolume)
            changeVolumeTo(systemVol)
            if (currentMPVVolume.value != 100) {
                changeMPVVolumeTo(100)
            }
        } else {
            if (currentVolume.value != maxVolume) {
                changeVolumeTo(maxVolume)
            }
            changeMPVVolumeTo(clamped.toInt())
        }
    }

    fun changeVolumeTo(volume: Int) {
        val newVolume = volume.coerceIn(0..maxVolume)
        activity.audioManager.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            newVolume,
            0,
        )
        currentVolume.update { newVolume }
    }

    fun changeMPVVolumeTo(volume: Int) {
        MPVLib.setPropertyInt("volume", volume)
    }

    fun setMPVVolume(volume: Int) {
        if (volume != currentMPVVolume.value) displayVolumeSlider()
        currentMPVVolume.update { volume }
    }

    fun displayVolumeSlider() {
        isVolumeSliderShown.update { true }
    }

    fun setAutoPlay(value: Boolean) {
        val textRes = if (value) {
            MR.strings.enable_auto_play
        } else {
            MR.strings.disable_auto_play
        }
        playerUpdate.update { PlayerUpdates.ShowTextResource(textRes) }
        playerPreferences.autoplayEnabled().set(value)
    }

    @Suppress("DEPRECATION")
    fun changeVideoAspect(aspect: VideoAspect, showUpdate: Boolean = true) {
        var ratio = -1.0
        var pan = 1.0
        when (aspect) {
            VideoAspect.Crop -> {
                pan = 1.0
            }

            VideoAspect.Fit -> {
                pan = 0.0
                setPropertyDouble("panscan", 0.0)
            }

            VideoAspect.Stretch -> {
                val dm = DisplayMetrics()
                activity.windowManager.defaultDisplay.getRealMetrics(dm)
                ratio = dm.widthPixels / dm.heightPixels.toDouble()
                pan = 0.0
            }
        }
        setPropertyDouble("panscan", pan)
        setPropertyDouble("video-aspect-override", ratio)
        _videoAspectOverride.value = ratio
        playerPreferences.aspectState().set(aspect)
        playerPreferences.lastAspectRatio().set(ratio.toFloat())
        if (showUpdate) {
            playerUpdate.update { PlayerUpdates.AspectRatio }
        }
    }

    fun setCustomVideoAspect(ratio: Double, label: String) {
        _videoAspectOverride.value = ratio
        setPropertyDouble("panscan", 0.0)
        setPropertyDouble("video-aspect-override", ratio)
        // Reset VideoAspect to Fit so the icon and standard toggle behavior are consistent
        playerPreferences.aspectState().set(VideoAspect.Fit)
        playerPreferences.lastAspectRatio().set(ratio.toFloat())
        val currentAnimeId = currentAnime.value?.id ?: -1L
        playerPreferences.lastAspectRatioAnimeId().set(currentAnimeId)

        playerUpdate.update {
            if (ratio == -1.0) {
                PlayerUpdates.ShowTextResource(MR.strings.video_fit_screen)
            } else {
                PlayerUpdates.ShowText(label)
            }
        }
    }

    fun restoreAspectRatio() {
        val aspect = playerPreferences.aspectState().get()
        val lastRatio = playerPreferences.lastAspectRatio().get().toDouble()
        val lastRatioAnimeId = playerPreferences.lastAspectRatioAnimeId().get()
        val rememberAspectRatio = playerPreferences.rememberAspectRatio().get()
        val currentAnimeId = currentAnime.value?.id ?: -1L

        if (aspect == VideoAspect.Stretch) {
            changeVideoAspect(VideoAspect.Stretch, showUpdate = false)
        } else if (lastRatio != -1.0 && (rememberAspectRatio || lastRatioAnimeId == currentAnimeId)) {
            _videoAspectOverride.value = lastRatio
            setPropertyDouble("panscan", 0.0)
            setPropertyDouble("video-aspect-override", lastRatio)
            playerPreferences.aspectState().set(VideoAspect.Fit)
        } else {
            if (lastRatio != -1.0) {
                playerPreferences.lastAspectRatio().set(-1f)
                playerPreferences.lastAspectRatioAnimeId().set(-1L)
            }
            changeVideoAspect(aspect, showUpdate = false)
        }
    }

    fun cycleScreenRotations() {
        activity.requestedOrientation = when (activity.requestedOrientation) {
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

    fun handleLuaInvocation(property: String, value: String) {
        val data = value
            .removePrefix("\"")
            .removeSuffix("\"")
            .ifEmpty { return }

        when (property.substringAfterLast("/")) {
            "show_text" -> playerUpdate.update { PlayerUpdates.ShowText(data) }
            "show_seek_text" -> {
                val (isForwardStr, text) = data.split("|", limit = 2)
                val isForward = isForwardStr.toBooleanStrictOrNull() ?: true
                _isSeekingForwards.value = isForward
                _doubleTapSeekAmount.update { if (isForward) 1 else -1 }
                _seekText.update { text }
                if (showSeekBar) showSeekBar()
            }
            "toggle_ui" -> {
                when (data) {
                    "show" -> showControls()
                    "toggle" -> {
                        if (controlsShown.value) hideControls() else showControls()
                    }
                    "hide" -> {
                        sheetShown.update { Sheets.None }
                        panelShown.update { Panels.None }
                        dialogShown.update { Dialogs.None }
                        hideControls()
                    }
                }
            }
            "show_panel" -> {
                when (data) {
                    "subtitle_settings" -> showPanel(Panels.SubtitleSettings)
                    "subtitle_delay" -> showPanel(Panels.SubtitleDelay)
                    "audio_delay" -> showPanel(Panels.AudioDelay)
                    "video_filters" -> showPanel(Panels.VideoFilters)
                }
            }
            "set_button_title" -> {
                _primaryButtonTitle.update { _ -> data }
            }
            "reset_button_title" -> {
                _customButtons.value.getButtons().firstOrNull { it.isFavorite }?.let {
                    setPrimaryCustomButtonTitle(it)
                }
            }
            "switch_episode" -> {
                when (data) {
                    "n" -> changeEpisode(false)
                    "p" -> changeEpisode(true)
                }
            }
            "launch_int_picker" -> {
                val (title, nameFormat, start, stop, step, pickerProperty) = data.split("|")
                val startInt = start.toDoubleOrNull()?.toInt() ?: 0
                val stopInt = stop.toDoubleOrNull()?.toInt() ?: 0
                val stepInt = step.toDoubleOrNull()?.toInt() ?: 1
                val defaultValue = MPVLib.getPropertyInt(pickerProperty) ?: startInt
                showDialog(
                    Dialogs.IntegerPicker(
                        defaultValue = defaultValue,
                        minValue = startInt,
                        maxValue = stopInt,
                        step = stepInt,
                        nameFormat = nameFormat,
                        title = title,
                        onChange = { MPVLib.setPropertyInt(pickerProperty, it) },
                        onDismissRequest = { showDialog(Dialogs.None) },
                    ),
                )
            }
            "pause" -> {
                when (data) {
                    "pause" -> pause()
                    "unpause" -> unpause()
                    "pauseunpause" -> pauseUnpause()
                }
            }
            "seek_to_with_text" -> {
                val (seekValue, text) = data.split("|", limit = 2)
                seekToWithText(seekValue.toDoubleOrNull()?.toInt() ?: 0, text)
            }
            "seek_by_with_text" -> {
                val (seekValue, text) = data.split("|", limit = 2)
                seekByWithText(seekValue.toDoubleOrNull()?.toInt() ?: 0, text)
            }
            "seek_by" -> seekByWithText(data.toDoubleOrNull()?.toInt() ?: 0, null)
            "seek_to" -> seekToWithText(data.toDoubleOrNull()?.toInt() ?: 0, null)
            "toggle_button" -> {
                fun showButton() {
                    if (_primaryButton.value == null) {
                        _primaryButton.update {
                            customButtons.value.getButtons().firstOrNull { it.isFavorite }
                        }
                    }
                }

                when (data) {
                    "show" -> showButton()
                    "hide" -> _primaryButton.update { null }
                    "toggle" -> if (_primaryButton.value == null) showButton() else _primaryButton.update { null }
                }
            }

            "software_keyboard" -> when (data) {
                "show" -> forceShowSoftwareKeyboard()
                "hide" -> forceHideSoftwareKeyboard()
                "toggle" -> if (inputMethodManager.isActive) {
                    forceHideSoftwareKeyboard()
                } else {
                    forceShowSoftwareKeyboard()
                }
            }
        }

        MPVLib.setPropertyString(property, "")
    }

    private operator fun <T> List<T>.component6(): T = get(5)

    private val inputMethodManager = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    private fun forceShowSoftwareKeyboard() {
        inputMethodManager.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
    }

    private fun forceHideSoftwareKeyboard() {
        inputMethodManager.toggleSoftInput(InputMethodManager.SHOW_IMPLICIT, 0)
    }

    private val doubleTapToSeekDuration = gesturePreferences.skipLengthPreference().get()
    private val preciseSeek = gesturePreferences.playerSmoothSeek().get()
    private val showSeekBar = gesturePreferences.showSeekBar().get()

    private fun seekToWithText(seekValue: Int, text: String?, forcePrecise: Boolean = false) {
        val isForward = seekValue >= pos.value.toInt()
        _isSeekingForwards.value = isForward
        _doubleTapSeekAmount.value = seekValue - pos.value.toInt()
        _seekText.update { _ -> text }
        seekTo(seekValue, precise = if (forcePrecise) true else preciseSeek)
        if (showSeekBar) showSeekBar()
    }

    private fun seekByWithText(value: Int, text: String?) {
        _doubleTapSeekAmount.update { if (value < 0 && it < 0 || pos.value + value > duration.value) 0 else it + value }
        _seekText.update { text }
        _isSeekingForwards.value = value > 0
        seekBy(value, preciseSeek)
        if (showSeekBar) showSeekBar()
    }

    fun updateSeekAmount(amount: Int) {
        _doubleTapSeekAmount.update { _ -> amount }
    }

    fun updateSeekText(value: String?) {
        _seekText.update { _ -> value }
    }

    fun leftSeek() {
        if (pos.value > 0) {
            _doubleTapSeekAmount.value -= doubleTapToSeekDuration
        }
        _isSeekingForwards.value = false
        seekBy(-doubleTapToSeekDuration, preciseSeek)
        if (showSeekBar) showSeekBar()
    }

    fun rightSeek() {
        if (pos.value < duration.value) {
            _doubleTapSeekAmount.value += doubleTapToSeekDuration
        }
        _isSeekingForwards.value = true
        seekBy(doubleTapToSeekDuration, preciseSeek)
        if (showSeekBar) showSeekBar()
    }

    /**
     * Reset state when changing episodes
     */
    fun resetState() {
        _pausedState.update { _ -> false }
        _hosterState.update { _ -> emptyList() }
        _hosterList.update { _ -> emptyList() }
        _hosterExpandedList.update { _ -> emptyList() }
        _selectedHosterVideoIndex.update { _ -> Pair(-1, -1) }
        synchronized(thumbnailTileCache) {
            thumbnailTileCache.clear()
        }
        thumbnailFetchJob?.cancel()
        lastScrubSeekTime = 0L
    }

    private fun setPropertyDouble(property: String, value: Double) {
        if (activity.player.initialized) {
            MPVLib.setPropertyDouble(property, value)
        }
    }

    fun setVideoZoom(zoom: Float) {
        videoZoom.value = zoom
        setPropertyDouble("video-zoom", zoom.toDouble())
    }

    fun setVideoPan(x: Float, y: Float) {
        videoPanX.value = x
        videoPanY.value = y
        setPropertyDouble("video-pan-x", x.toDouble())
        setPropertyDouble("video-pan-y", y.toDouble())
    }

    fun resetVideoZoomAndPan() {
        setVideoZoom(0f)
        setVideoPan(0f, 0f)
    }

    fun changeEpisode(previous: Boolean, autoPlay: Boolean = false) {
        if (previous && !hasPreviousEpisode.value) {
            activity.showToast(activity.stringResource(MR.strings.no_prev_episode))
            return
        }

        if (!previous && !hasNextEpisode.value) {
            activity.showToast(activity.stringResource(MR.strings.no_next_episode))
            return
        }

        val nextEpisodeId = getAdjacentEpisodeId(previous = previous)
        val currentEpisodeIndex = getCurrentEpisodeIndex()
        val nextEpisodeIndex = if (previous) currentEpisodeIndex - 1 else currentEpisodeIndex + 1

        if (!previous && playerPreferences.skipFillerEpisodes().get() && nextEpisodeId != -1L) {
            val playlist = currentPlaylist.value
            val actualNextEpisodeIndex = playlist.indexOfFirst { it.id == nextEpisodeId }
            if (actualNextEpisodeIndex > nextEpisodeIndex) {
                activity.showToast(activity.stringResource(MR.strings.player_filler_skipped))
            }
        }

        activity.changeEpisode(
            episodeId = nextEpisodeId,
            autoPlay = autoPlay,
        )
    }

    fun handleLeftDoubleTap() {
        when (gesturePreferences.leftDoubleTapGesture().get()) {
            SingleActionGesture.Seek -> {
                leftSeek()
            }
            SingleActionGesture.PlayPause -> {
                pauseUnpause()
            }
            SingleActionGesture.Custom -> {
                MPVLib.command(arrayOf("keypress", CustomKeyCodes.DoubleTapLeft.keyCode))
            }
            SingleActionGesture.None -> {}
            SingleActionGesture.Switch -> changeEpisode(true)
        }
    }

    fun handleCenterDoubleTap() {
        when (gesturePreferences.centerDoubleTapGesture().get()) {
            SingleActionGesture.PlayPause -> {
                pauseUnpause()
            }
            SingleActionGesture.Custom -> {
                MPVLib.command(arrayOf("keypress", CustomKeyCodes.DoubleTapCenter.keyCode))
            }
            SingleActionGesture.Seek -> {}
            SingleActionGesture.None -> {}
            SingleActionGesture.Switch -> {}
        }
    }

    fun handleRightDoubleTap() {
        when (gesturePreferences.rightDoubleTapGesture().get()) {
            SingleActionGesture.Seek -> {
                rightSeek()
            }
            SingleActionGesture.PlayPause -> {
                pauseUnpause()
            }
            SingleActionGesture.Custom -> {
                MPVLib.command(arrayOf("keypress", CustomKeyCodes.DoubleTapRight.keyCode))
            }
            SingleActionGesture.None -> {}
            SingleActionGesture.Switch -> changeEpisode(false)
        }
    }

    override fun onCleared() {
        if (currentEpisode.value != null) {
            saveWatchingProgress(currentEpisode.value!!)
            episodeToDownload?.let {
                downloadManager.addDownloadsToStartOfQueue(listOf(it))
            }
        }
        deletePendingEpisodes()
    }

    fun updateCastProgress(position: Float) {
        _pos.update { position }
    }

    fun resumeFromCast() {
        val lastPosition = _pos.value

        logcat { "Reanudando el video local desde: $lastPosition segundos" }

        if (lastPosition > 0) {
            seekTo(lastPosition.toInt()) // Mueve el reproductor local a la última posición
        }
    }

    // ====== OLD ======

    private val eventChannel = Channel<Event>()
    val eventFlow = eventChannel.receiveAsFlow()

    val incognitoMode = basePreferences.incognitoMode().get()
    private val downloadAheadAmount = downloadPreferences.autoDownloadWhileReading().get()

    internal val relativeTime = uiPreferences.relativeTime().get()
    internal val dateFormat = UiPreferences.dateFormat(uiPreferences.dateFormat().get())

    /**
     * The position in the current video. Used to restore from process kill.
     */
    private var episodePosition = savedState.get<Long>("episode_position")
        set(value) {
            savedState["episode_position"] = value
            field = value
        }

    /**
     * The current video's quality index. Used to restore from process kill.
     */
    private var qualityIndex = savedState.get<Pair<Int, Int>>("quality_index") ?: Pair(-1, -1)
        set(value) {
            savedState["quality_index"] = value
            field = value
        }

    /**
     * The episode id of the currently loaded episode. Used to restore from process kill.
     */
    private var episodeId = savedState.get<Long>("episode_id") ?: -1L
        set(value) {
            savedState["episode_id"] = value
            field = value
        }

    private var episodeToDownload: Download? = null

    private fun filterEpisodeList(episodes: List<Episode>): List<Episode> {
        val anime = currentAnime.value ?: return episodes
        val selectedEpisode = episodes.find { it.id == episodeId }
            ?: error("Requested episode of id $episodeId not found in episode list")

        var episodesForPlayer = episodes.filterNot {
            anime.unseenFilterRaw == Anime.EPISODE_SHOW_SEEN &&
                !it.seen ||
                anime.unseenFilterRaw == Anime.EPISODE_SHOW_UNSEEN &&
                it.seen ||
                anime.downloadedFilterRaw == Anime.EPISODE_SHOW_DOWNLOADED &&
                !downloadManager.isEpisodeDownloaded(
                    it.name,
                    it.scanlator,
                    anime.title,
                    anime.source,
                    episodeNumber = it.episode_number.toDouble(),
                ) ||
                anime.downloadedFilterRaw == Anime.EPISODE_SHOW_NOT_DOWNLOADED &&
                downloadManager.isEpisodeDownloaded(
                    it.name,
                    it.scanlator,
                    anime.title,
                    anime.source,
                    episodeNumber = it.episode_number.toDouble(),
                ) ||
                anime.bookmarkedFilterRaw == Anime.EPISODE_SHOW_BOOKMARKED &&
                !it.bookmark ||
                anime.bookmarkedFilterRaw == Anime.EPISODE_SHOW_NOT_BOOKMARKED &&
                it.bookmark ||
                // AM (FILLERMARK) -->
                anime.fillermarkedFilterRaw == Anime.EPISODE_SHOW_FILLERMARKED &&
                !it.fillermark ||
                anime.fillermarkedFilterRaw == Anime.EPISODE_SHOW_NOT_FILLERMARKED &&
                it.fillermark
            // <-- AM (FILLERMARK)
        }

        // AM (SEASON_TABS) -->
        if (anime.seasonGroupingMode == LibraryPreferences.SeasonGrouping.Tabs) {
            val savedSeason = libraryPreferences.lastSelectedSeason(anime.id).get()
            if (savedSeason.isNotEmpty()) {
                episodesForPlayer = episodesForPlayer.filter {
                    val domainEp = it.toDomainEpisode()!!
                    val epSeason = EpisodeSeasonUtils.getSeasonName(domainEp)
                    val effectiveSeason = when {
                        epSeason != null && epSeason != "Season 0" -> epSeason
                        EpisodeSeasonUtils.isSeasonZero(domainEp) -> "Specials"
                        else -> "Extras"
                    }
                    effectiveSeason == savedSeason
                }
            }
        }
        // <-- AM (SEASON_TABS)

        val result = episodesForPlayer.toMutableList()
        if (result.all { it.id != episodeId }) {
            result += listOf(selectedEpisode)
        }

        return result
    }

    fun getCurrentEpisodeIndex(): Int {
        return currentPlaylist.value.indexOfFirst { currentEpisode.value?.id == it.id }
    }

    private fun getAdjacentEpisodeId(previous: Boolean): Long {
        val playlist = currentPlaylist.value
        val skipFiller = playerPreferences.skipFillerEpisodes().get()
        var newIndex = if (previous) getCurrentEpisodeIndex() - 1 else getCurrentEpisodeIndex() + 1

        if (!previous && skipFiller) {
            while (newIndex <= playlist.lastIndex && (fillerEpisodes.contains(playlist[newIndex].episode_number) || playlist[newIndex].fillermark)) {
                newIndex++
            }
        }

        return when {
            previous && newIndex < 0 -> -1L
            !previous && newIndex > playlist.lastIndex -> -1L
            else -> playlist.getOrNull(newIndex)?.id ?: -1L
        }
    }

    fun updateHasNextEpisode(value: Boolean) {
        _hasNextEpisode.update { _ -> value }
    }

    fun updateHasPreviousEpisode(value: Boolean) {
        _hasPreviousEpisode.update { _ -> value }
    }

    fun showEpisodeListDialog() {
        if (currentAnime.value != null) {
            showDialog(Dialogs.EpisodeList)
        }
    }

    /**
     * Called when the activity is saved and not changing configurations. It updates the database
     * to persist the current progress of the active episode.
     */
    fun onSaveInstanceStateNonConfigurationChange() {
        val currentEpisode = currentEpisode.value ?: return
        viewModelScope.launchNonCancellable {
            saveEpisodeProgress(currentEpisode)
        }
    }

    // ====== Initialize anime, episode, hoster, and video list ======

    fun updateIsLoadingHosters(value: Boolean) {
        _isLoadingHosters.update { _ -> value }
    }

    /**
     * Whether this presenter is initialized yet.
     */
    private fun needsInit(): Boolean {
        return currentAnime.value == null || currentEpisode.value == null
    }

    data class InitResult(
        val hosterList: List<Hoster>?,
        val videoIndex: Pair<Int, Int>,
        val position: Long?,
    )

    private var currentHosterList: List<Hoster>? = null

    class ExceptionWithStringResource(
        message: String,
        val stringResource: StringResource,
    ) : Exception(message)

    suspend fun init(
        animeId: Long,
        initialEpisodeId: Long,
        hostList: String,
        hostIndex: Int,
        vidIndex: Int,
    ): Pair<InitResult, Result<Boolean>> {
        val defaultResult = InitResult(currentHosterList, qualityIndex, null)
        if (!needsInit()) return Pair(defaultResult, Result.success(true))
        return try {
            val anime = getAnime.await(animeId)
            if (anime != null) {
                _currentAnime.update { _ -> anime }
                animeTitle.update { _ -> anime.title }
                sourceManager.isInitialized.first { it }
                if (episodeId == -1L) episodeId = initialEpisodeId

                checkTrackers(anime)

                updateEpisodeList(initEpisodeList(anime))

                val episode = currentPlaylist.value.first { it.id == episodeId }
                val source = sourceManager.getOrStub(anime.source)

                _currentEpisode.update { _ -> episode }
                _currentSource.update { _ -> source }

                updateEpisode(episode)

                _hasPreviousEpisode.update { _ -> getCurrentEpisodeIndex() != 0 }
                _hasNextEpisode.update { _ -> getCurrentEpisodeIndex() != currentPlaylist.value.size - 1 }

                // Write to mpv table
                MPVLib.setPropertyString("user-data/current-anime/anime-title", anime.title)
                MPVLib.setPropertyInt("user-data/current-anime/intro-length", getAnimeSkipIntroLength())
                MPVLib.setPropertyString(
                    "user-data/current-anime/category",
                    getAnimeCategories.await(anime.id).joinToString {
                        it.name
                    },
                )

                val currentEp = currentEpisode.value
                    ?: throw ExceptionWithStringResource("No episode loaded", MR.strings.no_episode_loaded)
                if (hostList.isNotBlank()) {
                    currentHosterList = hostList.toHosterList().ifEmpty {
                        currentHosterList = null
                        throw ExceptionWithStringResource(
                            "Hoster selected from empty list",
                            MR.strings.select_hoster_from_empty_list,
                        )
                    }
                    qualityIndex = Pair(hostIndex, vidIndex)
                } else {
                    EpisodeLoader.getHosters(currentEp.toDomainEpisode()!!, anime, source)
                        .takeIf { it.isNotEmpty() }
                        ?.also { currentHosterList = it }
                        ?: run {
                            currentHosterList = null
                            throw ExceptionWithStringResource("Hoster list is empty", MR.strings.no_hosters)
                        }
                }

                val result = InitResult(
                    hosterList = currentHosterList,
                    videoIndex = qualityIndex,
                    position = episodePosition,
                )
                Pair(result, Result.success(true))
            } else {
                // Unlikely but okay
                Pair(defaultResult, Result.success(false))
            }
        } catch (e: Throwable) {
            Pair(defaultResult, Result.failure(e))
        }
    }

    private fun updateEpisode(episode: Episode) {
        mediaTitle.update { _ -> episode.name }
        _isEpisodeOnline.update { _ -> isEpisodeOnline() == true }
        setPropertyDouble("user-data/current-anime/episode-number", episode.episode_number.toDouble())
    }

    private fun initEpisodeList(anime: Anime): List<Episode> {
        // Optimizing: This should ideally be passed in or fetched earlier
        // but for now we keep it simple but non-blocking where possible.
        // We use runBlocking here because it's part of a chain that requires immediate return, 
        // but we'll optimize the call site in future reviews.
        val episodes = runBlocking { getEpisodesByAnimeId.await(anime.id) }

        return episodes
            .sortedWith(getEpisodeSort(anime, sortDescending = false))
            .run {
                if (basePreferences.downloadedOnly().get()) {
                    filterDownloadedEpisodes(anime)
                } else {
                    this
                }
            }
            .map { it.toDbEpisode() }
    }

    private var hasTrackers: Boolean = false
    private val checkTrackers: (Anime) -> Unit = { anime ->
        viewModelScope.launchIO {
            val tracks = getTracks.await(anime.id)
            hasTrackers = tracks.isNotEmpty()
        }
    }

    private var getHosterVideoLinksJob: Job? = null

    fun cancelHosterVideoLinksJob() {
        getHosterVideoLinksJob?.cancel()
    }

    /**
     * Set the video list for hosters.
     */
    fun loadHosters(source: AnimeSource, hosterList: List<Hoster>, hosterIndex: Int, videoIndex: Int) {
        val hasFoundPreferredVideo = AtomicBoolean(false)

        _hosterList.update { _ -> hosterList }
        _hosterExpandedList.update { _ ->
            List(hosterList.size) { true }
        }

        getHosterVideoLinksJob?.cancel()
        getHosterVideoLinksJob = viewModelScope.launchIO {
            lastFailedVideoAttempt = null
            val preloadedVideo = pendingPreloadedVideo
            val preloadedHosterIndex = pendingPreloadedHosterIndex
            val preloadedVideoIndex = pendingPreloadedVideoIndex
            val preloadedStates = pendingPreloadedHosterStates
            val preloadedStartPos = pendingPreloadedStartPosMs
            pendingPreloadedVideo = null
            pendingPreloadedHosterIndex = -1
            pendingPreloadedVideoIndex = -1
            pendingPreloadedHosterStates = null
            pendingPreloadedStartPosMs = null

            if (preloadedStates != null) {
                _hosterState.value = preloadedStates
            } else {
                _hosterState.update { _ ->
                    hosterList.map { hoster ->
                        if (hoster.videoList == null) {
                            HosterState.Loading(hoster.hosterName)
                        } else {
                            val videoList = hoster.videoList!!
                            HosterState.Ready(
                                hoster.hosterName,
                                videoList,
                                List(videoList.size) { Video.State.QUEUE },
                            )
                        }
                    }
                }
            }

            if (preloadedVideo != null && preloadedHosterIndex >= 0 && preloadedVideoIndex >= 0 && hosterIndex == -1) {
                logcat { "Preload: Immediate playback handoff for pre-resolved stream (startPos: ${preloadedStartPos?.div(1000)}s)" }
                tryAcquireAndLoadVideo(
                    source = source,
                    video = preloadedVideo,
                    hosterIndex = preloadedHosterIndex,
                    videoIndex = preloadedVideoIndex,
                    hasFoundPreferredVideo = hasFoundPreferredVideo,
                    resumePosition = preloadedStartPos,
                )
            }

            val defaultSelector = if (hosterIndex == -1) {
                DefaultStreamPreferenceStore(playerPreferences).getEffectiveSelector(currentAnime.value?.id)
            } else {
                ""
            }

            try {
                coroutineScope {
                    hosterList.mapIndexed { hosterIdx, hoster ->
                        async {
                            // If preloaded states already provided this hoster ready, skip duplicate fetch if video is playing
                            if (preloadedStates?.getOrNull(hosterIdx) is HosterState.Ready && hasFoundPreferredVideo.get()) {
                                return@async
                            }

                            val hosterState = EpisodeLoader.loadHosterVideos(source, hoster)

                            _hosterState.updateAt(hosterIdx, hosterState)

                            if (hosterState is HosterState.Ready) {
                                if (hosterIdx == hosterIndex && videoIndex >= 0) {
                                    hosterState.videoList.getOrNull(videoIndex)?.let { video ->
                                        if (tryAcquireAndLoadVideo(source, video, hosterIndex, videoIndex, hasFoundPreferredVideo)) {
                                            return@async
                                        }
                                    }
                                } else if (hosterIndex == -1 && !hasFoundPreferredVideo.get()) {
                                    if (defaultSelector.isNotBlank()) {
                                        val ranked = DefaultStreamSelector.findRankedInHosters(defaultSelector, listOf(hosterState))
                                        ranked.firstOrNull()?.let { (_, vIdx) ->
                                            hosterState.videoList.getOrNull(vIdx)?.let { video ->
                                                tryAcquireAndLoadVideo(source, video, hosterIdx, vIdx, hasFoundPreferredVideo)
                                            }
                                        }
                                    } else if (hosterIdx == 0) {
                                        val prefIndex = hosterState.videoList.indexOfFirst { it.preferred }
                                        val chosenIdx = if (prefIndex != -1) prefIndex else 0
                                        hosterState.videoList.getOrNull(chosenIdx)?.let { video ->
                                            tryAcquireAndLoadVideo(source, video, 0, chosenIdx, hasFoundPreferredVideo)
                                        }
                                    }
                                }
                            }
                        }
                    }.awaitAll()

                    if (!hasFoundPreferredVideo.get() && hosterIndex == -1) {
                        val states = hosterState.value

                        // 1) Pre-resolved next-episode stream from preload
                        if (preloadedVideo != null) {
                            DefaultStreamSelector.findVideoInHosters(states, preloadedVideo)?.let { (hIdx, vIdx) ->
                                val ready = states[hIdx] as? HosterState.Ready
                                val video = ready?.videoList?.getOrNull(vIdx)
                                if (video != null && tryAcquireAndLoadVideo(source, video, hIdx, vIdx, hasFoundPreferredVideo)) {
                                    logcat { "Loaded pre-resolved stream for episode" }
                                }
                            }
                        }

                        // 2) User-saved default (strict then relaxed) — always before extension sort order
                        if (!hasFoundPreferredVideo.get() && defaultSelector.isNotBlank()) {
                            val strictRanked = DefaultStreamSelector.findRankedInHosters(defaultSelector, states)
                            tryLoadRankedVideos(source, states, strictRanked, hasFoundPreferredVideo)
                            if (!hasFoundPreferredVideo.get()) {
                                val relaxedRanked = DefaultStreamSelector.findRankedInHostersRelaxed(defaultSelector, states)
                                    .filter { it !in strictRanked }
                                tryLoadRankedVideos(source, states, relaxedRanked, hasFoundPreferredVideo)
                            }
                        }

                        // 3) Extension preferred — only when user has no saved default
                        if (!hasFoundPreferredVideo.get() && defaultSelector.isBlank()) {
                            tryLoadExtensionPreferred(source, states, hasFoundPreferredVideo)
                        }
                    }

                    if (hasFoundPreferredVideo.compareAndSet(false, true)) {
                        val (hosterIdx, videoIdx) = HosterLoader.selectBestVideo(hosterState.value)
                        if (hosterIdx == -1) {
                            updateIsLoadingEpisode(false)
                            isLoading.value = false
                            setIsStopped(true)
                            throw ExceptionWithStringResource("No available videos", MR.strings.no_available_videos)
                        }

                        val video = (hosterState.value[hosterIdx] as HosterState.Ready).videoList[videoIdx]

                        val success = loadVideo(source, video, hosterIdx, videoIdx)
                        if (!success) {
                            updateIsLoadingEpisode(false)
                            isLoading.value = false
                            setIsStopped(true)
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) {
                    _hosterState.update { _ ->
                        hosterList.map { HosterState.Idle(it.hosterName) }
                    }
                    throw e
                }
                logcat(LogPriority.ERROR, e) { "Error loading hosters" }
                if (e is ExceptionWithStringResource) {
                    activity.runOnUiThread { activity.toast(e.stringResource) }
                }
                updateIsLoadingEpisode(false)
                isLoading.value = false
                setIsStopped(true)
            }
        }
    }

    private var loadingJob: Job? = null

    private suspend fun tryAcquireAndLoadVideo(
        source: AnimeSource,
        video: Video,
        hosterIndex: Int,
        videoIndex: Int,
        hasFoundPreferredVideo: AtomicBoolean,
        resumePosition: Long? = null,
    ): Boolean {
        if (!hasFoundPreferredVideo.compareAndSet(false, true)) return false
        if (hosterIndex == -1 && videoIndex == -1 && selectedHosterVideoIndex.value != Pair(-1, -1)) {
            hasFoundPreferredVideo.set(false)
            return false
        }
        val success = loadVideo(source, video, hosterIndex, videoIndex, resumePosition)
        if (!success) {
            hasFoundPreferredVideo.set(false)
        }
        return success
    }

    private suspend fun tryLoadRankedVideos(
        source: AnimeSource,
        hosterStates: List<HosterState>,
        ranked: List<Pair<Int, Int>>,
        hasFoundPreferredVideo: AtomicBoolean,
    ) {
        for ((hosterIdx, videoIdx) in ranked) {
            if (hasFoundPreferredVideo.get()) return
            val ready = hosterStates.getOrNull(hosterIdx) as? HosterState.Ready ?: continue
            val video = ready.videoList.getOrNull(videoIdx) ?: continue
            if (tryAcquireAndLoadVideo(source, video, hosterIdx, videoIdx, hasFoundPreferredVideo)) {
                return
            }
        }
    }

    private suspend fun tryLoadExtensionPreferred(
        source: AnimeSource,
        hosterStates: List<HosterState>,
        hasFoundPreferredVideo: AtomicBoolean,
    ) {
        hosterStates.forEachIndexed { hosterIdx, state ->
            if (hasFoundPreferredVideo.get()) return
            if (state !is HosterState.Ready) return@forEachIndexed
            val prefIndex = state.videoList.indexOfFirst { it.preferred }
            if (prefIndex == -1) return@forEachIndexed
            val video = state.videoList[prefIndex]
            if (tryAcquireAndLoadVideo(source, video, hosterIdx, prefIndex, hasFoundPreferredVideo)) {
                return
            }
        }
    }

    private suspend fun loadVideo(
        source: AnimeSource?,
        video: Video,
        hosterIndex: Int,
        videoIndex: Int,
        resumePosition: Long? = null,
    ): Boolean {
        val selectedHosterState = (_hosterState.value[hosterIndex] as? HosterState.Ready) ?: return false
        updateIsLoadingEpisode(true)
        setIsStopped(false)

        val oldSelectedIndex = _selectedHosterVideoIndex.value
        _selectedHosterVideoIndex.update { _ -> Pair(hosterIndex, videoIndex) }

        _hosterState.updateAt(
            hosterIndex,
            selectedHosterState.getChangedAt(videoIndex, video, Video.State.LOAD_VIDEO),
        )

        // Pause until everything has loaded
        updatePausedState()
        pause()
        kotlinx.coroutines.delay(500)

        val resolvedVideo = if (selectedHosterState.videoState[videoIndex] != Video.State.READY) {
            HosterLoader.getResolvedVideo(source, video)
        } else {
            video
        }

        if (resolvedVideo == null || resolvedVideo.videoUrl.isEmpty()) {
            if (currentVideo.value == null) {
                _hosterState.updateAt(
                    hosterIndex,
                    selectedHosterState.getChangedAt(videoIndex, video, Video.State.ERROR),
                )

                val (newHosterIdx, newVideoIdx) = HosterLoader.selectBestVideo(hosterState.value)
                if (newHosterIdx == -1) {
                    if (_hosterState.value.any { it is HosterState.Loading }) {
                        _selectedHosterVideoIndex.update { _ -> Pair(-1, -1) }
                        return false
                    } else {
                        throw ExceptionWithStringResource("No available videos", MR.strings.no_available_videos)
                    }
                }

                val newVideo = (hosterState.value[newHosterIdx] as HosterState.Ready).videoList[newVideoIdx]

                return loadVideo(source, newVideo, newHosterIdx, newVideoIdx, resumePosition)
            } else {
                _selectedHosterVideoIndex.update { _ -> oldSelectedIndex }
                _hosterState.updateAt(
                    hosterIndex,
                    selectedHosterState.getChangedAt(videoIndex, video, Video.State.ERROR),
                )
                return false
            }
        }

        _hosterState.updateAt(
            hosterIndex,
            selectedHosterState.getChangedAt(videoIndex, resolvedVideo, Video.State.READY),
        )

        _currentVideo.update { _ -> resolvedVideo }

        qualityIndex = Pair(hosterIndex, videoIndex)

        viewModelScope.launchIO {
            loadThumbnails(resolvedVideo, source)
        }

        activity.setVideo(resolvedVideo, position = if (resumePosition != null && resumePosition > 5000L) resumePosition else null)
        return true
    }

    fun setCurrentVideoError() {
        val (hosterIdx, videoIdx) = selectedHosterVideoIndex.value
        if (hosterIdx == -1 || videoIdx == -1) return
        val currentHosterState = (hosterState.value.getOrNull(hosterIdx) as? HosterState.Ready) ?: return
        val currentVideo = currentHosterState.videoList.getOrNull(videoIdx) ?: return

        _hosterState.updateAt(
            hosterIdx,
            currentHosterState.getChangedAt(videoIdx, currentVideo, Video.State.ERROR),
        )
    }

    private var lastFailedVideoAttempt: Pair<Int, Int>? = null

    suspend fun recoverOrLoadBestVideo(): Boolean {
        val source = currentSource.value ?: return false
        val (hosterIdx, videoIdx) = selectedHosterVideoIndex.value
        val enableSelfHealing = playerPreferences.selfHealingLinks().get()
        val currentPlaybackPos = (pos.value * 1000).toLong()

        // 1. Self-Healing: If token expired, try fresh re-resolution once on the current hoster/video
        if (enableSelfHealing && hosterIdx != -1 && videoIdx != -1 && lastFailedVideoAttempt != Pair(hosterIdx, videoIdx)) {
            lastFailedVideoAttempt = Pair(hosterIdx, videoIdx)
            val currentHosterState = (hosterState.value.getOrNull(hosterIdx) as? HosterState.Ready)
            val currentVid = currentHosterState?.videoList?.getOrNull(videoIdx)
            if (currentVid != null) {
                logcat { "Self-Healing: Stream playback failed. Attempting fresh token re-resolve for: ${currentVid.quality}" }
                try {
                    val uninitializedVideo = currentVid.copy(initialized = false)
                    val freshlyResolved = HosterLoader.getResolvedVideo(source, uninitializedVideo)
                    if (freshlyResolved != null && freshlyResolved.videoUrl.isNotBlank() && freshlyResolved.videoUrl != currentVid.videoUrl) {
                        logcat { "Self-Healing: Re-resolution successful! Resuming stream..." }
                        val success = loadVideo(source, freshlyResolved, hosterIdx, videoIdx, resumePosition = currentPlaybackPos)
                        if (success) return true
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    logcat(LogPriority.WARN, e) { "Self-Healing: Re-resolution failed" }
                }
            }
        }

        // 2. Mark error and cascade to next best video / hoster
        setCurrentVideoError()
        return loadBestVideo(resumePosition = currentPlaybackPos)
    }

    fun loadBestVideo(resumePosition: Long? = null): Boolean {
        val source = currentSource.value ?: return false
        val (hosterIdx, videoIdx) = HosterLoader.selectBestVideo(hosterState.value)
        if (hosterIdx == -1) return false
        val newVideo = (hosterState.value[hosterIdx] as HosterState.Ready).videoList[videoIdx]
        viewModelScope.launchIO {
            try {
                val success = loadVideo(source, newVideo, hosterIdx, videoIdx, resumePosition)
                if (!success) {
                    updateIsLoadingEpisode(false)
                    isLoading.value = false
                    setIsStopped(true)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logcat(LogPriority.ERROR, e) { "Error loading best video" }
                if (e is ExceptionWithStringResource) {
                    activity.runOnUiThread { activity.toast(e.stringResource) }
                }
                updateIsLoadingEpisode(false)
                isLoading.value = false
                setIsStopped(true)
            }
        }
        return true
    }

    fun setDefaultStreamSelector(hosterIndex: Int, videoIndex: Int) {
        val hoster = _hosterState.value.getOrNull(hosterIndex)
        val video = (hoster as? HosterState.Ready)
            ?.videoList
            ?.getOrNull(videoIndex)
            ?: return
        val currentComposite = getEffectiveDefaultStreamSelector()
        val newComposite = DefaultStreamSelector.updateCompositeSelector(
            currentComposite,
            hoster.name,
            DefaultStreamSelector.selectorFor(video, hoster.name)
        )
        DefaultStreamPreferenceStore(playerPreferences).setSelector(
            animeId = currentAnime.value?.id,
            selector = newComposite,
        )
    }

    fun getEffectiveDefaultStreamSelector(): String {
        return DefaultStreamPreferenceStore(playerPreferences).getEffectiveSelector(currentAnime.value?.id)
    }

    fun onVideoClicked(hosterIndex: Int, videoIndex: Int) {
        setDefaultStreamSelector(hosterIndex, videoIndex)
        val hosterState = _hosterState.value[hosterIndex] as? HosterState.Ready
        val video = hosterState?.videoList
            ?.getOrNull(videoIndex)
            ?: return // Shouldn't happen, but just in case™

        val videoState = hosterState.videoState
            .getOrNull(videoIndex)
            ?: return

        if (videoState == Video.State.ERROR) {
            return
        }

        viewModelScope.launchIO {
            try {
                val success = loadVideo(currentSource.value, video, hosterIndex, videoIndex)
                if (success) {
                    if (sheetShown.value == Sheets.QualityTracks) {
                        dismissSheet()
                    }
                } else {
                    updateIsLoadingEpisode(false)
                    isLoading.value = false
                    setIsStopped(true)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logcat(LogPriority.ERROR, e) { "Error manually loading video" }
                updateIsLoadingEpisode(false)
                isLoading.value = false
                setIsStopped(true)
            }
        }
    }

    fun ensureHosterExpanded(index: Int) {
        if (index !in _hosterExpandedList.value.indices) return
        if (!_hosterExpandedList.value[index]) {
            _hosterExpandedList.updateAt(index, true)
        }
    }

    fun onHosterClicked(index: Int) {
        val state = hosterState.value.getOrNull(index) ?: return
        when (state) {
            is HosterState.Ready -> {
                if (index in _hosterExpandedList.value.indices) {
                    _hosterExpandedList.updateAt(index, !_hosterExpandedList.value[index])
                }
            }
            is HosterState.Idle -> {
                val hoster = hosterList.value.getOrNull(index) ?: return
                val hosterName = hoster.hosterName
                _hosterState.updateAt(index, HosterState.Loading(hosterName))

                viewModelScope.launchIO {
                    val hosterState = EpisodeLoader.loadHosterVideos(currentSource.value!!, hoster)
                    _hosterState.updateAt(index, hosterState)
                }
            }
            is HosterState.Loading, is HosterState.Error -> {}
        }
    }

    private fun <T> MutableStateFlow<List<T>>.updateAt(index: Int, newValue: T) {
        this.update { values ->
            if (index !in values.indices) return@update values
            values.toMutableList().apply {
                this[index] = newValue
            }
        }
    }

    data class EpisodeLoadResult(
        val hosterList: List<Hoster>?,
        val episodeTitle: String,
        val source: AnimeSource,
    )

    suspend fun loadEpisode(episodeId: Long?): EpisodeLoadResult? {
        val anime = currentAnime.value ?: return null
        val source = sourceManager.getOrStub(anime.source)

        val chosenEpisode = currentPlaylist.value.firstOrNull { ep -> ep.id == episodeId } ?: return null

        _currentEpisode.update { _ -> chosenEpisode }
        updateEpisode(chosenEpisode)
        lastFailedVideoAttempt = null
        skippedSegments.clear()
        cancelPreload()

        return withIOContext {
            val meta = preloadedMeta
            try {
                val currentEpisode =
                    currentEpisode.value
                        ?: throw ExceptionWithStringResource("No episode loaded", MR.strings.no_episode_loaded)
                
                val isMetaStateValid = nextEpisodeState.value == PreloadState.MetadataReady || nextEpisodeState.value == PreloadState.BufferReady
                
                if (isMetaStateValid && meta != null && isMetaValid(meta) && episodeId == meta.episodeId) {
                    logcat { "Using preloaded hoster list for episode: ${currentEpisode.name}" }
                    currentHosterList = meta.hosterList
                    // We'll let HosterLoader pick up the initialized video from the list
                } else {
                    if (meta != null && !isMetaValid(meta)) {
                        logcat { "Preloaded meta expired (TTL). Fetching fresh hoster list." }
                    }
                    currentHosterList = EpisodeLoader.getHosters(
                        currentEpisode.toDomainEpisode()!!,
                        anime,
                        source,
                    )
                }

                this@PlayerViewModel.episodeId = currentEpisode.id!!
            } catch (e: Exception) {
                if (e is CancellationException) {
                    throw e
                }
                logcat(LogPriority.ERROR, e) { e.message ?: "Error getting links" }
            } finally {
                pendingPreloadedVideo = meta?.video
                pendingPreloadedHosterIndex = meta?.hosterIndex ?: -1
                pendingPreloadedVideoIndex = meta?.videoIndex ?: -1
                pendingPreloadedHosterStates = meta?.hosterStates
                pendingPreloadedStartPosMs = meta?.startPositionMs
                _nextEpisodeState.value = PreloadState.None
                preloadedMeta = null
            }

            EpisodeLoadResult(
                hosterList = currentHosterList,
                episodeTitle = anime.title + " - " + chosenEpisode.name,
                source = source,
            )
        }
    }

    /**
     * Called every time a second is reached in the player. Used to mark the flag of episode being
     * seen, update tracking services, enqueue downloaded episode deletion and download next episode.
     */
    private fun onSecondReached(position: Int, duration: Int) {
        if (isLoadingEpisode.value) return
        val currentEp = currentEpisode.value ?: return
        if (episodeId == -1L) return
        if (duration == 0) return

        val seconds = position * 1000L
        val totalSeconds = duration * 1000L
        // Save last second seen and mark as seen if needed
        currentEp.last_second_seen = seconds
        currentEp.total_seconds = totalSeconds

        episodePosition = seconds

        val progress = playerPreferences.progressPreference().get()
        val shouldTrack = !incognitoMode || hasTrackers
        if (seconds >= totalSeconds * progress && shouldTrack) {
            currentEp.seen = true
            updateTrackEpisodeSeen(currentEp)
            deleteEpisodeIfNeeded(currentEp)
        }

        saveWatchingProgress(currentEp)

        val currentProgress = seconds.toDouble() / totalSeconds
        val inDownloadRange = currentProgress > 0.35
        if (inDownloadRange) {
            downloadNextEpisodes()
        }

        // Dynamic Lead-Time Preload Engine (EWMA Predictor)
        val preloadMode = playerPreferences.preloadMode().get()
        val performanceProfile = decoderPreferences.performanceProfile().get()
        val canPreloadPerformance = when (performanceProfile) {
            PlayerEfficiency.MaxPerformance -> true
            PlayerEfficiency.PowerSaver -> false
            PlayerEfficiency.Balanced -> true
            PlayerEfficiency.Automatic -> DeviceTierManager.getTier(activity) != DeviceTierManager.Tier.LOW
        }

        // Hierarchy: PreloadMode (Explicit Intent) > PerformanceProfile (Global) > Tier (Default)
        val shouldPreload = when (preloadMode) {
            PreloadMode.Off -> false
            PreloadMode.Always -> true // User explicitly wants it Always
            PreloadMode.WifiOnly -> activity.isConnectedToWifi() && canPreloadPerformance
            else -> false
        }

        // Network-Aware Throttling: If the current video is struggling (buffering), delay preloading
        val networkThrottlingEnabled = playerPreferences.networkAwareThrottling().get()
        val isStruggling = isLoading.value && networkThrottlingEnabled

        // Calculate dynamic lead-time: clamp between 30s and 90s based on predicted resolution latency
        val predictedLeadSeconds = ((ewmaResolutionTimeMs / 1000f) * 3.5f + 25f).coerceIn(30f, 90f)
        val remainingSeconds = (totalSeconds - seconds) / 1000.0
        val isWithinLeadWindow = remainingSeconds <= predictedLeadSeconds || currentProgress > 0.85

        if (isWithinLeadWindow && !isStruggling && activity.player.paused != true && 
            nextEpisodeState.value == PreloadState.None && shouldPreload) {
            preloadNextEpisodeMetadata()
        }
    }

    data class PreloadedMeta(
        val episodeId: Long,
        val hosterList: List<Hoster>,
        val hosterStates: List<HosterState>? = null,
        val video: Video? = null,
        val hosterIndex: Int = -1,
        val videoIndex: Int = -1,
        val startPositionMs: Long? = null,
        val createdAtMs: Long = System.currentTimeMillis(),
    )

    private val _nextEpisodeState = MutableStateFlow(PreloadState.None)
    val nextEpisodeState = _nextEpisodeState.asStateFlow()
    private var preloadedMeta: PreloadedMeta? = null
    private var pendingPreloadedVideo: Video? = null
    private var pendingPreloadedHosterIndex: Int = -1
    private var pendingPreloadedVideoIndex: Int = -1
    private var pendingPreloadedHosterStates: List<HosterState>? = null
    private var pendingPreloadedStartPosMs: Long? = null
    private var preloadJob: Job? = null
    private var lastPreloadFailAt = 0L

    // EWMA Network & Resolution Latency Predictor for Preload Lead-Time (Initial default: 4.0s)
    private var ewmaResolutionTimeMs = 4000f

    private fun updateEwmaResolutionTime(measuredTimeMs: Long) {
        val clampedTime = measuredTimeMs.toFloat().coerceIn(800f, 25000f)
        ewmaResolutionTimeMs = (0.3f * clampedTime) + (0.7f * ewmaResolutionTimeMs)
        logcat { "Preload EWMA: Updated predicted resolution latency: ${ewmaResolutionTimeMs.toInt()}ms" }
    }

    private fun isMetaValid(meta: PreloadedMeta) =
        System.currentTimeMillis() - meta.createdAtMs < 3 * 60_000 // 3 minutes TTL

    private fun canRetryPreload(): Boolean =
        System.currentTimeMillis() - lastPreloadFailAt > 60_000 // 1 minute backoff

    fun cancelPreload() {
        pendingPreloadedVideo = null
        pendingPreloadedHosterIndex = -1
        pendingPreloadedVideoIndex = -1
        pendingPreloadedHosterStates = null
        pendingPreloadedStartPosMs = null
        preloadJob?.cancel()
        preloadJob = null
    }

    private fun preloadNextEpisodeMetadata() {
        val list = currentPlaylist.value
        if (list.isEmpty()) return
        val currentIndex = getCurrentEpisodeIndex()
        val hasNext = currentIndex in 0 until list.lastIndex
        
        if (!hasNext) {
            _nextEpisodeState.value = PreloadState.Unavailable
            return
        }
        
        if (_nextEpisodeState.value == PreloadState.Failed && !canRetryPreload()) return
        if (_nextEpisodeState.value == PreloadState.MetadataLoading || _nextEpisodeState.value == PreloadState.MetadataReady || _nextEpisodeState.value == PreloadState.PreloadingBuffer || _nextEpisodeState.value == PreloadState.BufferReady) return

        val nextEpisode = list[currentIndex + 1]
        val nextEpisodeId = nextEpisode.id ?: return

        _nextEpisodeState.value = PreloadState.MetadataLoading
        logcat { "Preload: Starting for episode=$nextEpisodeId" }
        val startTime = System.currentTimeMillis()
        preloadJob = viewModelScope.launchIO {
            try {
                val anime = currentAnime.value ?: return@launchIO
                val source = sourceManager.getOrStub(anime.source)
                
                logcat { "Preload: Fetching hosters for ${nextEpisode.name}" }
                val hosterList = EpisodeLoader.getHosters(
                    nextEpisode.toDomainEpisode()!!,
                    anime,
                    source,
                )
                
                var resolvedResult: HosterLoader.Companion.ResolvedVideoResult? = null
                
                // If intelligent buffer handoff or self-healing links are enabled, pre-resolve the best video
                val enableBuffering = playerPreferences.intelligentBufferHandoff().get()
                val enableSelfHealing = playerPreferences.selfHealingLinks().get()
                
                val defaultSelector = DefaultStreamPreferenceStore(playerPreferences)
                    .getEffectiveSelector(anime.id)

                if (defaultSelector.isNotBlank()) {
                    _nextEpisodeState.value = PreloadState.PreloadingBuffer
                    logcat { "Preload: Resolving saved default stream for episode=$nextEpisodeId" }
                    try {
                        resolvedResult = HosterLoader.resolveDefaultStreamWithResult(source, hosterList, defaultSelector)
                    } catch (e: Exception) {
                        logcat(LogPriority.WARN, e) { "Preload: Default stream resolution failed" }
                    }
                }

                if (resolvedResult == null && defaultSelector.isBlank() && (enableBuffering || enableSelfHealing)) {
                    _nextEpisodeState.value = PreloadState.PreloadingBuffer
                    logcat { "Preload: Resolving best video for episode=$nextEpisodeId" }
                    try {
                        resolvedResult = HosterLoader.getBestVideoWithResult(source, hosterList)
                    } catch (e: Exception) {
                         logcat(LogPriority.WARN, e) { "Preload: Video resolution failed" }
                    }
                }

                val resolvedVideo = resolvedResult?.video
                if (resolvedVideo != null) {
                    logcat { "Preload: Successfully resolved video: ${resolvedVideo.videoUrl.take(50)}..." }
                    if (enableBuffering && resolvedVideo.videoUrl.isNotBlank()) {
                        // 1. 0-RTT DNS & TLS Session Resumption socket pre-warming for main video stream
                        try {
                            val client = networkHelper.client
                            val sourceHeaders = (source as? eu.kanade.tachiyomi.animesource.online.AnimeHttpSource)?.headers
                            val headersBuilder = (resolvedVideo.headers ?: sourceHeaders)?.newBuilder()
                                ?: okhttp3.Headers.Builder()
                            val request = okhttp3.Request.Builder()
                                .url(resolvedVideo.videoUrl)
                                .headers(headersBuilder.build())
                                .head()
                                .build()
                            client.newCall(request).execute().use { }
                            logcat { "Preload: Successfully warmed 0-RTT TLS socket for main video." }
                        } catch (e: Exception) {
                            logcat(LogPriority.WARN, e) { "Preload: Video socket warming failed (non-fatal)" }
                        }

                        // 2. Subtitle & Font Cache Pre-Warming (libass font cache priming)
                        if (resolvedVideo.subtitleTracks.isNotEmpty()) {
                            try {
                                val client = networkHelper.client
                                val sourceHeaders = (source as? eu.kanade.tachiyomi.animesource.online.AnimeHttpSource)?.headers
                                val headersBuilder = (resolvedVideo.headers ?: sourceHeaders)?.newBuilder()
                                    ?: okhttp3.Headers.Builder()

                                resolvedVideo.subtitleTracks.take(3).forEach { subTrack ->
                                    if (subTrack.url.startsWith("http")) {
                                        try {
                                            val subReq = okhttp3.Request.Builder()
                                                .url(subTrack.url)
                                                .headers(headersBuilder.build())
                                                .head()
                                                .build()
                                            client.newCall(subReq).execute().use { }
                                            logcat { "Preload: Subtitle & font connection primed for ${subTrack.lang}" }
                                        } catch (_: Exception) {
                                            // non-fatal
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                logcat(LogPriority.WARN, e) { "Preload: Subtitle pre-warming skipped" }
                            }
                        }

                        // 3. External Audio Socket Pre-Warming
                        if (resolvedVideo.audioTracks.isNotEmpty()) {
                            try {
                                val client = networkHelper.client
                                val sourceHeaders = (source as? eu.kanade.tachiyomi.animesource.online.AnimeHttpSource)?.headers
                                val headersBuilder = (resolvedVideo.headers ?: sourceHeaders)?.newBuilder()
                                    ?: okhttp3.Headers.Builder()

                                resolvedVideo.audioTracks.take(2).forEach { audioTrack ->
                                    if (audioTrack.url.startsWith("http")) {
                                        try {
                                            val audioReq = okhttp3.Request.Builder()
                                                .url(audioTrack.url)
                                                .headers(headersBuilder.build())
                                                .head()
                                                .build()
                                            client.newCall(audioReq).execute().use { }
                                            logcat { "Preload: Audio track connection primed for ${audioTrack.lang}" }
                                        } catch (_: Exception) {
                                            // non-fatal
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                logcat(LogPriority.WARN, e) { "Preload: Audio pre-warming skipped" }
                            }
                        }
                    }
                } else {
                    logcat { "Preload: Hoster list ready without stream pre-resolution." }
                }

                // Pre-calculate AniSkip intro skip position for next episode if auto-skip is enabled
                var preloadedStartPosMs: Long? = null
                if (introSkipEnabled && autoSkip && playerPreferences.aniSkipEnabled().get()) {
                    try {
                        val nextEpNumber = nextEpisode.episode_number.toInt()
                        val stamps = getAniSkipStampsForEpisode(anime.id, nextEpNumber, 1440L)
                        val opStamp = stamps?.firstOrNull { it.type == ChapterType.Opening && it.start <= 5.0 }
                        if (opStamp != null) {
                            preloadedStartPosMs = ((opStamp.end + 0.5f) * 1000L).toLong()
                            logcat { "Preload: Pre-calculated intro skip start position at ${preloadedStartPosMs / 1000}s" }
                        }
                    } catch (e: Exception) {
                        logcat(LogPriority.WARN, e) { "Preload: AniSkip pre-positioning skipped" }
                    }
                }

                val elapsed = System.currentTimeMillis() - startTime
                updateEwmaResolutionTime(elapsed)
                
                preloadedMeta = PreloadedMeta(
                    episodeId = nextEpisodeId,
                    hosterList = hosterList,
                    hosterStates = resolvedResult?.hosterStates,
                    video = resolvedVideo,
                    hosterIndex = resolvedResult?.hosterIndex ?: -1,
                    videoIndex = resolvedResult?.videoIndex ?: -1,
                    startPositionMs = preloadedStartPosMs,
                )
                _nextEpisodeState.value = if (resolvedVideo != null) PreloadState.BufferReady else PreloadState.MetadataReady
                logcat { "Preload: Ready for episode=$nextEpisodeId (State: ${_nextEpisodeState.value})" }
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Preload: Failed for episode=$nextEpisodeId" }
                lastPreloadFailAt = System.currentTimeMillis()
                _nextEpisodeState.value = PreloadState.Failed
            }
        }
    }

    private fun downloadNextEpisodes() {
        if (downloadAheadAmount == 0) return
        val anime = currentAnime.value ?: return

        // Only download ahead if current + next episode is already downloaded too to avoid jank
        if (getCurrentEpisodeIndex() == currentPlaylist.value.lastIndex) return
        val currentEpisode = currentEpisode.value ?: return

        val nextEpisode = currentPlaylist.value[getCurrentEpisodeIndex() + 1]
        val episodesAreDownloaded =
            EpisodeLoader.isDownload(currentEpisode.toDomainEpisode()!!, anime) &&
                EpisodeLoader.isDownload(nextEpisode.toDomainEpisode()!!, anime)

        viewModelScope.launchIO {
            if (!episodesAreDownloaded) {
                return@launchIO
            }
            val episodesToDownload = getNextEpisodes.await(anime.id, nextEpisode.id!!)
                .take(downloadAheadAmount)
                .filterNot { EpisodeLoader.isDownload(it, anime) }
            downloadManager.downloadEpisodes(anime, episodesToDownload)
        }
    }

    /**
     * Determines if deleting option is enabled and nth to last episode actually exists.
     * If both conditions are satisfied enqueues episode for delete
     * @param chosenEpisode current episode, which is going to be marked as seen.
     */
    private fun deleteEpisodeIfNeeded(chosenEpisode: Episode) {
        // Determine which episode should be deleted and enqueue
        val currentEpisodePosition = currentPlaylist.value.indexOf(chosenEpisode)
        val removeAfterSeenSlots = downloadPreferences.removeAfterReadSlots().get()
        val episodeToDelete = currentPlaylist.value.getOrNull(
            currentEpisodePosition - removeAfterSeenSlots,
        )
        // If episode is completely seen no need to download it
        episodeToDownload = null

        // Check if deleting option is enabled and episode exists
        if (removeAfterSeenSlots != -1 && episodeToDelete != null) {
            viewModelScope.launchNonCancellable {
                enqueueDeleteSeenEpisodes(episodeToDelete)
                deletePendingEpisodes()
            }
        }

        if (downloadPreferences.removeAfterMarkedAsSeen().get() && chosenEpisode.seen) {
            viewModelScope.launchNonCancellable {
                enqueueDeleteSeenEpisodes(chosenEpisode)
                deletePendingEpisodes()
            }
        }
    }

    fun saveCurrentEpisodeWatchingProgress() {
        currentEpisode.value?.let { saveWatchingProgress(it) }
    }

    /**
     * Called when episode is changed in player or when activity is paused.
     */
    private fun saveWatchingProgress(episode: Episode) {
        viewModelScope.launchNonCancellable {
            saveEpisodeProgress(episode)
            saveEpisodeHistory(episode)
        }
    }

    /**
     * Saves this [episode] progress (last second seen and whether it's seen).
     * If incognito mode isn't on or has at least 1 tracker
     */
    private suspend fun saveEpisodeProgress(episode: Episode) {
        if (!incognitoMode || hasTrackers) {
            updateEpisode.await(
                EpisodeUpdate(
                    id = episode.id!!,
                    seen = episode.seen,
                    bookmark = episode.bookmark,
                    lastSecondSeen = episode.last_second_seen,
                    totalSeconds = episode.total_seconds,
                ),
            )
        }
    }

    /**
     * Saves this [episode] last seen history if incognito mode isn't on.
     */
    private suspend fun saveEpisodeHistory(episode: Episode) {
        if (!incognitoMode) {
            val episodeId = episode.id!!
            val seenAt = Date()
            upsertHistory.await(
                HistoryUpdate(episodeId, seenAt, 0),
            )
        }
    }

    /**
     * Bookmarks the currently active episode.
     */
    fun bookmarkEpisode(episodeId: Long?, bookmarked: Boolean) {
        viewModelScope.launchNonCancellable {
            updateEpisode.await(
                EpisodeUpdate(
                    id = episodeId!!,
                    bookmark = bookmarked,
                ),
            )
        }
    }

    // AM (FILLERMARK) -->
    /**
     * Fillermarks the currently active episode.
     */
    fun fillermarkEpisode(episodeId: Long?, fillermarked: Boolean) {
        viewModelScope.launchNonCancellable {
            updateEpisode.await(
                EpisodeUpdate(
                    id = episodeId!!,
                    fillermark = fillermarked,
                ),
            )
        }
    }
    // <-- AM (FILLERMARK)

    fun takeScreenshot(cachePath: String, showSubtitles: Boolean): InputStream? {
        val filename = cachePath + "/${System.currentTimeMillis()}_mpv_screenshot_tmp.png"
        val subtitleFlag = if (showSubtitles) "subtitles" else "video"

        MPVLib.command(arrayOf("screenshot-to-file", filename, subtitleFlag))
        val tempFile = File(filename).takeIf { it.exists() } ?: return null
        val newFile = File("$cachePath/mpv_screenshot.png")

        newFile.delete()
        tempFile.renameTo(newFile)
        return newFile.takeIf { it.exists() }?.inputStream()
    }

    /**
     * Saves the screenshot on the pictures directory and notifies the UI of the result.
     * There's also a notification to allow sharing the image somewhere else or deleting it.
     */
    fun saveImage(imageStream: () -> InputStream, timePos: Int?) {
        val anime = currentAnime.value ?: return

        val context = Injekt.get<Application>()
        val notifier = SaveImageNotifier(context)
        notifier.onClear()

        val seconds = timePos?.let { Utils.prettyTime(it) } ?: return
        val filename = generateFilename(anime, seconds) ?: return

        // Pictures directory.
        val relativePath = DiskUtil.buildValidFilename(anime.title)

        // Copy file in background.
        viewModelScope.launchNonCancellable {
            try {
                val uri = imageSaver.save(
                    image = Image.Page(
                        inputStream = imageStream,
                        name = filename,
                        location = Location.Pictures(relativePath),
                    ),
                )
                notifier.onComplete(uri)
                eventChannel.send(Event.SavedImage(SaveImageResult.Success(uri)))
            } catch (e: Throwable) {
                notifier.onError(e.message)
                eventChannel.send(Event.SavedImage(SaveImageResult.Error(e)))
            }
        }
    }

    /**
     * Shares the screenshot and notifies the UI with the path of the file to share.
     * The image must be first copied to the internal partition because there are many possible
     * formats it can come from, like a zipped chapter, in which case it's not possible to directly
     * get a path to the file and it has to be decompressed somewhere first. Only the last shared
     * image will be kept so it won't be taking lots of internal disk space.
     */
    fun shareImage(imageStream: () -> InputStream, timePos: Int?) {
        val anime = currentAnime.value ?: return

        val context = Injekt.get<Application>()
        val destDir = context.cacheImageDir

        val seconds = timePos?.let { Utils.prettyTime(it) } ?: return
        val filename = generateFilename(anime, seconds) ?: return

        try {
            viewModelScope.launchIO {
                destDir.deleteRecursively()
                val uri = imageSaver.save(
                    image = Image.Page(
                        inputStream = imageStream,
                        name = filename,
                        location = Location.Cache,
                    ),
                )
                eventChannel.send(Event.ShareImage(uri, seconds))
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
        }
    }

    /**
     * Sets the screenshot as cover and notifies the UI of the result.
     */
    fun setAsCover(imageStream: () -> InputStream) {
        val anime = currentAnime.value ?: return

        viewModelScope.launchNonCancellable {
            val result = try {
                anime.editCover(Injekt.get(), imageStream())
                if (anime.isLocal() || anime.favorite) {
                    SetAsCover.Success
                } else {
                    SetAsCover.AddToLibraryFirst
                }
            } catch (e: Exception) {
                SetAsCover.Error
            }
            eventChannel.send(Event.SetCoverResult(result))
        }
    }

    /**
     * Results of the save image feature.
     */
    sealed class SaveImageResult {
        class Success(val uri: Uri) : SaveImageResult()
        class Error(val error: Throwable) : SaveImageResult()
    }

    private fun updateTrackEpisodeSeen(episode: Episode) {
        if (basePreferences.incognitoMode().get() || !hasTrackers) return
        if (!trackPreferences.autoUpdateTrack().get()) return

        val anime = currentAnime.value ?: return
        val context = Injekt.get<Application>()

        viewModelScope.launchNonCancellable {
            logActivity.await(anime.source, ActivityLog.TYPE_COMPLETE, animeId = anime.id)
            trackEpisode.await(context, anime.id, episode.episode_number.toDouble())
        }
    }

    /**
     * Enqueues this [episode] to be deleted when [deletePendingEpisodes] is called. The download
     * manager handles persisting it across process deaths.
     */
    private suspend fun enqueueDeleteSeenEpisodes(episode: Episode) {
        if (!episode.seen) return
        val anime = currentAnime.value ?: return
        withIOContext {
            downloadManager.enqueueEpisodesToDelete(listOf(episode.toDomainEpisode()!!), anime)
        }
    }

    /**
     * Deletes all the pending episodes. This operation will run in a background thread and errors
     * are ignored.
     */
    fun deletePendingEpisodes() {
        viewModelScope.launchNonCancellable {
            downloadManager.deletePendingEpisodes()
        }
    }

    /**
     * Returns the skipIntroLength used by this anime or the default one.
     */
    fun getAnimeSkipIntroLength(): Int {
        val default = gesturePreferences.defaultIntroLength().get()
        val anime = currentAnime.value ?: return default
        val skipIntroLength = anime.skipIntroLength
        val skipIntroDisable = anime.skipIntroDisable
        return when {
            skipIntroDisable -> 0
            skipIntroLength <= 0 -> default
            else -> anime.skipIntroLength
        }
    }

    /**
     * Updates the skipIntroLength for the open anime.
     */
    fun setAnimeSkipIntroLength(skipIntroLength: Long) {
        val anime = currentAnime.value ?: return
        if (!anime.favorite) return
        // Skip unnecessary database operation
        if (skipIntroLength == getAnimeSkipIntroLength().toLong()) return
        viewModelScope.launchIO {
            setAnimeViewerFlags.awaitSetSkipIntroLength(anime.id, skipIntroLength)
            _currentAnime.update { _ -> getAnime.await(anime.id) }
        }
    }

    /**
     * Generate a filename for the given [anime] and [timePos]
     */
    private fun generateFilename(
        anime: Anime,
        timePos: String,
    ): String? {
        val episode = currentEpisode.value ?: return null
        val filenameSuffix = " - $timePos"
        return DiskUtil.buildValidFilename(
            "${anime.title} - ${episode.name}".takeBytes(
                DiskUtil.MAX_FILE_NAME_BYTES - filenameSuffix.byteSize(),
            ),
        ) + filenameSuffix
    }

    /**
     * Helper to fetch AniSkip timestamps for an anime and episode number.
     */
    suspend fun getAniSkipStampsForEpisode(animeId: Long, episodeNumber: Int, duration: Long): List<TimeStamp>? {
        val trackerManager = Injekt.get<TrackerManager>()
        val tracks = getTracks.await(animeId)
        if (tracks.isEmpty()) return null

        for (track in tracks) {
            val tracker = trackerManager.get(track.trackerId)
            val malId = when (tracker) {
                is MyAnimeList -> track.remoteId
                is Anilist -> AniSkipApi().getMalIdFromAL(track.remoteId)
                else -> null
            }
            if (malId != null) {
                return AniSkipApi().getResult(malId.toInt(), episodeNumber, duration)
            }
        }
        return null
    }

    /**
     * Returns the response of the AniSkipApi for this episode.
     * just works if tracking is enabled.
     */
    suspend fun aniSkipResponse(playerDuration: Int?): List<TimeStamp>? {
        val animeId = currentAnime.value?.id ?: return null
        val episodeNumber = currentEpisode.value?.episode_number?.toInt() ?: return null
        val duration = playerDuration?.toLong() ?: return null
        return getAniSkipStampsForEpisode(animeId, episodeNumber, duration)
    }

    val introSkipEnabled = playerPreferences.enableSkipIntro().get()
    private val autoSkip = playerPreferences.autoSkipIntro().get()
    private val netflixStyle = playerPreferences.enableNetflixStyleIntroSkip().get()

    private val defaultWaitingTime = playerPreferences.waitingTimeIntroSkip().get()
    var waitingSkipIntro = defaultWaitingTime

    // Hysteresis dead-zone tracker to prevent backwards seek bounce on chapter boundaries
    private val skippedSegments = mutableSetOf<String>()

    fun setChapter(position: Float) {
        getCurrentChapter(position)?.let { (chapterIndex, chapter) ->
            if (currentChapter.value != chapter) {
                _currentChapter.update { _ -> chapter }
            }

            if (!introSkipEnabled) {
                return
            }

            if (chapter.chapterType == ChapterType.Other) {
                _skipIntroText.update { _ -> null }
                waitingSkipIntro = defaultWaitingTime
            } else {
                val segmentKey = "${chapter.name}_${chapter.start.toInt()}"
                val isAlreadySkipped = segmentKey in skippedSegments

                // Determine true target after this segment: next chapter start, or end of episode if last chapter
                val nextChapterPos = chapters.value.getOrNull(chapterIndex + 1)?.start
                    ?: duration.value.takeIf { it > 0f }
                    ?: (pos.value + 85f)

                // Add hysteresis boundary offset (+0.5s) to guarantee landing cleanly past the intro
                val targetSeekPos = (nextChapterPos + 0.5f).coerceAtMost(duration.value.takeIf { it > 0f } ?: Float.MAX_VALUE)

                if (netflixStyle) {
                    if (isAlreadySkipped) {
                        _skipIntroText.update { _ -> null }
                        return
                    }
                    // show a toast with the seconds before the skip
                    if (waitingSkipIntro == defaultWaitingTime) {
                        activity.showToast(
                            "Skip Intro: ${activity.stringResource(
                                MR.strings.player_aniskip_dontskip_toast,
                                chapter.name,
                                waitingSkipIntro,
                            )}",
                        )
                    }
                    showSkipIntroButton(chapter, targetSeekPos, waitingSkipIntro, segmentKey)
                    waitingSkipIntro--
                } else if (autoSkip) {
                    if (!isAlreadySkipped) {
                        skippedSegments.add(segmentKey)
                        seekToWithText(
                            seekValue = targetSeekPos.toInt(),
                            text = activity.stringResource(MR.strings.player_intro_skipped, chapter.name),
                            forcePrecise = true,
                        )
                    }
                } else {
                    updateSkipIntroButton(chapter.chapterType)
                }
            }
        }
    }

    private fun updateSkipIntroButton(chapterType: ChapterType) {
        val skipButtonString = chapterType.getStringRes()

        _skipIntroText.update { _ ->
            skipButtonString?.let {
                activity.stringResource(
                    MR.strings.player_skip_action,
                    activity.stringResource(skipButtonString),
                )
            }
        }
    }

    private fun showSkipIntroButton(chapter: IndexedSegment, nextChapterPos: Float, waitingTime: Int, segmentKey: String? = null) {
        if (waitingTime > -1) {
            if (waitingTime > 0) {
                _skipIntroText.update { _ -> activity.stringResource(MR.strings.player_aniskip_dontskip) }
            } else {
                if (segmentKey != null) skippedSegments.add(segmentKey)
                seekToWithText(
                    seekValue = nextChapterPos.toInt(),
                    text = activity.stringResource(MR.strings.player_aniskip_skip, chapter.name),
                    forcePrecise = true,
                )
            }
        } else {
            // when waitingTime is -1, it means that the user cancelled the skip
            updateSkipIntroButton(chapter.chapterType)
        }
    }

    fun onSkipIntro() {
        getCurrentChapter()?.let { (chapterIndex, chapter) ->
            // this stops the counter
            if (waitingSkipIntro > 0 && netflixStyle) {
                waitingSkipIntro = -1
                return
            }

            val segmentKey = "${chapter.name}_${chapter.start.toInt()}"
            skippedSegments.add(segmentKey)

            val nextChapterPos = chapters.value.getOrNull(chapterIndex + 1)?.start
                ?: duration.value.takeIf { it > 0f }
                ?: (pos.value + 85f)
            val targetSeekPos = (nextChapterPos + 0.5f).coerceAtMost(duration.value.takeIf { it > 0f } ?: Float.MAX_VALUE)

            seekToWithText(
                seekValue = targetSeekPos.toInt(),
                text = activity.stringResource(MR.strings.player_aniskip_skip, chapter.name),
                forcePrecise = true,
            )
        }
    }

    private fun getCurrentChapter(position: Float? = null): IndexedValue<IndexedSegment>? {
        return chapters.value.withIndex()
            .filter { it.value.start <= (position ?: pos.value) }
            .maxByOrNull { it.value.start }
    }

    fun setPrimaryCustomButtonTitle(button: CustomButton) {
        _primaryButtonTitle.update { _ -> button.name }
    }

    sealed class Event {
        data class SetCoverResult(val result: SetAsCover) : Event()
        data class SavedImage(val result: SaveImageResult) : Event()
        data class ShareImage(val uri: Uri, val seconds: String) : Event()
    }

    suspend fun loadThumbnails(video: Video, source: AnimeSource?) {
        synchronized(thumbnailTileCache) {
            thumbnailTileCache.clear()
        }
        if (source is AnimeHttpSource) {
            try {
                val thumbInfo = source.getVideoThumbnails(video)
                if (thumbInfo != null) {
                    thumbnailInfo.update { _ ->
                        ThumbnailInfo(
                            tileInfo = thumbInfo.tileInfo.sortedBy { it.timeMs },
                            imageTileUrls = thumbInfo.imageTileUrls,
                        )
                    }

                    // Preload first 2 tilemaps
                    thumbInfo.imageTileUrls.take(2).forEachIndexed { index, tileUrl ->
                        val bitmap = source.getImageTile(tileUrl)
                        if (bitmap != null) {
                            synchronized(thumbnailTileCache) {
                                thumbnailTileCache[index] = bitmap
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logcat(LogPriority.ERROR, e) { "Failed to fetch thumbnails" }
            }
        }
    }
}

fun CustomButton.execute() {
    MPVLib.command(arrayOf("script-message", "call_button_$id"))
}

fun CustomButton.executeLongPress() {
    MPVLib.command(arrayOf("script-message", "call_button_${id}_long"))
}

fun Float.normalize(inMin: Float, inMax: Float, outMin: Float, outMax: Float): Float {
    return (this - inMin) * (outMax - outMin) / (inMax - inMin) + outMin
}
