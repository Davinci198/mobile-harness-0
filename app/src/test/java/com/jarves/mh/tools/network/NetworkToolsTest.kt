package com.jarves.mh.tools.network

import com.jarves.mh.tools.AIToolHook
import com.jarves.mh.tools.AIToolHookDecision
import com.jarves.mh.tools.ToolPermissionGate
import com.jarves.mh.tools.ToolPermissionLevel
import com.jarves.mh.tools.ToolPermissionStorage
import com.jarves.mh.tools.ToolPermissionStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

private class FakeHttp(var response: HttpResponseData) : HttpClientAdapter {
    var calls = 0
    var last: HttpRequestSpec? = null
    override suspend fun execute(request: HttpRequestSpec): HttpResponseData {
        calls++
        last = request
        return response
    }
}

private class FakeWeb(var page: WebPageResult) : WebPageExtractor {
    var calls = 0
    override suspend fun extract(request: WebPageRequest): WebPageResult {
        calls++
        return page
    }
}

private class FakeDownload(var result: DownloadResult) : DownloadAdapter {
    var calls = 0
    override suspend fun download(request: DownloadRequest): DownloadResult {
        calls++
        return result
    }
}

class NetworkToolsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var store: ToolPermissionStore
    private lateinit var http: FakeHttp
    private lateinit var web: FakeWeb
    private lateinit var download: FakeDownload
    private lateinit var tools: NetworkTools

    @Before
    fun setUp() {
        store = ToolPermissionStore(object : ToolPermissionStorage {
            private val values = mutableMapOf<String, String>()
            override fun read(key: String): String? = values[key]
            override fun write(key: String, value: String?) {
                if (value == null) values.remove(key) else values[key] = value
            }
        })
        store.globalDefault = ToolPermissionLevel.ALLOW
        http = FakeHttp(response())
        web = FakeWeb(WebPageResult("https://example.com", "https://example.com/", "Example", "text", emptyList(), false, "text/html"))
        download = FakeDownload(DownloadResult("/tmp/file", 3, "text/plain", "hash"))
        tools = NetworkTools(web, http, download, ToolPermissionGate(store))
    }

    @Test
    fun policyAcceptsHttpsAndRejectsUnsafeDestinations() {
        assertEquals("https://example.com", NetworkToolPolicy.validateUrl(" https://example.com "))
        for (url in listOf("http://example.com", "file:///x", "https://localhost", "https://127.0.0.1", "https://10.0.0.1", "https://user:pass@example.com")) {
            assertPolicyError { NetworkToolPolicy.validateUrl(url) }
        }
    }

    @Test
    fun policyRejectsCredentialsHeadersAndInvalidBodies() {
        assertPolicyError { NetworkToolPolicy.validateHeaders(mapOf("Authorization" to "Bearer x")) }
        assertPolicyError { NetworkToolPolicy.validateHeaders(mapOf("X-Test" to "bad\r\nvalue")) }
        assertPolicyError { NetworkToolPolicy.validateMethod("TRACE") }
        assertPolicyError { NetworkToolPolicy.validateRequestBody(ByteArray(1), "GET") }
        assertPolicyError { NetworkToolPolicy.validateFilename("../escape") }
    }

    @Test
    fun visitWebDelegatesAndReturnsPage() = runBlocking {
        val result = tools.visitWeb(WebPageRequest("https://example.com"))
        assertTrue(result is NetworkToolResult.Page)
        assertEquals(1, web.calls)
    }

    @Test
    fun httpRequestDelegatesBoundedSpec() = runBlocking {
        val result = tools.httpRequest(HttpRequestSpec("https://example.com", "GET"))
        assertTrue(result is NetworkToolResult.Http)
        assertEquals(1, http.calls)
        assertEquals("GET", http.last?.method)
    }

    @Test
    fun downloadDelegatesAndReturnsMetadata() = runBlocking {
        val result = tools.downloadFile(DownloadRequest("https://example.com/file", "file.txt"))
        assertTrue(result is NetworkToolResult.Downloaded)
        assertEquals(1, download.calls)
    }

    @Test
    fun askAndForbidBlockAllNetworkAdapters() = runBlocking {
        store.globalDefault = ToolPermissionLevel.ASK
        assertError(tools.visitWeb(WebPageRequest("https://example.com")), NetworkErrorCode.PERMISSION_REQUIRED)
        assertError(tools.httpRequest(HttpRequestSpec("https://example.com")), NetworkErrorCode.PERMISSION_REQUIRED)
        assertError(tools.downloadFile(DownloadRequest("https://example.com", "x")), NetworkErrorCode.PERMISSION_REQUIRED)
        store.globalDefault = ToolPermissionLevel.FORBID
        assertError(tools.httpRequest(HttpRequestSpec("https://example.com")), NetworkErrorCode.PERMISSION_DENIED)
        assertEquals(0, http.calls)
    }

    @Test
    fun hookBlockPreventsNetworkSideEffect() = runBlocking {
        val blocked = NetworkTools(web, http, download, ToolPermissionGate(store, listOf(object : AIToolHook {
            override fun onToolCallIntercept(toolName: String, explanation: String) = AIToolHookDecision.Block("no")
        })))
        assertError(blocked.httpRequest(HttpRequestSpec("https://example.com")), NetworkErrorCode.PERMISSION_DENIED)
        assertEquals(0, http.calls)
    }

    @Test
    fun hooksFireForSuccess() = runBlocking {
        val events = mutableListOf<String>()
        val hooked = NetworkTools(web, http, download, ToolPermissionGate(store), listOf(object : AIToolHook {
            override fun onToolExecutionStarted(toolName: String) { events += "start:$toolName" }
            override fun onToolExecutionResult(toolName: String, success: Boolean, summary: String) { events += "result:$toolName:$success" }
            override fun onToolExecutionFinished(toolName: String) { events += "end:$toolName" }
        }))
        hooked.httpRequest(HttpRequestSpec("https://example.com"))
        assertEquals(listOf("start:HttpRequest", "result:HttpRequest:true", "end:HttpRequest"), events)
    }

    @Test
    fun htmlExtractorBoundsTextAndKeepsHttpsLinks() = runBlocking {
        val adapter = FakeHttp(response("text/html", "<html><title> T </title><body><a href='/next'>Next</a><script>x</script>Hello</body></html>".toByteArray()))
        val page = HttpWebPageExtractor(adapter).extract(WebPageRequest("https://example.com", maxChars = 3))
        assertTrue(page.title.isNotBlank())
        assertTrue(page.text.length <= 3)
        assertTrue(page.links.size <= 100)
        assertTrue(page.truncated)
    }

    @Test
    fun downloaderWritesPartThenRenamesAndDoesNotOverwrite() = runBlocking {
        val root = temporaryFolder.newFolder("downloads")
        val adapter = FakeHttp(response("application/octet-stream", byteArrayOf(1, 2, 3)))
        val downloader = SandboxedDownloadAdapter(root, adapter)
        val result = downloader.download(DownloadRequest("https://example.com/file", "file.bin"))
        assertEquals(3, result.bytes)
        assertTrue(File(result.path).isFile)
        val duplicateError = try {
            downloader.download(DownloadRequest("https://example.com/file", "file.bin"))
            null
        } catch (error: Throwable) {
            error
        }
        assertTrue(duplicateError is NetworkOperationException)
    }

    @Test
    fun knownToolsIncludeNetworkTools() {
        assertTrue(ToolPermissionStore.knownTools.containsAll(listOf("VisitWeb", "HttpRequest", "DownloadFile")))
    }

    private fun response(contentType: String = "application/json", body: ByteArray = "{}".toByteArray()) = HttpResponseData(
        requestedUrl = "https://example.com",
        finalUrl = "https://example.com/",
        statusCode = 200,
        statusMessage = "OK",
        headers = emptyMap(),
        contentType = contentType,
        body = body,
        truncated = false,
    )

    private fun assertPolicyError(block: () -> Unit) {
        assertTrue(runCatching(block).exceptionOrNull() is NetworkOperationException)
    }

    private fun assertError(result: NetworkToolResult, code: NetworkErrorCode) {
        assertTrue(result.toString(), result is NetworkToolResult.Error)
        assertEquals(code, (result as NetworkToolResult.Error).code)
    }
}
