package eu.kanade.tachiyomi.data.download

import com.hippo.unifile.UniFile
import tachiyomi.domain.episode.service.EpisodeRecognition

/**
 * Shared rules deciding whether an on-disk artifact counts as a playable downloaded episode,
 * and the episode-number fallback used when a directory no longer matches the episode name
 * verbatim (renamed episodes, torrent file names, ...).
 *
 * A stray top-level video file in the anime directory only counts when it is realistically
 * sized: failed external handoffs and misclassified HLS responses leave tiny or empty
 * `.mp4`/`.mkv` files behind.
 *
 * An episode directory counts when it holds at least one direct child with a video
 * extension. Torrent sources legitimately produce `.ts`, `.avi`, `.webm`, ... files, so
 * this set is intentionally wider than the one for stray files. A child reporting
 * `length() == 0` is still accepted: several SAF documents report unknown sizes as 0, and
 * a video-extension child is already strong evidence that finalization wrote content.
 */

internal const val MIN_VALID_VIDEO_BYTES: Long = 1024L * 1024L

/** Extensions accepted for stray top-level files in the anime directory. */
// ANZ -->
internal val STRICT_VIDEO_EXTENSIONS = setOf("mp4", "mkv") // ANZ
// ANZ <--

/** Extensions accepted for files inside an episode directory. */
private val VIDEO_EXTENSIONS = setOf(
    "mp4", "mkv", "avi", "webm", "ts", "m4v", "mov", "flv", "mpeg", "mpg", "vob", "mts", "ogg",
)

/**
 * Release tags carrying numbers that are not episode numbers: resolutions (`1920x1080`,
 * `1080p`), codecs (`x264`, `HEVC`, `Hi10P`), bit depths and sources (`BD`, `WEB-DL`).
 * Torrent file names are full release names, and these tokens otherwise win the
 * episode-number parse ("... - 02 (BD 1920x1080 Hi10P FLAC).mkv" parses as 1920).
 */
private val RELEASE_TAG_REGEX = Regex(
    """\b\d{3,4}[x×]\d{3,4}\b|\b\d{3,4}p\b|\b[xh]\.?26[45]\b|\bhevc\b|\bhi10p?\b|\b\d+\s*bit\b|\b(?:bd|blu.?ray|web.?dl|dvd)\b""",
    RegexOption.IGNORE_CASE,
)

/**
 * A leading file-size token as written by torrent extensions (Nyaa's
 * `convertBytesToReadable`): `"2.29 GB_"`, `"700 MB_"`, ... . Stripped even when the
 * scanlator field is empty — the size would otherwise win the episode-number parse.
 */
private val LEADING_SIZE_PREFIX_REGEX = Regex(
    """^\d+(?:\.\d+)?\s*(?:[KMGT]i?B|[KMGT]B)_""",
    RegexOption.IGNORE_CASE,
)

fun UniFile.hasPlayableVideo(): Boolean {
    if (isFile) {
        val extension = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return extension in STRICT_VIDEO_EXTENSIONS && length() >= MIN_VALID_VIDEO_BYTES
    }
    if (!isDirectory) return false
    return listFiles().orEmpty().any { child ->
        val extension = child.name?.substringAfterLast('.', "")?.lowercase().orEmpty()
        if (!child.isFile || extension !in VIDEO_EXTENSIONS) return@any false
        val length = child.length()
        // 0 can mean "unknown" on SAF documents; anything positive must look like a video.
        length == 0L || length >= MIN_VALID_VIDEO_BYTES
    }
}

// ANZ -->
internal fun UniFile.isPlayableVideoFile(): Boolean {
    if (!isFile) return false // ANZ
    val extension = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
    val mimeType = type.orEmpty().lowercase()
    return "video" in mimeType || extension in VIDEO_EXTENSIONS
}
// ANZ <--

/**
 * Parses the episode number out of a downloaded directory name for the fallback match used
 * when the episode name no longer matches the directory verbatim.
 *
 * Torrent extensions like Nyaa store the file size in the scanlator field, producing
 * directory names such as `"2.29 GB_(IK) Some Anime - 02 (1080p).mkv"`. The size prefix is
 * validated against [episodeScanlator] and stripped before parsing; otherwise `2.29` is
 * parsed as the episode number and the fallback can never match.
 *
 * @param animeTitle title of the anime, used to strip it from the directory name.
 * @param dirName raw name of the downloaded directory (extension included).
 * @param episodeScanlator scanlator of the episode, or null/blank when there is none.
 * @return the parsed episode number, or null when the directory cannot match.
 */
fun parseEpisodeNumberFromDir(
    animeTitle: String,
    dirName: String,
    episodeScanlator: String?,
): Double? {
    if (dirName.endsWith(Downloader.TMP_DIR_SUFFIX)) return null
    var name = dirName
    if (!episodeScanlator.isNullOrBlank()) {
        val parsedScanlator = name.substringBefore('_', "")
        if (parsedScanlator.isNotBlank()) {
            if (!parsedScanlator.equals(episodeScanlator, ignoreCase = true)) return null
            name = name.removePrefix("${parsedScanlator}_")
        }
    }
    name = name.replaceFirst(LEADING_SIZE_PREFIX_REGEX, "")
    name = name.replace(RELEASE_TAG_REGEX, " ")
    val parsed = EpisodeRecognition.parseEpisodeNumber(animeTitle, name)
    return parsed.takeIf { it >= 0.0 }
}
