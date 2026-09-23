package com.jarves.mh.runtime

import com.jarves.mh.model.RuntimeEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadlessCliJsonlParserTest {
    private val sessionId = "session-1"

    // --- Hermes 0.21.x top-level stream-json ---

    @Test
    fun hermesTopLevelTextBecomesAssistantDelta() {
        val parsed = HermesJsonlParser.parseLine(
            """{"type": "text", "text": "\n\nroot", "timestamp": 1790120792535}""",
            sessionId,
        )
        assertTrue(parsed is CliParsed.Events)
        val events = (parsed as CliParsed.Events).events
        assertEquals(1, events.size)
        val delta = events.single() as RuntimeEvent.AssistantDelta
        assertEquals("\n\nroot", delta.text)
        assertEquals(sessionId, delta.sessionId)
    }

    @Test
    fun hermesTopLevelToolUseStartsTool() {
        val parsed = HermesJsonlParser.parseLine(
            """{"type": "tool_use", "name": "terminal", "input": {"command": "whoami", "timeout": 10}, "timestamp": 1790120788781}""",
            sessionId,
        )
        assertTrue(parsed is CliParsed.Events)
        val started = (parsed as CliParsed.Events).events.single() as RuntimeEvent.ToolStarted
        assertEquals("terminal", started.toolName)
        assertEquals("whoami", started.detail)
    }

    @Test
    fun hermesTopLevelToolResultCompletesTool() {
        val parsed = HermesJsonlParser.parseLine(
            """{"type": "tool_result", "name": "terminal", "output": "{\"output\": \"root\", \"exit_code\": 0}", "is_error": false, "timestamp": 1790120789927}""",
            sessionId,
        )
        assertTrue(parsed is CliParsed.Events)
        val completed = (parsed as CliParsed.Events).events.single() as RuntimeEvent.ToolCompleted
        assertEquals("terminal", completed.toolName)
        assertTrue(completed.summary.contains("root"))
    }

    @Test
    fun hermesSystemInitIsIgnored() {
        val parsed = HermesJsonlParser.parseLine(
            """{"type": "system", "subtype": "init", "model": "openai/gpt-oss-20b", "session_id": "s"}""",
            sessionId,
        )
        assertEquals(CliParsed.IGNORED, parsed)
    }

    @Test
    fun hermesSuccessResultIsTerminalWithFinalTextFallback() {
        val parsed = HermesJsonlParser.parseLine(
            """{"type": "result", "session_id": "s", "exit_code": 0, "text": "root", "tokens": {"input": 1}}""",
            sessionId,
        )
        assertTrue(parsed is CliParsed.Events)
        val events = parsed as CliParsed.Events
        assertTrue(events.terminal)
        assertEquals(null, events.failed)
        assertEquals("root", events.finalText)
        assertTrue(events.events.isEmpty())
    }

    @Test
    fun hermesNonZeroExitIsTerminalFailure() {
        val parsed = HermesJsonlParser.parseLine(
            """{"type": "result", "exit_code": 1, "error": "boom"}""",
            sessionId,
        )
        assertTrue(parsed is CliParsed.Events)
        val events = parsed as CliParsed.Events
        assertTrue(events.terminal)
        assertEquals("boom", events.failed)
    }

    @Test
    fun hermesLegacyNestedPartTextStillWorks() {
        val parsed = HermesJsonlParser.parseLine(
            """{"type": "message", "part": {"type": "text", "text": "hello"}}""",
            sessionId,
        )
        assertTrue(parsed is CliParsed.Events)
        val delta = (parsed as CliParsed.Events).events.single() as RuntimeEvent.AssistantDelta
        assertEquals("hello", delta.text)
    }

    // --- OpenCode v2.0.14 JSON-RPC session.event ---

    @Test
    fun openCodeJsonRpcTextDeltaBecomesAssistantDelta() {
        val line = """
            {"jsonrpc":"2.0","method":"session.event","params":{"sessionId":"x","event":{"type":"assistant/chunk","seq":12,"data":{"turn":1,"step":1,"chunk":{"type":"text-delta","index":0,"text":"Da, sunt DeepSeek."}}}}}
        """.trimIndent()
        val parsed = OpenCodeJsonlParser.parseLine(line, sessionId)
        assertTrue(parsed is CliParsed.Events)
        val delta = (parsed as CliParsed.Events).events.single() as RuntimeEvent.AssistantDelta
        assertEquals("Da, sunt DeepSeek.", delta.text)
    }

    @Test
    fun openCodeJsonRpcReasoningDeltaStartsBlockOnce() {
        fun line(text: String) = """
            {"jsonrpc":"2.0","method":"session.event","params":{"sessionId":"x","event":{"type":"assistant/chunk","seq":13,"data":{"turn":1,"step":1,"chunk":{"type":"reasoning-delta","index":0,"text":"$text"}}}}}
        """.trimIndent()
        val sid = "reason-block-${System.nanoTime()}"
        val first = OpenCodeJsonlParser.parseLine(line("The"), sid) as CliParsed.Events
        val second = OpenCodeJsonlParser.parseLine(line(" user"), sid) as CliParsed.Events
        val r1 = first.events.single() as RuntimeEvent.ReasoningSummary
        val r2 = second.events.single() as RuntimeEvent.ReasoningSummary
        assertTrue(r1.startsNewBlock)
        assertTrue(!r2.startsNewBlock)
        assertEquals("The", r1.summary)
        assertEquals(" user", r2.summary)
    }

    @Test
    fun openCodeSessionStatusIdleIsTerminal() {
        val line = """
            {"jsonrpc":"2.0","method":"session.status","params":{"sessionId":"x","status":"idle"}}
        """.trimIndent()
        val parsed = OpenCodeJsonlParser.parseLine(line, sessionId)
        assertTrue(parsed is CliParsed.Events)
        assertTrue((parsed as CliParsed.Events).terminal)
    }

    @Test
    fun openCodeSessionStatusRunningIsNotTerminal() {
        val line = """
            {"jsonrpc":"2.0","method":"session.status","params":{"sessionId":"x","status":"running"}}
        """.trimIndent()
        val parsed = OpenCodeJsonlParser.parseLine(line, sessionId)
        assertTrue(parsed is CliParsed.Events)
        assertTrue(!(parsed as CliParsed.Events).terminal)
    }

    @Test
    fun openCodeSuccessfulTurnEndIsNotTerminal() {
        val line = """
            {"jsonrpc":"2.0","method":"session.event","params":{"sessionId":"x","event":{"type":"turn/end","seq":122,"data":{"turn":1,"reason":{"kind":"completed"}}}}}
        """.trimIndent()
        val parsed = OpenCodeJsonlParser.parseLine(line, sessionId)
        assertTrue(parsed is CliParsed.Events)
        val events = parsed as CliParsed.Events
        assertTrue(!events.terminal)
        assertEquals(null, events.failed)
    }

    @Test
    fun openCodeTurnEndErrorIsTerminalFailure() {
        val line = """
            {"jsonrpc":"2.0","method":"session.event","params":{"sessionId":"x","event":{"type":"turn/end","data":{"turn":1,"reason":{"kind":"error","error":{"message":"provider exploded"}}}}}}
        """.trimIndent()
        val parsed = OpenCodeJsonlParser.parseLine(line, sessionId)
        assertTrue(parsed is CliParsed.Events)
        val events = parsed as CliParsed.Events
        assertTrue(events.terminal)
        assertEquals("provider exploded", events.failed)
    }

    @Test
    fun openCodeRpcReplyWithoutMethodIsIgnored() {
        val line = """{"jsonrpc":"2.0","id":1,"result":{"serverInfo":{"name":"x"}}}"""
        assertEquals(CliParsed.IGNORED, OpenCodeJsonlParser.parseLine(line, sessionId))
    }

    @Test
    fun openCodeAssistantMessageSuppliesFinalTextFallback() {
        val line = """
            {"jsonrpc":"2.0","method":"session.event","params":{"sessionId":"x","event":{"type":"assistant/message","data":{"message":{"role":"assistant","content":[{"type":"reasoning","text":"thinking"},{"type":"text","text":"Da, sunt DeepSeek."}]}}}}}
        """.trimIndent()
        val parsed = OpenCodeJsonlParser.parseLine(line, sessionId)
        assertTrue(parsed is CliParsed.Events)
        val events = parsed as CliParsed.Events
        assertEquals("Da, sunt DeepSeek.", events.finalText)
        assertTrue(events.events.isEmpty())
    }

    @Test
    fun openCodeToolCallStartsTool() {
        val line = """
            {"jsonrpc":"2.0","method":"session.event","params":{"sessionId":"x","event":{"type":"tool/call","data":{"callId":"c1","name":"bash","arguments":"{\"command\":\"ls\"}"}}}}
        """.trimIndent()
        val parsed = OpenCodeJsonlParser.parseLine(line, sessionId)
        assertTrue(parsed is CliParsed.Events)
        val started = (parsed as CliParsed.Events).events.single() as RuntimeEvent.ToolStarted
        assertEquals("bash", started.toolName)
        assertEquals("ls", started.detail)
    }

    @Test
    fun openCodeLegacyPartTextStillWorks() {
        val parsed = OpenCodeJsonlParser.parseLine(
            """{"type": "message.part.updated", "part": {"type": "text", "text": "legacy"}}""",
            sessionId,
        )
        assertTrue(parsed is CliParsed.Events)
        val delta = (parsed as CliParsed.Events).events.single() as RuntimeEvent.AssistantDelta
        assertEquals("legacy", delta.text)
    }

    @Test
    fun malformedLinesAreIgnored() {
        assertEquals(CliParsed.IGNORED, HermesJsonlParser.parseLine("not json", sessionId))
        assertEquals(CliParsed.IGNORED, OpenCodeJsonlParser.parseLine("not json", sessionId))
        assertEquals(CliParsed.IGNORED, HermesJsonlParser.parseLine("", sessionId))
    }
}
