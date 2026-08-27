package eu.kanade.tachiyomi.ui.anime

import android.content.Context
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.children
import coil3.load
import coil3.request.transformations
import coil3.transform.RoundedCornersTransformation
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.databinding.EditAnimeDialogBinding
import eu.kanade.tachiyomi.source.model.SAnime
import eu.kanade.tachiyomi.util.lang.chop
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.widget.materialdialogs.setTextInput
import exh.ui.metadata.adapters.MetadataUIUtil.getResourceColor
import exh.util.dropBlank
import exh.util.trimOrNull
import kotlinx.coroutines.CoroutineScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.MutableState
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.track.components.TrackLogoIcon
import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.model.Track
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.source.localanime.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
@Suppress("MagicNumber", "LongMethod")
fun EditAnimeDialog(
    anime: Anime,
    onDismissRequest: () -> Unit,
    onPositiveClick: (
        title: String?,
        author: String?,
        artist: String?,
        thumbnailUrl: String?,
        description: String?,
        tags: List<String>?,
        status: Long?,
    ) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var binding by remember {
        mutableStateOf<EditAnimeDialogBinding?>(null)
    }
    val showTrackerSelectionDialogue = remember { mutableStateOf(false) }
    val getTracks = remember { Injekt.get<GetTracks>() }
    val trackerManager = remember { Injekt.get<TrackerManager>() }
    val tracks = remember { mutableStateOf(emptyList<Pair<Track, Tracker>>()) }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                onClick = {
                    val binding = binding ?: return@TextButton
                    onPositiveClick(
                        binding.title.text.toString(),
                        binding.mangaAuthor.text.toString(),
                        binding.mangaArtist.text.toString(),
                        binding.thumbnailUrl.text.toString(),
                        binding.mangaDescription.text.toString(),
                        binding.mangaGenresTags.getTextStrings(),
                        binding.status.selectedItemPosition.let {
                            when (it) {
                                1 -> SAnime.ONGOING
                                2 -> SAnime.COMPLETED
                                3 -> SAnime.LICENSED
                                4 -> SAnime.PUBLISHING_FINISHED
                                5 -> SAnime.CANCELLED
                                6 -> SAnime.ON_HIATUS
                                7 -> SAnime.UPCOMING
                                else -> null
                            }
                        }?.toLong(),
                    )
                    onDismissRequest()
                },
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(android.R.string.cancel))
            }
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                AndroidView(
                    factory = { factoryContext ->
                        EditAnimeDialogBinding.inflate(LayoutInflater.from(factoryContext))
                            .also { binding = it }
                            .apply {
                                onViewCreated(anime, factoryContext, this, scope, getTracks, trackerManager, tracks, showTrackerSelectionDialogue)
                            }
                            .root
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )

    if (showTrackerSelectionDialogue.value) {
        TrackerSelectDialog(
            tracks = tracks.value,
            onDismissRequest = { showTrackerSelectionDialogue.value = false },
            onTrackerSelect = { tracker, track ->
                scope.launch {
                    autofillFromTracker(binding!!, track, tracker)
                }
            },
        )
    }
}

@Composable
private fun TrackerSelectDialog(
    tracks: List<Pair<Track, Tracker>>,
    onDismissRequest: () -> Unit,
    onTrackerSelect: (
        tracker: Tracker,
        track: Track,
    ) -> Unit,
) {
    AlertDialog(
        modifier = Modifier.fillMaxWidth(),
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(android.R.string.cancel))
            }
        },
        title = {
            Text(stringResource(SYMR.strings.select_tracker))
        },
        text = {
            FlowRow(
                modifier = Modifier
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                tracks.forEach { (track, tracker) ->
                    TrackLogoIcon(
                        tracker,
                        onClick = {
                            onTrackerSelect(tracker, track)
                            onDismissRequest()
                        },
                    )
                }
            }
        },
    )
}

