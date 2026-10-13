package com.jarves.mh.tools

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException
import javax.xml.parsers.DocumentBuilderFactory

class ToolCatalogLocaleTest {
    private fun mainDir(): File = listOf(File("src/main"), File("app/src/main"), File("../app/src/main"))
        .first { File(it, "assets/tool_catalog/en.json").isFile }

    private fun asset(path: String): String = File(mainDir(), "assets/$path").readText()

    @Test
    fun validJsonParsesRiskLabels() {
        val labels = ToolCatalogLocale.parse("""{"risk_levels":{"low":"Read only","destructive":"Destructive"}}""")

        assertEquals(mapOf("low" to "Read only", "destructive" to "Destructive"), labels)
    }

    @Test
    fun toolsSectionParsesToFlattenedNameAndDescriptionKeys() {
        val labels = ToolCatalogLocale.parse(
            """
            {"risk_levels":{"low":"Read only"},
             "tools":{"Bash":{"name":"Bash","description":"Run shell commands"}}}
            """.trimIndent(),
        )

        assertEquals("Bash", labels["tool_Bash_name"])
        assertEquals("Run shell commands", labels["tool_Bash_description"])
        assertEquals("Read only", labels["low"])
    }

    @Test
    fun blankToolEntriesAreDropped() {
        val labels = ToolCatalogLocale.parse(
            """
            {"risk_levels":{"low":"Read only"},
             "tools":{"Bash":{"name":"Bash","description":" "},"Read":{"name":null,"description":"Reads files"}}}
            """.trimIndent(),
        )

        assertEquals(
            mapOf(
                "low" to "Read only",
                "tool_Bash_name" to "Bash",
                "tool_Read_description" to "Reads files",
            ),
            labels,
        )
    }

    @Test
    fun bundledCatalogsHaveIdenticalCompleteKeySets() {
        val english = ToolCatalogLocale.parse(asset("tool_catalog/en.json"))
        val romanian = ToolCatalogLocale.parse(asset("tool_catalog/ro.json"))

        val riskKeys = setOf("low", "sensitive_read", "device_control", "external_effect", "destructive")
        val toolKeys = ToolPermissionStore.knownTools
            .flatMap { tool -> listOf("tool_${tool}_name", "tool_${tool}_description") }
            .toSet()

        assertEquals(riskKeys + toolKeys, english.keys)
        assertEquals(english.keys, romanian.keys)
    }

    @Test
    fun bundledToolDescriptionsAreLocalized() {
        val english = ToolCatalogLocale.parse(asset("tool_catalog/en.json"))
        val romanian = ToolCatalogLocale.parse(asset("tool_catalog/ro.json"))

        assertEquals("Rulează comenzi shell", romanian["tool_Bash_description"])
        assertEquals("Run shell commands", english["tool_Bash_description"])
        for (tool in ToolPermissionStore.knownTools) {
            assertEquals("$tool en description", true, !english["tool_${tool}_description"].isNullOrBlank())
            assertEquals("$tool ro description", true, !romanian["tool_${tool}_description"].isNullOrBlank())
            assertEquals("$tool names match", english["tool_${tool}_name"], romanian["tool_${tool}_name"])
        }
    }

    @Test
    fun bundledLabelsMatchStringResources() {
        val resourceNames = mapOf(
            "low" to "risk_label_low",
            "sensitive_read" to "risk_label_sensitive",
            "device_control" to "risk_label_device",
            "external_effect" to "risk_label_external",
            "destructive" to "risk_label_destructive",
        )
        for ((locale, directory) in mapOf("en" to "values", "ro" to "values-ro")) {
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(File(mainDir(), "res/$directory/strings.xml"))
            val strings = document.getElementsByTagName("string")
            val resources = (0 until strings.length).associate { index ->
                val node = strings.item(index)
                node.attributes.getNamedItem("name").nodeValue to node.textContent
            }
            val labels = ToolCatalogLocale.parse(asset("tool_catalog/$locale.json"))
            resourceNames.forEach { (key, resource) -> assertEquals("$locale: $key", resources[resource], labels[key]) }
        }
    }

    @Test
    fun unknownLocaleFallsBackToEnglish() {
        val loader = ToolCatalogLocale(::asset)

        assertEquals(loader.load("en"), loader.load("zz-ZZ"))
    }

    @Test
    fun localeTagsResolveTheLanguageCatalog() {
        val loader = ToolCatalogLocale(::asset)
        val romanian = ToolCatalogLocale.parse(asset("tool_catalog/ro.json"))

        assertEquals(romanian, loader.load("ro"))
        assertEquals(romanian, loader.load("ro-RO"))
        assertEquals(romanian, loader.load("RO_ro"))
        assertEquals(loader.load("en"), loader.load(""))
    }

    @Test
    fun missingLocaleKeyFallsBackToEnglishWithoutLosingTranslations() {
        val partial = JSONObject(asset("tool_catalog/ro.json"))
        partial.getJSONObject("risk_levels").remove("destructive")
        val loader = ToolCatalogLocale { path ->
            if (path == "tool_catalog/ro.json") partial.toString() else asset(path)
        }

        val labels = loader.load("ro")

        assertEquals(loader.load("en")["destructive"], labels["destructive"])
        assertEquals("Risc: doar citire", labels["low"])
        assertEquals(loader.load("en").keys, labels.keys)
    }

    @Test
    fun blankAndInvalidLabelsFallBackToEnglish() {
        val loader = ToolCatalogLocale { path ->
            if (path == "tool_catalog/ro.json") {
                """{"risk_levels":{"low":" ","sensitive_read":null,"device_control":7}}"""
            } else {
                asset(path)
            }
        }

        assertEquals(loader.load("en"), loader.load("ro"))
    }

    @Test
    fun malformedLocaleFallsBackToEnglish() {
        val loader = ToolCatalogLocale { path ->
            if (path == "tool_catalog/ro.json") "not json" else asset(path)
        }

        assertEquals(loader.load("en"), loader.load("ro"))
    }

    @Test(expected = FileNotFoundException::class)
    fun missingEnglishCatalogIsNotSilentlyIgnored() {
        ToolCatalogLocale { path -> throw FileNotFoundException(path) }.load("en")
    }
}
