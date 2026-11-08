package com.reviewcopilot.mcp

import com.reviewcopilot.AppJson
import com.reviewcopilot.core.ReviewCopilot
import com.reviewcopilot.rag.ReviewIndex
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedReader
import java.io.PrintStream

/**
 * A minimal Model Context Protocol server over stdio, written by hand on top of JSON-RPC 2.0
 * so the protocol is easy to read. It lets an MCP client (a desktop assistant, an IDE, an agent
 * framework, ...) call this service's features as tools.
 *
 * Transport: one JSON-RPC message per line on stdin, one response per line on stdout.
 * Anything else (logs) must go to stderr.
 */
class McpServer(
    private val index: ReviewIndex,
    private val copilot: ReviewCopilot,
) {
    private class ToolError(message: String) : Exception(message)

    private class RpcError(val code: Int, message: String) : Exception(message)

    private data class Tool(
        val name: String,
        val description: String,
        val inputSchema: JsonObject,
        val run: suspend (JsonObject) -> JsonElement,
    )

    private val tools: List<Tool> = listOf(
        Tool(
            name = "list_products",
            description = "List the products that have reviews in this service.",
            inputSchema = schema(),
        ) { AppJson.encodeToJsonElement(index.products()) },
        Tool(
            name = "search_reviews",
            description = "Semantic search over one product's customer reviews. Returns the most relevant reviews with similarity scores.",
            inputSchema = schema(
                "product_id" to stringProp("Product id from list_products"),
                "query" to stringProp("What to look for, e.g. 'does the battery last a full day'"),
                "top_k" to intProp("How many reviews to return (1-20, default 5)"),
                required = listOf("product_id", "query"),
            ),
        ) { args ->
            AppJson.encodeToJsonElement(index.search(args.string("product_id"), args.string("query"), args.int("top_k") ?: 5))
        },
        Tool(
            name = "summarize_product",
            description = "Summarize what customers say about a product: overview, pros, cons, average rating and cited review ids.",
            inputSchema = schema("product_id" to stringProp("Product id from list_products"), required = listOf("product_id")),
        ) { args -> AppJson.encodeToJsonElement(copilot.summarize(args.string("product_id"))) },
        Tool(
            name = "ask_about_product",
            description = "Answer a shopper's question about a product, grounded in its reviews (RAG), with citations.",
            inputSchema = schema(
                "product_id" to stringProp("Product id from list_products"),
                "question" to stringProp("The shopper's question"),
                required = listOf("product_id", "question"),
            ),
        ) { args -> AppJson.encodeToJsonElement(copilot.ask(args.string("product_id"), args.string("question"))) },
    )

    fun toolNames(): List<String> = tools.map { it.name }

    /** Reads requests until stdin closes. */
    suspend fun runStdio(input: BufferedReader, output: PrintStream) {
        while (true) {
            val line = input.readLine() ?: break
            if (line.isBlank()) continue
            handle(line)?.let {
                output.println(it)
                output.flush()
            }
        }
    }

    /** Handles one JSON-RPC message. Returns the response line, or null for notifications. */
    suspend fun handle(line: String): String? {
        val message = try {
            AppJson.parseToJsonElement(line).jsonObject
        } catch (e: Exception) {
            return errorResponse(JsonNull, -32700, "Parse error").toString()
        }
        val id = message["id"]
        val method = (message["method"] as? JsonPrimitive)?.contentOrNull
        val params = message["params"] as? JsonObject ?: JsonObject(emptyMap())

        if (id == null) return null // notifications (e.g. notifications/initialized) get no response
        if (method == null) return errorResponse(id, -32600, "Invalid request: missing method").toString()

        return try {
            val result = dispatch(method, params)
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("result", result)
            }.toString()
        } catch (e: RpcError) {
            errorResponse(id, e.code, e.message ?: "error").toString()
        }
    }

    private suspend fun dispatch(method: String, params: JsonObject): JsonElement = when (method) {
        "initialize" -> {
            val requested = (params["protocolVersion"] as? JsonPrimitive)?.contentOrNull
            buildJsonObject {
                put("protocolVersion", if (requested in SUPPORTED_VERSIONS) requested else SUPPORTED_VERSIONS.first())
                putJsonObject("capabilities") { putJsonObject("tools") {} }
                putJsonObject("serverInfo") {
                    put("name", "product-review-copilot")
                    put("version", "0.1.0")
                }
            }
        }
        "ping" -> JsonObject(emptyMap())
        "tools/list" -> buildJsonObject {
            putJsonArray("tools") {
                tools.forEach { tool ->
                    add(
                        buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("inputSchema", tool.inputSchema)
                        },
                    )
                }
            }
        }
        "tools/call" -> callTool(params)
        else -> throw RpcError(-32601, "Method not found: $method")
    }

    private suspend fun callTool(params: JsonObject): JsonElement {
        val name = (params["name"] as? JsonPrimitive)?.contentOrNull ?: throw RpcError(-32602, "Missing tool name")
        val tool = tools.find { it.name == name } ?: throw RpcError(-32602, "Unknown tool: $name")
        val args = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())

        // Per the MCP spec, a failing tool is reported inside the result (isError = true) so the
        // model can see the message and recover, not as a protocol-level error.
        val (text, isError) = try {
            tool.run(args).toString() to false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            (e.message ?: e::class.simpleName ?: "Tool failed") to true
        }
        return buildJsonObject {
            putJsonArray("content") {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", text)
                    },
                )
            }
            put("isError", isError)
        }
    }

    private fun errorResponse(id: JsonElement, code: Int, message: String) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        putJsonObject("error") {
            put("code", code)
            put("message", message)
        }
    }

    private fun JsonObject.string(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw ToolError("Missing required argument '$key'")

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    companion object {
        val SUPPORTED_VERSIONS = listOf("2025-06-18", "2025-03-26", "2024-11-05")

        private fun stringProp(description: String) = buildJsonObject {
            put("type", "string")
            put("description", description)
        }

        private fun intProp(description: String) = buildJsonObject {
            put("type", "integer")
            put("description", description)
        }

        private fun schema(vararg properties: Pair<String, JsonObject>, required: List<String> = emptyList()) =
            buildJsonObject {
                put("type", "object")
                put("properties", JsonObject(properties.toMap()))
                put("required", JsonArray(required.map(::JsonPrimitive)))
            }
    }
}
