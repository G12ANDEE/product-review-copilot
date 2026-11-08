package com.reviewcopilot

import com.reviewcopilot.core.ReviewCopilot
import com.reviewcopilot.mcp.McpServer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpServerTest {
    private suspend fun server(): McpServer {
        val index = sampleIndex()
        return McpServer(index, ReviewCopilot(index, llm = null))
    }

    private suspend fun McpServer.call(request: String): JsonObject =
        AppJson.parseToJsonElement(handle(request)!!).jsonObject

    @Test
    fun `initialize negotiates protocol version and advertises tools`() = runTest {
        val result = server().call(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}""",
        )["result"]!!.jsonObject

        assertEquals("2025-06-18", result["protocolVersion"]!!.jsonPrimitive.content)
        assertTrue("tools" in result["capabilities"]!!.jsonObject)
    }

    @Test
    fun `notifications get no response`() = runTest {
        assertNull(server().handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
    }

    @Test
    fun `tools list contains every tool with a schema`() = runTest {
        val tools = server().call("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")["result"]!!
            .jsonObject["tools"]!!.jsonArray

        assertEquals(listOf("list_products", "search_reviews", "summarize_product", "ask_about_product"), tools.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        assertTrue(tools.all { it.jsonObject["inputSchema"]!!.jsonObject["type"]!!.jsonPrimitive.content == "object" })
    }

    @Test
    fun `tools call returns text content`() = runTest {
        val result = server().call(
            """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"search_reviews","arguments":{"product_id":"breeze-airfryer","query":"crispy fries","top_k":2}}}""",
        )["result"]!!.jsonObject

        assertEquals(false, result["isError"]!!.jsonPrimitive.boolean)
        val text = result["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content
        assertEquals(2, AppJson.parseToJsonElement(text).jsonArray.size)
    }

    @Test
    fun `tool failures are reported in the result, protocol failures as errors`() = runTest {
        val s = server()
        val toolFailure = s.call("""{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"summarize_product","arguments":{}}}""")
        assertEquals(true, toolFailure["result"]!!.jsonObject["isError"]!!.jsonPrimitive.boolean)

        val unknownMethod = s.call("""{"jsonrpc":"2.0","id":5,"method":"resources/list"}""")
        assertEquals(-32601, unknownMethod["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)

        val parseError = s.call("not json")
        assertEquals(-32700, parseError["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
    }

    @Test
    fun `stdio transport writes one response line per request`() = runTest {
        val input = """
            {"jsonrpc":"2.0","id":1,"method":"ping"}
            {"jsonrpc":"2.0","method":"notifications/initialized"}
            {"jsonrpc":"2.0","id":2,"method":"tools/list"}
        """.trimIndent().reader().buffered()
        val out = ByteArrayOutputStream()
        server().runStdio(input, PrintStream(out))
        assertEquals(2, out.toString().trim().lines().size)
    }
}
