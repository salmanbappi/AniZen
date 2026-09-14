package eu.kanade.tachiyomi.util

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.net.Uri
import fi.iki.elonen.NanoHTTPD
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.net.URLDecoder

class LocalHttpServer(
    port: String,
    private val contentResolver: ContentResolver,
) : NanoHTTPD(port.toInt()) {

    @SuppressLint("Recycle")
    override fun serve(session: IHTTPSession): Response {
        val path = session.uri ?: "/"
        return when {
            path.startsWith("/proxy") -> serveProxy(session)
            else -> serveContentUri(session)
        }
    }

    /**
     * Proxy endpoint for Chromecast.
     * The Cast default receiver cannot send custom HTTP headers, so video sources
     * requiring Referer/User-Agent/cookies fail with a network error.
     * This proxies the request through the phone with the correct headers.
     *
     * Usage: GET /proxy?url=<encoded_url>&headers=<encoded_headers>
     * Headers format: key1=value1&key2=value2 (URL-encoded)
     */
    private fun serveProxy(session: IHTTPSession): Response {
        val params = session.parameters
        val targetUrl = params["url"]?.get(0) ?: return newFixedLengthResponse(
            Response.Status.BAD_REQUEST,
            "text/plain",
            "Missing url parameter",
        )

        return try {
            val connection = URL(targetUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true

            // Parse and apply forwarded headers
            val headersParam = params["headers"]?.get(0)
            if (!headersParam.isNullOrBlank()) {
                val decoded = URLDecoder.decode(headersParam, "UTF-8")
                decoded.split("\n").forEach { line ->
                    val parts = line.split(":", limit = 2)
                    if (parts.size == 2) {
                        connection.setRequestProperty(parts[0].trim(), parts[1].trim())
                    }
                }
            }

            // Forward Range header from the Cast receiver
            val rangeHeader = session.headers["range"]
            if (rangeHeader != null) {
                connection.setRequestProperty("Range", rangeHeader)
            }

            val responseCode = connection.responseCode
            val contentType = connection.contentType ?: "application/octet-stream"
            val inputStream = if (responseCode in 200..399) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            if (inputStream == null) {
                return newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "text/plain",
                    "No response from upstream",
                )
            }

            val contentLength = connection.contentLengthLong

            val status = when (responseCode) {
                206 -> Response.Status.PARTIAL_CONTENT
                in 200..299 -> Response.Status.OK
                else -> Response.Status.lookup(responseCode) ?: Response.Status.INTERNAL_ERROR
            }

            val response = if (contentLength > 0) {
                newFixedLengthResponse(status, contentType, inputStream, contentLength)
            } else {
                newChunkedResponse(status, contentType, inputStream)
            }

            // Forward relevant upstream response headers
            connection.getHeaderField("Content-Range")?.let {
                response.addHeader("Content-Range", it)
            }
            connection.getHeaderField("Accept-Ranges")?.let {
                response.addHeader("Accept-Ranges", it)
            }

            response
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Proxy error for $targetUrl" }
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain",
                "Proxy error: ${e.message}",
            )
        }
    }

    private fun serveContentUri(session: IHTTPSession): Response {
        val params = session.parameters
        val uriParam = params["uri"]?.get(0) ?: return newFixedLengthResponse(
            Response.Status.BAD_REQUEST,
            "text/plain",
            "Missing uri parameter",
        )

        val uri = try {
            Uri.parse(uriParam)
        } catch (e: Exception) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid URI")
        }

        val mimeType = contentResolver.getType(uri)
            ?: URLConnection.guessContentTypeFromName(uri.toString())
            ?: "application/octet-stream"

        val assetFileDescriptor = try {
            contentResolver.openAssetFileDescriptor(uri, "r")
        } catch (e: Exception) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found")
        }

        val fileLength = assetFileDescriptor?.length ?: -1L

        val rangeHeader = session.headers["range"]
        if (rangeHeader != null && fileLength > 0) {
            try {
                val range = rangeHeader.replace("bytes=", "").split("-")
                val start = range.getOrNull(0)?.toLongOrNull() ?: 0L
                val end = range.getOrNull(1)?.toLongOrNull() ?: (fileLength - 1)
                val length = end - start + 1

                val inputStream = contentResolver.openInputStream(uri)
                inputStream?.skip(start)

                val response = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mimeType, inputStream, length)
                response.addHeader("Content-Range", "bytes $start-$end/$fileLength")
                response.addHeader("Accept-Ranges", "bytes")
                return response
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Error processing Range header" }
            }
        }

        val inputStream = contentResolver.openInputStream(uri)
        return if (inputStream != null) {
            val response = newChunkedResponse(Response.Status.OK, mimeType, inputStream)
            response.addHeader("Accept-Ranges", "bytes")
            response
        } else {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found")
        }
    }
}
