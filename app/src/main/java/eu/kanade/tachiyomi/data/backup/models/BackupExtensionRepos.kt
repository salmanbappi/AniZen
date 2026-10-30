package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import mihon.domain.extensionrepo.model.ExtensionRepo

@Serializable
class BackupExtensionRepos(
    @ProtoNumber(1) var baseUrl: String,
    @ProtoNumber(2) var name: String,
    @ProtoNumber(3) var shortName: String?,
    @ProtoNumber(4) var website: String,
    @ProtoNumber(5) var signingKeyFingerprint: String,
    @ProtoNumber(100) var isVisible: Boolean = true,
    @ProtoNumber(101) var author: String? = null,
    @ProtoNumber(102) var discord: String? = null,
    @ProtoNumber(103) var icon: String? = null,
    // ANZ -->
    @ProtoNumber(104) var isLegacy: Boolean = true,
    @ProtoNumber(105) var extensionListUrl: String? = null,
    // ANZ <--
)

val backupExtensionReposMapper = { repo: ExtensionRepo ->
    BackupExtensionRepos(
        baseUrl = repo.baseUrl,
        name = repo.name,
        shortName = repo.shortName,
        website = repo.website,
        signingKeyFingerprint = repo.signingKeyFingerprint,
        isVisible = repo.isVisible,
        author = repo.author,
        discord = repo.discord,
        icon = repo.icon,
        // ANZ -->
        isLegacy = repo.isLegacy,
        extensionListUrl = repo.extensionListUrl,
        // ANZ <--
    )
}
