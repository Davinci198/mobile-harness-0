package com.jarves.mh.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRiskCatalogTest {

    @Test
    fun readOnlyToolsAreLow() {
        listOf("Read", "Glob", "Grep", "Calculate", "Sleep", "UseSkill").forEach {
            assertEquals(ToolRiskLevel.LOW, ToolRiskCatalog.riskFor(it))
        }
    }

    @Test
    fun stateChangingToolsAreDeviceControl() {
        listOf("Write", "Edit", "NotebookEdit", "DeviceToast", "ClipboardCopy").forEach {
            assertEquals(ToolRiskLevel.DEVICE_CONTROL, ToolRiskCatalog.riskFor(it))
        }
    }

    @Test
    fun shellAndNetworkAreExternalEffect() {
        listOf("Bash", "HttpRequest", "DownloadFile", "ShizukuExec").forEach {
            assertEquals(ToolRiskLevel.EXTERNAL_EFFECT, ToolRiskCatalog.riskFor(it))
        }
    }

    @Test
    fun clipboardReadIsSensitiveRead() {
        assertEquals(ToolRiskLevel.SENSITIVE_READ, ToolRiskCatalog.riskFor("ClipboardRead"))
        assertEquals(ToolRiskLevel.SENSITIVE_READ, ToolRiskCatalog.riskFor("MemoryRead"))
    }

    @Test
    fun unknownToolDefaultsToDeviceControl() {
        assertEquals(ToolRiskLevel.DEVICE_CONTROL, ToolRiskCatalog.riskFor("SomeAgentTool"))
    }

    @Test
    fun lookupIsCaseInsensitiveForKnownNames() {
        assertEquals(ToolRiskLevel.LOW, ToolRiskCatalog.riskFor("read"))
        assertEquals(ToolRiskLevel.EXTERNAL_EFFECT, ToolRiskCatalog.riskFor("bash"))
    }

    @Test
    fun onlyLowIsAutoEligible() {
        assertTrue(ToolRiskLevel.LOW.isAutoEligible)
        listOf(
            ToolRiskLevel.SENSITIVE_READ,
            ToolRiskLevel.DEVICE_CONTROL,
            ToolRiskLevel.EXTERNAL_EFFECT,
            ToolRiskLevel.DESTRUCTIVE,
        ).forEach { assertFalse(it.isAutoEligible) }
    }
}
