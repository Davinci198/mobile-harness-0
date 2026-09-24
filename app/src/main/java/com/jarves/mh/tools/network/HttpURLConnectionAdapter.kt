package com.jarves.mh.tools.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import kotlin.coroutines.coroutineContext

class HttpURLConnectionAdapter : HttpClientAdapter {
    override suspend fun execute(request: HttpRequestSpec): HttpResponseData = withContext(Dispatchers.IO) {
        val requestedUrl = NetworkToolPolicy.validateUrl(request.url)
        if (request.maxResponseBytes !in 1..NetworkToolLimits.MAX_HTTP_RESPONSE_BYTES) throw NetworkOperationException(NetworkErrorCode.INVALID_INPUT, "Response limit is invalid")
        if (request.connectTimeoutMillis !in 100..120_000 || request.readTimeoutMillis !in 100..120_000) throw NetworkOperationException(NetworkErrorCode.INVALID_INPUT, "HTTP timeout is invalid")
        val method = NetworkToolPolicy.validateMethod(request.method)
        val headers = NetworkToolPolicy.validateHeaders(request.headers)
        val body = NetworkToolPolicy.validateRequestBody(request.body, method)
        request.contentType?.let { NetworkToolPolicy.validateHeaderValue("Content-Type", it) }
        var current = URL(requestedUrl)
        var redirects = 0
        repeat(NetworkToolLimits.MAX_REDIRECTS + 1) {
            NetworkToolPolicy.validateResolvedHost(current.host)
            val connection = (current.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                instanceFollowRedirects = false
                connectTimeout = request.connectTimeoutMillis
                readTimeout = request.readTimeoutMillis
                headers.forEach { (name, value) -> setRequestProperty(name, value) }
                if (body != null) {
                    doOutput = true
                    if (request.contentType != null) setRequestProperty("Content-Type", request.contentType)
                    outputStream.use { it.write(body) }
                }
            }
            try {
                val status = connection.responseCode
                val location = connection.getHeaderField("Location")
                if (status in 300..399 && location != null) {
                    if (!request.followRedirects) throw NetworkOperationException(NetworkErrorCode.NETWORK_ERROR, "Redirects are disabled")
                    if (redirects++ >= NetworkToolLimits.MAX_REDIRECTS) throw NetworkOperationException(NetworkErrorCode.NETWORK_ERROR, "Too many redirects")
                    val next = NetworkToolPolicy.validateUrl(URL(current, location).toString())
                    val nextUrl = URL(next)
                    if (!sameOrigin(current, nextUrl)) throw NetworkOperationException(NetworkErrorCode.NETWORK_ERROR, "Cross-origin redirects are blocked")
                    current = nextUrl
                    return@repeat
                }
                val input = if (status >= 400) connection.errorStream else connection.inputStream
                val bytes = readBounded(input, request.maxResponseBytes)
                return@withContext HttpResponseData(
                    requestedUrl = requestedUrl,
                    finalUrl = current.toString(),
                    statusCode = status,
                    statusMessage = connection.responseMessage.orEmpty(),
                    headers = connection.headerFields
                        .filterKeys { it != null && it.lowercase() !in setOf("set-cookie", "www-authenticate") }
                        .mapValues { it.value?.joinToString(", ").orEmpty() },
                    contentType = connection.contentType,
                    body = bytes.first,
                    truncated = bytes.second,
                )
            } finally {
                connection.disconnect()
            }
        }
        throw NetworkOperationException(NetworkErrorCode.NETWORK_ERROR, "Too many redirects")
    }

    private fun sameOrigin(first: URL, second: URL): Boolean {
        val firstPort = if (first.port >= 0) first.port else 443
        val secondPort = if (second.port >= 0) second.port else 443
        return first.host.equals(second.host, ignoreCase = true) && firstPort == secondPort
    }

    private suspend fun readBounded(input: java.io.InputStream?, maxBytes: Int): Pair<ByteArray, Boolean> {
        if (input == null) return ByteArray(0) to false
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        var remaining = maxBytes.toLong() + 1L
        input.use {
            while (remaining > 0) {
                coroutineContext.ensureActive()
                val count = it.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (count < 0) break
                output.write(buffer, 0, count)
                remaining -= count
            }
        }
        val bytes = output.toByteArray()
        val truncated = bytes.size > maxBytes
        return (if (truncated) bytes.copyOf(maxBytes) else bytes) to truncated
    }
}
