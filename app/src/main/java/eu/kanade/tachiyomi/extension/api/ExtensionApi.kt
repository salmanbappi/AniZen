package eu.kanade.tachiyomi.extension.api

import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.protobuf.ProtoBuf
import logcat.LogPriority
import mihon.domain.extensionrepo.interactor.GetExtensionRepo
import mihon.domain.extensionrepo.model.ExtensionRepo
import mihon.domain.extensionrepo.model.normalizeSigningKey
import mihon.domain.extensionrepo.service.NetworkExtensionStore
import okhttp3.CacheControl
import okio.BufferedSource
import okio.buffer
import okio.gzip
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.injectLazy
import kotlin.coroutines.cancellation.CancellationException

internal class ExtensionApi {

    private val networkService: NetworkHelper by injectLazy()
    private val getExtensionRepo: GetExtensionRepo by injectLazy()
    private val json: Json by injectLazy()

    // ANZ -->
    private val protoBuf: ProtoBuf by injectLazy()
    // ANZ <--

    suspend fun findExtensions(forceNetwork: Boolean = false): List<Extension.Available> {
        return withIOContext {
            supervisorScope {
                getExtensionRepo.getAll()
                    .map { async { getExtensions(it, forceNetwork) } }
                    .awaitAll()
                    .flatten()
            }
        }
    }

