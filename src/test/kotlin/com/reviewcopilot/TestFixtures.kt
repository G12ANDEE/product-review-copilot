package com.reviewcopilot

import com.reviewcopilot.llm.LlmClient
import com.reviewcopilot.rag.HashingEmbedder
import com.reviewcopilot.rag.ReviewIndex

/** A fake LLM: returns a canned reply (or throws) and records the prompts it was given. */
class FakeLlm(private val reply: (prompt: String) -> String) : LlmClient {
    override val name = "fake"
    val prompts = mutableListOf<String>()

    override suspend fun complete(system: String, prompt: String, maxTokens: Int): String {
        prompts += prompt
        return reply(prompt)
    }
}

suspend fun sampleIndex(): ReviewIndex = ReviewIndex(HashingEmbedder()).apply { loadDataset(loadSampleData()) }

/** A fake vision model: returns a canned reply for every photo (or throws). */
class FakeVision(private val reply: () -> String) : com.reviewcopilot.llm.VisionModel {
    override val name = "fake-vision"
    var calls = 0

    override suspend fun describe(photo: com.reviewcopilot.model.Photo, system: String, instruction: String, maxTokens: Int): String {
        calls++
        return reply()
    }
}

/** A tiny valid base64 payload; the fake vision model never looks at the pixels. */
val TINY_PNG = com.reviewcopilot.model.Photo("image/png", java.util.Base64.getEncoder().encodeToString(byteArrayOf(-119, 80, 78, 71)))
