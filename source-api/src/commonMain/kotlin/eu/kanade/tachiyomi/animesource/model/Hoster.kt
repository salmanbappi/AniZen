package eu.kanade.tachiyomi.animesource.model

import eu.kanade.tachiyomi.animesource.model.SerializableVideo.Companion.serialize
import eu.kanade.tachiyomi.animesource.model.SerializableVideo.Companion.toVideoList
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

open class Hoster(
    val hosterUrl: String = "",
    val hosterName: String = "",
    val videoList: List<Video>? = null,
    val internalData: String = "",
    val lazy: Boolean = false,
    val memo: String = "",
) {
    @Transient
    @Volatile
    var status: State = State.IDLE

    var selected: Boolean = false

    enum class State {
        IDLE,
        LOADING,
        READY,
        ERROR,
    }

    // Ext lib 16 constructor
    @Deprecated("Used only for compatibility with ext lib 16, do not use", level = DeprecationLevel.HIDDEN)
    constructor(
        hosterUrl: String = "",
        hosterName: String = "",
        videoList: List<Video>? = null,
        internalData: String = "",
        lazy: Boolean = false,
    ) : this(
        hosterUrl = hosterUrl,
        hosterName = hosterName,
        videoList = videoList,
        internalData = internalData,
        lazy = lazy,
        memo = "",
    )

    fun copy(
        hosterUrl: String = this.hosterUrl,
        hosterName: String = this.hosterName,
        videoList: List<Video>? = this.videoList,
        internalData: String = this.internalData,
        lazy: Boolean = this.lazy,
        memo: String = this.memo,
    ): Hoster {
        return Hoster(hosterUrl, hosterName, videoList, internalData, lazy, memo).also {
            it.selected = this.selected
        }
    }

    // Ext lib 16 copy hoster
    @Deprecated("Used only for compatibility with ext lib 16, do not use", level = DeprecationLevel.HIDDEN)
    fun copy(
        hosterUrl: String = this.hosterUrl,
        hosterName: String = this.hosterName,
        videoList: List<Video>? = this.videoList,
        internalData: String = this.internalData,
        lazy: Boolean = this.lazy,
    ): Hoster {
        return Hoster(hosterUrl, hosterName, videoList, internalData, lazy, this.memo).also {
            it.selected = this.selected
        }
    }

    fun copy(
        hosterUrl: String = this.hosterUrl,
        hosterName: String = this.hosterName,
        videoList: List<Video>? = this.videoList,
        internalData: String = this.internalData,
        lazy: Boolean = this.lazy,
        selected: Boolean = this.selected,
        memo: String = this.memo,
    ): Hoster {
        return Hoster(hosterUrl, hosterName, videoList, internalData, lazy, memo).also {
            it.selected = selected
        }
    }

    // Ext lib 16 copy hoster with selected
    @Deprecated("Used only for compatibility with ext lib 16, do not use", level = DeprecationLevel.HIDDEN)
    fun copy(
        hosterUrl: String = this.hosterUrl,
        hosterName: String = this.hosterName,
        videoList: List<Video>? = this.videoList,
        internalData: String = this.internalData,
        lazy: Boolean = this.lazy,
        selected: Boolean = this.selected,
    ): Hoster {
        return Hoster(hosterUrl, hosterName, videoList, internalData, lazy, this.memo).also {
            it.selected = selected
        }
    }

    companion object {
        const val NO_HOSTER_LIST = "no_hoster_list"

        fun List<Video>.toHosterList(): List<Hoster> {
            return listOf(
                Hoster(
                    hosterUrl = "",
                    hosterName = NO_HOSTER_LIST,
                    videoList = this,
                ),
            )
        }
    }
}

@Serializable
data class SerializableHoster(
    val hosterUrl: String = "",
    val hosterName: String = "",
    val videoList: String? = null,
    val internalData: String = "",
    val lazy: Boolean = false,
    val selected: Boolean = false,
    val memo: String = "",
) {
    companion object {
        fun List<Hoster>.serialize(): String =
            Json.encodeToString(
                this.map { host ->
                    SerializableHoster(
                        host.hosterUrl,
                        host.hosterName,
                        host.videoList?.serialize(),
                        host.internalData,
                        host.lazy,
                        host.selected,
                        host.memo,
                    )
                },
            )

        fun String.toHosterList(): List<Hoster> =
            Json.decodeFromString<List<SerializableHoster>>(this)
                .map { sHost ->
                    Hoster(
                        sHost.hosterUrl,
                        sHost.hosterName,
                        sHost.videoList?.toVideoList(),
                        sHost.internalData,
                        sHost.lazy,
                        sHost.memo,
                    ).apply {
                        selected = sHost.selected
                    }
                }
    }
}
