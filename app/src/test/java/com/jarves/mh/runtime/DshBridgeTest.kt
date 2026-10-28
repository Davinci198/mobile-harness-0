package com.jarves.mh.runtime

import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.DEEPSEEK_HARNESS_PROVIDERS
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.ProviderProtocol
import com.jarves.mh.model.defaultDshApiForProvider
import com.jarves.mh.model.inferredDshApiForUrl
import com.jarves.mh.model.providerProtocolForAgent
import com.jarves.mh.model.providersForAgent
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

class DshHeadlessParserTest {
    @Test
    fun emptyReasoningHeadingStartsSection() {
        assertEquals(DshLine.ReasoningHeading, DshHeadlessParser.parseLine("dsh: reasoning:"))
    }

    @Test
    fun inlineReasoningDeltaIsReasoning() {
        val parsed = DshHeadlessParser.parseLine("dsh: reasoning: checking the workspace")
        assertEquals(DshLine.Reasoning("checking the workspace"), parsed)
    }

    @Test
    fun errorDiagnosticIsDiagnostic() {
        val parsed = DshHeadlessParser.parseLine("dsh: MISSING_CREDENTIAL: llm-deepseek: no API key")
        assertTrue(parsed is DshLine.Diagnostic)
        assertEquals("MISSING_CREDENTIAL: llm-deepseek: no API key", (parsed as DshLine.Diagnostic).text)
    }

    @Test
    fun authFailureIsDiagnostic() {
        val parsed = DshHeadlessParser.parseLine("dsh: AUTH: Authentication Fails, key is invalid")
        assertTrue(parsed is DshLine.Diagnostic)
    }

    @Test
    fun plainTextIsAnswer() {
        val parsed = DshHeadlessParser.parseLine("Here is the summary of your project.")
        assertEquals(DshLine.Answer("Here is the summary of your project."), parsed)
    }

    @Test
    fun whitespaceIsTrimmed() {
        val parsed = DshHeadlessParser.parseLine("   done   ")
        assertEquals(DshLine.Answer("done"), parsed)
    }
}

class DshSdkProtocolParserTest {
    private val parser = DshSdkProtocolParser("session-1")

    @Test
    fun parsesHandshakeAndLifecycle() {
        assertEquals(DshSdkProtocolEvent.Initialized, parser.parseLine("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}"))
        assertEquals(DshSdkProtocolEvent.ShutdownAcknowledged, parser.parseLine("{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{}}"))
        assertEquals(
            DshSdkProtocolEvent.Status(true),
            parser.parseLine(notification("session.status", JSONObject().put("sessionId", "session-1").put("status", "running"))),
        )
        assertEquals(
            DshSdkProtocolEvent.Status(false),
            parser.parseLine(notification("session.status", JSONObject().put("sessionId", "session-1").put("status", "idle"))),
        )
    }

    @Test
    fun accumulatesReasoningAndClosesItsBlock() {
        val first = parser.parseLine(
            sessionEvent(
                "assistant/chunk",
                JSONObject().put("turn", 1).put("step", 2).put(
                    "chunk",
                    JSONObject().put("type", "reasoning-delta").put("index", 0).put("text", "Checking "),
                ),
            ),
        )
        val second = parser.parseLine(
            sessionEvent(
                "assistant/chunk",
                JSONObject().put("turn", 1).put("step", 2).put(
                    "chunk",
                    JSONObject().put("type", "reasoning-delta").put("index", 0).put("text", "files"),
                ),
            ),
        )
        val end = parser.parseLine(
            sessionEvent(
                "assistant/chunk",
                JSONObject().put("turn", 1).put("step", 2).put(
                    "chunk",
                    JSONObject().put("type", "block-end").put("index", 0).put(
                        "block",
                        JSONObject().put("type", "reasoning").put("text", "Checking files"),
                    ),
                ),
            ),
        )

        assertTrue(first is DshSdkProtocolEvent.Reasoning && first.startsNewBlock && first.text == "Checking ")
        assertTrue(second is DshSdkProtocolEvent.Reasoning && !second.startsNewBlock && second.text == "Checking files")
        assertTrue(end is DshSdkProtocolEvent.Reasoning && end.isFinal && end.text == "Checking files")
    }

