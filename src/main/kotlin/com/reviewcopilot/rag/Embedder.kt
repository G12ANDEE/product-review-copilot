package com.reviewcopilot.rag

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlin.math.sqrt

/** Turns text into vectors. `suspend` because real implementations call a network API. */
interface Embedder {
    suspend fun embed(texts: List<String>): List<FloatArray>
}

/**
 * Offline embedder using the "hashing trick": every unigram and bigram is hashed into one of
 * [dimensions] buckets, so similar wording gives similar vectors. It has no notion of synonyms,
 * but it needs no API key, is deterministic, and is good enough for tests and demos.
 */
class HashingEmbedder(private val dimensions: Int = 1024) : Embedder {
    override suspend fun embed(texts: List<String>): List<FloatArray> = texts.map(::embedOne)

    fun embedOne(text: String): FloatArray {
        val vector = FloatArray(dimensions)
        val tokens = Text.tokenize(text)
        val features = tokens + tokens.zipWithNext { a, b -> "$a $b" }
        for (feature in features) {
            val hash = feature.hashCode()
            val bucket = Math.floorMod(hash, dimensions)
            // A second hash bit picks the sign, so collisions tend to cancel out instead of piling up.
            val sign = if ((hash shr 16) and 1 == 0) 1f else -1f
            vector[bucket] += sign
        }
        return vector.normalized()
    }
}

/**
 * Embedder for any OpenAI-compatible `/embeddings` endpoint (OpenAI, Azure OpenAI, Ollama, ...).
 */
class OpenAiCompatibleEmbedder(
    private val http: HttpClient,
    private val apiKey: String,
    private val model: String = "text-embedding-3-small",
    private val baseUrl: String = "https://api.openai.com/v1",
) : Embedder {
    @Serializable
    private data class Request(val model: String, val input: List<String>)

    @Serializable
    private data class Item(val index: Int, val embedding: List<Float>)

    @Serializable
    private data class Response(val data: List<Item>)

    override suspend fun embed(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        val response: Response = http.post("$baseUrl/embeddings") {
            bearerAuth(apiKey)
            contentType(ContentType.Application.Json)
            setBody(Request(model, texts))
        }.body()
        return response.data.sortedBy { it.index }.map { it.embedding.toFloatArray().normalized() }
    }
}

/** Scale a vector to length 1, so cosine similarity becomes a plain dot product. */
fun FloatArray.normalized(): FloatArray {
    val norm = sqrt(sumOf { (it * it).toDouble() }).toFloat()
    return if (norm == 0f) this else FloatArray(size) { this[it] / norm }
}

fun cosine(a: FloatArray, b: FloatArray): Double {
    require(a.size == b.size) { "Vector sizes differ: ${a.size} vs ${b.size}" }
    var dot = 0.0
    var normA = 0.0
    var normB = 0.0
    for (i in a.indices) {
        dot += a[i] * b[i]
        normA += a[i] * a[i]
        normB += b[i] * b[i]
    }
    if (normA == 0.0 || normB == 0.0) return 0.0
    return dot / (sqrt(normA) * sqrt(normB))
}
