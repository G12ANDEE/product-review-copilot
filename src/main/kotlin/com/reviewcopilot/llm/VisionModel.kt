package com.reviewcopilot.llm

import com.reviewcopilot.model.Photo

/**
 * A vision-language model (VLM): takes an image plus an instruction and returns text.
 * [AnthropicClient] and [OpenAiCompatibleClient] implement both [LlmClient] and this interface,
 * since current models from both providers accept images in the same API.
 */
interface VisionModel {
    val name: String

    suspend fun describe(photo: Photo, system: String, instruction: String, maxTokens: Int = 300): String
}
