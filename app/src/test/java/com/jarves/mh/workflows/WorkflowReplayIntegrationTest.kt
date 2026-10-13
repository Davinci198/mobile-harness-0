package com.jarves.mh.workflows

import com.jarves.mh.model.RuntimeEvent
import com.jarves.mh.tools.ToolPermissionLevel
import com.jarves.mh.tools.ToolPermissionStore
import com.jarves.mh.tools.ToolPermissionStorage
import com.jarves.mh.tools.calc.CalculateTool
import com.jarves.mh.tools.device.ClipboardAdapter
import com.jarves.mh.tools.device.DeviceToolResult
import com.jarves.mh.tools.device.DeviceTools
import com.jarves.mh.tools.device.HttpsLinkAdapter
import com.jarves.mh.tools.device.NotificationAdapter
import com.jarves.mh.tools.device.ToastAdapter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowReplayIntegrationTest {

    private class MemoryStorage : ToolPermissionStorage {
        val map = mutableMapOf<String, String>()
        override fun read(key: String): String? = map[key]
        override fun write(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }
    }

    @Test
    fun approvedAskToolRunsViaRealToolGateCheck() = runBlocking {
        val store = ToolPermissionStore(MemoryStorage().apply { write("tool_permission_global", "ALLOW") })
        val gate = com.jarves.mh.tools.ToolPermissionGate(store)
        var toastShown: String? = null
        val deviceTools = DeviceTools(
            clipboard = object : ClipboardAdapter {
                override fun copy(text: String) {}
                override fun read(): String = ""
            },
            toast = object : ToastAdapter {
                override fun show(text: String): Boolean {
                    toastShown = text
                    return true
                }
            },
            notifications = object : NotificationAdapter {
                override fun notify(title: String, text: String) =
                    com.jarves.mh.tools.device.DeviceNotificationDispatch("chan", 1)
            },
            links = object : HttpsLinkAdapter {
                override fun open(url: String) {}
            },
            gate = gate,
        )
        val calc = CalculateTool(gate)
        val events = mutableListOf<RuntimeEvent>()
        val executor = WorkflowReplayExecutor(
            gate = gate,
            sessionId = "wf-test",
            emit = { events += it },
            requestApproval = { true },
            runStep = { step ->
                when (step.toolName) {
                    "Calculate" -> {
                        val r = calc.calculate(step.arguments["expression"].orEmpty())
                        if (r is com.jarves.mh.tools.calc.CalcToolResult.Error) StepOutcome(false, r.message)
                        else StepOutcome(true, "calc ok")
                    }
                    "DeviceToast" -> {
                        val r = deviceTools.showToast(step.arguments["text"].orEmpty())
                        if (r is DeviceToolResult.Error) StepOutcome(false, r.message)
                        else StepOutcome(true, "toast ok")
                    }
                    else -> StepOutcome(false, "unsupported")
                }
            },
        )
        val outcome = executor.run(
            AgentWorkflow(
                "wf",
                "My Workflow",
                "",
                listOf("my workflow"),
                listOf(
                    WorkflowStep("DeviceToast", mapOf("text" to "hello")),
                    WorkflowStep("Calculate", mapOf("expression" to "7+7")),
                ),
            ),
        )
        assertTrue("outcome=$outcome", outcome is ReplayOutcome.Completed)
        assertTrue("toast=$toastShown", toastShown == "hello")
    }
}
