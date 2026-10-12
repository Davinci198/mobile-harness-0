package com.jarves.mh.workflows

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowCatalogTest {

    private fun workflow(steps: List<WorkflowStep>) =
        AgentWorkflow("id", "Name", "", listOf("phrase"), steps)

    @Test
    fun supportedToolsCoverAllCategories() {
        listOf("Read", "Write", "Edit", "Grep", "Glob", "Bash", "Calculate", "Sleep",
            "VisitWeb", "HttpRequest", "DownloadFile", "ClipboardCopy", "ClipboardRead",
            "DeviceToast", "DeviceNotification", "DeviceOpenHttpsLink",
            "MemoryCreate", "MemoryRead", "MemorySearch", "MemoryList").forEach {
            assertTrue("$it should be supported", WorkflowCatalog.supports(it))
        }
        assertFalse(WorkflowCatalog.supports("rm_rf_everything"))
        assertFalse(WorkflowCatalog.supports(""))
    }

    @Test
    fun executableWorkflowPasses() {
        val w = workflow(
            listOf(
                WorkflowStep("Calculate", mapOf("expression" to "1+1")),
                WorkflowStep("Write", mapOf("path" to "a.txt", "content" to "hello")),
            ),
        )
        assertTrue(WorkflowCatalog.isExecutable(w))
        assertNull(WorkflowCatalog.rejectionReason(w))
    }

    @Test
    fun unknownToolMakesWorkflowNonExecutable() {
        val w = workflow(listOf(WorkflowStep("mail_send", mapOf("to" to "x"))))
        assertFalse(WorkflowCatalog.isExecutable(w))
        assertTrue(WorkflowCatalog.rejectionReason(w)!!.contains("unsupported tool"))
    }

    @Test
    fun missingRequiredArgumentMakesWorkflowNonExecutable() {
        val w = workflow(listOf(WorkflowStep("Write", mapOf("path" to "a.txt"))))
        assertFalse(WorkflowCatalog.isExecutable(w))
        assertTrue(WorkflowCatalog.rejectionReason(w)!!.contains("content"))
    }

    @Test
    fun blankRequiredArgumentIsMissing() {
        val w = workflow(listOf(WorkflowStep("Read", mapOf("path" to "  "))))
        assertFalse(WorkflowCatalog.isExecutable(w))
    }

    @Test
    fun emptyWorkflowIsNotExecutable() {
        assertFalse(WorkflowCatalog.isExecutable(workflow(emptyList())))
    }
}
