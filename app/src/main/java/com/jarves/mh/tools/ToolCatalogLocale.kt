package com.jarves.mh.tools

import android.content.res.AssetManager
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

class ToolCatalogLocale(private val readAsset: (String) -> String) {
    constructor(assets: AssetManager) : this({ path ->
        assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
    })

    fun load(localeTag: String): Map<String, String> {
        val english = parse(readAsset("tool_catalog/en.json"))
        val language = Locale.forLanguageTag(localeTag.replace('_', '-')).language
        if (language.isEmpty() || language == "en") return english
        val localized = try {
            parse(readAsset("tool_catalog/$language.json"))
        } catch (_: IOException) {
            emptyMap()
        } catch (_: JSONException) {
            emptyMap()
        }
        return english + localized
    }

    companion object {
        fun parse(content: String): Map<String, String> {
            val levels = JSONObject(content).getJSONObject("risk_levels")
            return buildMap {
                for (key in levels.keys()) {
                    val label = levels.opt(key) as? String
                    if (!label.isNullOrBlank()) put(key, label)
                }
            }
        }
    }
}