    // ANZ -->
    private suspend fun getExtensions(
        extRepo: ExtensionRepo,
        forceNetwork: Boolean,
    ): List<Extension.Available> {
        val repoBaseUrl = extRepo.baseUrl
        return try {
            if (!extRepo.isLegacy) {
                getExtensionsFromStore(extRepo, forceNetwork)
            } else {
                getExtensionsFromLegacyRepo(extRepo, forceNetwork)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Failed to get extensions from $repoBaseUrl" }
            emptyList()
        }
    }

    private fun buildGetRequest(url: String, forceNetwork: Boolean) = if (forceNetwork) {
        GET(url, cache = CacheControl.FORCE_NETWORK)
    } else {
        GET(url)
    }

    private suspend fun getExtensionsFromStore(
        store: ExtensionRepo,
        forceNetwork: Boolean,
    ): List<Extension.Available> {
        val targetUrl = store.extensionListUrl ?: store.baseUrl
        val response = networkService.client.newCall(buildGetRequest(targetUrl, forceNetwork)).awaitSuccess()
        val author = store.shortName ?: store.author ?: store.name
        return response.body.source().decompressIfGzipped().use { source ->
            val firstByte = try {
                source.peek().readByte()
            } catch (_: Exception) {
                0.toByte()
            }
            val extensionList = if (store.extensionListUrl != null) {
                when (firstByte) {
                    0x7B.toByte() -> json.decodeFromString<NetworkExtensionStore.ExtensionList>(source.readUtf8())
                    else -> protoBuf.decodeFromByteArray<NetworkExtensionStore.ExtensionList>(source.readByteArray())
                }
            } else {
                when (firstByte) {
                    0x7B.toByte() -> json.decodeFromString<NetworkExtensionStore>(source.readUtf8()).extensionList
                    else -> protoBuf.decodeFromByteArray<NetworkExtensionStore>(source.readByteArray()).extensionList
                }
            }
            extensionList?.extensions?.map { ext ->
                val lang = ext.sources.map { it.language }.toSet()
                val contentWarning = when (ext.contentWarning) {
                    NetworkExtensionStore.ContentWarning.SAFE -> eu.kanade.tachiyomi.extension.model.ContentWarning.SAFE
                    NetworkExtensionStore.ContentWarning.MIXED -> eu.kanade.tachiyomi.extension.model.ContentWarning.MIXED
                    NetworkExtensionStore.ContentWarning.NSFW -> eu.kanade.tachiyomi.extension.model.ContentWarning.NSFW
                    else -> eu.kanade.tachiyomi.extension.model.ContentWarning.UNSPECIFIED
                }
                Extension.Available(
                    name = ext.name.substringAfter("Aniyomi: ").substringAfter("AniZen: "),
                    pkgName = ext.packageName,
                    versionName = ext.versionName,
                    versionCode = ext.versionCode,
                    libVersion = ext.extensionLib.toDoubleOrNull() ?: 16.0,
                    lang = if (lang.size == 1) lang.first() else "all",
                    isNsfw = contentWarning == eu.kanade.tachiyomi.extension.model.ContentWarning.NSFW || contentWarning == eu.kanade.tachiyomi.extension.model.ContentWarning.MIXED,
                    isTorrent = ext.isTorrent,
                    sources = ext.sources.map { s ->
                        Extension.Available.AnimeSource(
                            id = s.id,
                            lang = s.language,
                            name = s.name,
                            baseUrl = s.homeUrl,
                        )
                    },
                    apkName = ext.resources.apkUrl,
                    iconUrl = ext.resources.iconUrl,
                    repoUrl = store.baseUrl,
                    signingKeyFingerprint = normalizeSigningKey(store.signingKeyFingerprint),
                    author = author,
                    contentWarning = contentWarning,
                )
            }.orEmpty()
        }
    }

    private suspend fun getExtensionsFromLegacyRepo(
        extRepo: ExtensionRepo,
        forceNetwork: Boolean,
    ): List<Extension.Available> {
        val repoBaseUrl = extRepo.baseUrl
        val normalizedRepoUrl = repoBaseUrl.substringBefore("/index.min.json").removeSuffix("/")
        val response = networkService.client
            .newCall(buildGetRequest("$normalizedRepoUrl/index.min.json", forceNetwork))
            .awaitSuccess()

        val bodyString = response.body.source().decompressIfGzipped().use { it.readUtf8() }

        val repoHostAuthorRegex = """^https://(?:raw\.githubusercontent\.com|codeberg\.org|gitlab\.com)/([^/]+)/.*""".toRegex()
        val author = extRepo.author
            ?: repoHostAuthorRegex.find(repoBaseUrl)?.let {
                val (user) = it.destructured
                "@$user"
            } ?: extRepo.shortName ?: extRepo.name

        return with(json) {
            decodeFromString<List<ExtensionJsonObject>>(bodyString)
                .toExtensions(repoBaseUrl, extRepo.signingKeyFingerprint, author)
        }
    }
    // ANZ <--

    private fun BufferedSource.decompressIfGzipped(): BufferedSource {
        val isGzip = peek().use { peeked ->
            try {
                peeked.readShort().toInt() == 0x1f8b
            } catch (_: Exception) {
                false
            }
        }

        return if (isGzip) gzip().buffer() else this
    }

    private fun List<ExtensionJsonObject>.toExtensions(
        repoUrl: String,
        signingKeyFingerprint: String,
        author: String,
    ): List<Extension.Available> {
        val normalizedRepoUrl = repoUrl.substringBefore("/index.min.json").removeSuffix("/")
        val normalizedKey = normalizeSigningKey(signingKeyFingerprint)
        return this
            .filter {
                // ANZ -->
                val libVersion = it.extractLibVersion()
                libVersion >= ExtensionLoader.LIB_VERSION_MIN && libVersion < ExtensionLoader.LIB_VERSION_MAX + 1.0
                // ANZ <--
            }
            .map {
                val contentWarning = when (it.contentWarning) {
                    1 -> eu.kanade.tachiyomi.extension.model.ContentWarning.MIXED
                    2 -> eu.kanade.tachiyomi.extension.model.ContentWarning.NSFW
                    0 -> if (it.nsfw == 1) eu.kanade.tachiyomi.extension.model.ContentWarning.NSFW else eu.kanade.tachiyomi.extension.model.ContentWarning.SAFE
                    else -> if (it.nsfw == 1) eu.kanade.tachiyomi.extension.model.ContentWarning.NSFW else eu.kanade.tachiyomi.extension.model.ContentWarning.UNSPECIFIED
                }
                Extension.Available(
                    name = it.name.substringAfter("Aniyomi: "),
                    pkgName = it.pkg,
                    versionName = it.version,
                    versionCode = it.code,
                    libVersion = it.extractLibVersion(),
                    lang = it.lang,
                    isNsfw = contentWarning == eu.kanade.tachiyomi.extension.model.ContentWarning.NSFW || contentWarning == eu.kanade.tachiyomi.extension.model.ContentWarning.MIXED,
                    isTorrent = it.torrent == 1 || it.isTorrent,
                    sources = it.sources?.map(extensionSourceMapper).orEmpty(),
                    apkName = it.apk,
                    iconUrl = "$normalizedRepoUrl/icon/${it.pkg}.png",
                    repoUrl = normalizedRepoUrl,
                    signingKeyFingerprint = normalizedKey,
                    author = author,
                    contentWarning = contentWarning,
                )
            }
    }

    fun getApkUrl(extension: Extension.Available): String {
        return if (extension.apkName.startsWith("http")) {
            extension.apkName
        } else {
            val baseUrl = extension.repoUrl.substringBefore("/index.min.json").removeSuffix("/")
            "$baseUrl/apk/${extension.apkName}"
        }
    }

    private fun ExtensionJsonObject.extractLibVersion(): Double {
        return extensionLib?.doubleOrNull
            ?: libVersion?.doubleOrNull
            ?: extensionLib?.contentOrNull?.toDoubleOrNull()
            ?: libVersion?.contentOrNull?.toDoubleOrNull()
            ?: version.substringBeforeLast('.').toDoubleOrNull()
            ?: 0.0
    }
}

@Serializable
private data class ExtensionJsonObject(
    val name: String,
    val pkg: String,
    val apk: String,
    val lang: String,
    val code: Long,
    val version: String,
    val nsfw: Int = 0,
    val contentWarning: Int = 0,
    val torrent: Int = 0,
    val isTorrent: Boolean = false,
    val extensionLib: JsonPrimitive? = null,
    val libVersion: JsonPrimitive? = null,
    val sources: List<ExtensionSourceJsonObject>? = null,
)

@Serializable
private data class ExtensionSourceJsonObject(
    val id: Long,
    val lang: String,
    val name: String,
    val baseUrl: String,
)

private val extensionSourceMapper: (ExtensionSourceJsonObject) -> Extension.Available.AnimeSource = {
    Extension.Available.AnimeSource(
        id = it.id,
        lang = it.lang,
        name = it.name,
        baseUrl = it.baseUrl,
    )
}