    @Test
    fun parsesToolCallResultAndAssistantText() {
        val call = parser.parseLine(sessionEvent("tool/call", JSONObject()
            .put("callId", "call-1").put("name", "bash")
            .put("arguments", "{\"command\":\"pwd && ls\"}")))
        val resultBlock = JSONObject().put("type", "tool-result").put("toolCallId", "call-1")
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "done")))
        val result = parser.parseLine(sessionEvent("tool/result", JSONObject()
            .put("message", JSONObject().put("content", JSONArray().put(resultBlock)))))
        val answer = parser.parseLine(sessionEvent("assistant/message", JSONObject()
            .put("message", JSONObject().put("content", JSONArray().put(
                JSONObject().put("type", "text").put("text", "Finished"),
            )))))

        assertTrue(call is DshSdkProtocolEvent.ToolStarted && call.name == "Bash" && call.detail == "pwd && ls")
        assertTrue(result is DshSdkProtocolEvent.ToolCompleted && result.name == "Bash" && result.summary == "done")
        assertEquals(DshSdkProtocolEvent.AssistantText("Finished"), answer)
    }

    @Test
    fun streamsTextDeltasAndDoesNotRepeatCompletedMessage() {
        val first = parser.parseLine(sessionEvent("assistant/chunk", JSONObject()
            .put("turn", 1).put("step", 1).put("chunk", JSONObject()
                .put("type", "text-delta").put("index", 0).put("text", "Hel"))))
        val second = parser.parseLine(sessionEvent("assistant/chunk", JSONObject()
            .put("turn", 1).put("step", 1).put("chunk", JSONObject()
                .put("type", "text-delta").put("index", 0).put("text", "lo"))))
        val end = parser.parseLine(sessionEvent("assistant/chunk", JSONObject()
            .put("turn", 1).put("step", 1).put("chunk", JSONObject()
                .put("type", "block-end").put("index", 0).put("block", JSONObject()
                    .put("type", "text").put("text", "Hello")))))
        val completed = parser.parseLine(sessionEvent("assistant/message", JSONObject()
            .put("message", JSONObject().put("content", JSONArray().put(
                JSONObject().put("type", "text").put("text", "Hello"),
            )))))

        assertEquals(DshSdkProtocolEvent.AssistantText("Hel"), first)
        assertEquals(DshSdkProtocolEvent.AssistantText("lo"), second)
        assertEquals(DshSdkProtocolEvent.Ignored, end)
        assertEquals(DshSdkProtocolEvent.Ignored, completed)
    }

    @Test
    fun normalTurnEndCountsAsCompletedActivity() {
        val completed = parser.parseLine(
            sessionEvent(
                "turn/end",
                JSONObject().put("reason", JSONObject().put("kind", "completed")),
            ),
        )

        assertEquals(DshSdkProtocolEvent.TurnCompleted, completed)
    }

    @Test
    fun bootWarningLineIsIgnored() {
        assertEquals(
            DshSdkProtocolEvent.Ignored,
            parser.parseLine("dsh: warning: 5 entries did not activate"),
        )
    }

    @Test
    fun bootFailureLineStillFails() {
        assertEquals(
            DshSdkProtocolEvent.Failed("startup failed: 1 required plugin did not activate"),
            parser.parseLine("dsh: startup failed: 1 required plugin did not activate"),
        )
    }

    @Test
    fun warningDiagnosticContinuationIsIgnored() {
        assertEquals(
            DshSdkProtocolEvent.Ignored,
            parser.parseLine("tool-fs (@deepseek-ai/dsh-tool-fs): pending (waiting for service: fs)"),
        )
    }

    @Test
    fun staleSessionEventsFromAPreviousTurnAreIgnored() {
        // A reused SDK process keeps appending to one capture file, so the next
        // turn can still read the tail of the session it replaced. Nothing from
        // it may reach the UI of the current turn.
        assertEquals(
            DshSdkProtocolEvent.Ignored,
            parser.parseLine(
                notification(
                    "session.status",
                    JSONObject().put("sessionId", "session-old").put("status", "running"),
                ),
            ),
        )
        assertEquals(
            DshSdkProtocolEvent.Ignored,
            parser.parseLine(
                notification(
                    "session.event",
                    JSONObject().put("sessionId", "session-old").put(
                        "event",
                        JSONObject().put("type", "assistant/chunk").put("seq", 1).put("time", 1).put(
                            "data",
                            JSONObject().put("turn", 1).put("step", 1).put(
                                "chunk",
                                JSONObject().put("type", "text-delta").put("index", 0).put("text", "leak"),
                            ),
                        ),
                    ),
                ),
            ),
        )
    }

    private fun sessionEvent(type: String, data: JSONObject): String = notification(
        "session.event",
        JSONObject().put("sessionId", "session-1").put(
            "event",
            JSONObject().put("type", type).put("seq", 1).put("time", 1).put("data", data),
        ),
    )

    @Test
    fun replaysCompactedTextStreamFromAssistantMessage() {
        val stream = JSONArray()
            .put(JSONObject().put("type", "text-chunks").put("index", 0).put("time0", 1)
                .put("dt", JSONArray().put(0).put(1)).put("texts", JSONArray().put("Hel").put("lo")))
            .put(JSONObject().put("type", "reasoning-chunks").put("index", 1).put("time0", 1)
                .put("dt", JSONArray().put(0)).put("texts", JSONArray().put("Checking files")))
        val answer = parser.parseLine(sessionEvent("assistant/message", JSONObject()
            .put("turn", 1).put("step", 1)
            .put("stream", stream)
            .put("message", JSONObject().put("content", JSONArray().put(
                JSONObject().put("type", "text").put("text", "Hello"),
            )))))

        // The compacted text-chunks replay as one delta, and the completed
        // message content dedups against them instead of repeating.
        assertEquals(DshSdkProtocolEvent.AssistantText("Hello"), answer)
    }

    @Test
    fun reasoningOnlyStreamSurfacesThinking() {
        val stream = JSONArray().put(JSONObject().put("type", "reasoning-chunks").put("index", 0)
            .put("dt", JSONArray().put(0)).put("texts", JSONArray().put("Planning the change")))
        val event = parser.parseLine(sessionEvent("assistant/message", JSONObject()
            .put("turn", 1).put("step", 1)
            .put("stream", stream)
            .put("message", JSONObject().put("content", JSONArray().put(
                JSONObject().put("type", "reasoning").put("text", "Planning the change"),
            )))))

        assertTrue(event is DshSdkProtocolEvent.Reasoning)
        assertEquals("Planning the change", (event as DshSdkProtocolEvent.Reasoning).text)
        assertTrue(event.startsNewBlock)
        assertTrue(event.isFinal)
    }

    @Test
    fun blockEndKeyedByNestedIndexDoesNotRepeatTheMessage() {
        // Mirrors the real 0.2.0 stream: text-chunks carry a top-level index while
        // chunk records nest their own index inside the chunk. The block-end must
        // be keyed by that nested index, or it misses the accumulated text and
        // appends the completed message a second time.
        val stream = JSONArray()
            .put(JSONObject().put("type", "chunk").put("time", 1).put("chunk", JSONObject()
                .put("type", "block-start").put("index", 1).put("blockType", "text")))
            .put(JSONObject().put("type", "text-chunks").put("index", 1).put("time0", 1)
                .put("dt", JSONArray()).put("texts", JSONArray().put("Hel").put("lo")))
            .put(JSONObject().put("type", "chunk").put("time", 2).put("chunk", JSONObject()
                .put("type", "block-end").put("index", 1).put("block", JSONObject()
                    .put("type", "text").put("text", "Hello"))))
        val answer = parser.parseLine(sessionEvent("assistant/message", JSONObject()
            .put("turn", 1).put("step", 1)
            .put("stream", stream)
            .put("message", JSONObject().put("content", JSONArray().put(
                JSONObject().put("type", "text").put("text", "Hello"),
            )))))

        // The completed text must surface once, not doubled.
        assertEquals(DshSdkProtocolEvent.AssistantText("Hello"), answer)
    }

    @Test
    fun replaysRawChunkRecordsInsideStream() {
        val stream = JSONArray()
            .put(JSONObject().put("type", "chunk").put("time", 1).put("chunk", JSONObject()
                .put("type", "text-delta").put("index", 0).put("text", "Hel")))
            .put(JSONObject().put("type", "chunk").put("time", 2).put("chunk", JSONObject()
                .put("type", "text-delta").put("index", 0).put("text", "lo")))
        val answer = parser.parseLine(sessionEvent("assistant/message", JSONObject()
            .put("turn", 1).put("step", 1)
            .put("stream", stream)
            .put("message", JSONObject().put("content", JSONArray().put(
                JSONObject().put("type", "text").put("text", "Hello"),
            )))))

        assertEquals(DshSdkProtocolEvent.AssistantText("Hello"), answer)
    }

    @Test
    fun toolResultIn020ReadsMessageToolCallIdAndErrorReason() {
        val call = parser.parseLine(sessionEvent("tool/call", JSONObject()
            .put("callId", "call-9").put("name", "edit")
            .put("arguments", "{\"file_path\":\"a.txt\"}")))
        val result = parser.parseLine(sessionEvent("tool/result", JSONObject()
            .put("message", JSONObject().put("toolCallId", "call-9")
                .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "wrote")))
                .put("isError", false))
            .put("error", JSONObject().put("name", "ToolError").put("code", "TOOL_FAILED")
                .put("reason", "file missing"))))

        assertTrue(call is DshSdkProtocolEvent.ToolStarted && call.name == "Edit")
        assertTrue(result is DshSdkProtocolEvent.ToolCompleted)
        assertEquals("call-9", (result as DshSdkProtocolEvent.ToolCompleted).callId)
        assertEquals("file missing", result.summary)
    }

    @Test
    fun abortedTurnEndReportsCancellation() {
        val event = parser.parseLine(sessionEvent("turn/end",
            JSONObject().put("reason", JSONObject().put("kind", "aborted"))))

        assertTrue(event is DshSdkProtocolEvent.Failed)
    }

    @Test
    fun maxTokensTurnEndCountsAsCompletedActivity() {
        val event = parser.parseLine(sessionEvent("turn/end",
            JSONObject().put("reason", JSONObject().put("kind", "max-tokens"))))

        assertEquals(DshSdkProtocolEvent.TurnCompleted, event)
    }

    private fun notification(method: String, params: JSONObject): String = JSONObject()
        .put("jsonrpc", "2.0")
        .put("method", method)
        .put("params", params)
        .toString()
}

