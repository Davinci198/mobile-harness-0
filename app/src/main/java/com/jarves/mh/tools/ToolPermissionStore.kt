package com.jarves.mh.tools

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/** Minimal string key-value storage so the store can be unit-tested without Android. */
interface ToolPermissionStorage {
    fun read(key: String): String?
    fun write(key: String, value: String?)
}

/**
 * Persists the global default permission level and per-tool overrides.
 *
 * An override always wins over the global default. When no override exists
 * for a tool, [globalDefault] applies.
 *
 * Production uses SharedPreferences (same style as [com.jarves.mh.data.AppPreferences]);
 * unit tests inject an in-memory [ToolPermissionStorage].
 */
class ToolPermissionStore(private val storage: ToolPermissionStorage) {

    var globalDefault: ToolPermissionLevel
        get() = ToolPermissionLevel.fromString(storage.read(KEY_GLOBAL))
        set(value) { storage.write(KEY_GLOBAL, value.name) }

    fun overrideFor(toolName: String): ToolPermissionLevel? {
        val raw = storage.read(overrideKey(toolName)) ?: return null
        return ToolPermissionLevel.fromString(raw)
    }

    fun setOverride(toolName: String, level: ToolPermissionLevel?) {
        storage.write(overrideKey(toolName), level?.name)
    }

    fun allOverrides(): Map<String, ToolPermissionLevel> {
        // Storage does not enumerate keys; callers that need the full map
        // pass known tool names via resolve(). For UI we track tools separately
        // or re-read known Claude tools from the gate list.
        return knownTools.associateWithNotNull { tool -> overrideFor(tool) }
    }

    /** Resolve effective level: per-tool override wins over global default. */
    fun resolve(toolName: String): ToolPermissionLevel =
        overrideFor(toolName) ?: globalDefault

    fun snapshotJson(tools: Collection<String> = knownTools): String {
        val json = JSONObject()
        json.put("globalDefault", globalDefault.name)
        val overrides = JSONObject()
        tools.forEach { tool ->
            overrideFor(tool)?.let { overrides.put(tool, it.name) }
        }
        json.put("overrides", overrides)
        return json.toString()
    }

    private fun <T> Collection<T>.associateWithNotNull(transform: (T) -> ToolPermissionLevel?): Map<String, ToolPermissionLevel> {
        val result = mutableMapOf<String, ToolPermissionLevel>()
        for (item in this) {
            transform(item)?.let { result[item as String] = it }
        }
        return result
    }

    companion object {
        private const val KEY_GLOBAL = "tool_permission_global"
        private const val KEY_OVERRIDE_PREFIX = "tool_permission_override_"

        /** Tools MH surfaces in Settings for per-tool overrides (Claude Code set). */
        val knownTools: List<String> = listOf(
            "Bash",
            "Edit",
            "Write",
            "NotebookEdit",
            "Read",
            "Glob",
            "Grep",
        )

        fun overrideKey(toolName: String) = "$KEY_OVERRIDE_PREFIX$toolName"

        fun fromContext(context: Context): ToolPermissionStore =
            ToolPermissionStore(
                SharedPreferencesStorage(
                    context.getSharedPreferences("tool_permissions", Context.MODE_PRIVATE),
                ),
            )
    }
}

private class SharedPreferencesStorage(private val preferences: SharedPreferences) : ToolPermissionStorage {
    override fun read(key: String): String? = preferences.getString(key, null)
    override fun write(key: String, value: String?) {
        val editor = preferences.edit()
        if (value == null) editor.remove(key) else editor.putString(key, value)
        editor.apply()
    }
}
