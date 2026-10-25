package eu.kanade.tachiyomi.network.model

data class InfrastructureReport(
    val nodes: List<SourceNode>,
    val globalMetrics: GlobalNetworkMetrics,
    val systemLogs: List<SystemLogEntry>,
)

data class SourceNode(
    val name: String,
    val pkgName: String,
    val version: String,
    val status: NodeStatus,
    val network: NetworkDiagnostics,
    val capabilities: SourceCapabilities,
    val uptimeScore: Double, // 0.0 to 1.0
)

enum class NodeStatus {
    OPERATIONAL,
    DEGRADED,
    CRITICAL,
    OFFLINE,
}

data class NetworkDiagnostics(
    val latency: Int, // ms
    val topology: String, // BDIX, PEER, Global CDN
    val ipAddress: String,
    val tlsVersion: String,
    val dnsResolved: Boolean,
)

data class SourceCapabilities(
    val isApi: Boolean,
    val mtSupport: Boolean,
    val latestSupport: Boolean,
    val searchSupport: Boolean,
)

data class GlobalNetworkMetrics(
    val bdixSaturation: Int, // %
    val totalDataConsumed: Long, // bytes
    val avgLatency: Int,
    val activeNodeCount: Int,
)

data class SystemLogEntry(
    val timestamp: Long,
    val level: LogLevel,
    val source: String,
    val message: String,
)

enum class LogLevel {
    INFO,
    WARN,
    ERROR,
}

data class ExtensionHealth(
    val name: String,
    val isOnline: Boolean,
    val latency: Int,
    val type: String, // BDIX, API, SCRAPE
    val issue: String? = null,
)
