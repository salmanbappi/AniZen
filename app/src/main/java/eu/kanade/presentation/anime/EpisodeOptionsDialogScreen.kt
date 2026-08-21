package eu.kanade.presentation.anime

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Input
import androidx.compose.material.icons.outlined.NavigateNext
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.core.screen.Screen
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.common.images.WebImage
import eu.kanade.presentation.components.TabbedDialogPaddings
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.util.subtitles.SubtitleDownloader
import tachiyomi.presentation.core.components.material.IconButtonTokens
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.player.controls.components.sheets.HosterState
import eu.kanade.tachiyomi.ui.player.controls.components.sheets.QualitySheetHosterContent
import eu.kanade.tachiyomi.ui.player.controls.components.sheets.QualitySheetVideoContent
import eu.kanade.tachiyomi.ui.player.controls.components.sheets.getChangedAt
import eu.kanade.tachiyomi.ui.player.loader.EpisodeLoader
import eu.kanade.tachiyomi.ui.player.loader.HosterLoader
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource as stringResourceContext
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchUI
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.anime.interactor.GetAnime
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.episode.interactor.GetEpisode
import tachiyomi.domain.episode.model.Episode
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.i18n.ank.AMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

private val playerPreferences = Injekt.get<PlayerPreferences>()

enum class SubtitleDownloadStatus {
    IDLE,
    DOWNLOADING,
    SUCCESS,
    ERROR,
}

class EpisodeOptionsDialogScreen(
    private val useExternalDownloader: Boolean,
    private val episodeTitle: String,
    private val episodeId: Long,
    private val animeId: Long,
    private val sourceId: Long,
) : Screen {

    @Composable
    override fun Content() {
        val sm = rememberScreenModel {
            EpisodeOptionsDialogScreenModel(
                episodeId = episodeId,
                animeId = animeId,
                sourceId = sourceId,
            )
        }

        val episode by sm.episode.collectAsState()
        val anime by sm.anime.collectAsState()
        val hosterState by sm.hosterState.collectAsState()
        val hosterExpandedList by sm.hosterExpandedList.collectAsState()
        val selectedHosterVideoIndex by sm.selectedHosterVideoIndex.collectAsState()
        val currentVideo by sm.currentVideo.collectAsState()
        val showAllQualities by sm.showAllQualities.collectAsState()
        val showSubtitles by sm.showSubtitles.collectAsState()
        val subtitleStatuses by sm.subtitleStatuses.collectAsState()

        EpisodeOptionsDialog(
            useExternalDownloader = useExternalDownloader,
            episodeTitle = episodeTitle,
            episode = episode,
            anime = anime,
            showAllQualities = showAllQualities,
            showSubtitles = showSubtitles,
            subtitleStatuses = subtitleStatuses,
            resultList = hosterState,
            expandedList = hosterExpandedList,
            currentVideo = currentVideo,
            selectedHosterVideoIndex = selectedHosterVideoIndex,
            onShowAllQualities = sm::onShowAllQualities,
            onShowSubtitles = sm::onShowSubtitles,
            onDownloadSubtitle = sm::downloadSubtitle,
            onDownloadAllSubtitles = sm::downloadAllSubtitles,
            onClickHoster = sm::onClickHoster,
            onClickVideo = sm::onClickVideo,
            getHosterList = sm::getHosterList,
        )
    }

    companion object {
        var onDismissDialog: () -> Unit = {}
    }
}

