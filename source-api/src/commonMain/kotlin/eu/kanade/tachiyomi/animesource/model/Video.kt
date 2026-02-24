package eu.kanade.tachiyomi.animesource.model

import android.net.Uri
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Headers

@Serializable
data class Track(val url: String, val lang: String)

@Serializable
enum class ChapterType {
    Opening,
    Ending,
    Recap,
    MixedOp,
    Other,
}

@Serializable
data class TimeStamp(
    val start: Double,
    val end: Double,
    val name: String,
    val type: ChapterType = ChapterType.Other,
)

open class Video(
    var videoUrl: String = "",
    val videoTitle: String = "",
    val resolution: Int? = null,
    val bitrate: Int? = null,
    val headers: Headers? = null,
    val preferred: Boolean = false,
    val subtitleTracks: List<Track> = emptyList(),
    val audioTracks: List<Track> = emptyList(),
    val timestamps: List<TimeStamp> = emptyList(),
    val internalData: String = "",
    val initialized: Boolean = false,
    val type: VideoType = VideoType.VIDEO,
    // TODO(1.6): Remove after ext lib bump
    val videoPageUrl: String = "",
) {

    // TODO(1.6): Remove after ext lib bump
    @Deprecated("Use videoTitle instead", ReplaceWith("videoTitle"))
    val quality: String
        get() = videoTitle

    // TODO(1.6): Remove after ext lib bump
    @Deprecated("Use videoPageUrl instead", ReplaceWith("videoPageUrl"))
    val url: String
        get() = videoPageUrl

    // TODO(1.6): Remove after ext lib bump
    constructor(
        url: String,
        quality: String,
        videoUrl: String?,
        headers: Headers? = null,
        subtitleTracks: List<Track> = emptyList(),
        audioTracks: List<Track> = emptyList(),
    ) : this(
        videoPageUrl = url,
        videoTitle = quality,
        videoUrl = videoUrl ?: "null",
        headers = headers,
        subtitleTracks = subtitleTracks,
        audioTracks = audioTracks,
        type = VideoType.VIDEO, // Default to VIDEO
    )

    // TODO(1.6): Remove after ext lib bump
    constructor(
        videoUrl: String = "",
        videoTitle: String = "",
        resolution: Int? = null,
        bitrate: Int? = null,
        headers: Headers? = null,
        preferred: Boolean = false,
        subtitleTracks: List<Track> = emptyList(),
        audioTracks: List<Track> = emptyList(),
        timestamps: List<TimeStamp> = emptyList(),
        internalData: String = "",
        type: VideoType = VideoType.VIDEO,
    ) : this(
        videoUrl = videoUrl,
        videoTitle = videoTitle,
        resolution = resolution,
        bitrate = bitrate,
        headers = headers,
        preferred = preferred,
        subtitleTracks = subtitleTracks,
        audioTracks = audioTracks,
        timestamps = timestamps,
        internalData = internalData,
        type = type,
        videoPageUrl = "",
    )

    // TODO(1.6): Remove after ext lib bump
    @Suppress("UNUSED_PARAMETER")
    constructor(
        url: String,
        quality: String,
        videoUrl: String?,
        uri: Uri? = null,
        headers: Headers? = null,
    ) : this(
        videoPageUrl = url,
        videoTitle = quality,
        videoUrl = videoUrl ?: "null",
        headers = headers,
        type = VideoType.VIDEO,
    )

    @Transient
    @Volatile
    var status: State = State.QUEUE
        set(value) {
            field = value
        }

    @Transient
    @Volatile
    var lastByteRead: Long = 0
        set(value) {
            field = value
        }

    fun copy(
        videoUrl: String = this.videoUrl,
        videoTitle: String = this.videoTitle,
        resolution: Int? = this.resolution,
        bitrate: Int? = this.bitrate,
        headers: Headers? = this.headers,
        preferred: Boolean = this.preferred,
        subtitleTracks: List<Track> = this.subtitleTracks,
        audioTracks: List<Track> = this.audioTracks,
        timestamps: List<TimeStamp> = this.timestamps,
        internalData: String = this.internalData,
        type: VideoType = this.type,
    ): Video {
        return Video(
            videoUrl = videoUrl,
            videoTitle = videoTitle,
            resolution = resolution,
            bitrate = bitrate,
            headers = headers,
            preferred = preferred,
            subtitleTracks = subtitleTracks,
            audioTracks = audioTracks,
            timestamps = timestamps,
            internalData = internalData,
            type = type,
        )
    }

    fun copy(
        videoUrl: String = this.videoUrl,
        videoTitle: String = this.videoTitle,
        resolution: Int? = this.resolution,
        bitrate: Int? = this.bitrate,
        headers: Headers? = this.headers,
        preferred: Boolean = this.preferred,
        subtitleTracks: List<Track> = this.subtitleTracks,
        audioTracks: List<Track> = this.audioTracks,
        timestamps: List<TimeStamp> = this.timestamps,
        internalData: String = this.internalData,
        initialized: Boolean = this.initialized,
        type: VideoType = this.type,
        videoPageUrl: String = this.videoPageUrl,
    ): Video {
        return Video(
            videoUrl = videoUrl,
            videoTitle = videoTitle,
            resolution = resolution,
            bitrate = bitrate,
            headers = headers,
            preferred = preferred,
            subtitleTracks = subtitleTracks,
            audioTracks = audioTracks,
            timestamps = timestamps,
            internalData = internalData,
            initialized = initialized,
            type = type,
            videoPageUrl = videoPageUrl,
        )
    }

    enum class State {
        QUEUE,
        LOAD_VIDEO,
        READY,
        ERROR,
    }
}

@Serializable
data class SerializableVideo(
    val videoUrl: String = "",
    val videoTitle: String = "",
    val resolution: Int? = null,
    val bitrate: Int? = null,
    val headers: List<Pair<String, String>>? = null,
    val preferred: Boolean = false,
    val subtitleTracks: List<Track> = emptyList(),
    val audioTracks: List<Track> = emptyList(),
    val timestamps: List<TimeStamp> = emptyList(),
    val internalData: String = "",
    val initialized: Boolean = false,
    val type: VideoType = VideoType.VIDEO,
    // TODO(1.6): Remove after ext lib bump
    val videoPageUrl: String = "",
) {

    companion object {
        fun List<Video>.serialize(): String =
            Json.encodeToString(
                this.map { vid ->
                    SerializableVideo(
                        vid.videoUrl,
                        vid.videoTitle,
                        vid.resolution,
                        vid.bitrate,
                        vid.headers?.toList(),
                        vid.preferred,
                        vid.subtitleTracks,
                        vid.audioTracks,
                        vid.timestamps,
                        vid.internalData,
                        vid.initialized,
                        vid.type,
                        vid.videoPageUrl,
                    )
                },
            )

        fun String.toVideoList(): List<Video> =
            Json.decodeFromString<List<SerializableVideo>>(this)
                .map { sVid ->
                    Video(
                        sVid.videoUrl,
                        sVid.videoTitle,
                        sVid.resolution,
                        sVid.bitrate,
                        sVid.headers
                            ?.flatMap { it.toList() }
                            ?.let { Headers.headersOf(*it.toTypedArray()) },
                        sVid.preferred,
                        sVid.subtitleTracks,
                        sVid.audioTracks,
                        sVid.timestamps,
                        sVid.internalData,
                        sVid.initialized,
                        sVid.type,
                        sVid.videoPageUrl,
                    )
                }
    }
}
