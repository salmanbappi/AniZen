package mihon.domain.extensionrepo.service

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import mihon.domain.extensionrepo.model.ExtensionRepo

@Serializable
data class ExtensionRepoMetaDto(
    val meta: ExtensionRepoDto,
)

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
    )
}

fun ExtensionRepoMetaDto.toExtensionRepo(
    baseUrl: String,
): ExtensionRepo {
    return meta.toExtensionRepo(baseUrl)
}