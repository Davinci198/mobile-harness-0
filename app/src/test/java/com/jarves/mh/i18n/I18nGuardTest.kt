package com.jarves.mh.i18n

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Locale guardrails for the assistant i18n work (MH-11 / W0): Mobile Harness ships
 * exactly two locales — English (values/) and Romanian (values-ro/). No other
 * locale folders may appear, every English key needs a Romanian counterpart and
 * no foreign (East-Asian) script may leak into any string resource.
 */
class I18nGuardTest {
    private fun resDir(): File {
        val direct = listOf(
            File("src/main/res"),
            File("app/src/main/res"),
            File("../app/src/main/res"),
        ).firstOrNull { it.isDirectory }
        if (direct != null) return direct
        val found = generateSequence(File(".").absoluteFile) { it.parentFile }
            .map { File(it, "app/src/main/res") }
            .firstOrNull { it.isDirectory }
        return found ?: error("Could not locate app/src/main/res from ${File(".").absolutePath}")
    }

    private fun localeDirs(): List<File> =
        resDir().listFiles { f: File -> f.isDirectory && f.name.startsWith("values") }
            ?.sortedBy { it.name }
            .orEmpty()

    private fun stringFiles(): List<File> =
        localeDirs().flatMap { dir ->
            dir.listFiles { f: File -> f.isFile && f.name == "strings.xml" }?.toList().orEmpty()
        }

    private fun stringNames(file: File): Set<String> =
        Regex("<string name=\"([^\"]+)\"").findAll(file.readText()).map { it.groupValues[1] }.toSet()

    @Test
    fun `only english and romanian locale folders exist`() {
        val allowed = setOf("values", "values-ro")
        val unexpected = localeDirs().map { it.name }.filterNot { it in allowed }
        assertTrue("Unexpected locale folders (only EN + RO are allowed): $unexpected", unexpected.isEmpty())
    }

    @Test
    fun `no chinese or other east-asian script in any string resource`() {
        val forbidden = Regex("[\u4E00-\u9FFF\u3400-\u4DBF\u3040-\u30FF\uAC00-\uD7AF\u3130-\u318F]")
        val offenders = stringFiles().flatMap { file ->
            forbidden.findAll(file.readText()).map { "${file.path}: '${it.value}'" }.toList()
        }
        assertTrue("Foreign script found in string resources: $offenders", offenders.isEmpty())
    }

    @Test
    fun `romanian translation covers every english key`() {
        val en = File(resDir(), "values/strings.xml")
        val ro = File(resDir(), "values-ro/strings.xml")
        assertTrue("values/strings.xml missing", en.isFile)
        assertTrue("values-ro/strings.xml missing", ro.isFile)
        val missing = stringNames(en) - stringNames(ro)
        val extra = stringNames(ro) - stringNames(en)
        assertTrue("Missing Romanian translations: $missing", missing.isEmpty())
        assertTrue("Romanian keys missing from English: $extra", extra.isEmpty())
    }
}
