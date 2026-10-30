package mihon.domain.extensionrepo.service

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.protobuf.ProtoNumber
import mihon.domain.extensionrepo.model.ExtensionRepo

// ANZ -->
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ExtensionRepoMetaDto(
    @SerialName("index_v2")
    val indexV2: String? = null,
    val meta: ExtensionRepoDto,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class NetworkExtensionStore(
    @ProtoNumber(1) val name: String,
    @ProtoNumber(2) val badgeLabel: String,
    @ProtoNumber(3) val signingKey: String,
    @ProtoNumber(4) val contact: Contact = Contact(),
    @ProtoNumber(101) val extensionList: ExtensionList? = null,
    @ProtoNumber(102) val extensionListUrl: String? = null,
) {
    @Serializable
    data class Contact(
        @ProtoNumber(1) val website: String = "",
        @ProtoNumber(2) val discord: String? = null,
    )

    @Serializable
    data class ExtensionList(@ProtoNumber(1) val extensions: List<Extension> = emptyList())

    @Serializable
    data class Extension(
        @ProtoNumber(1) val name: String,
        @ProtoNumber(2) val packageName: String,
        @ProtoNumber(3) val resources: Resources,
        @ProtoNumber(4) val extensionLib: String = "16.0",
        @ProtoNumber(5) val versionCode: Long,
        @ProtoNumber(6) val versionName: String,
        @ProtoNumber(7) val contentWarning: ContentWarning = ContentWarning.UNSPECIFIED,
        @ProtoNumber(8) val isTorrent: Boolean = false,
        @ProtoNumber(9) val sources: List<Source> = emptyList(),
    )

    @Serializable
    data class Resources(
        @ProtoNumber(1) val apkUrl: String,
        @ProtoNumber(2) val iconUrl: String,
    )

    @Serializable
    data class Source(
        @ProtoNumber(1) val id: Long,
        @ProtoNumber(2) val name: String,
        @ProtoNumber(3) val language: String,
        @ProtoNumber(4) val homeUrl: String = "",
        @ProtoNumber(5) val mirrorUrls: List<String> = emptyList(),
        @ProtoNumber(6) val message: String? = null,
    )

    @Suppress("Unused")
    enum class ContentWarning {
        @ProtoNumber(0)
        @JsonNames("CONTENT_WARNING_UNSPECIFIED")
        UNSPECIFIED,

        @ProtoNumber(1)
        @JsonNames("CONTENT_WARNING_SAFE")
        SAFE,

        @ProtoNumber(2)
        @JsonNames("CONTENT_WARNING_MIXED")
        MIXED,

        @ProtoNumber(3)
        @JsonNames("CONTENT_WARNING_NSFW")
        NSFW,
    }

    fun toExtensionRepo(indexUrl: String): ExtensionRepo {
        return ExtensionRepo(
            baseUrl = indexUrl,
            name = name,
            shortName = badgeLabel,
            website = contact.website,
            signingKeyFingerprint = signingKey.trim().lowercase().padStart(64, '0'),
            isVisible = true,
            author = badgeLabel,
            discord = contact.discord,
            icon = null,
            isLegacy = false,
            extensionListUrl = extensionListUrl,
        )
    }
}
// ANZ <--

@Serializable
data class ExtensionRepoDto(
    val name: String,
    val shortName: String? = null,
    val website: String = "",
    @SerialName("signingKeyFingerprint")
    val signingKeyFingerprint: String? = null,
    @SerialName("signingKey")
    val signingKey: String? = null,
    val author: String? = null,
    val discord: String? = null,
    val icon: String? = null,
) {
    fun getFingerprint(): String {
        return (signingKeyFingerprint ?: signingKey ?: "")
            .trim()
            .lowercase()
            .padStart(64, '0')
    }
}

fun ExtensionRepoDto.toExtensionRepo(
    baseUrl: String,
): ExtensionRepo {
    return ExtensionRepo(
        baseUrl = baseUrl,
        name = name,
        shortName = shortName,
        website = website,
        signingKeyFingerprint = getFingerprint(),
        isVisible = true,
        author = author,
        discord = discord,
        icon = icon,
        // ANZ -->
        isLegacy = true,
        // ANZ <--
    )
}

fun ExtensionRepoMetaDto.toExtensionRepo(
    baseUrl: String,
): ExtensionRepo {
    // ANZ -->
    return meta.toExtensionRepo(baseUrl).copy(isLegacy = true)
    // ANZ <--
}
