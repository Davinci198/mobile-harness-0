package com.jarves.mh.tools.network

object NetworkToolLimits {
    const val MAX_REQUEST_BODY_BYTES = 262_144
    const val MAX_HTTP_RESPONSE_BYTES = 2_097_152
    const val MAX_WEB_RESPONSE_BYTES = 2_097_152
    const val MAX_WEB_TEXT_CHARS = 12_000
    const val MAX_DOWNLOAD_BYTES = 33_554_432
    const val MAX_HEADERS = 64
    const val MAX_HEADER_VALUE_CHARS = 8_192
    const val MAX_REDIRECTS = 5
    const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000
    const val DEFAULT_READ_TIMEOUT_MS = 20_000
    const val DEFAULT_DOWNLOAD_TIMEOUT_MS = 120_000
    const val MAX_URL_CHARS = 2_048
}

enum class NetworkErrorCode {
    INVALID_INPUT,
    PERMISSION_REQUIRED,
    PERMISSION_DENIED,
    BLOCKED_DESTINATION,
    UNSUPPORTED_SCHEME,
    RESPONSE_TOO_LARGE,
    NETWORK_ERROR,
    FILE_EXISTS,
    FILE_TOO_LARGE,
    ADAPTER_ERROR,
}

class NetworkOperationException(
    val code: NetworkErrorCode,
    message: String,
) : IllegalStateException(message)

data class HttpRequestSpec(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val contentType: String? = null,
    val connectTimeoutMillis: Int = NetworkToolLimits.DEFAULT_CONNECT_TIMEOUT_MS,
    val readTimeoutMillis: Int = NetworkToolLimits.DEFAULT_READ_TIMEOUT_MS,
    val followRedirects: Boolean = false,
    val maxResponseBytes: Int = NetworkToolLimits.MAX_HTTP_RESPONSE_BYTES,
)

data class HttpResponseData(
    val requestedUrl: String,
    val finalUrl: String,
    val statusCode: Int,
    val statusMessage: String,
    val headers: Map<String, String>,
    val contentType: String?,
    val body: ByteArray,
    val truncated: Boolean,
)

data class WebPageRequest(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val timeoutMillis: Int = 20_000,
    val maxChars: Int = NetworkToolLimits.MAX_WEB_TEXT_CHARS,
)

data class WebPageLink(val url: String, val text: String)

data class WebPageResult(
    val requestedUrl: String,
    val finalUrl: String,
    val title: String,
    val text: String,
    val links: List<WebPageLink>,
    val truncated: Boolean,
    val contentType: String?,
)

data class DownloadRequest(
    val url: String,
    val filename: String,
    val maxBytes: Int = NetworkToolLimits.MAX_DOWNLOAD_BYTES,
    val timeoutMillis: Int = NetworkToolLimits.DEFAULT_DOWNLOAD_TIMEOUT_MS,
)

data class DownloadResult(
    val path: String,
    val bytes: Long,
    val contentType: String?,
    val sha256: String,
)

sealed interface NetworkToolResult {
    data class Page(val page: WebPageResult) : NetworkToolResult
    data class Http(val response: HttpResponseData) : NetworkToolResult
    data class Downloaded(val download: DownloadResult) : NetworkToolResult
    data class Error(
        val tool: String,
        val code: NetworkErrorCode,
        val message: String,
    ) : NetworkToolResult
}