class DshStuckGuardTest {
    @Test
    fun multipleFailingResultsTriggerLoopAbort() {
        val guard = DshStuckGuard()
        repeat(DshStuckGuard.LIMIT - 1) {
            assertFalse(guard.noteToolCompleted("Error: cannot read a.txt: not found"))
        }
        assertTrue(guard.noteToolCompleted("Error: cannot read a.txt: not found"))
    }

    @Test
    fun consecutiveSuccessResetsLoopStreak() {
        val guard = DshStuckGuard()
        repeat(DshStuckGuard.LIMIT - 1) {
            assertFalse(guard.noteToolCompleted("Error: cannot read a.txt: not found"))
        }
        assertFalse(guard.noteToolCompleted("done"))
        repeat(DshStuckGuard.LIMIT - 1) {
            assertFalse(guard.noteToolCompleted("Error: cannot read a.txt: not found"))
        }
        assertTrue(guard.noteToolCompleted("Error: cannot read a.txt: not found"))
    }

    @Test
    fun resetClearsLoopStreak() {
        val guard = DshStuckGuard()
        repeat(DshStuckGuard.LIMIT - 1) {
            guard.noteToolCompleted("invalid arguments: \"replace_all\" must be a boolean")
        }
        guard.reset()
        repeat(DshStuckGuard.LIMIT - 1) {
            assertFalse(guard.noteToolCompleted("invalid arguments: \"replace_all\" must be a boolean"))
        }
        assertTrue(guard.noteToolCompleted("invalid arguments: \"replace_all\" must be a boolean"))
    }

