package com.jarves.mh.tools.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import java.util.Locale

class HttpWebPageExtractor(
    private val httpClient: HttpClientAdapter,
) : WebPageExtractor {
    override suspend fun extract(request: WebPageRequest): WebPageResult = withContext(Dispatchers.IO) {
        val url = NetworkToolPolicy.validateUrl(request.url)
        val response = httpClient.execute(HttpRequestSpec(
            url = url,
            headers = request.headers,
            connectTimeoutMillis = request.timeoutMillis,
            readTimeoutMillis = request.timeoutMillis,
            maxResponseBytes = NetworkToolLimits.MAX_WEB_RESPONSE_BYTES,
        ))
        val contentType = response.contentType?.lowercase(Locale.ROOT).orEmpty()
        if (contentType.isNotEmpty() && !contentType.contains("html") && !contentType.contains("text")) {
            throw NetworkOperationException(NetworkErrorCode.INVALID_INPUT, "URL is not an HTML page")
        }
        val html = response.body.toString(Charsets.UTF_8)
        val title = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)?.groupValues?.getOrNull(1)?.let(::decode)?.trim()?.take(200).orEmpty()
        val text = decode(Regex("<script[^>]*>.*?</script>|<style[^>]*>.*?</style>|<[^>]+>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).replace(html, " "))
            .replace(Regex("\\s+"), " ").trim()
        val links = Regex("<a[^>]+href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(html)
            .mapNotNull { match ->
                val href = decode(match.groupValues[1])
                if (!href.startsWith("https://", ignoreCase = true) && !href.startsWith("http://", ignoreCase = true)) return@mapNotNull null
                val absolute = runCatching { URL(URL(response.finalUrl), href).toString() }.getOrNull() ?: return@mapNotNull null
                WebPageLink(absolute, decode(Regex("<[^>]+>").replace(match.groupValues[2], "")).trim())
            }
            .take(100)
            .toList()
        val boundedText = text.take(request.maxChars.coerceIn(1, NetworkToolLimits.MAX_WEB_TEXT_CHARS))
        WebPageResult(
            requestedUrl = url,
            finalUrl = response.finalUrl,
            title = title.take(200),
            text = boundedText,
            links = links,
            truncated = response.truncated || text.length > boundedText.length,
            contentType = response.contentType,
        )
    }

    private fun decode(value: String): String = value
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
}
