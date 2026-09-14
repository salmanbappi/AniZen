package tachiyomi.domain.anime.model

import eu.kanade.tachiyomi.source.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SAnime as SAnimeSource

fun Anime.toSAnime(): SAnime = SAnime.create().also {
    it.url = url
    it.title = title
    it.artist = artist
    it.author = author
    it.description = description
    it.genre = genre.orEmpty().joinToString()
    it.status = status.toInt()
    it.thumbnail_url = thumbnailUrl
    it.initialized = initialized
}

fun SAnimeSource.toDomainAnime(sourceId: Long): Anime {
    return Anime.create().copy(
        url = url,
        // SY -->
        ogTitle = title,
        ogArtist = artist,
        ogAuthor = author,
        ogThumbnailUrl = thumbnail_url,
        ogDescription = description,
        ogGenre = genre?.split(", ")?.map { it.trim() },
        ogStatus = status.toLong(),
        // SY <--
        updateStrategy = update_strategy,
        initialized = initialized,
        source = sourceId,
    )
}

fun Anime.isLocal(): Boolean = source == 0L
