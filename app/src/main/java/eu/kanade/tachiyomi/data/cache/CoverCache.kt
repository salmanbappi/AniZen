package eu.kanade.tachiyomi.data.cache

import android.content.Context
import eu.kanade.tachiyomi.util.storage.DiskUtil
import tachiyomi.domain.anime.model.Anime
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Class used to create cover cache.
 * It is used to store the covers of the library.
 * Names of files are created with the md5 of the thumbnail URL.
 *
 * @param context the application context.
 * @constructor creates an instance of the cover cache.
 */
class CoverCache(private val context: Context) {

    companion object {
        private const val COVERS_DIR = "covers"
        private const val CUSTOM_COVERS_DIR = "covers/custom"
    }

    /**
     * Cache directory used for cache management.
     */
    private val cacheDir = getCacheDir(COVERS_DIR)

    private val customCoverCacheDir = getCacheDir(CUSTOM_COVERS_DIR)

    // ANZ -->
    private val coverFiles = java.util.concurrent.ConcurrentHashMap.newKeySet<String>().apply {
        cacheDir.mkdirs()
        cacheDir.list()?.let { addAll(it) }
    }

    private val customCoverFiles = java.util.concurrent.ConcurrentHashMap.newKeySet<String>().apply {
        customCoverCacheDir.mkdirs() // ANZ
        customCoverCacheDir.list()?.let { addAll(it) }
    }

    fun hasCustomCover(animeId: Long?): Boolean { // ANZ
        if (animeId == null) return false
        return customCoverFiles.contains(DiskUtil.hashKeyForDisk(animeId.toString()))
    }

    fun hasCustomCoverFile(file: File): Boolean { // ANZ
        return customCoverFiles.contains(file.name)
    }

    fun hasCover(animeThumbnailUrl: String?): Boolean { // ANZ
        if (animeThumbnailUrl == null) return false
        return coverFiles.contains(DiskUtil.hashKeyForDisk(animeThumbnailUrl))
    }

    fun hasCoverFile(file: File): Boolean { // ANZ
        return coverFiles.contains(file.name)
    }

    fun recordCover(file: File) { // ANZ
        coverFiles.add(file.name)
    }
    // ANZ <--

    /**
     * Returns the cover from cache.
     *
     * @param animeThumbnailUrl thumbnail url for the anime.
     * @return cover image.
     */
    fun getCoverFile(animeThumbnailUrl: String?): File? {
        return animeThumbnailUrl?.let {
            File(cacheDir, DiskUtil.hashKeyForDisk(it))
        }
    }

    /**
     * Returns the custom cover from cache.
     *
     * @param animeId the anime id.
     * @return cover image.
     */
    fun getCustomCoverFile(animeId: Long?): File {
        return File(customCoverCacheDir, DiskUtil.hashKeyForDisk(animeId.toString()))
    }

    /**
     * Saves the given stream as the anime's custom cover to cache.
     *
     * @param anime the anime.
     * @param inputStream the stream to copy.
     * @throws IOException if there's any error.
     */
    @Throws(IOException::class)
    fun setCustomCoverToCache(anime: Anime, inputStream: InputStream) {
        getCustomCoverFile(anime.id).outputStream().use {
            inputStream.copyTo(it)
        }
        // ANZ -->
        anime.id?.let { customCoverFiles.add(DiskUtil.hashKeyForDisk(it.toString())) }
        // ANZ <--
    }

    /**
     * Delete the cover files of the anime from the cache.
     *
     * @param anime the anime.
     * @param deleteCustomCover whether the custom cover should be deleted.
     * @return number of files that were deleted.
     */
    fun deleteFromCache(anime: Anime, deleteCustomCover: Boolean = false): Int {
        var deleted = 0

        getCoverFile(anime.thumbnailUrl)?.let {
            if (it.exists() && it.delete()) {
                // ANZ -->
                coverFiles.remove(it.name)
                // ANZ <--
                ++deleted
            }
        }

        if (deleteCustomCover) {
            if (deleteCustomCover(anime.id)) ++deleted
        }

        return deleted
    }

    /**
     * Delete custom cover of the anime from the cache
     *
     * @param animeId the anime id.
     * @return whether the cover was deleted.
     */
    fun deleteCustomCover(animeId: Long?): Boolean {
        return getCustomCoverFile(animeId).let {
            val deleted = it.exists() && it.delete()
            // ANZ -->
            if (deleted && animeId != null) {
                customCoverFiles.remove(DiskUtil.hashKeyForDisk(animeId.toString()))
            }
            // ANZ <--
            deleted
        }
    }

    private fun getCacheDir(dir: String): File {
        return context.getExternalFilesDir(dir)
            ?: File(context.filesDir, dir).also { it.mkdirs() }
    }
}
