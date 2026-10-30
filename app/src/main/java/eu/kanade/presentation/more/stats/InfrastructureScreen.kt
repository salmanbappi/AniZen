package eu.kanade.presentation.more.stats

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.tachiyomi.network.model.*
import eu.kanade.tachiyomi.ui.stats.InfrastructureState
import kotlinx.coroutines.launch
import tachiyomi.presentation.core.util.secondaryItemAlpha

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfrastructureScreen(
    state: InfrastructureState,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    contentPadding: PaddingValues,
    snackbarHostState: SnackbarHostState,
) {
    if (state is InfrastructureState.Loading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val report = (state as InfrastructureState.Success).report
    val pagerState = rememberPagerState(pageCount = { 2 })
    val scope = rememberCoroutineScope()
    val refreshState = rememberPullToRefreshState()

    Column(modifier = Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
        PrimaryTabRow(
            selectedTabIndex = pagerState.currentPage,
            containerColor = Color.Transparent,
            divider = {}
        ) {
            listOf("GLOBAL CDN", "SYSTEM LOGS").forEachIndexed { index, title ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = { scope.launch { pagerState.animateScrollToPage(index) } }
                ) {
                    Text(title, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            state = refreshState,
            modifier = Modifier.fillMaxSize()
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1
            ) { pageIndex ->
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
                    contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 16.dp)
                ) {
                    when (pageIndex) {
                        0 -> {
                            item { GlobalTelemetryHeader(report.globalMetrics) }
                            item { InfrastructureHealthBoard(report.nodes) }
                            items(
                                items = report.nodes.distinctBy { it.name },
                                key = { "node-${it.name}" }
                            ) { node ->
                                SourceNodeAuditCard(node)
                            }
                        }
                        1 -> {
                            if (report.systemLogs.isEmpty()) {
                                item { EmptyState("System is nominal. No alerts generated.") }
                            }
                            itemsIndexed(
                                items = report.systemLogs,
                                key = { index, log -> "log-${log.timestamp}-$index" }
                            ) { _, log ->
                                LogEntryRow(log)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text = message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.secondaryItemAlpha())
    }
}

@Composable
private fun GlobalTelemetryHeader(metrics: GlobalNetworkMetrics) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Analytics, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Text("NETWORK STATUS", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
            }
            Spacer(modifier = Modifier.height(24.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                MetricSquare("ACTIVE SOURCES", "${metrics.activeNodeCount}", Color(0xFF4CAF50))
                MetricSquare("AVG LATENCY", "${metrics.avgLatency}ms", Color(0xFFFFC107))
            }
        }
    }
}

@Composable
private fun MetricSquare(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Black,
            color = color,
            fontFamily = FontFamily.Monospace
        )
        Text(text = label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.secondaryItemAlpha(), fontSize = 8.sp)
    }
}

@Composable
private fun InfrastructureHealthBoard(nodes: List<SourceNode>) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "SOURCE STATUS GRID",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                nodes.forEach { node ->
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                when (node.status) {
                                    NodeStatus.OPERATIONAL -> Color(0xFF4CAF50)
                                    NodeStatus.DEGRADED -> Color(0xFFFFC107)
                                    else -> Color(0xFFF44336)
                                }
                            )
                    )
                }
            }
        }
    }
}

@Composable
private fun LogEntryRow(log: SystemLogEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = "[${log.level.name}]",
            color = when(log.level) {
                LogLevel.ERROR -> Color.Red
                LogLevel.WARN -> Color.Yellow
                else -> Color.Green
            },
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            fontSize = 9.sp,
            modifier = Modifier.width(50.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(text = log.source, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Text(text = log.message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.secondaryItemAlpha())
        }
    }
}

@Composable
private fun SourceNodeAuditCard(node: SourceNode) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        border = if (expanded) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)) else null
    ) {
        Column(modifier = Modifier.clickable { expanded = !expanded }.padding(16.dp).animateContentSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusIndicator(node.status)
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = node.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = node.network.topology,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        if (node.capabilities.isApi) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = Color(0xFF4CAF50).copy(alpha = 0.1f)
                            ) {
                                Text(
                                    text = "API",
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF4CAF50),
                                    fontWeight = FontWeight.Black
                                )
                            }
                        }
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = if (node.version == "Scanning...") "..." else "${node.network.latency}ms",
                        style = MaterialTheme.typography.titleSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = if (node.network.latency < 100 && node.network.latency > 0) Color(0xFF4CAF50) else Color.Unspecified
                    )
                    Text("LATENCY", style = MaterialTheme.typography.labelSmall, fontSize = 7.sp, modifier = Modifier.secondaryItemAlpha())
                }
            }

            if (expanded) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp).alpha(0.1f))

                Text("TECHNICAL DETAILS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.ExtraBold)
                Spacer(modifier = Modifier.height(8.dp))

                InfoRow("Package ID", node.pkgName)
                InfoRow("IPv4 Address", node.network.ipAddress)
                InfoRow("Encryption", "Encrypted via ${node.network.tlsVersion}")

                Spacer(modifier = Modifier.height(16.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CapabilityBadge("API BASE", node.capabilities.isApi)
                    CapabilityBadge("LATEST UPDATE", node.capabilities.latestSupport)
                    CapabilityBadge("SEARCH SUPPORT", node.capabilities.searchSupport)
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.secondaryItemAlpha())
        Text(text = value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun StatusIndicator(status: NodeStatus) {
    val color = when (status) {
        NodeStatus.OPERATIONAL -> Color(0xFF4CAF50)
        NodeStatus.DEGRADED -> Color(0xFFFFC107)
        else -> Color(0xFFF44336)
    }
    Box(contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.2f))
        )
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
    }
}

@Composable
private fun CapabilityBadge(label: String, active: Boolean) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = if (active) Color(0xFF4CAF50).copy(alpha = 0.1f) else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.alpha(if (active) 1f else 0.5f)
    ) {
        Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (active) Icon(Icons.Outlined.Check, null, modifier = Modifier.size(10.dp), tint = Color(0xFF4CAF50))
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = FontFamily.Monospace,
                color = if (active) Color(0xFF4CAF50) else Color.Unspecified
            )
        }
    }
}