@Suppress("MagicNumber", "LongMethod", "CyclomaticComplexMethod")
private fun onViewCreated(
    anime: Anime,
    context: Context,
    binding: EditAnimeDialogBinding,
    scope: CoroutineScope,
    getTracks: GetTracks,
    trackerManager: TrackerManager,
    tracks: MutableState<List<Pair<Track, Tracker>>>,
    showTrackerSelectionDialogue: MutableState<Boolean>,
) {
    loadCover(anime, binding)

    val statusAdapter: ArrayAdapter<String> = ArrayAdapter(
        context,
        android.R.layout.simple_spinner_dropdown_item,
        listOf(
            R.string.label_default,
            R.string.ongoing,
            R.string.completed,
            R.string.licensed,
            R.string.publishing_finished,
            R.string.cancelled,
            R.string.on_hiatus,
            R.string.upcoming,
        ).map { context.getString(it) },
    )

    binding.status.adapter = statusAdapter
    if (anime.status != anime.ogStatus) {
        binding.status.setSelection(
            when (anime.status.toInt()) {
                SAnime.UNKNOWN -> 0
                SAnime.ONGOING -> 1
                SAnime.COMPLETED -> 2
                SAnime.LICENSED -> 3
                SAnime.PUBLISHING_FINISHED, 61 -> 4
                SAnime.CANCELLED, 62 -> 5
                SAnime.ON_HIATUS, 63 -> 6
                SAnime.UPCOMING, 64 -> 7
                else -> 0
            },
        )
    }

    if (anime.isLocal()) {
        if (anime.title != anime.url) {
            binding.title.setText(anime.title)
        }

        binding.titleOutline.hint = context.getString(R.string.title)

        binding.mangaAuthorOutline.hint = context.getString(R.string.author)
        binding.mangaAuthor.setText(anime.author.orEmpty())

        binding.mangaArtistOutline.hint = context.getString(R.string.artist)
        binding.mangaArtist.setText(anime.artist.orEmpty())

        binding.thumbnailUrlOutline.hint = context.getString(R.string.thumbnail_url)
        binding.thumbnailUrl.setText(anime.thumbnailUrl.orEmpty())

        binding.mangaDescriptionOutline.hint = context.getString(R.string.description_hint, "").trim().removeSuffix(":").trim()
        binding.mangaDescription.setText(anime.description.orEmpty())
        binding.mangaGenresTags.setChips(anime.genre.orEmpty().dropBlank(), scope)
    } else {
        if (anime.title != anime.ogTitle) {
            binding.title.append(anime.title)
        }
        if (anime.author != anime.ogAuthor) {
            binding.mangaAuthor.append(anime.author.orEmpty())
        }
        if (anime.artist != anime.ogArtist) {
            binding.mangaArtist.append(anime.artist.orEmpty())
        }
        if (anime.thumbnailUrl != anime.ogThumbnailUrl) {
            binding.thumbnailUrl.append(anime.thumbnailUrl.orEmpty())
        }
        if (anime.description != anime.ogDescription) {
            binding.mangaDescription.append(anime.description.orEmpty())
        }
        binding.mangaGenresTags.setChips(anime.genre.orEmpty().dropBlank(), scope)

        binding.titleOutline.hint = context.stringResource(SYMR.strings.title_hint, anime.ogTitle)

        binding.mangaAuthorOutline.hint = context.stringResource(SYMR.strings.author_hint, anime.ogAuthor ?: "")

        binding.mangaArtistOutline.hint = context.stringResource(SYMR.strings.artist_hint, anime.ogArtist ?: "")

        binding.thumbnailUrlOutline.hint = context.stringResource(
            SYMR.strings.thumbnail_url_hint,
            anime.ogThumbnailUrl?.let {
                it.chop(40) + if (it.length > 46) "." + it.substringAfterLast(".").chop(6) else ""
            } ?: "",
        )

        binding.mangaDescriptionOutline.hint = context.stringResource(
            SYMR.strings.description_hint,
            anime.ogDescription?.takeIf { it.isNotBlank() }?.replace("\n", " ")?.chop(20) ?: "",
        )
    }
    binding.mangaGenresTags.clearFocus()

    binding.resetTags.setOnClickListener { resetTags(anime, binding, scope) }
    // SY -->
    binding.resetInfo.setOnClickListener { resetInfo(anime, binding, scope) }
    // SY <--

    binding.autofillFromTracker.setOnClickListener {
        scope.launch {
            getTrackers(anime, binding, context, getTracks, trackerManager, tracks, showTrackerSelectionDialogue)
        }
    }
}