class EpisodeOptionsDialogScreenModel(
    episodeId: Long,
    private val animeId: Long,
    sourceId: Long,
) : ScreenModel {
    private val sourceManager: SourceManager = Injekt.get()

    private val _hosterState = MutableStateFlow<Result<List<HosterState>>?>(null)
    val hosterState = _hosterState.asStateFlow()
    private val _hosterExpandedList = MutableStateFlow<List<Boolean>>(emptyList())
    val hosterExpandedList = _hosterExpandedList.asStateFlow()
    private val _selectedHosterVideoIndex = MutableStateFlow(Pair(-1, -1))
    val selectedHosterVideoIndex = _selectedHosterVideoIndex.asStateFlow()
    private val _currentVideo = MutableStateFlow<Video?>(null)
    val currentVideo = _currentVideo.asStateFlow()

    private val _episode = MutableStateFlow<Episode?>(null)
    val episode = _episode.asStateFlow()
    private val _anime = MutableStateFlow<Anime?>(null)
    val anime = _anime.asStateFlow()

    @Suppress("ktlint:standard:backing-property-naming")
    private val _hosterList = MutableStateFlow<List<Hoster>>(emptyList())

    @Suppress("ktlint:standard:backing-property-naming")
    private val _source = MutableStateFlow<AnimeSource?>(null)

    private val _showAllQualities = MutableStateFlow(false)
    val showAllQualities = _showAllQualities.asStateFlow()

    private val _showSubtitles = MutableStateFlow(false)
    val showSubtitles = _showSubtitles.asStateFlow()

    private val _subtitleStatuses = MutableStateFlow<Map<String, SubtitleDownloadStatus>>(emptyMap())
    val subtitleStatuses = _subtitleStatuses.asStateFlow()

    init {
        val hasFoundPreferredVideo = AtomicBoolean(false)

        screenModelScope.launchIO {
            val episode = Injekt.get<GetEpisode>().await(episodeId)!!
            val anime = Injekt.get<GetAnime>().await(animeId)!!
            val source = sourceManager.getOrStub(sourceId)

            _episode.update { _ -> episode }
            _anime.update { _ -> anime }
            _source.update { _ -> source }

            val hosterListResult = withIOContext {
                try {
                    Result.success(EpisodeLoader.getHosters(episode, anime, source))
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }

            if (hosterListResult.isFailure) {
                _hosterState.update { _ -> Result.failure(hosterListResult.exceptionOrNull()!!) }
                return@launchIO
            }

            val hosterList = hosterListResult.getOrThrow()
            _hosterList.update { _ -> hosterList }
            _hosterExpandedList.update { _ ->
                List(hosterList.size) { true }
            }

            val initialHosterState = hosterList.map { hoster ->
                if (hoster.videoList == null) {
                    HosterState.Loading(hoster.hosterName)
                } else {
                    val videoList = hoster.videoList!!
                    HosterState.Ready(
                        hoster.hosterName,
                        videoList,
                        List(videoList.size) { Video.State.LOAD_VIDEO },
                    )
                }
            }

            _hosterState.update { _ -> Result.success(initialHosterState) }

            try {
                val defaultSelector = eu.kanade.tachiyomi.ui.player.utils.DefaultStreamPreferenceStore(playerPreferences).getEffectiveSelector(animeId)

                hosterList.mapIndexed { hosterIdx, hoster ->
                    async {
                        val hosterState = EpisodeLoader.loadHosterVideos(source, hoster)

                        _hosterState.updateAt(hosterIdx, hosterState)

                        if (defaultSelector.isBlank() && hosterState is HosterState.Ready) {
                            val prefIndex = hosterState.videoList.indexOfFirst { it.preferred }
                            if (prefIndex != -1) {
                                if (hasFoundPreferredVideo.compareAndSet(false, true)) {
                                    val success =
                                        loadVideo(source, hosterState.videoList[prefIndex], hosterIdx, prefIndex)
                                    if (!success) {
                                        hasFoundPreferredVideo.set(false)
                                    }
                                }
                            }
                        }
                    }
                }.awaitAll()

                if (!hasFoundPreferredVideo.get() && defaultSelector.isNotBlank()) {
                    val states = hosterState.value?.getOrNull().orEmpty()
                    val strictRanked = eu.kanade.tachiyomi.ui.player.utils.DefaultStreamSelector.findRankedInHosters(defaultSelector, states)
                    val ranked = strictRanked + eu.kanade.tachiyomi.ui.player.utils.DefaultStreamSelector.findRankedInHostersRelaxed(defaultSelector, states)
                        .filter { it !in strictRanked }

                    for ((hIdx, vIdx) in ranked.distinct()) {
                        val ready = states.getOrNull(hIdx) as? HosterState.Ready ?: continue
                        val video = ready.videoList.getOrNull(vIdx) ?: continue
                        if (hasFoundPreferredVideo.compareAndSet(false, true)) {
                            val success = loadVideo(source, video, hIdx, vIdx)
                            if (success) break
                            hasFoundPreferredVideo.set(false)
                        }
                    }
                }

                if (hasFoundPreferredVideo.compareAndSet(false, true)) {
                    val hosterStateList = hosterState.value!!.getOrThrow()
                    val (hosterIdx, videoIdx) = HosterLoader.selectBestVideo(hosterStateList)
                    if (hosterIdx == -1) {
                        _hosterState.update { _ ->
                            Result.failure(NoSuchElementException("No available videos"))
                        }
                        return@launchIO
                    }

                    val video = (hosterStateList[hosterIdx] as HosterState.Ready).videoList[videoIdx]

                    loadVideo(source, video, hosterIdx, videoIdx)
                }
            } catch (e: CancellationException) {
                _hosterState.update { _ ->
                    Result.success(hosterList.map { HosterState.Idle(it.hosterName) })
                }

                throw e
            }
        }
    }

    private suspend fun loadVideo(source: AnimeSource, video: Video, hosterIndex: Int, videoIndex: Int): Boolean {
        val selectedHosterState = (_hosterState.value!!.getOrThrow()[hosterIndex] as? HosterState.Ready) ?: return false

        val oldSelectedIndex = _selectedHosterVideoIndex.value
        _selectedHosterVideoIndex.update { _ -> Pair(hosterIndex, videoIndex) }

        _hosterState.updateAt(
            hosterIndex,
            selectedHosterState.getChangedAt(videoIndex, video, Video.State.LOAD_VIDEO),
        )

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

                val hosterStateList = hosterState.value?.getOrNull() ?: return false

                val (newHosterIdx, newVideoIdx) = HosterLoader.selectBestVideo(hosterStateList)
                if (newHosterIdx == -1) {
                    _hosterState.update { _ ->
                        Result.failure(NoSuchElementException("No available videos"))
                    }
                    return false
                }

                val newVideo = (hosterStateList[newHosterIdx] as HosterState.Ready).videoList[newVideoIdx]

                return loadVideo(source, newVideo, newHosterIdx, newVideoIdx)
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

        return true
    }

    private fun <T> MutableStateFlow<Result<List<T>>?>.updateAt(index: Int, newValue: T) {
        this.update { values ->
            values?.getOrNull()?.let {
                Result.success(
                    it.toMutableList().apply {
                        this[index] = newValue
                    },
                )
            } ?: values
        }
    }

    fun onShowAllQualities(value: Boolean) {
        _showAllQualities.update { _ -> value }
    }

    fun onShowSubtitles(value: Boolean) {
        _showSubtitles.update { _ -> value }
    }

    fun downloadSubtitle(context: Context, track: Track) {
        val anime = _anime.value ?: return
        val episode = _episode.value ?: return
        val video = _currentVideo.value

        _subtitleStatuses.update { it + (track.url to SubtitleDownloadStatus.DOWNLOADING) }

        screenModelScope.launchIO {
            val result = SubtitleDownloader.downloadSubtitleTrack(anime, episode, _source.value, track, video)
            if (result.isSuccess) {
                _subtitleStatuses.update { it + (track.url to SubtitleDownloadStatus.SUCCESS) }
                launchUI {
                    context.toast(context.stringResourceContext(MR.strings.subtitles_downloaded, track.lang.ifBlank { "sub" }))
                }
            } else {
                _subtitleStatuses.update { it + (track.url to SubtitleDownloadStatus.ERROR) }
                launchUI {
                    context.toast(context.stringResourceContext(MR.strings.subtitles_download_failed, track.lang.ifBlank { "sub" }))
                }
            }
        }
    }

    fun downloadAllSubtitles(context: Context) {
        val anime = _anime.value ?: return
        val episode = _episode.value ?: return
        val video = _currentVideo.value ?: return
        val tracks = video.subtitleTracks
        if (tracks.isEmpty()) return

        tracks.forEach { track ->
            _subtitleStatuses.update { it + (track.url to SubtitleDownloadStatus.DOWNLOADING) }
        }

        screenModelScope.launchIO {
            var successCount = 0
            tracks.forEach { track ->
                val result = SubtitleDownloader.downloadSubtitleTrack(anime, episode, _source.value, track, video)
                if (result.isSuccess) {
                    successCount++
                    _subtitleStatuses.update { it + (track.url to SubtitleDownloadStatus.SUCCESS) }
                } else {
                    _subtitleStatuses.update { it + (track.url to SubtitleDownloadStatus.ERROR) }
                }
            }
            launchUI {
                if (successCount > 0) {
                    context.toast(context.stringResourceContext(MR.strings.subtitles_downloaded, "$successCount tracks"))
                } else {
                    context.toast(context.stringResourceContext(MR.strings.subtitles_download_failed, "All"))
                }
            }
        }
    }

    fun onClickHoster(hosterIndex: Int) {
        val hosterState = hosterState.value?.getOrNull()?.getOrNull(hosterIndex) ?: return

        when (hosterState) {
            is HosterState.Ready -> {
                _hosterExpandedList.update { values ->
                    values.toMutableList().apply {
                        this[hosterIndex] = !hosterExpandedList.value[hosterIndex]
                    }
                }
            }
            is HosterState.Error, is HosterState.Idle -> {
                val hosterName = hosterState.name
                _hosterState.updateAt(hosterIndex, HosterState.Loading(hosterName))

                screenModelScope.launchIO {
                    val newHosterState = EpisodeLoader.loadHosterVideos(
                        _source.value!!,
                        _hosterList.value[hosterIndex],
                    )
                    _hosterState.updateAt(hosterIndex, newHosterState)
                }
            }
            is HosterState.Loading -> {}
        }
    }

    fun onClickVideo(hosterIndex: Int, videoIndex: Int) {
        val video = (_hosterState.value?.getOrNull()?.getOrNull(hosterIndex) as? HosterState.Ready)
            ?.videoList
            ?.getOrNull(videoIndex)
            ?: return

        screenModelScope.launchIO {
            val success = loadVideo(_source.value!!, video, hosterIndex, videoIndex)
            if (success) {
                _showAllQualities.update { _ -> false }
                _showSubtitles.update { _ -> false }
                _subtitleStatuses.update { emptyMap() }
                val hoster = _hosterList.value.getOrNull(hosterIndex)
                if (hoster != null) {
                    val store = eu.kanade.tachiyomi.ui.player.utils.DefaultStreamPreferenceStore(playerPreferences)
                    val currentComposite = store.getEffectiveSelector(animeId)
                    val newComposite = eu.kanade.tachiyomi.ui.player.utils.DefaultStreamSelector.updateCompositeSelector(
                        currentComposite,
                        hoster.hosterName,
                        eu.kanade.tachiyomi.ui.player.utils.DefaultStreamSelector.selectorFor(video, hoster.hosterName),
                    )
                    store.setSelector(animeId, newComposite)
                }
            }
        }
    }

    fun getHosterList(): List<Hoster>? {
        val hosterStateList = hosterState.value?.getOrNull() ?: return null
        return _hosterList.value.mapIndexed { index, h ->
            if (hosterStateList[index] is HosterState.Ready) {
                Hoster(
                    hosterName = h.hosterName,
                    hosterUrl = h.hosterUrl,
                    videoList = (hosterStateList[index] as HosterState.Ready).videoList,
                )
            } else {
                Hoster(
                    hosterName = h.hosterName,
                    hosterUrl = h.hosterUrl,
                    videoList = h.videoList,
                )
            }
        }
    }
}

@Composable
fun EpisodeOptionsDialog(
    useExternalDownloader: Boolean,
    episodeTitle: String,
    episode: Episode?,
    anime: Anime?,
    showAllQualities: Boolean,
    showSubtitles: Boolean,
    subtitleStatuses: Map<String, SubtitleDownloadStatus>,
    resultList: Result<List<HosterState>>? = null,
    expandedList: List<Boolean>,
    currentVideo: Video?,
    selectedHosterVideoIndex: Pair<Int, Int>,
    onShowAllQualities: (Boolean) -> Unit,
    onShowSubtitles: (Boolean) -> Unit,
    onDownloadSubtitle: (Context, Track) -> Unit,
    onDownloadAllSubtitles: (Context) -> Unit,
    onClickHoster: (Int) -> Unit,
    onClickVideo: (Int, Int) -> Unit,
    getHosterList: () -> List<Hoster>?,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .animateContentSize()
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(vertical = TabbedDialogPaddings.Vertical)
            .windowInsetsPadding(WindowInsets.systemBars),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    ) {
        Text(
            text = episodeTitle,
            modifier = Modifier.padding(horizontal = TabbedDialogPaddings.Horizontal),
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            style = MaterialTheme.typography.titleSmall,
        )

        Text(
            text = stringResource(MR.strings.choose_video_quality),
            modifier = Modifier.padding(horizontal = TabbedDialogPaddings.Horizontal),
            fontStyle = FontStyle.Italic,
            style = MaterialTheme.typography.bodyMedium,
        )

        val onError: () -> Unit = {
            logcat(LogPriority.ERROR) { "Error getting links" }
            scope.launchUI { context.toast("No available videos") }
            EpisodeOptionsDialogScreen.onDismissDialog()
        }
        if (resultList?.isFailure == true) {
            onError()
        }

        if (resultList == null || episode == null || anime == null || currentVideo == null) {
            LoadingScreen()
        } else {
            val hosterStateList = resultList.getOrNull()
            if (!hosterStateList.isNullOrEmpty()) {
                VideoList(
                    useExternalDownloader = useExternalDownloader,
                    episode = episode,
                    anime = anime,
                    showAllQualities = showAllQualities,
                    showSubtitles = showSubtitles,
                    subtitleStatuses = subtitleStatuses,
                    hosterStateList = hosterStateList,
                    expandedList = expandedList,
                    currentVideo = currentVideo,
                    selectedHosterVideoIndex = selectedHosterVideoIndex,
                    onShowAllQualities = onShowAllQualities,
                    onShowSubtitles = onShowSubtitles,
                    onDownloadSubtitle = onDownloadSubtitle,
                    onDownloadAllSubtitles = onDownloadAllSubtitles,
                    onClickHoster = onClickHoster,
                    onClickVideo = onClickVideo,
                    getHosterList = getHosterList,
                )
            } else {
                onError()
            }
        }
    }
}

