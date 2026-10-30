// ANZ -->
package eu.kanade.tachiyomi.data.coil

import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.request.SuccessResult
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.model.AnimeCover
import tachiyomi.presentation.core.util.ScrollActivity

/**
 * De-duplicates concurrent requests for the same cover or icon and pauses offscreen network
 * fetches while a lazy list is actively flinging.
 */
class CoverRequestInterceptor : Interceptor {

    private val concurrentRequests = DeDupeConcurrentRequests<ImageResult> { it is SuccessResult } // ANZ

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val data = chain.request.data

        // De-duplicate concurrent requests for anime covers, extension icons, and icon URLs
        val key = when (data) {
            is AnimeCover -> "cover:${data.animeId}:${data.lastModified}"
            is Anime -> "anime:${data.id}:${data.coverLastModified}"
            is String -> "url:$data"
            is InstalledExtensionIcon -> "ext:${data.pkgName}:${data.versionCode}:${data.density}"
            is InstalledSourceIcon -> "src:${data.sourceId}"
            else -> return chain.proceed()
        }

        // Defer expensive network/disk fetches for items that are being rapidly flung past.
        // If the item scrolls offscreen before settling, its coroutine is cancelled without
        // paying the network or decoding cost.
        if (ScrollActivity.isScrolling && (data is String || data is AnimeCover || data is Anime)) {
            ScrollActivity.awaitIdle(50L)
        }

        return concurrentRequests.apply(key to chain.size) { chain.proceed() }
    }
}
// ANZ <--
