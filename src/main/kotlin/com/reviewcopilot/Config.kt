package com.reviewcopilot

import com.reviewcopilot.llm.AnthropicClient
import com.reviewcopilot.llm.LlmClient
import com.reviewcopilot.llm.OpenAiCompatibleClient
import com.reviewcopilot.llm.VisionModel
import com.reviewcopilot.rag.Embedder
import com.reviewcopilot.rag.HashingEmbedder
import com.reviewcopilot.rag.OpenAiCompatibleEmbedder
import io.ktor.client.HttpClient

/**
 * Configuration from environment variables, so no secret ever lives in the code.
 *
 * LLM_PROVIDER       anthropic | openai | offline   (default: whichever API key is set, else offline)
 * LLM_MODEL          model name override
 * EMBEDDING_PROVIDER hashing | openai               (default: hashing, works offline)
 * OPENAI_BASE_URL    for OpenAI-compatible servers such as Ollama (default https://api.openai.com/v1)
 * PORT               HTTP port (default 8080)
 */
data class AppConfig(
    val llmProvider: String,
    val llmModel: String?,
    val embeddingProvider: String,
    val anthropicApiKey: String?,
    val openAiApiKey: String?,
    val openAiBaseUrl: String,
    val port: Int,
) {
    fun llm(http: HttpClient): LlmClient? = when (llmProvider) {
        "anthropic" -> AnthropicClient(http, anthropicApiKey ?: error("ANTHROPIC_API_KEY is not set"), llmModel ?: "claude-haiku-5-5")
        "openai" -> OpenAiCompatibleClient(http, openAiApiKey.orEmpty(), llmModel ?: "gpt-4o-mini", openAiBaseUrl)
        "offline" -> null
        else -> error("Unknown LLM_PROVIDER '$llmProvider'")
    }

    /** Both API clients also accept images, so the configured LLM doubles as the vision model. */
    fun vision(llm: LlmClient?): VisionModel? = llm as? VisionModel

    fun embedder(http: HttpClient): Embedder = when (embeddingProvider) {
        "hashing" -> HashingEmbedder()
        "openai" -> OpenAiCompatibleEmbedder(http, openAiApiKey.orEmpty(), baseUrl = openAiBaseUrl)
        else -> error("Unknown EMBEDDING_PROVIDER '$embeddingProvider'")
    }

    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): AppConfig {
            val anthropicKey = env["ANTHROPIC_API_KEY"]?.takeIf { it.isNotBlank() }
            val openAiKey = env["OPENAI_API_KEY"]?.takeIf { it.isNotBlank() }
            val defaultProvider = when {
                anthropicKey != null -> "anthropic"
                openAiKey != null -> "openai"
                else -> "offline"
            }
            return AppConfig(
                llmProvider = env["LLM_PROVIDER"]?.lowercase() ?: defaultProvider,
                llmModel = env["LLM_MODEL"],
                embeddingProvider = env["EMBEDDING_PROVIDER"]?.lowercase() ?: "hashing",
                anthropicApiKey = anthropicKey,
                openAiApiKey = openAiKey,
                openAiBaseUrl = env["OPENAI_BASE_URL"] ?: "https://api.openai.com/v1",
                port = env["PORT"]?.toIntOrNull() ?: 8080,
            )
        }
    }
}
