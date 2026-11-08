package com.reviewcopilot

import com.reviewcopilot.core.ReviewCopilot
import com.reviewcopilot.model.AnswerSource
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReviewCopilotTest {
    @Test
    fun `offline summary finds praise and complaints`() = runTest {
        val summary = ReviewCopilot(sampleIndex(), llm = null).summarize("aurora-earbuds")

        assertEquals(AnswerSource.EXTRACTIVE, summary.source)
        assertEquals(9, summary.reviewCount)
        assertEquals(3.4, summary.averageRating)
        assertTrue(summary.pros.any { it.startsWith("Battery life") }, "pros: ${summary.pros}")
        assertTrue(summary.cons.any { it.startsWith("Comfort") || it.startsWith("Connectivity") }, "cons: ${summary.cons}")
    }

    @Test
    fun `llm summary is used and invented citations are dropped`() = runTest {
        val llm = FakeLlm {
            """Here you go:
            {"overview": "Great battery, flimsy case.", "pros": ["Battery"], "cons": ["Case hinge"],
             "citedReviewIds": ["earbuds-01", "made-up-id"]}"""
        }
        val summary = ReviewCopilot(sampleIndex(), llm).summarize("aurora-earbuds")

        assertEquals(AnswerSource.LLM, summary.source)
        assertEquals("Great battery, flimsy case.", summary.overview)
        assertEquals(listOf("earbuds-01"), summary.citedReviewIds)
        assertTrue(llm.prompts.single().contains("<reviews>"))
    }

    @Test
    fun `falls back to extractive summary when the llm fails or returns junk`() = runTest {
        val failing = ReviewCopilot(sampleIndex(), FakeLlm { error("provider down") })
        assertEquals(AnswerSource.EXTRACTIVE, failing.summarize("breeze-airfryer").source)

        val junk = ReviewCopilot(sampleIndex(), FakeLlm { "Sorry, I can't do that." })
        assertEquals(AnswerSource.EXTRACTIVE, junk.summarize("breeze-airfryer").source)
    }

    @Test
    fun `ask retrieves relevant reviews and keeps only cited ones`() = runTest {
        val llm = FakeLlm { "One buyer says the chair squeaks when leaning back [chair-03]." }
        val response = ReviewCopilot(sampleIndex(), llm).ask("nimbus-chair", "Does the chair squeak?")

        assertEquals(AnswerSource.LLM, response.source)
        assertEquals(listOf("chair-03"), response.citations.map { it.review.id })
        // The retrieved squeak review must have been put into the prompt (that's the "R" in RAG).
        assertTrue(llm.prompts.single().contains("[chair-03]"))
    }

    @Test
    fun `offline ask returns the best matching review first`() = runTest {
        val response = ReviewCopilot(sampleIndex(), llm = null).ask("breeze-airfryer", "Is the non-stick coating peeling?")

        assertEquals(AnswerSource.EXTRACTIVE, response.source)
        assertEquals("airfryer-03", response.citations.first().review.id)
    }

    @Test
    fun `extractJsonObject strips prose and code fences`() {
        val raw = "```json\n{\"a\": {\"b\": 1}}\n```"
        assertEquals("{\"a\": {\"b\": 1}}", ReviewCopilot.extractJsonObject(raw))
    }
}
