package eu.kanade.tachiyomi.ui.anime.merged

import android.view.View
import coil3.load
import eu.davidea.viewholders.FlexibleViewHolder
import eu.kanade.tachiyomi.databinding.EditMergedAnimeSettingsItemBinding
import tachiyomi.domain.anime.model.MergedAnimeReference
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class EditMergedAnimeHolder(view: View, val adapter: EditMergedAnimeAdapter) : FlexibleViewHolder(view, adapter) {

    lateinit var reference: MergedAnimeReference
    var binding = EditMergedAnimeSettingsItemBinding.bind(view)

    init {
        setDragHandleView(binding.reorder)
        binding.cover.setOnClickListener {
            adapter.editMergedAnimeItemListener.onOpenEntryClick(bindingAdapterPosition)
        }
        binding.remove.setOnClickListener {
            adapter.editMergedAnimeItemListener.onDeleteClick(bindingAdapterPosition)
        }
        binding.getEpisodeUpdates.setOnClickListener {
            adapter.editMergedAnimeItemListener.onToggleEpisodeUpdatesClicked(bindingAdapterPosition)
        }
        binding.download.setOnClickListener {
            adapter.editMergedAnimeItemListener.onToggleEpisodeDownloadsClicked(bindingAdapterPosition)
        }
        setHandelAlpha(adapter.isPriorityOrder)
    }

    override fun onItemReleased(position: Int) {
        super.onItemReleased(position)
        adapter.editMergedAnimeItemListener.onItemReleased(position)
    }

    fun bind(item: EditMergedAnimeItem) {
        reference = item.mergedAnimeReference
        item.mergedAnime?.let {
            binding.cover.load(it)
        }

        binding.title.text = Injekt.get<SourceManager>().getOrStub(item.mergedAnimeReference.animeSourceId).toString()
        binding.subtitle.text = item.mergedAnime?.title
        updateDownloadEpisodesIcon(item.mergedAnimeReference.downloadEpisodes)
        updateEpisodeUpdatesIcon(item.mergedAnimeReference.getEpisodeUpdates)
    }

    fun setHandelAlpha(isPriorityOrder: Boolean) {
        binding.reorder.alpha = when (isPriorityOrder) {
            true -> 1F
            false -> 0.5F
        }
    }

    fun updateDownloadEpisodesIcon(enabled: Boolean) {
        binding.download.alpha = if (enabled) 1F else 0.4F
    }

    fun updateEpisodeUpdatesIcon(enabled: Boolean) {
        binding.getEpisodeUpdates.alpha = if (enabled) 1F else 0.4F
    }
}
