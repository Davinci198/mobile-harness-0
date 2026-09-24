package com.jarves.mh.tools.network

import java.net.InetAddress
import java.net.URI
import java.util.Locale

interface HttpClientAdapter {
    suspend fun execute(request: HttpRequestSpec): HttpResponseData
}

interface WebPageExtractor {
    suspend fun extract(request: WebPageRequest): WebPageResult
}

interface DownloadAdapter {
    suspend fun download(request: DownloadRequest): DownloadResult
}

object NetworkToolPolicy {
    private val methods = setOf("GET", "POST", "PUT", "DELETE", "HEAD", "OPTIONS")
    private val sensitiveHeaders = setOf("authorization", "cookie", "proxy-authorization", "host", "content-length", "transfer-encoding", "connection")

    fun validateUrl(value: String): String {
        val url = value.trim()
        if (url.isEmpty() || url.length > NetworkToolLimits.MAX_URL_CHARS) invalid("Invalid URL")
        if (url.any { it.isISOControl() } || url.contains('\\')) invalid("Invalid URL")
        val uri = try {
            URI(url)
        } catch (_: Throwable) {
            invalid("Invalid URL")
        }
        if (!uri.isAbsolute || !uri.scheme.equals("https", ignoreCase = true)) {
            throw NetworkOperationException(NetworkErrorCode.UNSUPPORTED_SCHEME, "Only HTTPS URLs are allowed")
        }
        if (uri.host.isNullOrBlank() || uri.rawUserInfo != null) invalid("Invalid HTTPS URL")
        val host = uri.host.lowercase(Locale.ROOT).removeSuffix(".").trim('[', ']')
        if (host == "localhost" || host.endsWith(".localhost") || host == "0.0.0.0" || host == "::1") {
            blocked("Local destinations are blocked")
        }
        if (host.split('.').size == 4 && host.split('.').all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }) {
            val address = host.split('.').joinToString(".") { it.toInt().toString() }
            if (address.startsWith("127.") || address.startsWith("10.") || address.startsWith("192.168.") || address.startsWith("169.254.")) {
                blocked("Private destinations are blocked")
            }
        }
        return url
    }

    fun validateMethod(method: String): String {
        val normalized = method.trim().uppercase(Locale.ROOT)
        if (normalized !in methods) invalid("HTTP method is not allowed")
        return normalized
    }

    fun validateHeaders(headers: Map<String, String>): Map<String, String> {
        if (headers.size > NetworkToolLimits.MAX_HEADERS) invalid("Too many HTTP headers")
        headers.forEach { (name, value) ->
            if (name.isBlank() || name.any { it.isISOControl() } || name.any { it == ':' || it == ' ' }) invalid("Invalid HTTP header name")
            if (value.length > NetworkToolLimits.MAX_HEADER_VALUE_CHARS || value.any { it == '\r' || it == '\n' }) invalid("Invalid HTTP header value")
            if (name.lowercase(Locale.ROOT) in sensitiveHeaders) invalid("Credential and routing headers are blocked")
        }
        return headers
    }

    fun validateHeaderValue(name: String, value: String) {
        if (name.isBlank() || value.length > NetworkToolLimits.MAX_HEADER_VALUE_CHARS || value.any { it == '\r' || it == '\n' }) invalid("Invalid HTTP header value")
    }

    fun validateRequestBody(body: ByteArray?, method: String): ByteArray? {
        if (body == null) return null
        if (body.size > NetworkToolLimits.MAX_REQUEST_BODY_BYTES) invalid("Request body is too large")
        if (method in setOf("GET", "HEAD", "DELETE", "OPTIONS") && body.isNotEmpty()) invalid("HTTP method does not accept a body")
        return body
    }

    fun validateFilename(filename: String): String {
        val value = filename.trim()
        if (value.isEmpty() || value.length > 180 || value == "." || value == ".." || value.contains('/') || value.contains('\\') || value.any { it.isISOControl() }) {
            invalid("Invalid download filename")
        }
        return value
    }

    fun validateResolvedHost(host: String) {
        try {
            InetAddress.getAllByName(host).forEach { address ->
                val bytes = address.address.map { it.toInt() and 0xff }
                val cgnat = bytes.size == 4 && bytes[0] == 100 && bytes[1] in 64..127
                val ula = bytes.isNotEmpty() && (bytes[0] and 0xfe) == 0xfc
                if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress || cgnat || ula) {
                    blocked("Private or local destination is blocked")
                }
            }
        } catch (error: NetworkOperationException) {
            throw error
        } catch (_: Throwable) {
            throw NetworkOperationException(NetworkErrorCode.NETWORK_ERROR, "Destination could not be resolved")
        }
    }

    private fun invalid(message: String): Nothing = throw NetworkOperationException(NetworkErrorCode.INVALID_INPUT, message)
    private fun blocked(message: String): Nothing = throw NetworkOperationException(NetworkErrorCode.BLOCKED_DESTINATION, message)
}
