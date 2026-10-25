package eu.kanade.tachiyomi.ui.stats

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.network.model.*
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Request
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.source.service.SourceHealthCache
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.localanime.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

class InfrastructureScreenModel(
    private val context: Application = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val networkHelper: NetworkHelper = Injekt.get(),
    private val sourcePreferences: SourcePreferences = Injekt.get(),
) : StateScreenModel<InfrastructureState>(InfrastructureState.Loading) {

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _events = Channel<Event>(Int.MAX_VALUE)
    val events = _events.receiveAsFlow()

    private val semaphore = Semaphore(5)

    init {
        runDiagnostics()
    }

    fun copyReportToClipboard() {
        val state = state.value
        if (state !is InfrastructureState.Success) return

        val report = state.report
        val sb = StringBuilder()
        sb.append("--- ANIZEN EXTENSION HEALTH REPORT ---\n")
        sb.append("Timestamp: ${java.time.Instant.now()}\n")
        sb.append("Avg Latency: ${report.globalMetrics.avgLatency}ms\n")
        sb.append("Active Sources: ${report.globalMetrics.activeNodeCount}/${report.nodes.size}\n\n")

        sb.append("--- SOURCE STATUS ---\n")
        report.nodes.forEach { node ->
            sb.append("${node.name} [${node.status}]: ${node.network.latency}ms (${node.network.topology})\n")
            sb.append("  IP: ${node.network.ipAddress}, TLS: ${node.network.tlsVersion}\n")
        }

        sb.append("\n--- SYSTEM LOGS ---\n")
        report.systemLogs.forEach { log ->
            sb.append("[${log.level.name}] ${log.source}: ${log.message}\n")
        }

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("AniZen Infra Report", sb.toString())
        clipboard.setPrimaryClip(clip)

        screenModelScope.launchIO {
            _events.send(Event.ReportCopied)
        }
    }

    fun runDiagnostics() {
        if (_isRefreshing.value) return
        _isRefreshing.value = true

        screenModelScope.launchIO {
            val disabledSourceIds = sourcePreferences.disabledSources().get()
            val sources = sourceManager.getOnlineSources()
                .filter { !it.isLocal() }
                .filter { it.id.toString() !in disabledSourceIds }

            // Populate state with ALL sources immediately
            val initialNodes = sources.map { source ->
                createPlaceholderNode(source)
            }

            mutableState.update {
                InfrastructureState.Success(InfrastructureReport(initialNodes, generateEmptyMetrics(initialNodes.size), emptyList()))
            }

            // Parallel Update
            val nodes = sources.map { source ->
                async {
                    semaphore.withPermit {
                        probeNode(source).also { finishedNode ->
                            updateNodeInState(finishedNode)
                            SourceHealthCache.updateStatus(source.id, finishedNode.status, finishedNode.network.latency)
                        }
                    }
                }
            }.awaitAll()

            val sortedNodes = nodes.sortedWith(compareByDescending<SourceNode> { it.status == NodeStatus.OPERATIONAL }
                .thenBy { it.network.latency })

            val logs = nodes.filter { it.status != NodeStatus.OPERATIONAL }.map { node ->
                SystemLogEntry(
                    timestamp = System.currentTimeMillis(),
                    level = if (node.status == NodeStatus.OFFLINE) LogLevel.ERROR else LogLevel.WARN,
                    source = node.name,
                    message = "Alert: ${node.status}. Response: ${node.network.latency}ms"
                )
            }

            val metrics = GlobalNetworkMetrics(
                bdixSaturation = 0,
                totalDataConsumed = 0,
                avgLatency = if (nodes.isNotEmpty()) nodes.filter { it.status == NodeStatus.OPERATIONAL }.map { it.network.latency }.average().toInt() else 0,
                activeNodeCount = nodes.count { it.status == NodeStatus.OPERATIONAL }
            )

            mutableState.update {
                InfrastructureState.Success(InfrastructureReport(sortedNodes, metrics, logs))
            }
            _isRefreshing.value = false
        }
    }

    private fun createPlaceholderNode(source: HttpSource): SourceNode {
        return SourceNode(
            name = source.name,
            pkgName = source::class.java.name.substringBeforeLast("."),
            version = "Scanning...",
            status = NodeStatus.OPERATIONAL,
            network = NetworkDiagnostics(0, "Global CDN", "...", "...", false),
            capabilities = SourceCapabilities(detectIsApi(source), false, source.supportsLatest, true),
            uptimeScore = 1.0
        )
    }

    private fun updateNodeInState(node: SourceNode) {
        mutableState.update { state ->
            if (state is InfrastructureState.Success) {
                val updatedNodes = state.report.nodes.map { if (it.name == node.name) node else it }
                state.copy(report = state.report.copy(nodes = updatedNodes))
            } else state
        }
    }

    private fun generateEmptyMetrics(count: Int) = GlobalNetworkMetrics(0, 0, 0, count)

    private fun detectIsApi(source: HttpSource): Boolean {
        val name = source.name.lowercase()
        val className = source::class.java.simpleName.lowercase()
        val pkg = source::class.java.name.lowercase()

        // Basic name heuristics
        val nameMatch = className.contains("api") ||
               className.contains("json") ||
               className.contains("graphql") ||
               name.contains("api") ||
               name.contains("json") ||
               pkg.contains("api") ||
               pkg.contains("json")

        if (nameMatch) return true

        // Advanced detection: Check if source has a 'json' field or uses serialization
        return try {
            val isParsed = source::class.java.name.contains("Parsed")
            if (isParsed) return false

            source::class.java.declaredFields.any {
                it.type.name.contains("kotlinx.serialization.json.Json") ||
                it.name.contains("json")
            } || source::class.java.methods.any {
                it.name.contains("parseAs") || it.returnType.name.contains("Json")
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun detectSearchSupport(source: HttpSource): Boolean {
        // 1. If it has filters, it likely supports search
        try {
            if (source.getFilterList().isNotEmpty()) return true
        } catch (e: Exception) {}

        // 2. Check if searchAnimeRequest is overridden from AnimeHttpSource
        // Since it's abstract in base, we check if it's implemented in a non-abstract way
        // that doesn't just throw UnsupportedOperationException (heuristic)
        return try {
            val method = source::class.java.getMethod("searchAnimeRequest", Int::class.java, String::class.java, eu.kanade.tachiyomi.animesource.model.AnimeFilterList::class.java)
            val declaringClass = method.declaringClass.name
            !declaringClass.contains("AnimeHttpSource") && !declaringClass.contains("HttpSource")
        } catch (e: Exception) {
            true // Fallback to true if we can't tell
        }
    }

    private suspend fun probeNode(source: HttpSource): SourceNode {
        var latency = 999
        var resolved = false
        var ip = "Scanning"
        var tls = "Unknown"
        var status = NodeStatus.OFFLINE

        try {
            val probeClient = source.client.newBuilder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()

            val request = Request.Builder()
                .url(source.baseUrl)
                .headers(source.headers)
                .header("X-AniZen-Probe", "CommandCenter-v2.2")
                .build()

            measureTimeMillis {
                probeClient.newCall(request).execute().use { response ->
                    resolved = true
                    tls = response.handshake?.tlsVersion?.javaName ?: "v1.3"
                    if (response.code in 200..499) {
                        status = NodeStatus.OPERATIONAL
                    } else if (response.code >= 500) {
                        status = NodeStatus.DEGRADED
                    } else {
                        status = NodeStatus.OPERATIONAL
                    }
                }
            }.let { latency = it.toInt() }

            val domain = source.baseUrl.substringAfter("://").substringBefore("/")
            ip = try { InetAddress.getByName(domain).hostAddress ?: "0.0.0.0" } catch(e: Exception) { "DNS Fail" }

        } catch (e: Exception) {
            status = NodeStatus.OFFLINE
            ip = "Network Err"
        }

        return SourceNode(
            name = source.name,
            pkgName = source::class.java.name.substringBeforeLast("."),
            version = "PROBED",
            status = if (status == NodeStatus.OPERATIONAL && latency > 2500) NodeStatus.DEGRADED else status,
            network = NetworkDiagnostics(
                latency = latency,
                topology = "Global CDN",
                ipAddress = ip,
                tlsVersion = tls,
                dnsResolved = resolved
            ),
            capabilities = SourceCapabilities(
                isApi = detectIsApi(source),
                mtSupport = false,
                latestSupport = source.supportsLatest,
                searchSupport = detectSearchSupport(source)
            ),
            uptimeScore = if (status == NodeStatus.OPERATIONAL) 1.0 else 0.0
        )
    }

    sealed interface Event {
        data object ReportCopied : Event
    }
}

sealed interface InfrastructureState {
    data object Loading : InfrastructureState
    data class Success(val report: InfrastructureReport) : InfrastructureState
}