    @Test
    fun missesLoopAcrossDistinctToolsDoNotAbort() {
        val guard = DshStuckGuard()
        repeat(DshStuckGuard.LIMIT + 2) {
            assertFalse(guard.noteToolCompleted("wrote"))
        }
        assertFalse(guard.noteToolCompleted("Error: cannot read a.txt: not found"))
    }

    @Test
    fun identicalSuccessfulCallsTriggerLoopAbort() {
        val guard = DshStuckGuard()
        repeat(DshStuckGuard.IDENTICAL_LIMIT - 1) {
            assertFalse(guard.noteToolStarted("UpdateGoal", "goal-1"))
        }
        assertTrue(guard.noteToolStarted("UpdateGoal", "goal-1"))
    }

    @Test
    fun differingToolCallsResetIdenticalStreak() {
        val guard = DshStuckGuard()
        repeat(DshStuckGuard.IDENTICAL_LIMIT - 1) {
            assertFalse(guard.noteToolStarted("UpdateGoal", "goal-1"))
        }
        assertFalse(guard.noteToolStarted("Read", "other.txt"))
        repeat(DshStuckGuard.IDENTICAL_LIMIT - 1) {
            assertFalse(guard.noteToolStarted("UpdateGoal", "goal-1"))
        }
        assertTrue(guard.noteToolStarted("UpdateGoal", "goal-1"))
    }

