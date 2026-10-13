package com.jarves.mh.workflows

import com.jarves.mh.tools.ToolPermissionGate
import com.jarves.mh.tools.ToolPermissionLevel
import com.jarves.mh.tools.ToolPermissionStorage
import com.jarves.mh.tools.ToolPermissionStore
import com.jarves.mh.tools.calc.CalculateTool
import com.jarves.mh.tools.calc.SleepTool
import com.jarves.mh.tools.device.AccessibilityAdapter
import com.jarves.mh.tools.device.ClipboardAdapter
import com.jarves.mh.tools.device.DeviceTools
import com.jarves.mh.tools.device.HttpsLinkAdapter
import com.jarves.mh.tools.device.NotificationAdapter
import com.jarves.mh.tools.device.ToastAdapter
import com.jarves.mh.tools.device.UiNodeSnapshot
import com.jarves.mh.tools.file.FileTools
import com.jarves.mh.tools.memory.MemoryData
import com.jarves.mh.tools.memory.MemoryStore
import com.jarves.mh.tools.memory.MemoryTools
import com.jarves.mh.tools.network.DownloadAdapter
import com.jarves.mh.tools.network.HttpClientAdapter
import com.jarves.mh.tools.network.NetworkTools
import com.jarves.mh.tools.network.WebPageExtractor
import com.jarves.mh.tools.terminal.TerminalCommandRun
import com.jarves.mh.tools.terminal.TerminalCommandRunner
import com.jarves.mh.tools.terminal.TerminalTools
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private class RunnerStorage : ToolPermissionStorage {
    val map = mutableMapOf<String, String>()
    override fun read(key: String): String? = map[key]
    override fun write(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
}

private class FakeAccessibility(
    var enabled: Boolean = true,
    var tree: List<UiNodeSnapshot> = emptyList(),
    var clickResult: Boolean = true,
) : AccessibilityAdapter {
    var treeReads = 0
    val clicks = mutableListOf<String>()
    override fun isServiceEnabled(): Boolean = enabled
    override fun readTree(maxNodes: Int): List<UiNodeSnapshot>? {
        treeReads++
        return tree.take(maxNodes)
    }

    override fun clickNode(nodeId: String): Boolean {
        clicks += nodeId
        return clickResult
    }
}

class WorkflowStepRunnerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var store: ToolPermissionStore
    private lateinit var accessibility: FakeAccessibility
    private lateinit var runner: WorkflowStepRunner

    @Before
    fun setUp() {
        store = ToolPermissionStore(RunnerStorage())
        store.globalDefault = ToolPermissionLevel.ALLOW
        // Risk rule: UI tools are SENSITIVE_READ/DEVICE_CONTROL, so a blanket
        // global ALLOW does not auto-run them — opt in per tool.
        listOf("UiReadTree", "UiClick", "UiStatus").forEach {
            store.setOverride(it, ToolPermissionLevel.ALLOW)
        }
        val gate = ToolPermissionGate(store)
        accessibility = FakeAccessibility()
        runner = WorkflowStepRunner(
            projectSlug = "wf-test",
            fileTools = FileTools(temporaryFolder.root, gate),
            terminalTools = TerminalTools(object : TerminalCommandRunner {
                override fun run(
                    projectSlug: String,
                    command: String,
                    timeoutMs: Long,
                    rows: Int,
                    columns: Int,
                ) = TerminalCommandRun("", 0, false)
            }, gate),
            networkTools = NetworkTools(
                object : WebPageExtractor {
                    override suspend fun extract(request: com.jarves.mh.tools.network.WebPageRequest) =
                        throw UnsupportedOperationException("not used in this test")
                },
                object : HttpClientAdapter {
                    override suspend fun execute(request: com.jarves.mh.tools.network.HttpRequestSpec) =
                        throw UnsupportedOperationException("not used in this test")
                },
                object : DownloadAdapter {
                    override suspend fun download(request: com.jarves.mh.tools.network.DownloadRequest) =
                        throw UnsupportedOperationException("not used in this test")
                },
                gate,
            ),
            deviceTools = DeviceTools(
                clipboard = object : ClipboardAdapter {
                    override fun copy(text: String) {}
                    override fun read(): String = ""
                },
                toast = object : ToastAdapter {
                    override fun show(text: String): Boolean = true
                },
                notifications = object : NotificationAdapter {
                    override fun notify(title: String, text: String) =
                        com.jarves.mh.tools.device.DeviceNotificationDispatch("chan", 1)
                },
                links = object : HttpsLinkAdapter {
                    override fun open(url: String) {}
                },
                accessibility = accessibility,
                gate = gate,
            ),
            memoryTools = MemoryTools(object : MemoryStore {
                var data = MemoryData()
                override fun load(): MemoryData = data
                override fun save(data: MemoryData) {
                    this.data = data
                }
            }, gate),
            calculateTool = CalculateTool(gate),
            sleepTool = SleepTool(gate),
        )
    }

    private fun step(toolName: String, arguments: Map<String, String> = emptyMap()) =
        WorkflowStep(toolName, arguments)

    @Test
    fun uiReadTreeDispatchesAndReportsNodeCount() = runBlocking {
        accessibility.tree = List(3) {
            UiNodeSnapshot("id$it", "android.widget.TextView", "t", "", false, true, 0, 0, 0, 0)
        }
        val outcome = runner.run(step("UiReadTree"))
        assertTrue(outcome.summary, outcome.ok)
        assertEquals("read 3 UI nodes", outcome.summary)
        assertEquals(1, accessibility.treeReads)
    }

    @Test
    fun uiStatusDispatchesAndReportsServiceState() = runBlocking {
        accessibility.enabled = true
        val on = runner.run(step("UiStatus"))
        assertTrue(on.ok)
        assertEquals("accessibility enabled", on.summary)

        accessibility.enabled = false
        val off = runner.run(step("UiStatus"))
        assertTrue(off.ok)
        assertEquals("accessibility disabled", off.summary)
    }

    @Test
    fun uiClickDispatchesWithNodeId() = runBlocking {
        val outcome = runner.run(step("UiClick", mapOf("nodeId" to "node-7")))
        assertTrue(outcome.summary, outcome.ok)
        assertEquals("clicked UI node node-7", outcome.summary)
        assertEquals(listOf("node-7"), accessibility.clicks)
    }

    @Test
    fun uiClickFailureIsReportedAsFailedStep() = runBlocking {
        accessibility.clickResult = false
        val outcome = runner.run(step("UiClick", mapOf("nodeId" to "node-9")))
        assertFalse(outcome.ok)
        assertTrue(outcome.summary, outcome.summary.contains("could not be clicked"))
        assertEquals(listOf("node-9"), accessibility.clicks)
    }

    @Test
    fun uiReadTreeWithoutServiceFails() = runBlocking {
        accessibility.enabled = false
        val outcome = runner.run(step("UiReadTree"))
        assertFalse(outcome.ok)
        assertTrue(outcome.summary, outcome.summary.contains("not enabled"))
        assertEquals(0, accessibility.treeReads)
    }
}
