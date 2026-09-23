package com.jarves.mh.tools.memory

object MemoryToolLimits {
    const val MAX_TITLE_CHARS = 200
    const val MAX_CONTENT_CHARS = 65_536
    const val MAX_PATH_CHARS = 512
    const val MAX_TAGS = 50
    const val MAX_TAG_CHARS = 64
    const val MAX_QUERY_CHARS = 512
    const val MAX_LINK_TYPE_CHARS = 64
    const val MAX_LINK_DESCRIPTION_CHARS = 1_000
    const val DEFAULT_SEARCH_LIMIT = 20
    const val DEFAULT_LIST_LIMIT = 50
    const val MAX_RESULTS = 100
}

data class MemoryRecord(
    val id: String,
    val title: String,
    val content: String,
    val path: String,
    val tags: List<String>,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

data class MemoryLink(
    val id: String,
    val fromId: String,
    val toId: String,
    val type: String,
    val description: String,
    val createdAtMillis: Long,
)

data class MemoryData(
    val memories: List<MemoryRecord> = emptyList(),
    val links: List<MemoryLink> = emptyList(),
)

enum class MemoryErrorCode {
    INVALID_INPUT,
    NOT_FOUND,
    ALREADY_EXISTS,
    CORRUPT_STORE,
    STORE_ERROR,
    PERMISSION_REQUIRED,
    PERMISSION_DENIED,
}

sealed interface MemoryToolResult {
    data class Created(val memory: MemoryRecord) : MemoryToolResult
    data class Found(val memory: MemoryRecord) : MemoryToolResult
    data class Updated(val memory: MemoryRecord) : MemoryToolResult
    data class Deleted(val memoryId: String, val removedLinks: Int) : MemoryToolResult
    data class Memories(val items: List<MemoryRecord>, val total: Int) : MemoryToolResult
    data class SearchResults(val items: List<MemoryRecord>, val total: Int) : MemoryToolResult
    data class Linked(val link: MemoryLink, val created: Boolean) : MemoryToolResult
    data class Unlinked(val linkId: String) : MemoryToolResult
    data class Links(val items: List<MemoryLink>, val total: Int) : MemoryToolResult
    data class Error(
        val tool: String,
        val code: MemoryErrorCode,
        val message: String,
    ) : MemoryToolResult
}

interface MemoryStore {
    fun load(): MemoryData
    fun save(data: MemoryData)
}

class MemoryStoreException(message: String) : IllegalStateException(message)
class MemoryCorruptStoreException(message: String) : MemoryStoreException(message)
class MemoryStorageException(message: String) : MemoryStoreException(message)