private fun resetTags(anime: Anime, binding: EditAnimeDialogBinding, scope: CoroutineScope) {
    if (anime.genre.isNullOrEmpty() || anime.isLocal()) {
        binding.mangaGenresTags.setChips(emptyList(), scope)
    } else {
        binding.mangaGenresTags.setChips(anime.ogGenre.orEmpty(), scope)
    }
}

private fun resetInfo(anime: Anime, binding: EditAnimeDialogBinding, scope: CoroutineScope) {
    binding.title.setText("")
    binding.mangaAuthor.setText("")
    binding.mangaArtist.setText("")
    binding.mangaDescription.setText("")
    resetTags(anime, binding, scope)
}

private fun loadCover(anime: Anime, binding: EditAnimeDialogBinding) {
    binding.mangaCover.load(anime) {
        transformations(RoundedCornersTransformation(4.dpToPx.toFloat()))
    }
}

private fun ChipGroup.setChips(items: List<String>, scope: CoroutineScope) {
    removeAllViews()

    items.asSequence().map { item ->
        Chip(context).apply {
            text = item

            isCloseIconVisible = true
            closeIcon?.setTint(context.getResourceColor(R.attr.colorAccent))
            setOnCloseIconClickListener {
                removeView(this)
            }
        }
    }.forEach {
        addView(it)
    }

    val addTagChip = Chip(context).apply {
        setText(R.string.add_tag)

        chipIcon = ContextCompat.getDrawable(context, R.drawable.ic_add_24dp)?.apply {
            isChipIconVisible = true
            setTint(context.getResourceColor(R.attr.colorAccent))
        }

        setOnClickListener {
            var newTag: String? = null
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.add_tag)
                .setTextInput {
                    newTag = it.trimOrNull()
                }
                .setPositiveButton(R.string.action_ok) { _, _ ->
                    if (newTag != null) setChips(items + listOfNotNull(newTag), scope)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }
    addView(addTagChip)
}

private fun ChipGroup.getTextStrings(): List<String> = children.mapNotNull {
    if (it is Chip &&
        !it.text.toString().contains(
            context.getString(R.string.add_tag),
            ignoreCase = true,
        )
    ) {
        it.text.toString()
    } else {
        null
    }
}.toList()

private fun setTextIfNotBlank(field: (String) -> Unit, value: String?) {
    value?.takeIf { it.isNotBlank() }?.let { field(it) }
}

private suspend fun autofillFromTracker(binding: EditAnimeDialogBinding, track: Track, tracker: Tracker) {
    try {
        val trackerAnimeMetadata = tracker.getAnimeMetadata(track)

        setTextIfNotBlank(binding.title::setText, trackerAnimeMetadata?.title)
        setTextIfNotBlank(binding.mangaAuthor::setText, trackerAnimeMetadata?.author)
        setTextIfNotBlank(binding.mangaArtist::setText, trackerAnimeMetadata?.artist)
        setTextIfNotBlank(binding.thumbnailUrl::setText, trackerAnimeMetadata?.thumbnailUrl)
        setTextIfNotBlank(binding.mangaDescription::setText, trackerAnimeMetadata?.description)
        trackerAnimeMetadata?.genres?.let {
            binding.mangaGenresTags.setChips(it, kotlinx.coroutines.MainScope())
        }
    } catch (e: Throwable) {
        tracker.logcat(LogPriority.ERROR, e)
        binding.root.context.toast(
            binding.root.context.stringResource(
                MR.strings.track_error,
                tracker.name,
                e.message ?: "",
            ),
        )
    }
}

private suspend fun getTrackers(
    anime: Anime,
    binding: EditAnimeDialogBinding,
    context: Context,
    getTracks: GetTracks,
    trackerManager: TrackerManager,
    tracks: MutableState<List<Pair<Track, Tracker>>>,
    showTrackerSelectionDialogue: MutableState<Boolean>,
) {
    tracks.value = getTracks.await(anime.id).mapNotNull { track ->
        track to (trackerManager.get(track.trackerId) ?: return@mapNotNull null)
    }
        .filterNot { (_, tracker) -> tracker is EnhancedTracker }

    if (tracks.value.isEmpty()) {
        context.toast(context.stringResource(SYMR.strings.entry_not_tracked))
        return
    }

    if (tracks.value.size > 1) {
        showTrackerSelectionDialogue.value = true
        return
    }

    autofillFromTracker(binding, tracks.value.first().first, tracks.value.first().second)
}
