// ANZ -->
package eu.kanade.tachiyomi.ui.download

import android.content.Context
import android.content.Intent
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.player.PlayerActivity
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.anime.interactor.GetAllAnime
import tachiyomi.domain.anime.interactor.GetAnime
import tachiyomi.domain.anime.interactor.GetLibraryAnime
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.episode.interactor.GetEpisodesByAnimeId
import tachiyomi.domain.episode.model.Episode
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

data class CompletedDownloadItem(
    val anime: Anime,
    val episode: Episode,
    val sizeBytes: Long,
)

class DownloadQueueScreenModel(
    private val downloadManager: DownloadManager = Injekt.get(),
    private val downloadPreferences: DownloadPreferences = Injekt.get(),
    private val getLibraryAnime: GetLibraryAnime = Injekt.get(),
    private val getAllAnime: GetAllAnime = Injekt.get(),
    private val getEpisodesByAnimeId: GetEpisodesByAnimeId = Injekt.get(),
    private val getAnime: GetAnime = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val downloadProvider: DownloadProvider = Injekt.get(),
) : ScreenModel {

    private val _state = MutableStateFlow(emptyList<DownloadHeaderItem>())
    val state = _state.asStateFlow()

    private val _completedDownloads = MutableStateFlow<List<CompletedDownloadItem>>(emptyList())
    val completedDownloads: StateFlow<List<CompletedDownloadItem>> = _completedDownloads.asStateFlow()

    val alwaysUseInternalDownloader = downloadPreferences.alwaysUseInternalDownloader().stateIn(screenModelScope)

    fun toggleAlwaysUseInternalDownloader() {
        downloadPreferences.alwaysUseInternalDownloader().set(!alwaysUseInternalDownloader.value)
    }

    lateinit var controllerBinding: eu.kanade.tachiyomi.databinding.DownloadListBinding

    var adapter: DownloadAdapter? = null

    private val progressJobs = mutableMapOf<Download, kotlinx.coroutines.Job>()

    val listener = object : DownloadAdapter.DownloadItemListener {
        override fun onItemReleased(position: Int) {
            val adapter = adapter ?: return
            val downloads = adapter.headerItems.flatMap { header ->
                adapter.getSectionItems(header).map { item ->
                    (item as DownloadItem).download
                }
            }
            reorder(downloads)
        }

        override fun onMenuItemClick(position: Int, menuItem: android.view.MenuItem) {
            val adapter = adapter ?: return
            val item = adapter.getItem(position) ?: return
            if (item is DownloadItem) {
                val headerItem = item.header as? DownloadHeaderItem ?: return
                when (menuItem.itemId) {
                    R.id.move_to_top, R.id.move_to_bottom -> {
                        val newDownloads = mutableListOf<Download>()
                        adapter.headerItems.forEach { header ->
                            header as DownloadHeaderItem
                            if (header == headerItem) {
                                header.removeSubItem(item)
                                if (menuItem.itemId == R.id.move_to_top) {
                                    header.addSubItem(0, item)
                                } else {
                                    header.addSubItem(item)
                                }
                            }
                            newDownloads.addAll(header.subItems.map { it.download })
                        }
                        reorder(newDownloads)
                    }
                    R.id.move_to_top_series, R.id.move_to_bottom_series -> {
                        val seriesDownloads = mutableListOf<Download>()
                        val otherDownloads = mutableListOf<Download>()
                        adapter.headerItems.forEach { header ->
                            header as DownloadHeaderItem
                            val target = if (header == headerItem) seriesDownloads else otherDownloads
                            target.addAll(header.subItems.map { it.download })
                        }
                        if (seriesDownloads.isEmpty()) return
                        reorder(
                            if (menuItem.itemId == R.id.move_to_top_series) {
                                seriesDownloads + otherDownloads
                            } else {
                                otherDownloads + seriesDownloads
                            },
                        )
                    }
                    R.id.cancel_download -> cancel(listOf(item.download))
                    R.id.cancel_series -> cancel(headerItem.subItems.map { it.download })
                }
            }
        }
    }

    init {
        screenModelScope.launch {
            downloadManager.queueState
                .map { downloads ->
                    downloads
                        .groupBy { it.source }
                        .map { entry ->
                            DownloadHeaderItem(entry.key.id, entry.key.name, entry.value.size).apply {
                                addSubItems(0, entry.value.map { DownloadItem(it, this) })
                            }
                        }
                }
                .collectLatest { newList ->
                    _state.update { newList }
                    refreshCompletedDownloads()
                }
        }

        refreshCompletedDownloads()
    }

    fun refreshCompletedDownloads() {
        screenModelScope.launch(Dispatchers.IO) {
            // ANZ -->
            val allAnimeList = getAllAnime.await()
            val completedList = mutableListOf<CompletedDownloadItem>()

            for (anime in allAnimeList) {
                val episodes = getEpisodesByAnimeId.await(anime.id)
                val source = sourceManager.get(anime.source) ?: continue
                for (episode in episodes) {
                    if (downloadManager.isEpisodeDownloaded(
                            episode.name, episode.scanlator, anime.title, anime.source
                        )) {
                        val size = downloadProvider.getEpisodeFileSize(
                            episodeName = episode.name,
                            episodeUrl = episode.url,
                            episodeScanlator = episode.scanlator,
                            animeTitle = anime.title,
                            source = source,
                            episodeNumber = episode.episodeNumber,
                        ) ?: 0L
                        completedList.add(CompletedDownloadItem(anime, episode, size))
                    }
                }
            }

            _completedDownloads.value = completedList
            // ANZ <--
        }
    }

    fun playOffline(context: Context, item: CompletedDownloadItem) {
        val intent = PlayerActivity.newIntent(
            context = context,
            animeId = item.anime.id,
            episodeId = item.episode.id,
        )
        context.startActivity(intent)
    }

    fun saveToDevice(context: Context, item: CompletedDownloadItem) {
        screenModelScope.launch(Dispatchers.IO) {
            val source = sourceManager.get(item.anime.source)
            if (source == null) {
                withContext(Dispatchers.Main) {
                    context.toast("Source unavailable")
                }
                return@launch
            }
            val epDir = downloadProvider.findEpisodeDir(
                episodeName = item.episode.name,
                episodeScanlator = item.episode.scanlator,
                animeTitle = item.anime.title,
                source = source,
                episodeNumber = item.episode.episodeNumber,
            )
            // ANZ -->
            val fileToExport = epDir?.listFiles()?.firstOrNull { it.isFile } ?: epDir?.takeIf { it.isFile }
            // ANZ <--
            if (fileToExport == null || !fileToExport.exists()) {
                withContext(Dispatchers.Main) {
                    context.toast("Downloaded file not found")
                }
                return@launch
            }

            try {
                val downloadsFolder = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS,
                )
                // ANZ -->
                if (!downloadsFolder.exists()) {
                    downloadsFolder.mkdirs()
                }

                val epLabel = if (item.episode.episodeNumber > 0) {
                    val epNumStr = if (item.episode.episodeNumber % 1.0 == 0.0) {
                        item.episode.episodeNumber.toInt().toString()
                    } else {
                        item.episode.episodeNumber.toString()
                    }
                    "Episode $epNumStr"
                } else {
                    item.episode.name
                }

                val sanitizedAnimeTitle = item.anime.title.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
                val sanitizedEpLabel = epLabel.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
                val originalExt = fileToExport.name?.substringAfterLast('.', "mp4")?.takeIf { it.isNotBlank() } ?: "mp4"
                val outputFilename = "$sanitizedAnimeTitle - $sanitizedEpLabel.$originalExt"

                val destFile = java.io.File(downloadsFolder, outputFilename)
                fileToExport.openInputStream().use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                withContext(Dispatchers.Main) {
                    context.toast("Saved $outputFilename to Downloads")
                }
                // ANZ <--
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    context.toast("Failed to save file: ${e.message}")
                }
            }
        }
    }

    fun deleteCompleted(item: CompletedDownloadItem) {
        screenModelScope.launch(Dispatchers.IO) {
            val source = sourceManager.get(item.anime.source) ?: return@launch
            downloadManager.deleteEpisodes(listOf(item.episode), item.anime, source)
            refreshCompletedDownloads()
        }
    }

    override fun onDispose() {
        progressJobs.values.forEach { it.cancel() }
        progressJobs.clear()
        adapter = null
    }

    val isDownloaderRunning = downloadManager.isDownloaderRunning
        .stateIn(screenModelScope, SharingStarted.Eagerly, false)

    fun getDownloadStatusFlow() = downloadManager.statusFlow()
        .sample(1000)
    fun getDownloadProgressFlow() = downloadManager.progressFlow()
        .sample(1000)

    fun startDownloads() = downloadManager.startDownloads()
    fun pauseDownloads() = downloadManager.pauseDownloads()
    fun clearQueue() = downloadManager.clearQueue()
    fun reorder(downloads: List<Download>) = downloadManager.reorderQueue(downloads)
    fun cancel(downloads: List<Download>) = downloadManager.cancelQueuedDownloads(downloads)

    fun <R : Comparable<R>> reorderQueue(selector: (DownloadItem) -> R, reverse: Boolean = false) {
        val adapter = adapter ?: return
        val newDownloads = mutableListOf<Download>()
        adapter.headerItems.forEach { headerItem ->
            headerItem as DownloadHeaderItem
            headerItem.subItems = headerItem.subItems.sortedBy(selector).toMutableList().apply {
                if (reverse) reverse()
            }
            newDownloads.addAll(headerItem.subItems.map { it.download })
        }
        reorder(newDownloads)
    }

    fun onStatusChange(download: Download) {
        onUpdateProgress(download)
        onUpdateDownloadedPages(download)
    }

    private fun onUpdateProgress(download: Download) {
        getHolder(download)?.notifyProgress()
        getHolder(download)?.notifyDownloadedPages()
    }

    fun onUpdateDownloadedPages(download: Download) {
        getHolder(download)?.notifyDownloadedPages()
        getHolder(download)?.notifyProgress()
    }

    private fun getHolder(download: Download): DownloadHolder? {
        return if (::controllerBinding.isInitialized) {
            controllerBinding.root.findViewHolderForItemId(download.episode.id) as? DownloadHolder
        } else null
    }
}
// ANZ <--