    @Test
    fun resetClearsIdenticalStreak() {
        val guard = DshStuckGuard()
        repeat(DshStuckGuard.IDENTICAL_LIMIT - 1) {
            assertFalse(guard.noteToolStarted("UpdateGoal", "goal-1"))
        }
        guard.reset()
        repeat(DshStuckGuard.IDENTICAL_LIMIT - 1) {
            assertFalse(guard.noteToolStarted("UpdateGoal", "goal-1"))
        }
        assertTrue(guard.noteToolStarted("UpdateGoal", "goal-1"))
    }

    @Test
    fun identicalCallsThatFailCountOnBothStreaks() {
        val guard = DshStuckGuard()
        repeat(DshStuckGuard.LIMIT - 1) {
            guard.noteToolStarted("Bash", "ls")
            assertFalse(guard.noteToolCompleted("Error: ls: not found"))
        }
        guard.noteToolStarted("Bash", "ls")
        assertTrue(guard.noteToolCompleted("Error: ls: not found"))
    }
}

class DshRouteMapperTest {
    @Test
    fun deepseekUsesNativeRoute() {
        val route = DshRouteMapper.forProfile(ProviderProfile(ProviderKind.DEEPSEEK))
        assertEquals("deepseek-official", route.name)
        assertEquals("DEEPSEEK_API_KEY", route.keyEnv)
        assertNull(route.custom)
    }

    @Test
    fun zenUsesFixedUrlAndResponsesProtocol() {
        val route = DshRouteMapper.forProfile(ProviderProfile(ProviderKind.OPENCODE_ZEN))
        assertEquals("opencode-zen", route.name)
        assertEquals("openai-responses", route.custom?.api)
        assertEquals("https://opencode.ai/zen/v1", route.custom?.baseUrl)
    }

    @Test
    fun customHonorsDshApiChoice() {
        val profile = ProviderProfile(ProviderKind.CUSTOM, baseUrl = "https://gw.example/v1", model = "m", dshApi = "openai-completions")
        val route = DshRouteMapper.forProfile(profile)
        assertEquals("openai-completions", route.custom?.api)
        assertEquals("https://gw.example/v1", route.custom?.baseUrl)
    }

    @Test
    fun nvidiaNimUsesFixedOpenAiCompletionsRoute() {
        val route = DshRouteMapper.forProfile(ProviderProfile(ProviderKind.NVIDIA_NIM))
        assertEquals("nvidia-nim", route.name)
        assertEquals("openai-completions", route.custom?.api)
        assertEquals("https://integrate.api.nvidia.com/v1", route.custom?.baseUrl)
    }

    @Test
    fun kiloFreeRidesTheStripProxyWhenOneIsUp() {
        val route = DshRouteMapper.forProfile(
            ProviderProfile(ProviderKind.KILO_FREE),
            "http://127.0.0.1:40001/api/gateway",
        )
        assertEquals("kilo-free", route.name)
        assertEquals(DshRuntimeBridge.FALLBACK_KEY_ENV, route.keyEnv)
        assertEquals("openai-completions", route.custom?.api)
        assertEquals("http://127.0.0.1:40001/api/gateway", route.custom?.baseUrl)
    }

    @Test
    fun kiloFreeWithoutProxyKeepsTheFixedOrigin() {
        val route = DshRouteMapper.forProfile(ProviderProfile(ProviderKind.KILO_FREE))
        assertEquals("kilo-free", route.name)
        assertEquals("openai-completions", route.custom?.api)
        assertEquals("https://api.kilo.ai/api/gateway", route.custom?.baseUrl)
    }

    @Test(expected = IllegalArgumentException::class)
    fun claudeSubscriptionIsRejected() {
        DshRouteMapper.forProfile(ProviderProfile(ProviderKind.CLAUDE))
    }
}

class AgentProviderPresetTest {
    @Test
    fun customGatewayUrlSuggestsProtocolWithoutRemovingManualChoice() {
        assertEquals("openai-completions", inferredDshApiForUrl("https://api.example.com/v1/"))
        assertEquals("anthropic-messages", inferredDshApiForUrl("https://api.example.com/anthropic"))
        assertEquals("openai-responses", inferredDshApiForUrl("https://api.example.com/v1/responses"))
    }

