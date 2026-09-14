package eu.kanade.tachiyomi.ui.anime.merged

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import eu.kanade.tachiyomi.databinding.EditMergedAnimeSettingsHeaderBinding
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.anime.model.MergedAnimeReference
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.sy.SYMR
import uy.kohesive.injekt.injectLazy

class EditMergedAnimeSettingsHeaderAdapter(
    private val state: EditMergedAnimeSettingsState,
    adapter: EditMergedAnimeAdapter,
) : RecyclerView.Adapter<EditMergedAnimeSettingsHeaderAdapter.HeaderViewHolder>() {

    private val sourceManager: SourceManager by injectLazy()

    private lateinit var binding: EditMergedAnimeSettingsHeaderBinding

    val editMergedAnimeItemSortingListener: SortingListener = adapter

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HeaderViewHolder {
        binding = EditMergedAnimeSettingsHeaderBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return HeaderViewHolder(binding.root)
    }

    override fun getItemCount(): Int = 1

    override fun onBindViewHolder(holder: HeaderViewHolder, position: Int) {
        holder.bind()
    }

    inner class HeaderViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
        fun bind() {
            val dedupeAdapter = ArrayAdapter(
                view.context,
                android.R.layout.simple_spinner_dropdown_item,
                listOfNotNull(
                    itemView.context.stringResource(SYMR.strings.dedupe_priority),
                    itemView.context.stringResource(SYMR.strings.dedupe_most_chapters),
                    itemView.context.stringResource(SYMR.strings.dedupe_highest_chapter),
                ),
            )
            binding.dedupeModeSpinner.adapter = dedupeAdapter
            state.mergeReference?.let {
                binding.dedupeModeSpinner.setSelection(
                    when (it.episodeSortMode) {
                        MergedAnimeReference.EPISODE_SORT_PRIORITY -> 0
                        MergedAnimeReference.EPISODE_SORT_MOST_EPISODES -> 1
                        MergedAnimeReference.EPISODE_SORT_HIGHEST_EPISODE_NUMBER -> 2
                        else -> 0
                    },
                )
            }

            binding.dedupeModeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    state.mergeReference = state.mergeReference?.copy(
                        episodeSortMode = when (position) {
                            0 -> MergedAnimeReference.EPISODE_SORT_PRIORITY
                            1 -> MergedAnimeReference.EPISODE_SORT_MOST_EPISODES
                            2 -> MergedAnimeReference.EPISODE_SORT_HIGHEST_EPISODE_NUMBER
                            else -> MergedAnimeReference.EPISODE_SORT_NONE
                        },
                    )
                    editMergedAnimeItemSortingListener.onSetPrioritySort(canMove())

                    if (view != null) (view as TextView).setBackgroundColor(Color.TRANSPARENT)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {
                    state.mergeReference = state.mergeReference?.copy(
                        episodeSortMode = MergedAnimeReference.EPISODE_SORT_NONE,
                    )
                }
            }

            val mergedAnimes = state.mergedAnimes

            val animeInfoAdapter = ArrayAdapter(
                view.context,
                android.R.layout.simple_spinner_dropdown_item,
                mergedAnimes.map {
                    sourceManager.getOrStub(it.second.animeSourceId).toString() + " " + it.first?.title
                },
            )
            binding.animeInfoSpinner.adapter = animeInfoAdapter

            mergedAnimes.indexOfFirst { it.second.isInfoAnime }.let {
                if (it != -1) {
                    binding.animeInfoSpinner.setSelection(it)
                } else {
                    binding.animeInfoSpinner.setSelection(0)
                }
            }

            binding.animeInfoSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    state.mergedAnimes = state.mergedAnimes.map { (anime, reference) ->
                        anime to reference.copy(
                            isInfoAnime = reference.id == mergedAnimes.getOrNull(position)?.second?.id,
                        )
                    }

                    if (view != null) (view as TextView).setBackgroundColor(Color.TRANSPARENT)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {
                    mergedAnimes.find { it.second.isInfoAnime }?.second?.let { newInfoAnime ->
                        state.mergedAnimes = state.mergedAnimes.map { (anime, reference) ->
                            anime to reference.copy(
                                isInfoAnime = reference.id == newInfoAnime.id,
                            )
                        }
                    }
                }
            }

            binding.dedupeSwitch.isChecked = state.mergeReference?.let {
                it.episodeSortMode != MergedAnimeReference.EPISODE_SORT_NONE
            } ?: false
            binding.dedupeSwitch.setOnCheckedChangeListener { _, isChecked ->
                binding.dedupeModeSpinner.isEnabled = isChecked
                binding.dedupeModeSpinner.alpha = if (isChecked) 1F else 0.5F
                state.mergeReference = state.mergeReference?.copy(
                    episodeSortMode = if (isChecked) MergedAnimeReference.EPISODE_SORT_PRIORITY else MergedAnimeReference.EPISODE_SORT_NONE,
                )

                if (isChecked) binding.dedupeModeSpinner.setSelection(0)
            }

            binding.dedupeModeSpinner.isEnabled = binding.dedupeSwitch.isChecked
            binding.dedupeModeSpinner.alpha = if (binding.dedupeSwitch.isChecked) 1F else 0.5F
        }
    }

    fun canMove() =
        state.mergeReference?.let { it.episodeSortMode == MergedAnimeReference.EPISODE_SORT_PRIORITY } ?: false

    interface SortingListener {
        fun onSetPrioritySort(isPriorityOrder: Boolean)
    }
}
