package com.jarves.mh.tools.memory

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class MemoryJsonStore(private val file: File) : MemoryStore {
    override fun load(): MemoryData {
        if (!file.exists()) return MemoryData()
        return try {
            val root = JSONObject(file.readText())
            val version = root.optInt("schemaVersion", -1)
            if (version != SCHEMA_VERSION) throw MemoryCorruptStoreException("Unsupported memory schema: $version")
            val memories = root.optJSONArray("memories") ?: JSONArray()
            val links = root.optJSONArray("links") ?: JSONArray()
            val records = (0 until memories.length()).map { index ->
                val item = memories.getJSONObject(index)
                MemoryRecord(
                    id = item.getString("id"),
                    title = item.getString("title"),
                    content = item.getString("content"),
                    path = item.optString("path", ""),
                    tags = item.optJSONArray("tags").toStringList(),
                    createdAtMillis = item.getLong("createdAtMillis"),
                    updatedAtMillis = item.getLong("updatedAtMillis"),
                )
            }
            val memoryLinks = (0 until links.length()).map { index ->
                val item = links.getJSONObject(index)
                MemoryLink(
                    id = item.getString("id"),
                    fromId = item.getString("fromId"),
                    toId = item.getString("toId"),
                    type = item.getString("type"),
                    description = item.optString("description", ""),
                    createdAtMillis = item.getLong("createdAtMillis"),
                )
            }
            validate(records, memoryLinks)
            MemoryData(records, memoryLinks)
        } catch (error: MemoryStoreException) {
            throw error
        } catch (error: Throwable) {
            throw MemoryCorruptStoreException("Memory store is corrupt: ${error.message ?: "invalid JSON"}")
        }
    }

    override fun save(data: MemoryData) {
        validate(data.memories, data.links)
        try {
            val absoluteFile = file.absoluteFile
            val parent = absoluteFile.parentFile ?: throw MemoryStorageException("Memory store parent is unavailable")
            if (!parent.exists() && !parent.mkdirs()) throw MemoryStorageException("Memory store directory could not be created")
            val root = JSONObject()
                .put("schemaVersion", SCHEMA_VERSION)
                .put("memories", JSONArray().apply {
                    data.memories.forEach { memory ->
                        put(JSONObject()
                            .put("id", memory.id)
                            .put("title", memory.title)
                            .put("content", memory.content)
                            .put("path", memory.path)
                            .put("tags", JSONArray(memory.tags))
                            .put("createdAtMillis", memory.createdAtMillis)
                            .put("updatedAtMillis", memory.updatedAtMillis))
                    }
                })
                .put("links", JSONArray().apply {
                    data.links.forEach { link ->
                        put(JSONObject()
                            .put("id", link.id)
                            .put("fromId", link.fromId)
                            .put("toId", link.toId)
                            .put("type", link.type)
                            .put("description", link.description)
                            .put("createdAtMillis", link.createdAtMillis))
                    }
                })
            val temporary = Files.createTempFile(parent.toPath(), ".${file.name}.", ".tmp").toFile()
            try {
                temporary.writeText(root.toString())
                try {
                    Files.move(
                        temporary.toPath(),
                        absoluteFile.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary.toPath(), absoluteFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                temporary.delete()
            }
        } catch (error: MemoryStoreException) {
            throw error
        } catch (error: Throwable) {
            throw MemoryStorageException("Memory store could not be saved: ${error.message ?: "write failed"}")
        }
    }

    private fun validate(memories: List<MemoryRecord>, links: List<MemoryLink>) {
        val memoryIds = memories.map { it.id }
        if (memoryIds.size != memoryIds.toSet().size) throw MemoryCorruptStoreException("Duplicate memory IDs")
        if (memories.any { runCatching { java.util.UUID.fromString(it.id).toString() }.getOrNull() != it.id }) {
            throw MemoryCorruptStoreException("Invalid memory ID")
        }
        if (memories.any { it.createdAtMillis > it.updatedAtMillis }) {
            throw MemoryCorruptStoreException("Invalid memory timestamps")
        }
        val memoryIdSet = memoryIds.toSet()
        val linkIds = links.map { it.id }
        if (linkIds.size != linkIds.toSet().size) throw MemoryCorruptStoreException("Duplicate link IDs")
        if (links.any { runCatching { java.util.UUID.fromString(it.id).toString() }.getOrNull() != it.id }) {
            throw MemoryCorruptStoreException("Invalid link ID")
        }
        if (links.any { it.fromId !in memoryIdSet || it.toId !in memoryIdSet }) {
            throw MemoryCorruptStoreException("Dangling memory link")
        }
        if (links.any { it.fromId == it.toId }) throw MemoryCorruptStoreException("Self memory link")
        val semanticKeys = links.map { Triple(it.fromId, it.toId, it.type) }
        if (semanticKeys.size != semanticKeys.toSet().size) throw MemoryCorruptStoreException("Duplicate semantic memory link")
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).map { index -> getString(index) }
    }

    companion object {
        const val SCHEMA_VERSION = 1
    }
}
