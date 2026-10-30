package mihon.domain.extensionrepo.service

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.okio.decodeFromBufferedSource
import kotlinx.serialization.protobuf.ProtoBuf
import logcat.LogPriority
import mihon.domain.extensionrepo.model.ExtensionRepo
import okio.BufferedSource
import okio.buffer
import okio.gzip
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import kotlin.coroutines.cancellation.CancellationException

class ExtensionRepoService(
    networkHelper: NetworkHelper,
    private val json: Json,
    // ANZ -->
    private val protoBuf: ProtoBuf,
    // ANZ <--
) {
    val client = networkHelper.client

    // ANZ -->
    suspend fun fetchRepoDetails(
        url: String,
    ): ExtensionRepo? {
        return withIOContext {
            fetchRepo(url)
        }
    }

    private suspend fun fetchRepo(url: String): ExtensionRepo? {
        var targetUrl = url.trim().removeSuffix("/")

        return try {
            val response = try {
                client.newCall(GET(targetUrl)).awaitSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!targetUrl.endsWith(".json") && !targetUrl.endsWith(".pb")) {
                    targetUrl = "$targetUrl/repo.json"
                    client.newCall(GET(targetUrl)).awaitSuccess()
                } else {
                    throw e
                }
            }

            var nextUrlToFetch: String? = null
            val repo: ExtensionRepo = response.body.source().decompressIfGzipped().use { source ->
                val firstByte = try {
                    source.peek().readByte()
                } catch (_: Exception) {
                    0.toByte()
                }

                when (firstByte) {
                    // '[' (0x5B) -> Legacy index.min.json array
                    0x5B.toByte() -> {
                        if (targetUrl.endsWith("/index.min.json")) {
                            val repoJsonUrl = targetUrl.replace("/index.min.json", "/repo.json")
                            val repoResponse = client.newCall(GET(repoJsonUrl)).awaitSuccess()
                            repoResponse.body.source().decompressIfGzipped().use { s ->
                                val parsed = parseJsonRepo(s, repoJsonUrl.removeSuffix("/repo.json"))
                                if (parsed.first != null) {
                                    nextUrlToFetch = parsed.first
                                }
                                parsed.second
                            }
                        } else {
                            val baseUrl = targetUrl.substringBeforeLast('/')
                            ExtensionRepo(
                                baseUrl = baseUrl,
                                name = baseUrl.substringAfterLast('/'),
                                shortName = null,
                                website = baseUrl,
                                signingKeyFingerprint = "NOFINGERPRINT",
                                isVisible = true,
                                isLegacy = true,
                            )
                        }
                    }
                    // '{' (0x7B) -> JSON Object
                    0x7B.toByte() -> {
                        val baseUrl = targetUrl.removeSuffix("/repo.json").removeSuffix("/index.json")
                        val parsed = parseJsonRepo(source, baseUrl)
                        if (parsed.first != null) {
                            nextUrlToFetch = parsed.first
                        }
                        parsed.second
                    }
                    // Otherwise Protobuf binary
                    else -> {
                        val bytes = source.readByteArray()
                        val store = protoBuf.decodeFromByteArray<NetworkExtensionStore>(bytes)
                        store.toExtensionRepo(targetUrl)
                    }
                }
            }

            if (nextUrlToFetch != null) {
                return fetchRepo(nextUrlToFetch!!)
            }

            repo
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to fetch repo details from $url" }
            null
        }
    }

    private fun parseJsonRepo(source: BufferedSource, baseUrl: String): Pair<String?, ExtensionRepo> {
        val peeked = source.peek()
        try {
            val meta = json.decodeFromBufferedSource<ExtensionRepoMetaDto>(peeked)
            if (meta.indexV2 != null) {
                return meta.indexV2 to meta.toExtensionRepo(baseUrl)
            }
            return null to meta.toExtensionRepo(baseUrl)
        } catch (_: Exception) {
            // Try NetworkExtensionStore (JSON)
            try {
                val store = json.decodeFromBufferedSource<NetworkExtensionStore>(source.peek())
                return null to store.toExtensionRepo(baseUrl)
            } catch (_: Exception) {
                val legacy = json.decodeFromBufferedSource<ExtensionRepoDto>(source)
                return null to legacy.toExtensionRepo(baseUrl)
            }
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
}