    @Test
    fun selectedCustomProtocolIsUsedByEveryAgent() {
        val profile = ProviderProfile(
            ProviderKind.CUSTOM,
            baseUrl = "https://api.example.com/v1",
            model = "model",
            dshApi = "openai-completions",
        )
        // The picker belongs to the provider, not to DeepSeek Harness: the
        // OpenCode and Hermes bridges read dshApi directly, and Claude Code
        // resolves it too so an OpenAI gateway gets the local format gateway.
        assertEquals(ProviderProtocol.OPENAI_CHAT, providerProtocolForAgent(profile, AgentKind.DEEPSEEK_HARNESS))
        assertEquals(ProviderProtocol.OPENAI_CHAT, providerProtocolForAgent(profile, AgentKind.CLAUDE_CODE))
        assertEquals(ProviderProtocol.OPENAI_CHAT, providerProtocolForAgent(profile, AgentKind.HERMES))
        assertEquals(
            ProviderProtocol.ANTHROPIC_GATEWAY,
            providerProtocolForAgent(profile.copy(dshApi = "anthropic-messages"), AgentKind.CLAUDE_CODE),
        )
    }

    @Test
    fun openCodeZenPresetIsLocked() {
        val zen = ProviderKind.OPENCODE_ZEN
        assertEquals("https://opencode.ai/zen/v1", zen.defaultBaseUrl)
        assertTrue(zen.fixedBaseUrl)
        assertTrue(zen.fixedProtocol)
        assertEquals("https://opencode.ai/zen/v1", ProviderProfile(zen).resolvedBaseUrl)
    }

    @Test
    fun kiloFreePresetIsLocked() {
        val kilo = ProviderKind.KILO_FREE
        assertEquals("https://api.kilo.ai/api/gateway", kilo.defaultBaseUrl)
        assertEquals("nvidia/nemotron-3.5-lightning:free", kilo.defaultModel)
        assertTrue(kilo.fixedBaseUrl)
        assertTrue(kilo.fixedProtocol)
        assertEquals("https://api.kilo.ai/api/gateway", ProviderProfile(kilo).resolvedBaseUrl)
        // Kilo speaks plain OpenAI chat completions; the DSH wire is pinned too.
        assertEquals(
            ProviderProtocol.OPENAI_CHAT,
            providerProtocolForAgent(ProviderProfile(kilo), AgentKind.DEEPSEEK_HARNESS),
        )
        assertEquals("openai-completions", defaultDshApiForProvider(kilo))
    }

    @Test
    fun storedDriftCannotOverrideFixedUrl() {
        val profile = ProviderProfile(ProviderKind.OPENCODE_ZEN, baseUrl = "https://evil.example/", model = "x")
        assertEquals("https://opencode.ai/zen/v1", profile.resolvedBaseUrl)
    }

    @Test
    fun storedDriftCannotOverrideFixedProtocol() {
        val profile = ProviderProfile(ProviderKind.NVIDIA_NIM, dshApi = "anthropic-messages")
        assertEquals(ProviderProtocol.OPENAI_CHAT, providerProtocolForAgent(profile, AgentKind.DEEPSEEK_HARNESS))
        assertEquals("openai-completions", DshRouteMapper.forProfile(profile).custom?.api)
    }

    @Test
    fun dshHarnessExcludesClaudeSubscription() {
        assertFalse(ProviderKind.CLAUDE in DEEPSEEK_HARNESS_PROVIDERS)
        assertTrue(ProviderKind.OPENCODE_ZEN in DEEPSEEK_HARNESS_PROVIDERS)
        assertTrue(ProviderKind.DEEPSEEK in DEEPSEEK_HARNESS_PROVIDERS)
        assertTrue(ProviderKind.NVIDIA_NIM in DEEPSEEK_HARNESS_PROVIDERS)
        assertTrue(ProviderKind.KILO_FREE in DEEPSEEK_HARNESS_PROVIDERS)
        assertEquals(8, DEEPSEEK_HARNESS_PROVIDERS.size)
    }

    @Test
    fun openCodeZenIsOnlyShownForDeepSeekHarness() {
        assertTrue(ProviderKind.OPENCODE_ZEN in providersForAgent(AgentKind.DEEPSEEK_HARNESS))
        assertFalse(ProviderKind.OPENCODE_ZEN in providersForAgent(AgentKind.CLAUDE_CODE))
    }

    @Test
    fun kiloFreeIsShownForAllKeylessCapableAgents() {
        assertTrue(ProviderKind.KILO_FREE in providersForAgent(AgentKind.DEEPSEEK_HARNESS))
        assertTrue(ProviderKind.KILO_FREE in providersForAgent(AgentKind.OPENCODE))
        assertTrue(ProviderKind.KILO_FREE in providersForAgent(AgentKind.HERMES))
        assertTrue(ProviderKind.KILO_FREE in providersForAgent(AgentKind.CLAUDE_CODE))
        assertFalse(ProviderKind.KILO_FREE in providersForAgent(AgentKind.ANTIGRAVITY))
    }

