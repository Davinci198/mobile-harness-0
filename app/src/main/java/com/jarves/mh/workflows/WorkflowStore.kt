package com.jarves.mh.workflows

import java.io.File

/**
 * Workflow files on disk: one JSON document per file inside the directory
 * (`filesDir/workflows/`, same layout as FoneClaw). Corrupt or non-workflow
 * files are skipped so one bad document never blocks the rest.
 */
class WorkflowStore(private val directory: File) {

    fun load(): List<AgentWorkflow> {
        val files = directory.listFiles { file -> file.isFile && file.extension.equals("json", ignoreCase = true) }
            ?: return emptyList()
        return files
            .sortedBy { it.name.lowercase() }
            .mapNotNull { file ->
                val text = try {
                    file.readText()
                } catch (_: Throwable) {
                    null
                } ?: return@mapNotNull null
                AgentWorkflowJson.parse(text)
            }
            .sortedBy { it.id }
    }

    fun save(workflow: AgentWorkflow): Boolean {
        return try {
            directory.mkdirs()
            val target = File(directory, "${sanitizeFileName(workflow.id)}.json")
            val temp = File(directory, "${target.name}.tmp")
            temp.writeText(AgentWorkflowJson.serialize(workflow))
            if (!temp.renameTo(target)) {
                target.writeText(temp.readText())
                temp.delete()
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun delete(id: String): Boolean {
        val file = File(directory, "${sanitizeFileName(id)}.json")
        return file.isFile && file.delete()
    }

    private fun sanitizeFileName(id: String): String =
        id.lowercase().replace(Regex("[^a-z0-9_-]"), "_").trim('_').ifBlank { "workflow" }
}
