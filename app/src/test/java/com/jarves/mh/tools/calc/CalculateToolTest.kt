package com.jarves.mh.tools.calc

import com.jarves.mh.tools.AIToolHook
import com.jarves.mh.tools.ToolPermissionGate
import com.jarves.mh.tools.ToolPermissionLevel
import com.jarves.mh.tools.ToolPermissionStorage
import com.jarves.mh.tools.ToolPermissionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class CalcMemStorage : ToolPermissionStorage {
    private val values = mutableMapOf<String, String>()
    override fun read(key: String): String? = values[key]
    override fun write(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

class CalculateToolTest {
    private lateinit var store: ToolPermissionStore
    private lateinit var tool: CalculateTool

    @Before
    fun setUp() {
        store = ToolPermissionStore(CalcMemStorage())
        store.globalDefault = ToolPermissionLevel.ALLOW
        tool = CalculateTool(ToolPermissionGate(store))
    }

    private fun value(expression: String): Double {
        val result = tool.calculate(expression)
        assertTrue(result is CalcToolResult.Calculated)
        return (result as CalcToolResult.Calculated).value
    }

    private fun error(expression: String): CalcToolResult.Error {
        val result = tool.calculate(expression)
        assertTrue(result is CalcToolResult.Error)
        return result as CalcToolResult.Error
    }

    @Test
    fun basicArithmetic() {
        assertEquals(14.0, value("2 + 3 * 4"), 1e-9)
        assertEquals(20.0, value("(2 + 3) * 4"), 1e-9)
        assertEquals(2.0, value("10 / 2 - 3"), 1e-9)
        assertEquals(1.0, value("7 % 3"), 1e-9)
    }

    @Test
    fun powerAndUnary() {
        assertEquals(8.0, value("2 ^ 3"), 1e-9)
        assertEquals(-4.0, value("-2 ^ 2"), 1e-9)
        assertEquals(0.5, value("2 ^ -1"), 1e-9)
        assertEquals(18.0, value("2 * 3 ^ 2"), 1e-9)
        assertEquals(3.0, value("+3"), 1e-9)
    }

    @Test
    fun constantsAndFunctions() {
        assertEquals(Math.PI, value("PI"), 1e-9)
        assertEquals(Math.E, value("e"), 1e-9)
        assertEquals(4.0, value("sqrt(16)"), 1e-9)
        assertEquals(5.0, value("abs(-5)"), 1e-9)
        assertEquals(0.0, value("sin(0)"), 1e-9)
        assertEquals(1.0, value("sin(PI / 2)"), 1e-9)
        assertEquals(2.0, value("log(100)"), 1e-9)
        assertEquals(3.0, value("max(1, 3)"), 1e-9)
        assertEquals(8.0, value("pow(2, 3)"), 1e-9)
        assertEquals(10.0, value("min(10, 20)"), 1e-9)
    }

    @Test
    fun scientificNotation() {
        assertEquals(1500.0, value("1.5e3"), 1e-9)
        assertEquals(0.002, value("2e-3"), 1e-9)
    }

    @Test
    fun emptyExpressionIsError() {
        assertTrue(error("   ").message.contains("empty"))
    }

    @Test
    fun tooLongExpressionIsError() {
        val long = "1+".repeat(CalcToolLimits.MAX_EXPRESSION_CHARS) + "1"
        assertTrue(long.length > CalcToolLimits.MAX_EXPRESSION_CHARS)
        assertTrue(error(long).message.contains("too long"))
    }

    @Test
    fun divisionByZeroIsError() {
        assertTrue(error("1 / 0").message.contains("Division by zero"))
        assertTrue(error("1 % 0").message.contains("Modulo by zero"))
    }

    @Test
    fun invalidSyntaxIsError() {
        assertTrue(error("2 +").message.contains("end of expression"))
        assertTrue(error("(1 + 2").message.contains("Missing ')'"))
        assertTrue(error("foo").message.contains("Unknown identifier"))
        assertTrue(error("nope(1)").message.contains("Unknown function"))
        assertTrue(error("sqrt(1, 2)").message.contains("expects 1"))
        assertTrue(error("1 ; 2").message.contains("Unexpected"))
    }

    @Test
    fun askDoesNotEvaluate() {
        store.globalDefault = ToolPermissionLevel.ASK
        val result = tool.calculate("1 + 1")
        assertTrue(result is CalcToolResult.Error)
        assertTrue((result as CalcToolResult.Error).message.contains("Approval"))
    }

    @Test
    fun forbidDoesNotEvaluate() {
        store.globalDefault = ToolPermissionLevel.FORBID
        val result = tool.calculate("1 + 1")
        assertTrue(result is CalcToolResult.Error)
        assertTrue((result as CalcToolResult.Error).message.contains("forbidden"))
    }

    @Test
    fun overrideAllowWhileGlobalAsk() {
        store.globalDefault = ToolPermissionLevel.ASK
        store.setOverride("Calculate", ToolPermissionLevel.ALLOW)
        assertEquals(2.0, value("1 + 1"), 1e-9)
    }

    @Test
    fun hooksFireLifecycle() {
        val events = mutableListOf<String>()
        val hook = object : AIToolHook {
            override fun onToolExecutionStarted(toolName: String) { events += "start:$toolName" }
            override fun onToolExecutionResult(toolName: String, success: Boolean, summary: String) {
                events += "result:$toolName:$success"
            }
            override fun onToolExecutionFinished(toolName: String) { events += "end:$toolName" }
        }
        CalculateTool(ToolPermissionGate(store), listOf(hook)).calculate("1 + 1")
        assertEquals(listOf("start:Calculate", "result:Calculate:true", "end:Calculate"), events)
    }

    @Test
    fun hooksReportParseFailure() {
        val events = mutableListOf<String>()
        val hook = object : AIToolHook {
            override fun onToolExecutionResult(toolName: String, success: Boolean, summary: String) {
                events += "result:$toolName:$success"
            }
            override fun onToolExecutionFinished(toolName: String) { events += "end:$toolName" }
        }
        CalculateTool(ToolPermissionGate(store), listOf(hook)).calculate("1 +")
        assertEquals(listOf("result:Calculate:false", "end:Calculate"), events)
    }
}