@Composable
private fun VideoList(
    useExternalDownloader: Boolean,
    episode: Episode,
    anime: Anime,
    showAllQualities: Boolean,
    showSubtitles: Boolean,
    subtitleStatuses: Map<String, SubtitleDownloadStatus>,
    hosterStateList: List<HosterState>,
    expandedList: List<Boolean>,
    currentVideo: Video,
    selectedHosterVideoIndex: Pair<Int, Int>,
    onShowAllQualities: (Boolean) -> Unit,
    onShowSubtitles: (Boolean) -> Unit,
    onDownloadSubtitle: (Context, Track) -> Unit,
    onDownloadAllSubtitles: (Context) -> Unit,
    onClickHoster: (Int) -> Unit,
    onClickVideo: (Int, Int) -> Unit,
    getHosterList: () -> List<Hoster>?,
) {
    val downloadManager = Injekt.get<DownloadManager>()
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val copiedString = stringResource(MR.strings.copied_video_link_to_clipboard)

    AnimatedVisibility(
        visible = !showAllQualities && !showSubtitles,
        enter = slideInHorizontally(),
        exit = slideOutHorizontally(),
    ) {
        Column {
            if (currentVideo.videoUrl.isNotEmpty() && !showAllQualities && !showSubtitles) {
                ClickableRow(
                    text = currentVideo.videoTitle,
                    icon = null,
                    onClick = { onShowAllQualities(true) },
                    showDropdownArrow = true,
                )

                if (currentVideo.subtitleTracks.isNotEmpty()) {
                    ClickableRow(
                        text = stringResource(MR.strings.action_subtitles_only),
                        icon = Icons.Outlined.Subtitles,
                        onClick = { onShowSubtitles(true) },
                        showDropdownArrow = true,
                    )
                }

                val downloadEpisode: (Boolean) -> Unit = {
                    downloadManager.downloadEpisodes(
                        anime,
                        listOf(episode),
                        true,
                        it,
                        currentVideo,
                    )
                }

                QualityOptions(
                    onDownloadClicked = { downloadEpisode(false) },
                    onExtDownloadClicked = { downloadEpisode(true) },
                    onCopyClicked = {
                        clipboardManager.setText(AnnotatedString(currentVideo.videoUrl))
                        scope.launch { context.toast(copiedString) }
                    },
                    onExtPlayerClicked = {
                        scope.launch {
                            MainActivity.startPlayerActivity(
                                context,
                                anime.id,
                                episode.id,
                                true,
                                currentVideo,
                            )
                        }
                    },
                    onIntPlayerClicked = {
                        scope.launch {
                            MainActivity.startPlayerActivity(
                                context,
                                anime.id,
                                episode.id,
                                false,
                                currentVideo,
                                selectedHosterVideoIndex.first,
                                selectedHosterVideoIndex.second,
                                getHosterList(),
                            )
                        }
                    },
                    // start tail cast
                    onCastClicked = {
                        scope.launch {
                            if (playerPreferences.enableCast().get()) {
                                sendEpisodesToCast(
                                    context,
                                    anime.title,
                                    episode.name,
                                    episode.lastSecondSeen,
                                    anime.thumbnailUrl.orEmpty(),
                                    currentVideo.videoUrl,
                                )
                            } else {
                                context.toast("Cast is disabled")
                            }
                        }
                    },
                    // end tail cast
                )
            }
        }
    }

    AnimatedVisibility(
        visible = showAllQualities,
        enter = slideInHorizontally(initialOffsetX = { it / 2 }),
        exit = slideOutHorizontally(targetOffsetX = { it / 2 }),
    ) {
        if (showAllQualities) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = TabbedDialogPaddings.Horizontal)
                    .heightIn(max = 600.dp),
            ) {
                if (
                    hosterStateList.size == 1 &&
                    hosterStateList.first().name == Hoster.NO_HOSTER_LIST &&
                    hosterStateList.first() is HosterState.Ready
                ) {
                    QualitySheetVideoContent(
                        videoList = (hosterStateList.first() as HosterState.Ready).videoList,
                        videoState = (hosterStateList.first() as HosterState.Ready).videoState,
                        selectedVideoIndex = selectedHosterVideoIndex.second,
                        onClickVideo = onClickVideo,
                    )
                } else {
                    QualitySheetHosterContent(
                        hosterState = hosterStateList,
                        expandedState = expandedList,
                        selectedVideoIndex = selectedHosterVideoIndex,
                        onClickHoster = onClickHoster,
                        onClickVideo = onClickVideo,
                        displayHosters = Pair(false, false),
                    )
                }
            }
        }
    }

    AnimatedVisibility(
        visible = showSubtitles,
        enter = slideInHorizontally(initialOffsetX = { it / 2 }),
        exit = slideOutHorizontally(targetOffsetX = { it / 2 }),
    ) {
        if (showSubtitles) {
            SubtitleListContent(
                subtitleTracks = currentVideo.subtitleTracks,
                subtitleStatuses = subtitleStatuses,
                onBack = { onShowSubtitles(false) },
                onDownloadTrack = { onDownloadSubtitle(context, it) },
                onDownloadAll = { onDownloadAllSubtitles(context) },
            )
        }
    }
}