    @Test
    fun agentKindsAreStable() {
        assertEquals(AgentKind.CLAUDE_CODE, AgentKind.valueOf("CLAUDE_CODE"))
        assertEquals(AgentKind.DEEPSEEK_HARNESS, AgentKind.valueOf("DEEPSEEK_HARNESS"))
        assertEquals(AgentKind.ANTIGRAVITY, AgentKind.fromStored("antigravity"))
        assertEquals(AgentKind.CLAUDE_CODE, AgentKind.fromStored("CLAUDE_CODE"))
        assertEquals(AgentKind.DEEPSEEK_HARNESS, AgentKind.fromStored("DEEPSEEK_HARNESS"))
    }
}

class DshHomePatchTest {
    @Test
    fun customRouteWritesProvidersPatch() {
        val route = DshRoute(
            name = "nvidia-nim",
            keyEnv = DshRuntimeBridge.FALLBACK_KEY_ENV,
            defaultModel = "meta/llama-3.2-11b-vision-instruct",
            custom = DshCustomRoute("openai-completions", "https://integrate.api.nvidia.com/v1"),
        )
        val patch = dshHomePatch(route, "meta/llama-3.2-11b-vision-instruct")

        assertTrue(patch.contains("- id: llm-pi-ai"))
        assertTrue(patch.contains("nvidia-nim:"))
        assertTrue(patch.contains("apiKeyEnv: MH_DSH_API_KEY"))
        assertTrue(patch.contains("api: openai-completions"))
        assertTrue(patch.contains("baseURL: 'https://integrate.api.nvidia.com/v1'"))
        assertTrue(patch.contains("- id: 'meta/llama-3.2-11b-vision-instruct'"))
    }

    @Test
    fun kiloFreeRouteWritesTheLoopbackStripProxyPatch() {
        val route = DshRoute(
            name = "kilo-free",
            keyEnv = DshRuntimeBridge.FALLBACK_KEY_ENV,
            defaultModel = "nvidia/nemotron-3.5-lightning:free",
            custom = DshCustomRoute("openai-completions", "http://127.0.0.1:43126/api/gateway"),
        )
        val patch = dshHomePatch(route, "nvidia/nemotron-3.5-lightning:free")

        assertTrue(patch.contains("- id: llm-pi-ai"))
        assertTrue(patch.contains("kilo-free:"))
        assertTrue(patch.contains("apiKeyEnv: MH_DSH_API_KEY"))
        assertTrue(patch.contains("api: openai-completions"))
        assertTrue(patch.contains("baseURL: 'http://127.0.0.1:43126/api/gateway'"))
        assertTrue(patch.contains("- id: 'nvidia/nemotron-3.5-lightning:free'"))
    }

    @Test
    fun officialRouteStillWiresTheApprovalAnswerer() {
        val route = DshRoute("deepseek-official", "DEEPSEEK_API_KEY", "deepseek-chat")

        // No llm-pi-ai entry keeps dsh-base's default providers; the official key
        // arrives through the exported DEEPSEEK_API_KEY environment.
        val patch = dshHomePatch(route, "deepseek-chat")

        assertFalse(patch.contains("- id: llm-pi-ai"))
        assertTrue(patch.contains("- insert:"))
        assertTrue(patch.contains("name: /root/.dsh/plugins/mh-approval-answerer/index.js"))
        assertTrue(patch.contains("- id: mh-question"))
        assertTrue(patch.contains("name: /root/.dsh/plugins/mh-question-answerer/index.js"))
        // The sdk profile ships no model-facing question tool on its own.
        assertTrue(patch.contains("- id: tool-ask-user"))
        assertTrue(patch.contains("name: '@deepseek-ai/dsh-tool-ask-user'"))
    }

    @Test
    fun customRouteKeepsApprovalWiringToo() {
        val route = DshRoute(
            name = "nvidia-nim",
            keyEnv = DshRuntimeBridge.FALLBACK_KEY_ENV,
            defaultModel = "meta/llama-3.2-11b-vision-instruct",
            custom = DshCustomRoute("openai-completions", "https://integrate.api.nvidia.com/v1"),
        )

        val patch = dshHomePatch(route, "meta/llama-3.2-11b-vision-instruct")

        assertTrue(patch.contains("- id: llm-pi-ai"))
        assertTrue(patch.contains("- insert:"))
        assertTrue(patch.contains("name: /root/.dsh/plugins/mh-approval-answerer/index.js"))
        assertTrue(patch.contains("name: /root/.dsh/plugins/mh-question-answerer/index.js"))
    }
}

