package eu.kanade.tachiyomi.torrentServer

import eu.kanade.tachiyomi.torrentServer.model.FileStat
import eu.kanade.tachiyomi.torrentServer.model.Torrent
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URLEncoder

object TorrentServerUtils {
    private val preferences: TorrentServerPreferences by injectLazy()

    val hostUrl = "http://${getLocalIpAddress()}:${preferences.port().get()}"

    // Is necessary separate the trackers by comma because is hardcoded in go-torrent-server
    private val animeTrackers = preferences.trackers().get().split("\n").joinToString(",\n")

    fun setTrackersList() {
        torrServer.TorrServer.addTrackers(animeTrackers)
    }

    fun getTorrentPlayLink(torr: Torrent, index: Int): String {
        val file = findFile(torr, index)
        val name = file?.let { File(it.path).name } ?: torr.title
        return "$hostUrl/stream/${name.urlEncode()}?link=${torr.hash}&index=$index&play"
    }

    /**
     * Whether [url] points at the embedded torrent server (e.g. a `/stream` play link).
     * Such URLs need patient timeouts: the server only produces data once peers connect.
     */
    fun isTorrentServerUrl(url: String): Boolean {
        if (!url.startsWith("http", ignoreCase = true)) return false
        return url == hostUrl || url.startsWith("$hostUrl/")
    }

    private fun findFile(torrent: Torrent, index: Int): FileStat? {
        torrent.file_stats?.forEach {
            if (it.id == index) {
                return it
            }
        }
        return null
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces().toList()
            val ipAddresses = mutableListOf<Pair<String, String>>()
            for (intf in interfaces) {
                val name = intf.name.lowercase()
                val addresses = intf.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress
                        if (ip != null) {
                            ipAddresses.add(name to ip)
                        }
                    }
                }
            }

            val wifiIp = ipAddresses.find { (name, _) ->
                name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("eth")
            }?.second
            if (wifiIp != null) return wifiIp

            val nonMobileIp = ipAddresses.find { (name, _) ->
                !name.startsWith("rmnet") &&
                    !name.startsWith("ccmni") &&
                    !name.startsWith("pdp") &&
                    !name.startsWith("tun") &&
                    !name.startsWith("tap") &&
                    !name.startsWith("p2p")
            }?.second
            if (nonMobileIp != null) return nonMobileIp

            if (ipAddresses.isNotEmpty()) {
                return ipAddresses.first().second
            }
        } catch (ex: Exception) {
            logcat(LogPriority.DEBUG) { "Error getting local IP address" }
        }
        return "127.0.0.1"
    }

    private fun String.urlEncode(): String = URLEncoder.encode(this, "utf8")
}
