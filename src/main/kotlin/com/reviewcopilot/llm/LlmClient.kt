package com.reviewcopilot.llm

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import com.reviewcopilot.model.Photo
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The only thing the rest of the app knows about LLMs. Swapping providers means writing
 * another implementation of this interface; nothing else changes.
 */
interface LlmClient {
    val name: String

    suspend fun complete(system: String, prompt: String, maxTokens: Int = 800): String
}

class LlmException(message: String) : RuntimeException(message)

private suspend fun HttpResponse.requireSuccess(provider: String): HttpResponse {
    if (!status.isSuccess()) throw LlmException("$provider returned ${status.value}: ${bodyAsText().take(300)}")
    return this
}

/** Anthropic Messages API (https://docs.anthropic.com/en/api/messages). */
class AnthropicClient(
    private val http: HttpClient,
    private val apiKey: String,
    private val model: String = "claude-haiku-5-5",
    private val baseUrl: String = "https://api.anthropic.com",
) : LlmClient, VisionModel {
    override val name = "anthropic:$model"

    @Serializable
    private data class Message(val role: String, val content: String)

    @Serializable
    private data class Request(
        val model: String,
        @SerialName("max_tokens") val maxTokens: Int,
        val system: String,
        val messages: List<Message>,
    )

    @Serializable
    private data class ContentBlock(val type: String, val text: String? = null)

    @Serializable
    private data class Response(val content: List<ContentBlock>)

    override suspend fun complete(system: String, prompt: String, maxTokens: Int): String {
        val response: Response = http.post("$baseUrl/v1/messages") {
            header("x-api-key", apiKey)
            header("anthropic-version", "2023-06-01")
            contentType(ContentType.Application.Json)
            setBody(Request(model, maxTokens, system, listOf(Message("user", prompt))))
        }.requireSuccess("Anthropic").body()
        return response.content.filter { it.type == "text" }.joinToString("") { it.text.orEmpty() }
    }

    /** Sends the image as a base64 content block next to the text instruction. */
    override suspend fun describe(photo: Photo, system: String, instruction: String, maxTokens: Int): String {
        val request: JsonObject = buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            put("system", system)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "image")
                            putJsonObject("source") {
                                put("type", "base64")
                                put("media_type", photo.mediaType)
                                put("data", photo.data)
                            }
                        }
                        addJsonObject {
                            put("type", "text")
                            put("text", instruction)
                        }
                    }
                }
            }
        }
        val response: Response = http.post("$baseUrl/v1/messages") {
            header("x-api-key", apiKey)
            header("anthropic-version", "2023-06-01")
            contentType(ContentType.Application.Json)
            setBody(request)
        }.requireSuccess("Anthropic").body()
        return response.content.filter { it.type == "text" }.joinToString("") { it.text.orEmpty() }
    }
}

/** Any OpenAI-compatible Chat Completions API (OpenAI, Azure OpenAI, Ollama, vLLM, ...). */
class OpenAiCompatibleClient(
    private val http: HttpClient,
    private val apiKey: String,
    private val model: String = "gpt-4o-mini",
    private val baseUrl: String = "https://api.openai.com/v1",
) : LlmClient, VisionModel {
    override val name = "openai:$model"

    @Serializable
    private data class Message(val role: String, val content: String)

    @Serializable
    private data class Request(
        val model: String,
        val messages: List<Message>,
        @SerialName("max_tokens") val maxTokens: Int,
    )

    @Serializable
    private data class Choice(val message: Message)

    @Serializable
    private data class Response(val choices: List<Choice>)

    override suspend fun complete(system: String, prompt: String, maxTokens: Int): String {
        val response: Response = http.post("$baseUrl/chat/completions") {
            bearerAuth(apiKey)
            contentType(ContentType.Application.Json)
            setBody(Request(model, listOf(Message("system", system), Message("user", prompt)), maxTokens))
        }.requireSuccess("OpenAI-compatible API").body()
        return response.choices.firstOrNull()?.message?.content
            ?: throw LlmException("OpenAI-compatible API returned no choices")
    }

    /** Sends the image as a data URL in an `image_url` content part. */
    override suspend fun describe(photo: Photo, system: String, instruction: String, maxTokens: Int): String {
        val request: JsonObject = buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    put("content", system)
                }
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "text")
                            put("text", instruction)
                        }
                        addJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") { put("url", "data:${photo.mediaType};base64,${photo.data}") }
                        }
                    }
                }
            }
        }
        val response: Response = http.post("$baseUrl/chat/completions") {
            bearerAuth(apiKey)
            contentType(ContentType.Application.Json)
            setBody(request)
        }.requireSuccess("OpenAI-compatible API").body()
        return response.choices.firstOrNull()?.message?.content
            ?: throw LlmException("OpenAI-compatible API returned no choices")
    }
}