class DshRetrySessionIdTest {
    @Test
    fun firstAttemptKeepsTheAppSessionId() {
        assertEquals("app-session", dshSessionIdForAttempt("app-session", 1))
    }

    @Test
    fun retriesMintFreshIdsSoDshDoesNotRejectTheDuplicate() {
        val app = "app-session"
        val second = dshSessionIdForAttempt(app, 2)
        val third = dshSessionIdForAttempt(app, 3)

        assertNotEquals(app, second)
        assertNotEquals(second, third)
        // A fresh id must be a UUID: dsh's session store keys on that shape.
        assertEquals(36, second.length)
    }
}

class DshQuestionParseTest {
    @Test
    fun parsesTheGuestQuestionPayload() {
        val array = JSONArray(
            """[{"id":"choice","question":"Which path?","detail":"Pick one","header":"Path",""" +
                """"options":[{"label":"src","description":"Source tree"},{"label":"test"}],"multiSelect":false}]""",
        )

        val items = parseQuestionItems(array)

        assertEquals(1, items!!.size)
        val question = items[0]
        assertEquals("choice", question.id)
        assertEquals("Which path?", question.question)
        assertEquals("Pick one", question.detail)
        assertEquals("Path", question.header)
        assertFalse(question.multiSelect)
        assertEquals(listOf("src", "test"), question.options.map { it.label })
        assertEquals("Source tree", question.options[0].description)
        assertNull(question.options[1].description)
    }

    @Test
    fun parsesMultiSelectQuestionsWithoutOptions() {
        val array = JSONArray("""[{"id":"q1","question":"Anything to add?","multiSelect":true}]""")

        val items = parseQuestionItems(array)

        assertEquals(1, items!!.size)
        assertTrue(items[0].multiSelect)
        assertTrue(items[0].options.isEmpty())
        assertNull(items[0].detail)
    }

    @Test
    fun emptyOrMalformedPayloadsFailClosed() {
        assertNull(parseQuestionItems(JSONArray()))
        assertNull(parseQuestionItems(JSONArray("""[{"question":"missing id"}]""")))
        assertNull(parseQuestionItems(JSONArray("""[{"id":"x"}]""")))
    }
}

class DshWarmSignatureTest {
    private val officialRoute = DshRoute("deepseek-official", "DEEPSEEK_API_KEY", "deepseek-chat")
    private val workspace = File("/data/data/com.jarves.mh/files/projects/demo")

    private fun signature(
        route: DshRoute = officialRoute,
        model: String = "deepseek-chat",
        guestWorkspacePath: String = "/workspace/demo",
        workspace: File = this.workspace,
        environment: Map<String, String> = mapOf("DEEPSEEK_API_KEY" to "sk-live"),
    ): String = dshWarmSignature(route, model, guestWorkspacePath, workspace, environment)

    @Test
    fun identicalInputsReuseTheSameProcess() {
        assertEquals(signature(), signature())
    }

    @Test
    fun aDifferentModelBootsFresh() {
        assertNotEquals(signature(), signature(model = "deepseek-reasoner"))
    }

    @Test
    fun aDifferentProjectBootsFresh() {
        assertNotEquals(signature(), signature(guestWorkspacePath = "/workspace/other"))
        assertNotEquals(signature(), signature(workspace = File(workspace.parentFile, "other")))
    }

    @Test
    fun aRotatedKeyBootsFresh() {
        assertNotEquals(signature(), signature(environment = mapOf("DEEPSEEK_API_KEY" to "sk-rotated")))
    }

    @Test
    fun aDifferentRouteBootsFresh() {
        val custom = DshRoute(
            name = "mh-custom",
            keyEnv = DshRuntimeBridge.FALLBACK_KEY_ENV,
            defaultModel = "deepseek-chat",
            custom = DshCustomRoute("anthropic-messages", "https://gateway.example/v1"),
        )
        assertNotEquals(signature(), signature(route = custom))
    }

    @Test
    fun aNonLoopbackRouteStillGetsItsRealKey() {
        assertEquals("sk-live", dshGuestKey(officialRoute, "sk-live"))
        assertEquals(DshRuntimeBridge.LOOPBACK_KEY, dshGuestKey(officialRoute, ""))
    }
}
