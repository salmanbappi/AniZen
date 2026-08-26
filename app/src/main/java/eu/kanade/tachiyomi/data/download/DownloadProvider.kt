package eu.kanade.tachiyomi.data.download

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.util.size
import eu.kanade.tachiyomi.util.storage.DiskUtil
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.storage.displayablePath
import tachiyomi.core.common.storage.nameWithoutExtension
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.episode.model.Episode
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.i18n.MR
import tachiyomi.source.localanime.io.LocalAnimeSourceFileSystem
import tachiyomi.source.localanime.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

import tachiyomi.domain.episode.service.EpisodeRecognition

/**
 * This class is used to provide the directories where the downloads should be saved.
 * It uses the following path scheme: /<root downloads dir>/<source name>/<anime>/<episode>
 *
 * @param context the application context.
 */
class DownloadProvider(
    private val context: Context,
    private val storageManager: StorageManager = Injekt.get(),
    // AM (FILE_SIZE) -->
    private val localFileSystem: LocalAnimeSourceFileSystem = Injekt.get(),
    // <-- AM (FILE_SIZE)
) {

    private val downloadsDir: UniFile?
        get() = storageManager.getDownloadsDirectory()

    private val dirMutex = Mutex()
    private val animeDirCache = android.util.LruCache<String, UniFile>(100)

    /**
     * Returns the download directory for an anime. For internal use only.
     *
     * @param animeTitle the title of the anime to query.
     * @param source the source of the anime.
     */
    suspend fun getAnimeDir(animeTitle: String, source: Source): UniFile {
        val cacheKey = "${source.id}_$animeTitle"
        animeDirCache.get(cacheKey)?.let { 
            if (it.exists()) return it else animeDirCache.remove(cacheKey) 
        }
        
        try {
            return dirMutex.withLock {
                val dir = downloadsDir!!
                    .createDirectory(getSourceDirName(source))!!
                    .createDirectory(getAnimeDirName(animeTitle))!!
                animeDirCache.put(cacheKey, dir)
                dir
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Invalid download directory" }
            throw Exception(
                context.stringResource(
                    MR.strings.invalid_location,
                    downloadsDir?.displayablePath ?: "",
                ),
            )
        }
    }

    /**
     * Returns the download directory for a source if it exists.
     *
     * @param source the source to query.
     */
    fun findSourceDir(source: Source): UniFile? {
        if (source.isLocal()) {
            return localFileSystem.getBaseDirectory()
        }
        return downloadsDir?.findFile(getSourceDirName(source))
    }

    /**
     * Returns the download directory for an anime if it exists.
     *
     * @param animeTitle the title of the anime to query.
     * @param source the source of the anime.
     */
    fun findAnimeDir(animeTitle: String, source: Source): UniFile? {
        val cacheKey = "${source.id}_$animeTitle"
        animeDirCache.get(cacheKey)?.let {
            if (it.exists()) return it else animeDirCache.remove(cacheKey)
        }

        val dir = if (source.isLocal()) {
            localFileSystem.getAnimeDirectory(animeTitle)
        } else {
            val sourceDir = findSourceDir(source)
            sourceDir?.findFile(getAnimeDirName(animeTitle))
        }

        if (dir != null) {
            animeDirCache.put(cacheKey, dir)
        }
        return dir
    }

    /**
     * Returns the download directory for an episode if it exists.
     *
     * @param episodeName the name of the episode to query.
     * @param episodeScanlator scanlator of the episode to query
     * @param animeTitle the title of the anime to query.
     * @param source the source of the episode.
     * @param episodeNumber the episode number to fallback to if the name was changed
     */
    fun findEpisodeDir(
        episodeName: String,
        episodeScanlator: String?,
        animeTitle: String,
        source: Source,
        episodeNumber: Double = -1.0,
    ): UniFile? {
        val animeDir = findAnimeDir(animeTitle, source)
        if (source.isLocal()) {
            return animeDir?.listFiles().orEmpty().find { it.nameWithoutExtension == episodeName }
        }
        val exact = getValidEpisodeDirNames(episodeName, episodeScanlator).asSequence()
            .mapNotNull { animeDir?.findFile(it) }
            .firstOrNull()
        if (exact != null) return exact

        if (episodeNumber >= 0.0 && animeDir != null) {
            val files = animeDir.listFiles().orEmpty()
            return files.firstOrNull { file ->
                val name = file.nameWithoutExtension ?: file.name ?: return@firstOrNull false
                if (name.endsWith(Downloader.TMP_DIR_SUFFIX)) return@firstOrNull false
                if (!episodeScanlator.isNullOrBlank()) {
                    val parsedScanlator = name.substringBefore('_', "")
                    if (parsedScanlator.isNotBlank() && !parsedScanlator.equals(episodeScanlator, ignoreCase = true)) {
                        return@firstOrNull false
                    }
                }
                val parsedNum = EpisodeRecognition.parseEpisodeNumber(animeTitle, name)
                parsedNum == episodeNumber
            }
        }
        return null
    }

    /**
     * Returns a list of downloaded directories for the episodes that exist.
     *
     * @param episodes the episodes to query.
     * @param anime the anime of the episode.
     * @param source the source of the episode.
     */
    fun findEpisodeDirs(episodes: List<Episode>, anime: Anime, source: Source): Pair<UniFile?, List<UniFile>> {
        val animeDir = findAnimeDir(if (source.isLocal()) anime.url else anime.ogTitle, source)
        if (source.isLocal()) {
            val files = animeDir?.listFiles().orEmpty()
            return animeDir to episodes.mapNotNull { episode ->
                // Try finding by URL filename first
                val filenameFromUrl = episode.url.split('/', limit = 2).lastOrNull()
                val fileByUrl = filenameFromUrl?.let { animeDir?.findFile(it) }

                // Fallback to finding by name without extension
                fileByUrl ?: files.find { it.nameWithoutExtension == episode.name }
            }
        }
        if (animeDir == null) return null to emptyList()
        val allFiles = animeDir.listFiles().orEmpty()
        return animeDir to episodes.mapNotNull { episode ->
            val exact = getValidEpisodeDirNames(episode.name, episode.scanlator).asSequence()
                .mapNotNull { animeDir.findFile(it) }
                .firstOrNull()
            if (exact != null) return@mapNotNull exact

            if (episode.isRecognizedNumber) {
                allFiles.firstOrNull { file ->
                    val name = file.nameWithoutExtension ?: file.name ?: return@firstOrNull false
                    if (name.endsWith(Downloader.TMP_DIR_SUFFIX)) return@firstOrNull false
                    if (!episode.scanlator.isNullOrBlank()) {
                        val parsedScanlator = name.substringBefore('_', "")
                        if (parsedScanlator.isNotBlank() && !parsedScanlator.equals(episode.scanlator, ignoreCase = true)) {
                            return@firstOrNull false
                        }
                    }
                    val parsedNum = EpisodeRecognition.parseEpisodeNumber(anime.ogTitle, name)
                    parsedNum == episode.episodeNumber
                }
            } else {
                null
            }
        }
    }

    /**
     * Returns the download directory name for a source.
     *
     * @param source the source to query.
     */
    fun getSourceDirName(source: Source): String {
        return DiskUtil.buildValidFilename(source.toString())
    }

    /**
     * Returns the download directory name for an anime.
     *
     * @param animeTitle the title of the anime to query.
     */
    fun getAnimeDirName(animeTitle: String): String {
        return DiskUtil.buildValidFilename(animeTitle)
    }

    /**
     * Returns the episode directory name for an episode.
     *
     * @param episodeName the name of the episode to query.
     * @param episodeScanlator scanlator of the episode to query
     */
    fun getEpisodeDirName(episodeName: String, episodeScanlator: String?): String {
        val newEpisodeName = sanitizeEpisodeName(episodeName)
        return DiskUtil.buildValidFilename(
            when {
                !episodeScanlator.isNullOrBlank() -> "${episodeScanlator}_$newEpisodeName"
                else -> newEpisodeName
            },
        )
    }

    /**
     * Return the new name for the episode (in case it's empty or blank)
     *
     * @param episodeName the name of the episode
     */
    private fun sanitizeEpisodeName(episodeName: String): String {
        return episodeName.ifBlank {
            "Episode"
        }
    }

    /**
     * Returns the episode directory name for an episode.
     *
     * @param episodeName the name of the episode to query.
     * @param episodeScanlator scanlator of the episode to query
     */
    fun getOldEpisodeDirName(episodeName: String, episodeScanlator: String?): String {
        return DiskUtil.buildValidFilename(
            when {
                episodeScanlator != null -> "${episodeScanlator}_$episodeName"
                else -> episodeName
            },
        )
    }

    fun isEpisodeDirNameChanged(oldEpisode: Episode, newEpisode: Episode): Boolean {
        return oldEpisode.name != newEpisode.name ||
            oldEpisode.scanlator?.takeIf { it.isNotBlank() } != newEpisode.scanlator?.takeIf { it.isNotBlank() }
    }

    /**
     * Returns valid downloaded episode directory names.
     *
     * @param episodeName the name of the episode to query.
     * @param episodeScanlator scanlator of the episode to query
     */
    fun getValidEpisodeDirNames(episodeName: String, episodeScanlator: String?): List<String> {
        val episodeDirName = getEpisodeDirName(episodeName, episodeScanlator)
        val oldEpisodeDirName = getOldEpisodeDirName(episodeName, episodeScanlator)
        return listOf(episodeDirName, oldEpisodeDirName)
    }

    // AM (FILE_SIZE) -->
    /**
     * Returns an episode file size in bytes.
     * Returns null if the episode is not found in expected location
     *
     * @param episodeName the name of the episode to query.
     * @param episodeScanlator scanlator of the episode to query
     * @param animeTitle the title of the anime
     * @param source the source of the anime
     */
    fun getEpisodeFileSize(
        episodeName: String,
        episodeUrl: String?,
        episodeScanlator: String?,
        animeTitle: String,
        source: Source?,
        episodeNumber: Double = -1.0,
    ): Long? {
        if (source == null) return null
        return if (source.isLocal()) {
            val (animeDirName, episodeDirName) = episodeUrl?.split('/', limit = 2) ?: return null
            localFileSystem.getBaseDirectory()?.findFile(animeDirName)?.findFile(episodeDirName)?.size()
        } else {
            findEpisodeDir(episodeName, episodeScanlator, animeTitle, source, episodeNumber)?.size()
        }
    }
    // <-- AM (FILE_SIZE)
}
