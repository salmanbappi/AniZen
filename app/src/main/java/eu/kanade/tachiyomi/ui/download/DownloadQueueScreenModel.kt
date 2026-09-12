package eu.kanade.tachiyomi.ui.download

import android.view.MenuItem
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.databinding.DownloadListBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
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
     * Map of jobs for active downloads.
     */
    private val progressJobs = mutableMapOf<Download, Job>()

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

        override fun onMenuItemClick(position: Int, menuItem: MenuItem) {
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
                .collectLatest { newList -> _state.update { newList } }
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
