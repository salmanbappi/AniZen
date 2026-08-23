package eu.kanade.tachiyomi.extension.model

import android.graphics.drawable.Drawable
import androidx.compose.runtime.Immutable
import eu.kanade.tachiyomi.source.Source
import tachiyomi.domain.source.model.StubSource

@Immutable
sealed class Extension {

    abstract val name: String
    abstract val pkgName: String
    abstract val versionName: String
    abstract val versionCode: Long
    abstract val libVersion: Double
    abstract val lang: String?
    abstract val isNsfw: Boolean
    abstract val isTorrent: Boolean
    abstract val repoUrl: String?

    @Immutable
    data class Installed(
        override val name: String,
        override val pkgName: String,
        override val versionName: String,
        override val versionCode: Long,
        override val libVersion: Double,
        override val lang: String,
        override val isNsfw: Boolean,
        override val isTorrent: Boolean,
        val pkgFactory: String?,
        val sources: List<Source>,
        val icon: Drawable?,
        val hasUpdate: Boolean = false,
        val isObsolete: Boolean = false,
        val isShared: Boolean,
        val signatureHash: String,
        override val repoUrl: String? = null,
        val author: String? = null,
    ) : Extension()

    @Immutable
    data class Available(
        override val name: String,
        override val pkgName: String,
        override val versionName: String,
        override val versionCode: Long,
        override val libVersion: Double,
        override val lang: String,
        override val isNsfw: Boolean,
        override val isTorrent: Boolean,
        val sources: List<AnimeSource>,
        val apkName: String,
        val iconUrl: String,
        override val repoUrl: String,
        val author: String? = null,
    ) : Extension() {

        @Immutable
        data class AnimeSource(
            val id: Long,
            val lang: String,
            val name: String,
            val baseUrl: String,
        ) {
            fun toStubSource(): StubSource {
                return StubSource(
                    id = this.id,
                    lang = this.lang,
                    name = this.name,
                )
            }
        }
    }

    @Immutable
    data class Untrusted(
        override val name: String,
        override val pkgName: String,
        override val versionName: String,
        override val versionCode: Long,
        override val libVersion: Double,
        val signatureHash: String,
        override val lang: String? = null,
        override val isNsfw: Boolean = false,
        override val isTorrent: Boolean = false,
        override val repoUrl: String? = null,
        val author: String? = null,
    ) : Extension()
}
