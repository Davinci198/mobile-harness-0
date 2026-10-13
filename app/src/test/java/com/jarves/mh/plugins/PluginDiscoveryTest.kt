package com.jarves.mh.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MH = "com.jarves.mh"

private fun pkg(
    name: String,
    capabilities: Set<String> = setOf("TOOLS"),
    entryPoint: String? = null,
    services: Set<String> = emptySet(),
    versionName: String? = "1.0",
    versionCode: Long = 1L,
) = InstalledPackage(
    packageName = name,
    label = name,
    versionName = versionName,
    versionCode = versionCode,
    declaredCapabilities = capabilities,
    entryPointService = entryPoint,
    declaredServices = services,
)

class PluginDiscoveryTest {

    @Test
    fun admitsPluginWithRecognisedCapability() {
        val discovery = PluginDiscovery(MH)
        val admitted = discovery.discover(listOf(pkg("com.example.plugin")))
        assertEquals(listOf("com.example.plugin"), admitted.map { it.packageName })
    }

    @Test
    fun dropsSelfPackage() {
        val discovery = PluginDiscovery(MH)
        val admitted = discovery.discover(listOf(pkg(MH)))
        assertTrue(admitted.isEmpty())
    }

    @Test
    fun dropsPluginWithNoUsableCapability() {
        val discovery = PluginDiscovery(MH)
        // "TELEPORT" is not a recognised capability, so the set maps to empty.
        val admitted = discovery.discover(listOf(pkg("com.example.plugin", capabilities = setOf("TELEPORT"))))
        assertTrue(admitted.isEmpty())
    }

    @Test
    fun dropsPluginWithBlankVersion() {
        val discovery = PluginDiscovery(MH)
        val admitted = discovery.discover(listOf(pkg("com.example.plugin", versionName = "  ")))
        assertTrue(admitted.isEmpty())
    }

    @Test
    fun dropsPluginWithNonPositiveVersionCode() {
        val discovery = PluginDiscovery(MH)
        val admitted = discovery.discover(listOf(pkg("com.example.plugin", versionCode = 0L)))
        assertTrue(admitted.isEmpty())
    }

    @Test
    fun dropsPluginWhoseEntryPointServiceIsNotDeclared() {
        val discovery = PluginDiscovery(MH)
        val candidate = pkg(
            "com.example.plugin",
            entryPoint = "com.example.plugin.EntryService",
            services = setOf("com.example.plugin.OtherService"),
        )
        assertTrue(discovery.discover(listOf(candidate)).isEmpty())
    }

    @Test
    fun admitsPluginWhoseEntryPointServiceIsDeclared() {
        val discovery = PluginDiscovery(MH)
        val candidate = pkg(
            "com.example.plugin",
            entryPoint = "com.example.plugin.EntryService",
            services = setOf("com.example.plugin.EntryService"),
        )
        assertEquals(
            listOf("com.example.plugin"),
            discovery.discover(listOf(candidate)).map { it.packageName },
        )
    }

    @Test
    fun capabilityNamesAreCaseInsensitive() {
        val discovery = PluginDiscovery(MH)
        val candidate = pkg("com.example.plugin", capabilities = setOf("tools"))
        assertEquals(
            setOf(PluginCapability.TOOLS),
            discovery.discover(listOf(candidate)).single().declaredCapabilities,
        )
    }

    @Test
    fun verdictsExposeRejectionsAlongsideAdmissions() {
        val discovery = PluginDiscovery(MH)
        val verdicts = discovery.discoverWithVerdicts(
            listOf(pkg("com.example.good"), pkg(MH)),
        )
        assertEquals(2, verdicts.size)
        assertTrue(verdicts[0] is PluginVerdict.Admitted)
        val rejected = verdicts[1] as PluginVerdict.Rejected
        assertEquals(MH, rejected.packageName)
        assertEquals(PluginRejectReason.SELF_PACKAGE, rejected.reason)
    }

    @Test
    fun validatorIsPureAcrossRepeatedCalls() {
        val discovery = PluginDiscovery(MH)
        val candidate = pkg("com.example.plugin")
        val first = discovery.discoverWithVerdicts(listOf(candidate))
        val second = discovery.discoverWithVerdicts(listOf(candidate))
        assertEquals(first, second)
    }
}