@Composable
private fun SubtitleListContent(
    subtitleTracks: List<Track>,
    subtitleStatuses: Map<String, SubtitleDownloadStatus>,
    onBack: () -> Unit,
    onDownloadTrack: (Track) -> Unit,
    onDownloadAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        ClickableRow(
            text = stringResource(MR.strings.action_subtitles_only),
            icon = Icons.Outlined.ArrowBack,
            onClick = onBack,
        )

        if (subtitleTracks.size > 1) {
            ClickableRow(
                text = stringResource(MR.strings.action_download_all_subtitles),
                icon = Icons.Outlined.Download,
                onClick = onDownloadAll,
            )
        }

        subtitleTracks.forEachIndexed { index, track ->
            val status = subtitleStatuses[track.url] ?: SubtitleDownloadStatus.IDLE
            val langLabel = track.lang.ifBlank { "Track ${index + 1}" }
            val ext = SubtitleDownloader.getCleanExtension(track.url)
            val displayText = "$langLabel (.$ext)"

            Row(
                modifier = Modifier
                    .padding(horizontal = TabbedDialogPaddings.Horizontal)
                    .clickable(
                        role = Role.Button,
                        enabled = status != SubtitleDownloadStatus.DOWNLOADING,
                        onClick = { onDownloadTrack(track) },
                    )
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = MaterialTheme.padding.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Subtitles,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.width(MaterialTheme.padding.small))
                    Text(
                        text = displayText,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Box(
                    modifier = Modifier.size(IconButtonTokens.StateLayerSize),
                    contentAlignment = Alignment.Center,
                ) {
                    when (status) {
                        SubtitleDownloadStatus.DOWNLOADING -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                        SubtitleDownloadStatus.SUCCESS -> {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        SubtitleDownloadStatus.ERROR -> {
                            Icon(
                                imageVector = Icons.Outlined.ErrorOutline,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                        SubtitleDownloadStatus.IDLE -> {
                            Icon(
                                imageVector = Icons.Outlined.Download,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QualityOptions(
    onDownloadClicked: () -> Unit = {},
    onExtDownloadClicked: () -> Unit = {},
    onCopyClicked: () -> Unit = {},
    onExtPlayerClicked: () -> Unit = {},
    onIntPlayerClicked: () -> Unit = {},
    onCastClicked: () -> Unit = {},
) {
    val closeMenu = { EpisodeOptionsDialogScreen.onDismissDialog() }

    Column {
        ClickableRow(
            text = stringResource(MR.strings.copy),
            icon = Icons.Outlined.ContentCopy,
            onClick = { onCopyClicked() },
        )

        ClickableRow(
            text = stringResource(MR.strings.action_start_download_internally),
            icon = Icons.Outlined.Download,
            onClick = {
                onDownloadClicked()
                closeMenu()
            },
        )

        ClickableRow(
            text = stringResource(MR.strings.action_start_download_externally),
            icon = Icons.Outlined.SystemUpdateAlt,
            onClick = {
                onExtDownloadClicked()
                closeMenu()
            },
        )

        ClickableRow(
            text = stringResource(MR.strings.action_play_externally),
            icon = Icons.Outlined.OpenInNew,
            onClick = {
                onExtPlayerClicked()
                closeMenu()
            },
        )

        ClickableRow(
            text = stringResource(MR.strings.action_play_internally),
            icon = Icons.Outlined.Input,
            onClick = {
                onIntPlayerClicked()
                closeMenu()
            },

        )
        ClickableRow(
            text = stringResource(AMR.strings.action_cast), // Texto para la nueva opción
            icon = Icons.Outlined.Cast, // Icono para la nueva opción
            onClick = {
                onCastClicked()
                closeMenu()
            },
        )
    }
}

@Composable
private fun ClickableRow(
    text: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    showDropdownArrow: Boolean = false,
) {
    Row(
        modifier = Modifier
            .padding(horizontal = TabbedDialogPaddings.Horizontal)
            .clickable(role = Role.DropdownList, onClick = onClick)
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        var textPadding = MaterialTheme.padding.medium

        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(Modifier.width(MaterialTheme.padding.small))

            textPadding = MaterialTheme.padding.small
        }
        Text(
            text = text,
            modifier = Modifier.padding(vertical = textPadding),
            style = MaterialTheme.typography.bodyMedium,
        )

        if (showDropdownArrow) {
            Icon(
                imageVector = Icons.Outlined.NavigateNext,
                contentDescription = null,
                modifier = Modifier,
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

// Start tail cast

private fun sendEpisodesToCast(
    context: Context,
    title: String,
    episode: String,
    lastSecondSeen: Long,
    image: String,
    videoUrl: String,
) {
    val castSession = CastContext.getSharedInstance(context).sessionManager.currentCastSession
    val remoteMediaClient = castSession?.remoteMediaClient
    if (castSession == null || !castSession.isConnected) {
        Toast.makeText(context, "Cast is not connected", Toast.LENGTH_SHORT).show()
        return
    }
    if (remoteMediaClient != null) {
        val mediaMetadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, title)
            putString(MediaMetadata.KEY_SUBTITLE, episode)
            addImage(WebImage(Uri.parse(image)))
        }
        val castContentType = when {
            videoUrl.contains(".m3u8", ignoreCase = true) -> "application/x-mpegURL"
            videoUrl.contains(".mpd", ignoreCase = true) -> "application/dash+xml"
            videoUrl.contains(".mkv", ignoreCase = true) -> "video/x-matroska"
            else -> "video/mp4"
        }
        val mediaInfo = MediaInfo.Builder(videoUrl)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(castContentType)
            .setMetadata(mediaMetadata)
            .build()
        val mediaQueueItem = MediaQueueItem.Builder(mediaInfo)
            .setAutoplay(true)
            .setStartTime(lastSecondSeen.toDouble() / 1000)
            .build()
        val mediaStatus = remoteMediaClient.mediaStatus
        if (mediaStatus != null && mediaStatus.playerState == MediaStatus.PLAYER_STATE_PLAYING) {
            // Si hay un video reproduciéndose, agregar el nuevo video a la cola
            remoteMediaClient.queueAppendItem(mediaQueueItem, null)
        } else {
            // Si no hay un video reproduciéndose, cargar el video directamente
            val mediaLoadRequestData = MediaLoadRequestData.Builder()
                .setMediaInfo(mediaInfo)
                .setCurrentTime(lastSecondSeen)
                .build()
            remoteMediaClient.load(mediaLoadRequestData)
        }
    } else {
        Toast.makeText(context, "remoteMediaClient is null", Toast.LENGTH_SHORT).show()
    }
}

// End tail cast0
