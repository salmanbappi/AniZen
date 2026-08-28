package eu.kanade.tachiyomi.ui.player.loader

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.ui.player.controls.components.sheets.HosterState
import eu.kanade.tachiyomi.ui.player.controls.components.sheets.getChangedAt
import eu.kanade.tachiyomi.ui.player.utils.DefaultStreamSelector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

class HosterLoader {
    companion object {
        /**
         * Check for the best video from the current hosterState.
         *
         * The first video with the `preferred` attribute is selected, however
         * if no such video is selected the first video with a non-empty url is selected.
         * If there are no viable videos at all, an error is thrown.
         *
         * @return the indices of the hoster & video
         */
        fun selectBestVideo(hosterState: List<HosterState>): Pair<Int, Int> {
            val availableHosters = hosterState.withIndex()
                .filter { (_, state) -> state is HosterState.Ready }

            // Check for first preferred
            val isPreferred: (Pair<Video, Video.State>) -> Boolean = { (v, s) ->
                v.preferred && (s == Video.State.READY || s == Video.State.QUEUE)
            }
            val prefHosterIdx = availableHosters.indexOfFirst {
                (it.value as HosterState.Ready).let { hoster ->
                    hoster.videoList zip hoster.videoState
                }.any(isPreferred)
            }
            if (prefHosterIdx != -1) {
                val videoList = (availableHosters[prefHosterIdx].value as HosterState.Ready).let { hoster ->
                    hoster.videoList zip hoster.videoState
                }
                val prefVideoIdx = videoList.indexOfFirst(isPreferred)
                return availableHosters[prefHosterIdx].index to prefVideoIdx
            }

            // Check for first video (we no longer require videoUrl to be non-empty,
            // because unresolved videos from extensions like Torrent/Stremio
            // intentionally have empty URLs until they are clicked/resolved).
            val firstValid: (Pair<Video, Video.State>) -> Boolean = { (v, s) ->
                s == Video.State.READY || s == Video.State.QUEUE
            }
            val firstAvailableHosterIdx = availableHosters.indexOfFirst {
                (it.value as HosterState.Ready).let { hoster ->
                    hoster.videoList zip hoster.videoState
                }.any(firstValid)
            }
            if (firstAvailableHosterIdx != -1) {
                val videoList = (availableHosters[firstAvailableHosterIdx].value as HosterState.Ready).let { hoster ->
                    hoster.videoList zip hoster.videoState
                }
                val firstVideoIdx = videoList.indexOfFirst(firstValid)
                return availableHosters[firstAvailableHosterIdx].index to firstVideoIdx
            }

            // No success
            return Pair(-1, -1)
        }

        data class ResolvedVideoResult(
            val video: Video,
            val hosterIndex: Int,
            val videoIndex: Int,
            val hosterStates: List<HosterState>,
        )

        class EarlyReturnException(
            val video: Video,
            val hosterIndex: Int,
            val videoIndex: Int,
        ) : Exception()

        /**
         * Return the first loaded and valid "best" video, based on the criteria in the function `selectBestVideo` above.
         *
         * @param source The source for the episode
         * @param hosterList the list of hosters
         * @return the video, or null if no valid video was found
         */
        suspend fun resolveDefaultStream(
            source: AnimeSource,
            hosterList: List<Hoster>,
            defaultSelector: String,
        ): Video? = resolveDefaultStreamWithResult(source, hosterList, defaultSelector)?.video

        suspend fun resolveDefaultStreamWithResult(
            source: AnimeSource,
            hosterList: List<Hoster>,
            defaultSelector: String,
        ): ResolvedVideoResult? {
            if (defaultSelector.isBlank()) return null
            val hosterStates = MutableList<HosterState>(hosterList.size) { HosterState.Idle("") }
            return try {
                withContext(Dispatchers.IO) {
                    hosterList.mapIndexed { hosterIdx, hoster ->
                        async {
                            val hosterState = EpisodeLoader.loadHosterVideos(source, hoster)
                            hosterStates[hosterIdx] = hosterState
                        }
                    }.awaitAll()

                    val strictRanked = DefaultStreamSelector.findRankedInHosters(defaultSelector, hosterStates)
                    val ranked = strictRanked +
                        DefaultStreamSelector.findRankedInHostersRelaxed(defaultSelector, hosterStates)
                            .filter { it !in strictRanked }

                    for ((hosterIdx, videoIdx) in ranked.distinct()) {
                        val ready = hosterStates[hosterIdx] as? HosterState.Ready ?: continue
                        val video = ready.videoList.getOrNull(videoIdx) ?: continue
                        val resolved = getResolvedVideo(source, video)
                        if (resolved?.videoUrl?.isNotEmpty() == true) {
                            val updatedReady = ready.getChangedAt(videoIdx, resolved, Video.State.READY)
                            hosterStates[hosterIdx] = updatedReady
                            return@withContext ResolvedVideoResult(resolved, hosterIdx, videoIdx, hosterStates)
                        }
                    }
                    null
                }
            } catch (e: CancellationException) {
                throw e
            }
        }

        suspend fun getBestVideo(source: AnimeSource, hosterList: List<Hoster>): Video? =
            getBestVideoWithResult(source, hosterList)?.video

        suspend fun getBestVideoWithResult(source: AnimeSource, hosterList: List<Hoster>): ResolvedVideoResult? {
            val hosterStates = MutableList<HosterState>(hosterList.size) { HosterState.Idle("") }

            return try {
                withContext<ResolvedVideoResult?>(Dispatchers.IO) {
                    hosterList.mapIndexed { hosterIdx, hoster ->
                        async {
                            val hosterState = EpisodeLoader.loadHosterVideos(source, hoster)
                            hosterStates[hosterIdx] = hosterState

                            if (hosterState is HosterState.Ready) {
                                val prefIndex = hosterState.videoList.indexOfFirst { it.preferred && !it.initialized }
                                if (prefIndex != -1) {
                                    val video = hosterState.videoList[prefIndex]
                                    hosterStates[hosterIdx] =
                                        (hosterStates[hosterIdx] as HosterState.Ready).getChangedAt(
                                            prefIndex,
                                            video,
                                            Video.State.LOAD_VIDEO,
                                        )

                                    val resolvedVideo = getResolvedVideo(source, video)
                                    if (resolvedVideo?.videoUrl?.isNotEmpty() == true) {
                                        val updatedReady = (hosterStates[hosterIdx] as HosterState.Ready).getChangedAt(
                                            prefIndex,
                                            resolvedVideo,
                                            Video.State.READY,
                                        )
                                        hosterStates[hosterIdx] = updatedReady
                                        coroutineContext.cancelChildren()
                                        throw EarlyReturnException(resolvedVideo, hosterIdx, prefIndex)
                                    }

                                    hosterStates[hosterIdx] =
                                        (hosterStates[hosterIdx] as HosterState.Ready).getChangedAt(
                                            prefIndex,
                                            video,
                                            Video.State.ERROR,
                                        )
                                }
                            }
                        }
                    }.awaitAll()

                    var (hosterIdx, videoIdx) = selectBestVideo(hosterStates)
                    while (hosterIdx != -1) {
                        val hosterState = hosterStates[hosterIdx] as HosterState.Ready
                        val video = hosterState.videoList[videoIdx]
                        hosterStates[hosterIdx] =
                            (hosterStates[hosterIdx] as HosterState.Ready).getChangedAt(
                                videoIdx,
                                video,
                                Video.State.LOAD_VIDEO,
                            )

                        val resolvedVideo = getResolvedVideo(source, video)
                        if (resolvedVideo?.videoUrl?.isNotEmpty() == true) {
                            val updatedReady = (hosterStates[hosterIdx] as HosterState.Ready).getChangedAt(
                                videoIdx,
                                resolvedVideo,
                                Video.State.READY,
                            )
                            hosterStates[hosterIdx] = updatedReady
                            coroutineContext.cancelChildren()
                            return@withContext ResolvedVideoResult(resolvedVideo, hosterIdx, videoIdx, hosterStates)
                        }

                        hosterStates[hosterIdx] =
                            (hosterStates[hosterIdx] as HosterState.Ready).getChangedAt(
                                videoIdx,
                                video,
                                Video.State.ERROR,
                            )
                        val newResult = selectBestVideo(hosterStates)
                        hosterIdx = newResult.first
                        videoIdx = newResult.second
                    }

                    coroutineContext.cancelChildren()
                    return@withContext null
                }
            } catch (e: EarlyReturnException) {
                ResolvedVideoResult(e.video, e.hosterIndex, e.videoIndex, hosterStates)
            } finally {
                // Ensure everything is cleaned up
            }
        }

        suspend fun getResolvedVideo(source: AnimeSource?, video: Video): Video? {
            val resolvedVideo = if (source is AnimeHttpSource && !video.initialized) {
                try {
                    source.resolveVideo(video)
                } catch (e: Exception) {
                    if (e is CancellationException) {
                        throw e
                    }

                    null
                }
            } else {
                video
            }

            return resolvedVideo?.copy(initialized = true)
        }
    }
}
