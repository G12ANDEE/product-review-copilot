package com.reviewcopilot

import com.reviewcopilot.llm.AnthropicClient
import com.reviewcopilot.llm.LlmException
import com.reviewcopilot.llm.OpenAiCompatibleClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Tests the HTTP clients against a mock engine: no network, no API key. */
class LlmClientTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun client(engine: MockEngine) = HttpClient(engine) { install(ContentNegotiation) { json(AppJson) } }

    @Test
    fun `anthropic client sends the right request and reads text blocks`() = runTest {
        val engine = MockEngine { request ->
            assertEquals("https://api.anthropic.com/v1/messages", request.url.toString())
            assertEquals("test-key", request.headers["x-api-key"])
            assertEquals("2023-06-01", request.headers["anthropic-version"])
            val body = (request.body as TextContent).text
            assertTrue(body.contains("\"system\":\"be brief\"") && body.contains("\"max_tokens\":100"), body)
            respond("""{"id":"msg_1","content":[{"type":"text","text":"Hello"},{"type":"text","text":" world"}]}""", HttpStatusCode.OK, jsonHeaders)
        }
        val reply = AnthropicClient(client(engine), "test-key", "test-model").complete("be brief", "hi", 100)
        assertEquals("Hello world", reply)
    }

    @Test
    fun `openai compatible client reads the first choice`() = runTest {
        val engine = MockEngine { request ->
            assertEquals("Bearer k", request.headers[HttpHeaders.Authorization])
            respond("""{"choices":[{"message":{"role":"assistant","content":"Hi!"}}]}""", HttpStatusCode.OK, jsonHeaders)
        }
        assertEquals("Hi!", OpenAiCompatibleClient(client(engine), "k", "m", "http://localhost/v1").complete("s", "p"))
    }

    @Test
    fun `anthropic vision request sends the image as a base64 block`() = runTest {
        val engine = MockEngine { request ->
            val body = AppJson.parseToJsonElement((request.body as TextContent).text).jsonObject
            val content = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
            val source = content[0].jsonObject["source"]!!.jsonObject
            assertEquals("image", content[0].jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals("image/png", source["media_type"]!!.jsonPrimitive.content)
            assertEquals(TINY_PNG.data, source["data"]!!.jsonPrimitive.content)
            respond("""{"content":[{"type":"text","text":"A cracked case."}]}""", HttpStatusCode.OK, jsonHeaders)
        }
        assertEquals("A cracked case.", AnthropicClient(client(engine), "k").describe(TINY_PNG, "s", "describe"))
    }

    @Test
    fun `openai vision request sends the image as a data url`() = runTest {
        val engine = MockEngine { request ->
            val body = (request.body as TextContent).text
            assertTrue(body.contains("data:image/png;base64,${TINY_PNG.data}"), body)
            respond("""{"choices":[{"message":{"role":"assistant","content":"ok"}}]}""", HttpStatusCode.OK, jsonHeaders)
        }
        assertEquals("ok", OpenAiCompatibleClient(client(engine), "k").describe(TINY_PNG, "s", "describe"))
    }

    @Test
    fun `http errors become LlmException`() = runTest {
        val engine = MockEngine { respond("""{"error":"overloaded"}""", HttpStatusCode.ServiceUnavailable, jsonHeaders) }
        assertFailsWith<LlmException> { AnthropicClient(client(engine), "k").complete("s", "p") }
    }
}
