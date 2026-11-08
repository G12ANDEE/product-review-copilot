package com.reviewcopilot

import com.reviewcopilot.api.copilotModule
import com.reviewcopilot.core.PhotoAnalyzer
import com.reviewcopilot.core.ReviewCopilot
import com.reviewcopilot.mcp.McpServer
import com.reviewcopilot.model.Dataset
import com.reviewcopilot.rag.ReviewIndex
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

/**
 * Usage:
 *   ./gradlew run                 -> REST API on http://localhost:8080
 *   ./gradlew run --args="mcp"    -> MCP server on stdin/stdout
 */
fun main(args: Array<String>) {
    val log = LoggerFactory.getLogger("Main")
    val config = AppConfig.fromEnv()

    val http = HttpClient(CIO) {
        install(ContentNegotiation) { json(AppJson) }
        install(HttpTimeout) { requestTimeoutMillis = 60_000 }
    }

    val index = ReviewIndex(config.embedder(http))
    val llm = config.llm(http)
    val copilot = ReviewCopilot(index, llm, PhotoAnalyzer(config.vision(llm)))

    // `runBlocking` bridges normal code into coroutines; used once at startup to load data.
    runBlocking { index.loadDataset(loadSampleData()) }
    log.info("Loaded ${index.products().size} products. LLM: ${config.llmProvider}, embeddings: ${config.embeddingProvider}")

    when (val mode = args.firstOrNull() ?: "serve") {
        "serve" -> embeddedServer(Netty, port = config.port) { copilotModule(index, copilot) }.start(wait = true)
        "mcp" -> runBlocking { McpServer(index, copilot).runStdio(System.`in`.bufferedReader(), System.out) }
        else -> error("Unknown mode '$mode'. Use 'serve' or 'mcp'.")
    }
}

fun loadSampleData(): Dataset {
    val text = object {}.javaClass.getResource("/sample-reviews.json")?.readText()
        ?: error("sample-reviews.json not found on the classpath")
    return AppJson.decodeFromString<Dataset>(text)
}
