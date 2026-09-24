package com.jarves.mh.tools.network

import java.io.File
import java.security.MessageDigest
import java.nio.file.Files

class SandboxedDownloadAdapter(
    private val root: File,
    private val httpClient: HttpClientAdapter,
) : DownloadAdapter {
    private val rootPath = root.canonicalFile.absolutePath

    private fun isInsideRoot(file: File): Boolean =
        file.path == rootPath || file.path.startsWith(rootPath + File.separator)

    override suspend fun download(request: DownloadRequest): DownloadResult {
        val filename = NetworkToolPolicy.validateFilename(request.filename)
        if (request.maxBytes !in 1..NetworkToolLimits.MAX_DOWNLOAD_BYTES) {
            throw NetworkOperationException(NetworkErrorCode.INVALID_INPUT, "Download size limit is invalid")
        }
        val destination = File(root, filename).canonicalFile
        if (destination.path != rootPath && !destination.path.startsWith(rootPath + File.separator)) {
            throw NetworkOperationException(NetworkErrorCode.INVALID_INPUT, "Download path escapes sandbox")
        }
        if (destination.exists()) throw NetworkOperationException(NetworkErrorCode.FILE_EXISTS, "Destination already exists")
        val response = httpClient.execute(HttpRequestSpec(
            url = request.url,
            method = "GET",
            maxResponseBytes = request.maxBytes,
            connectTimeoutMillis = request.timeoutMillis,
            readTimeoutMillis = request.timeoutMillis,
        ))
        if (response.statusCode !in 200..299) throw NetworkOperationException(NetworkErrorCode.NETWORK_ERROR, "Download failed with HTTP ${response.statusCode}")
        if (response.truncated || response.body.size > request.maxBytes) {
            throw NetworkOperationException(NetworkErrorCode.FILE_TOO_LARGE, "Download is too large")
        }
        val parent = destination.parentFile ?: throw NetworkOperationException(NetworkErrorCode.ADAPTER_ERROR, "Download directory is unavailable")
        if (!parent.exists() && !parent.mkdirs()) throw NetworkOperationException(NetworkErrorCode.ADAPTER_ERROR, "Download directory could not be created")
        val partial = File.createTempFile(".mh-", ".part", parent).also {
            if (!isInsideRoot(it.canonicalFile)) {
                it.delete()
                throw NetworkOperationException(NetworkErrorCode.INVALID_INPUT, "Download path escapes sandbox")
            }
        }
        try {
            partial.writeBytes(response.body)
            try {
                Files.createLink(destination.toPath(), partial.toPath())
                partial.delete()
            } catch (error: Throwable) {
                partial.delete()
                throw NetworkOperationException(NetworkErrorCode.FILE_EXISTS, "Destination already exists or cannot be published")
            }
        } catch (error: Throwable) {
            partial.delete()
            throw error
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(response.body).joinToString("") { "%02x".format(it) }
        return DownloadResult(destination.absolutePath, response.body.size.toLong(), response.contentType, digest)
    }
}
