package com.jarves.mh.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesHookConfigTest {
    private val url = "http://127.0.0.1:42421/hook"

    @Test
    fun appendsHooksSectionWhenMissing() {
        val result = ensureHermesHooks("model: kimi\nagents:\n  default: x\n", url)
        assertTrue(result.changed)
        assertTrue(result.config.startsWith("model: kimi\nagents:\n  default: x\n"))
        assertTrue(result.config.contains("\nhooks:\n  outbound:\n"))
        assertTrue(result.config.contains("    - name: $HERMES_HOOK_NAME"))
        assertTrue(result.config.contains("      url: $url"))
        assertTrue(result.config.contains("      events: $HERMES_HOOK_EVENTS"))
        assertTrue(result.config.contains("      timeout: 3"))
    }

    @Test
    fun isIdempotentForTheSameUrl() {
        val first = ensureHermesHooks("model: kimi\n", url)
        val second = ensureHermesHooks(first.config, url)
        assertFalse(second.changed)
        assertEquals(first.config, second.config)
    }

    @Test
    fun addsOutboundUnderAnExistingHooksSection() {
        val config = "model: kimi\nhooks:\n  shell:\n    on_boot: echo hi\nagents:\n  default: x\n"
        val result = ensureHermesHooks(config, url)
        assertTrue(result.changed)
        assertTrue(result.config.contains("hooks:\n  outbound:\n    - name: $HERMES_HOOK_NAME"))
        assertTrue(result.config.contains("  shell:\n    on_boot: echo hi"))
        assertTrue(result.config.endsWith("agents:\n  default: x\n"))
    }

    @Test
    fun updatesTheUrlOfOurExistingEntryInPlace() {
        val existing = ensureHermesHooks("model: kimi\n", "http://127.0.0.1:1111/hook").config
        val result = ensureHermesHooks(existing, url)
        assertTrue(result.changed)
        assertTrue(result.config.contains("      url: $url"))
        assertFalse(result.config.contains("1111"))
        assertEquals(1, result.config.split(HERMES_HOOK_NAME).size - 1)
    }

    @Test
    fun appendsAfterOtherOutboundEntries() {
        val config = """
            model: kimi
            hooks:
              outbound:
                - name: other
                  url: http://example.invalid/hook
                  events: [on_stream_end]
                  timeout: 3
              shell:
                on_boot: echo hi
        """.trimIndent() + "\n"
        val result = ensureHermesHooks(config, url)
        assertTrue(result.changed)
        assertTrue(result.config.contains("- name: other\n      url: http://example.invalid/hook"))
        assertTrue(result.config.contains("- name: $HERMES_HOOK_NAME\n      url: $url"))
        assertTrue(result.config.contains("  shell:\n    on_boot: echo hi"))
        val second = ensureHermesHooks(result.config, url)
        assertFalse(second.changed)
    }

    @Test
    fun leavesUnrelatedConfigByteForByte() {
        val config = "model: kimi\n# a comment\nproviders:\n  custom:\n    base_url: https://x\n"
        val result = ensureHermesHooks(config, url)
        assertTrue(result.config.startsWith(config))
    }
}
