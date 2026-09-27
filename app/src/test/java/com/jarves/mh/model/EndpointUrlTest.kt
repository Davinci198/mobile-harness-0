package com.jarves.mh.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointUrlTest {
    @Test
    fun loopbackHostsNeedNoApiKey() {
        assertTrue(isLoopbackBaseUrl("http://localhost:11434"))
        assertTrue(isLoopbackBaseUrl("localhost:11434"))
        assertTrue(isLoopbackBaseUrl("https://127.0.0.1:8080/v1"))
        assertTrue(isLoopbackBaseUrl("http://10.0.2.2:1234"))
        assertTrue(isLoopbackBaseUrl("http://[::1]:8000"))
    }

    @Test
    fun remoteHostsStillRequireAnApiKey() {
        assertFalse(isLoopbackBaseUrl("https://api.anthropic.com"))
        assertFalse(isLoopbackBaseUrl("api.moonshot.ai/anthropic"))
        assertFalse(isLoopbackBaseUrl("http://localhost.example.com"))
        assertFalse(isLoopbackBaseUrl(""))
    }

    @Test
    fun schemeHelpersClassifyUserInput() {
        assertEquals("", schemeOf("localhost:11434"))
        assertEquals("https", schemeOf("HTTPS://api.example.com/v1"))
        assertTrue(isHttpScheme("http://localhost:11434"))
        assertTrue(isHttpScheme("https://api.anthropic.com"))
        assertFalse(isHttpScheme("ftp://files.example.com"))
        assertFalse(isHttpScheme("localhost:11434"))
    }

    @Test
    fun defaultSchemeMakesBareHostsRequestable() {
        assertEquals("http://localhost:11434", withDefaultScheme("localhost:11434"))
        assertEquals("http://localhost:11434", withDefaultScheme("  localhost:11434  "))
        assertEquals("https://api.anthropic.com", withDefaultScheme("https://api.anthropic.com"))
        assertEquals("", withDefaultScheme("  "))
    }
}
