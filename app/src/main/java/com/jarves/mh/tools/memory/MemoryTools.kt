package com.jarves.mh.tools.memory

import com.jarves.mh.tools.AIToolHook
import com.jarves.mh.tools.ToolPermissionGate
import com.jarves.mh.tools.ToolPermissionGateDecision
import java.util.UUID

class MemoryTools(
    private val store: MemoryStore,
    private val gate: ToolPermissionGate,
    private val hooks: List<AIToolHook> = emptyList(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    @Synchronized
    fun create(
        title: String,
        content: String,
        path: String = "",
        tags: List<String> = emptyList(),
    ): MemoryToolResult = execute("MemoryCreate", "create memory: $title") {
        val normalizedTitle = validateTitle(title)
        val normalizedContent = validateContent(content)
        val normalizedPath = normalizePath(path)
        val normalizedTags = normalizeTags(tags)
        val now = clock()
        val memory = MemoryRecord(
            id = newId(),
            title = normalizedTitle,
            content = normalizedContent,
            path = normalizedPath,
            tags = normalizedTags,
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        val data = store.load()
        store.save(data.copy(memories = data.memories + memory))
        MemoryToolResult.Created(memory)
    }

    @Synchronized
    fun read(id: String): MemoryToolResult = execute("MemoryRead", "read memory: $id") {
        val memoryId = validateId(id)
        val memory = store.load().memories.firstOrNull { it.id == memoryId }
            ?: throw MemoryNotFoundException("Memory not found: $memoryId")
        MemoryToolResult.Found(memory)
    }

    @Synchronized
    fun update(
        id: String,
        title: String? = null,
        content: String? = null,
        path: String? = null,
        tags: List<String>? = null,
    ): MemoryToolResult = execute("MemoryUpdate", "update memory: $id") {
        val memoryId = validateId(id)
        val data = store.load()
        val current = data.memories.firstOrNull { it.id == memoryId }
            ?: throw MemoryNotFoundException("Memory not found: $memoryId")
        val updated = current.copy(
            title = title?.let(::validateTitle) ?: current.title,
            content = content?.let(::validateContent) ?: current.content,
            path = path?.let(::normalizePath) ?: current.path,
            tags = tags?.let(::normalizeTags) ?: current.tags,
        )
        if (updated == current) return@execute MemoryToolResult.Updated(current)
        val result = updated.copy(updatedAtMillis = clock())
        store.save(data.copy(memories = data.memories.map { if (it.id == memoryId) result else it }))
        MemoryToolResult.Updated(result)
    }

    @Synchronized
    fun delete(id: String): MemoryToolResult = execute("MemoryDelete", "delete memory: $id") {
        val memoryId = validateId(id)
        val data = store.load()
        if (data.memories.none { it.id == memoryId }) throw MemoryNotFoundException("Memory not found: $memoryId")
        val remainingLinks = data.links.filterNot { it.fromId == memoryId || it.toId == memoryId }
        store.save(data.copy(
            memories = data.memories.filterNot { it.id == memoryId },
            links = remainingLinks,
        ))
        MemoryToolResult.Deleted(memoryId, data.links.size - remainingLinks.size)
    }

    @Synchronized
    fun search(query: String, limit: Int = MemoryToolLimits.DEFAULT_SEARCH_LIMIT): MemoryToolResult =
        execute("MemorySearch", "search memories: $query") {
            val normalizedQuery = validateQuery(query)
            validateLimit(limit)
            val terms = normalizedQuery.lowercase().split(Regex("\\s+"))
            val matches = store.load().memories.mapNotNull { memory ->
                val title = memory.title.lowercase()
                val content = memory.content.lowercase()
                val score = terms.sumOf { term ->
                    when {
                        title == term -> 8
                        title.contains(term) -> 4
                        content.contains(term) -> 1
                        else -> 0
                    }
                }
                if (score > 0) memory to score else null
            }.sortedWith(
                compareByDescending<Pair<MemoryRecord, Int>> { it.second }
                    .thenByDescending { it.first.updatedAtMillis }
                    .thenBy { it.first.id },
            )
            MemoryToolResult.SearchResults(matches.map { it.first }.take(limit), matches.size)
        }

    @Synchronized
    fun list(
        path: String = "",
        limit: Int = MemoryToolLimits.DEFAULT_LIST_LIMIT,
        offset: Int = 0,
    ): MemoryToolResult = execute("MemoryList", "list memories: $path") {
        val normalizedPath = normalizePath(path)
        validatePage(limit, offset)
        val matches = store.load().memories
            .filter { normalizedPath.isEmpty() || it.path == normalizedPath || it.path.startsWith("$normalizedPath/") }
            .sortedWith(compareByDescending<MemoryRecord> { it.updatedAtMillis }.thenBy { it.id })
        MemoryToolResult.Memories(matches.drop(offset).take(limit), matches.size)
    }

    @Synchronized
    fun link(
        fromId: String,
        toId: String,
        type: String = "related",
        description: String = "",
    ): MemoryToolResult = execute("MemoryLink", "link memories: $fromId -> $toId") {
        val sourceId = validateId(fromId)
        val targetId = validateId(toId)
        requireInput(sourceId != targetId) { "A memory cannot link to itself" }
        val linkType = validateLinkType(type)
        val linkDescription = validateLinkDescription(description)
        val data = store.load()
        if (data.memories.none { it.id == sourceId }) throw MemoryNotFoundException("Source memory not found: $sourceId")
        if (data.memories.none { it.id == targetId }) throw MemoryNotFoundException("Target memory not found: $targetId")
        data.links.firstOrNull { it.fromId == sourceId && it.toId == targetId && it.type == linkType }?.let {
            return@execute MemoryToolResult.Linked(it, false)
        }
        val link = MemoryLink(
            id = newId(),
            fromId = sourceId,
            toId = targetId,
            type = linkType,
            description = linkDescription,
            createdAtMillis = clock(),
        )
        store.save(data.copy(links = data.links + link))
        MemoryToolResult.Linked(link, true)
    }

    @Synchronized
    fun unlink(linkId: String): MemoryToolResult = execute("MemoryUnlink", "unlink memory: $linkId") {
        val id = validateId(linkId)
        val data = store.load()
        if (data.links.none { it.id == id }) throw MemoryNotFoundException("Memory link not found: $id")
        store.save(data.copy(links = data.links.filterNot { it.id == id }))
        MemoryToolResult.Unlinked(id)
    }

    @Synchronized
    fun listLinks(
        memoryId: String? = null,
        limit: Int = MemoryToolLimits.DEFAULT_LIST_LIMIT,
        offset: Int = 0,
    ): MemoryToolResult = execute("MemoryLinkList", "list memory links: ${memoryId.orEmpty()}") {
        validatePage(limit, offset)
        val incidentId = memoryId?.let(::validateId)
        val data = store.load()
        if (incidentId != null && data.memories.none { it.id == incidentId }) {
            throw MemoryNotFoundException("Memory not found: $incidentId")
        }
        val matches = data.links
            .filter { incidentId == null || it.fromId == incidentId || it.toId == incidentId }
            .sortedWith(compareByDescending<MemoryLink> { it.createdAtMillis }.thenBy { it.id })
        MemoryToolResult.Links(matches.drop(offset).take(limit), matches.size)
    }

    private fun execute(
        tool: String,
        explanation: String,
        block: () -> MemoryToolResult,
    ): MemoryToolResult = synchronized(STORE_LOCK) {
        executeLocked(tool, explanation, block)
    }

    private fun executeLocked(
        tool: String,
        explanation: String,
        block: () -> MemoryToolResult,
    ): MemoryToolResult {
        when (val decision = gate.evaluate(tool, explanation.take(240))) {
            is ToolPermissionGateDecision.Allowed -> Unit
            is ToolPermissionGateDecision.NeedsApproval ->
                return MemoryToolResult.Error(tool, MemoryErrorCode.PERMISSION_REQUIRED, "Approval required (ASK)")
            is ToolPermissionGateDecision.Blocked ->
                return MemoryToolResult.Error(tool, MemoryErrorCode.PERMISSION_DENIED, decision.reason)
        }
        hooks.forEach { hook -> safely { hook.onToolExecutionStarted(tool) } }
        return try {
            val result = block()
            hooks.forEach { hook ->
                safely { hook.onToolExecutionResult(tool, result !is MemoryToolResult.Error, result.summary()) }
            }
            result
        } catch (error: MemoryInputException) {
            val result = MemoryToolResult.Error(tool, MemoryErrorCode.INVALID_INPUT, error.message ?: "Invalid input")
            hooks.forEach { hook -> safely { hook.onToolExecutionResult(tool, false, error.message ?: "Invalid input") } }
            result
        } catch (error: MemoryNotFoundException) {
            val result = MemoryToolResult.Error(tool, MemoryErrorCode.NOT_FOUND, error.message ?: "Memory not found")
            hooks.forEach { hook -> safely { hook.onToolExecutionResult(tool, false, error.message ?: "Memory not found") } }
            result
        } catch (error: MemoryCorruptStoreException) {
            hooks.forEach { hook -> safely { hook.onToolExecutionError(tool, error) } }
            MemoryToolResult.Error(tool, MemoryErrorCode.CORRUPT_STORE, error.message ?: "Memory store is corrupt")
        } catch (error: MemoryStorageException) {
            hooks.forEach { hook -> safely { hook.onToolExecutionError(tool, error) } }
            MemoryToolResult.Error(tool, MemoryErrorCode.STORE_ERROR, error.message ?: "Memory store write failed")
        } catch (error: Throwable) {
            hooks.forEach { hook -> safely { hook.onToolExecutionError(tool, error) } }
            MemoryToolResult.Error(tool, MemoryErrorCode.STORE_ERROR, error.message ?: "Memory operation failed")
        } finally {
            hooks.forEach { hook -> safely { hook.onToolExecutionFinished(tool) } }
        }
    }

    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
        }
    }

    private fun validateTitle(value: String): String {
        val title = value.trim()
        requireInput(title.isNotEmpty()) { "Memory title is empty" }
        requireInput(title.length <= MemoryToolLimits.MAX_TITLE_CHARS) { "Memory title is too long" }
        return title
    }

    private fun validateContent(value: String): String {
        requireInput(value.isNotBlank()) { "Memory content is empty" }
        requireInput(value.length <= MemoryToolLimits.MAX_CONTENT_CHARS) { "Memory content is too long" }
        return value
    }

    private fun validateQuery(value: String): String {
        val query = value.trim()
        requireInput(query.isNotEmpty()) { "Search query is empty" }
        requireInput(query.length <= MemoryToolLimits.MAX_QUERY_CHARS) { "Search query is too long" }
        return query
    }

    private fun validateLimit(limit: Int) {
        requireInput(limit in 1..MemoryToolLimits.MAX_RESULTS) {
            "Limit must be between 1 and ${MemoryToolLimits.MAX_RESULTS}"
        }
    }

    private fun validatePage(limit: Int, offset: Int) {
        validateLimit(limit)
        requireInput(offset >= 0) { "Offset must not be negative" }
    }

    private fun normalizePath(value: String): String {
        requireInput(value.length <= MemoryToolLimits.MAX_PATH_CHARS) { "Memory path is too long" }
        requireInput(value.none { it == '\u0000' || it.isISOControl() }) { "Memory path contains control characters" }
        val parts = value.replace('\\', '/').split('/').map { it.trim() }.filter { it.isNotEmpty() && it != "." }
        requireInput(parts.none { it == ".." }) { "Memory path cannot contain '..'" }
        return parts.joinToString("/")
    }

    private fun normalizeTags(tags: List<String>): List<String> {
        requireInput(tags.size <= MemoryToolLimits.MAX_TAGS) { "Too many tags" }
        val result = linkedMapOf<String, String>()
        tags.forEach { raw ->
            val tag = raw.trim()
            requireInput(tag.isNotEmpty()) { "Memory tags cannot be empty" }
            requireInput(tag.length <= MemoryToolLimits.MAX_TAG_CHARS) { "Memory tag is too long: $tag" }
            result.putIfAbsent(tag.lowercase(), tag)
        }
        return result.values.toList()
    }

    private fun validateLinkType(value: String): String {
        val type = value.trim()
        requireInput(type.length in 1..MemoryToolLimits.MAX_LINK_TYPE_CHARS) { "Invalid link type" }
        requireInput(type.matches(Regex("[a-z0-9][a-z0-9._-]*"))) { "Invalid link type" }
        return type
    }

    private fun validateLinkDescription(value: String): String {
        requireInput(value.length <= MemoryToolLimits.MAX_LINK_DESCRIPTION_CHARS) { "Link description is too long" }
        return value
    }

    private fun validateId(value: String): String {
        val id = value.trim()
        requireInput(runCatching { UUID.fromString(id).toString() }.getOrNull() == id) { "Invalid memory ID" }
        return id
    }

    private fun newId(): String {
        val id = idFactory()
        if (runCatching { UUID.fromString(id).toString() }.getOrNull() != id) {
            throw MemoryStorageException("Memory ID factory returned an invalid ID")
        }
        return id
    }

    private inline fun requireInput(condition: Boolean, message: () -> String) {
        if (!condition) throw MemoryInputException(message())
    }

    private fun MemoryToolResult.summary(): String = when (this) {
        is MemoryToolResult.Created -> "created ${memory.id}"
        is MemoryToolResult.Found -> "read ${memory.id}"
        is MemoryToolResult.Updated -> "updated ${memory.id}"
        is MemoryToolResult.Deleted -> "deleted $memoryId"
        is MemoryToolResult.Memories -> "listed $total memories"
        is MemoryToolResult.SearchResults -> "found $total memories"
        is MemoryToolResult.Linked -> "linked ${link.id}"
        is MemoryToolResult.Unlinked -> "unlinked $linkId"
        is MemoryToolResult.Links -> "listed $total links"
        is MemoryToolResult.Error -> message
    }

    companion object {
        private val STORE_LOCK = Any()
    }
}

private class MemoryInputException(message: String) : IllegalArgumentException(message)

private class MemoryNotFoundException(message: String) : IllegalStateException(message)
