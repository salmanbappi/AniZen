package eu.kanade.tachiyomi.ui.download

import android.view.MenuItem
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.databinding.DownloadListBinding
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.domain.download.service.DownloadPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class DownloadQueueScreenModel(
    private val downloadManager: DownloadManager = Injekt.get(),
    private val downloadPreferences: DownloadPreferences = Injekt.get(),
) : ScreenModel {

    private val _state = MutableStateFlow(emptyList<DownloadHeaderItem>())
    val state = _state.asStateFlow()

    val alwaysUseInternalDownloader = downloadPreferences.alwaysUseInternalDownloader().stateIn(screenModelScope)

    fun toggleAlwaysUseInternalDownloader() {
        downloadPreferences.alwaysUseInternalDownloader().set(!alwaysUseInternalDownloader.value)
    }

    lateinit var controllerBinding: DownloadListBinding

    /**
     * Adapter containing the active downloads.
     */
    var adapter: DownloadAdapter? = null

    /**
     * Maintains the queue of downloads.
     */
    private var downloadQueue: List<Download> = emptyList()

    init {
        screenModelScope.launch {
            downloadManager.queueState
                .map { downloads ->
                    val grouped = downloads.groupBy { it.anime.id }
                    grouped.map { (animeId, animeDownloads) ->
                        val anime = animeDownloads[0].anime
                        DownloadHeaderItem(anime, animeDownloads.size).apply {
                            animeDownloads.forEach { download ->
                                addSubItem(DownloadItem(download, this))
                            }
                        }
                    }
                }
                .update(_state)
        }
    }

    fun getDownloadStatusFlow() = downloadManager.statusFlow()

    fun getDownloadProgressFlow() = downloadManager.progressFlow()

    fun pauseDownloads() {
        downloadManager.pauseDownloads()
    }

    fun startDownloads() {
        downloadManager.startDownloads()
    }

    fun clearQueue() {
        downloadManager.clearQueue()
    }

    fun reorderQueue(downloads: List<Download>) {
        downloadManager.reorderQueue(downloads)
    }

    fun <T : Comparable<T>> reorderQueue(selector: (DownloadItem) -> T, reverse: Boolean) {
        val newQueue = state.value
            .flatMap { header ->
                header.subItems.map { it }
            }
            .sortedBy(selector)
            .run { if (reverse) reversed() else this }
            .map { it.download }
        reorderQueue(newQueue)
    }

    fun cancelDownload(download: Download) {
        downloadManager.cancelQueuedDownloads(listOf(download))
    }

    fun cancelDownloads(downloads: List<Download>) {
        downloadManager.cancelQueuedDownloads(downloads)
    }

    fun onMove(from: Int, to: Int) {
        adapter?.let { adapter ->
            val obj = adapter.getItem(from) as? DownloadItem ?: return
            val target = adapter.getItem(to) as? DownloadItem ?: return
            
            val list = downloadManager.queueState.value.toMutableList()
            val fromIdx = list.indexOf(obj.download)
            val toIdx = list.indexOf(target.download)
            if (fromIdx != -1 && toIdx != -1) {
                val item = list.removeAt(fromIdx)
                list.add(toIdx, item)
                reorderQueue(list)
            }
        }
    }

    fun onMenuItemClick(position: Int, item: MenuItem) {
        val download = (adapter?.getItem(position) as? DownloadItem)?.download ?: return
        when (item.itemId) {
            R.id.move_to_top -> {
                val list = downloadManager.queueState.value.toMutableList()
                list.remove(download)
                list.add(0, download)
                reorderQueue(list)
            }
            R.id.move_to_bottom -> {
                val list = downloadManager.queueState.value.toMutableList()
                list.remove(download)
                list.add(download)
                reorderQueue(list)
            }
            R.id.cancel_download -> {
                cancelDownload(download)
            }
        }
    }

    /**
     * Returns the holder of the download or null if it's not bound.
     */
    private fun getHolder(download: Download): DownloadHolder? {
        return controllerBinding.root.findViewHolderForItemId(download.episode.id) as? DownloadHolder
    }
}
