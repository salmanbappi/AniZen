package eu.kanade.tachiyomi.ui.download

import android.view.View
import androidx.recyclerview.widget.ItemTouchHelper
import eu.davidea.viewholders.FlexibleViewHolder
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.databinding.DownloadItemBinding
import eu.kanade.tachiyomi.util.view.popupMenu
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

/**
 * Class used to hold the data of a download.
 * All the elements from the layout file "download_item" are available in this class.
 *
 * @param view the inflated view for this holder.
 * @constructor creates a new download holder.
 */
class DownloadHolder(private val view: View, val adapter: DownloadAdapter) :
    FlexibleViewHolder(view, adapter) {

    private val binding = DownloadItemBinding.bind(view)

    init {
        setDragHandleView(binding.reorder)
        binding.menu.setOnClickListener { it.post { showPopupMenu(it) } }
    }

    private lateinit var download: Download

    /**
     * Binds this holder with the given category.
     *
     * @param download the download to bind.
     */
    fun bind(download: Download) {
        this.download = download
        // Update the chapter name.
        binding.chapterTitle.text = download.episode.name

        // Update the manga title
        binding.mangaFullTitle.text = download.anime.title

        // Update the progress bar and the number of downloaded pages
        val video = download.video
        if (video == null) {
            binding.downloadProgress.progress = 0
            binding.downloadProgress.max = 1
            binding.downloadProgressText.text = ""
        } else {
            binding.downloadProgress.max = 100
            notifyProgress()
            notifyDownloadedPages()
        }
    }

    /**
     * Updates the progress bar of the download.
     */
    fun notifyProgress() {
        if (binding.downloadProgress.max == 1) {
            binding.downloadProgress.max = 100
        }
        if (download.progress == 0) {
            binding.downloadProgress.isIndeterminate = true
        } else {
            binding.downloadProgress.isIndeterminate = false
            binding.downloadProgress.setProgressCompat(download.progress, true)
        }
    }

    /**
     * Updates the text field of the number of downloaded pages.
     */
    fun notifyDownloadedPages() {
        val speed = download.speed
        val eta = download.eta
        val sizeInfo = download.downloadedSize
        val engine = download.engineType ?: "Normal"
        val isHls = engine == "HLS"
        
        // 1DM+ Core Status Logic
        val statusText = buildString {
            // Line 1: Progress & Size
            if (sizeInfo.isNotEmpty()) {
                append(sizeInfo).append(" (").append(download.progress).append("%)")
            } else if (download.progress > 0) {
                append(download.progress).append("%")
            } else {
                append("0% • Starting...")
            }
            
            // Line 2: Network Performance
            if (speed.isNotEmpty() || eta.isNotEmpty()) {
                append("\n")
                if (speed.isNotEmpty()) append("Speed: ").append(speed)
                if (eta.isNotEmpty()) {
                    if (speed.isNotEmpty()) append(" • ")
                    append("ETA: ").append(eta)
                }
            }
            
            // Line 3: Connection Intelligence
            append("\n")
            append("Threads: ").append(download.activeThreads).append(" Active")
            if (download.totalSegments > 0) {
                append(" • ").append(if (isHls) "Segments: " else "Parts: ")
                append(download.downloadedSegments).append("/").append(download.totalSegments)
            }
            
            // Line 4: Engine Identity
            append("\nEngine: ").append(if (isHls) "HLS (Sequential Merge)" else "Normal (Direct Multi-threaded)")
        }
        
        binding.downloadProgressText.text = statusText

        // Update Engine Icon & Visibility
        binding.engineIcon.visibility = View.VISIBLE
        when (engine) {
            "Normal" -> {
                binding.engineIcon.setImageResource(R.drawable.ic_download_item_24dp)
            }
            "HLS" -> {
                binding.engineIcon.setImageResource(R.drawable.ic_video_chapter_20dp)
            }
            "Torrent" -> {
                binding.engineIcon.setImageResource(R.drawable.ic_sync_24dp)
            }
            else -> {
                binding.engineIcon.visibility = View.GONE
            }
        }
    }

    override fun onItemReleased(position: Int) {
        super.onItemReleased(position)
        adapter.downloadItemListener.onItemReleased(position)
        binding.container.isDragged = false
    }

    override fun onActionStateChanged(position: Int, actionState: Int) {
        super.onActionStateChanged(position, actionState)
        if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
            binding.container.isDragged = true
        }
    }

    private fun showPopupMenu(view: View) {
        view.popupMenu(
            menuRes = R.menu.download_single,
            initMenu = {
                findItem(R.id.move_to_top).isVisible = bindingAdapterPosition > 1
                findItem(R.id.move_to_bottom).isVisible =
                    bindingAdapterPosition != adapter.itemCount - 1
            },
            onMenuItemClick = {
                adapter.downloadItemListener.onMenuItemClick(bindingAdapterPosition, this)
            },
        )
    }
}
