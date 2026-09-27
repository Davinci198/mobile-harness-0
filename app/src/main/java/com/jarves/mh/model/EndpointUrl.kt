package com.jarves.mh.model

import java.util.Locale

/** Hosts that mean "this device": a local gateway is reachable without an API key. */
private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "0.0.0.0", "10.0.2.2", "::1")

/** Host part of a base URL, tolerating a missing scheme and a missing port. */
private fun hostOf(raw: String): String {
    val authority = raw.trim().substringAfter("://").substringAfterLast("@").substringBefore("/")
    val host = when {
        authority.startsWith("[") -> authority.substringAfter("[").substringBefore("]")
        authority.startsWith(":") -> authority
        else -> authority.substringBefore(":")
    }
    return host.lowercase(Locale.ROOT)
}

/** True for localhost / 127.0.0.1 / 10.0.2.2 / ::1 endpoints, which need no API key. */
fun isLoopbackBaseUrl(raw: String): Boolean = raw.isNotBlank() && hostOf(raw) in LOOPBACK_HOSTS

/** Scheme of a base URL in lower case, or an empty string when the user typed a bare host. */
fun schemeOf(raw: String): String =
    if ("://" in raw) raw.trim().substringBefore("://").lowercase(Locale.ROOT) else ""

/** True when the URL carries an http:// or https:// scheme. */
fun isHttpScheme(raw: String): Boolean = schemeOf(raw) == "http" || schemeOf(raw) == "https"

/** Adds http:// to a bare host so `localhost:11434` becomes a requestable URL. */
fun withDefaultScheme(raw: String): String {
    val value = raw.trim()
    return if (value.isEmpty() || "://" in value) value else "http://$value"
}
