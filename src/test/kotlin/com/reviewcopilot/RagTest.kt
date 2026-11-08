package com.reviewcopilot

import com.reviewcopilot.model.Product
import com.reviewcopilot.model.NewReview
import com.reviewcopilot.model.Review
import com.reviewcopilot.rag.HashingEmbedder
import com.reviewcopilot.rag.InMemoryVectorStore
import com.reviewcopilot.rag.ReviewIndex
import com.reviewcopilot.rag.cosine
import io.ktor.server.plugins.NotFoundException
import kotlinx.coroutines.test.runTest
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RagTest {
    private val embedder = HashingEmbedder()

    @Test
    fun `hashing embedder is deterministic and normalized`() {
        val a = embedder.embedOne("Great battery life")
        assertContentEquals(a, embedder.embedOne("Great battery life"))
        assertEquals(1.0, sqrt(a.sumOf { (it * it).toDouble() }), 1e-5)
    }

    @Test
    fun `similar texts score higher than unrelated ones`() {
        val query = embedder.embedOne("how long does the battery last")
        val related = embedder.embedOne("The battery lasts a long time")
        val unrelated = embedder.embedOne("The chair arrived with a cracked armrest")
        assertTrue(cosine(query, related) > cosine(query, unrelated))
    }

    @Test
    fun `vector store returns top k by score and applies the filter`() {
        val store = InMemoryVectorStore()
        val reviews = listOf(
            Review("1", "h1", 5, "Bass", "The bass is great"),
            Review("2", "h1", 4, "Bass and mic", "Nice bass, weak mic"),
            Review("3", "h2", 5, "Bass", "The bass is great"),
            Review("4", "h1", 2, "Case", "The case broke"),
        )
        reviews.forEach { store.add(it, embedder.embedOne(it.embeddingText())) }

        val hits = store.search(embedder.embedOne("bass"), topK = 2) { it.productId == "h1" }

        assertEquals(listOf("1", "2"), hits.map { it.review.id })
        assertTrue(hits[0].score >= hits[1].score)
    }

    @Test
    fun `search only returns reviews of the requested product`() = runTest {
        val index = sampleIndex()
        val hits = index.search("aurora-earbuds", "battery", topK = 10)
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.all { it.review.productId == "aurora-earbuds" })
    }

    @Test
    fun `added review becomes searchable`() = runTest {
        val index = ReviewIndex(embedder).apply { addProduct(Product("h1", "Test Product", "Bangkok")) }
        val review = index.addReview("h1", NewReview(5, "Gift", "Bought this as a birthday gift and my dad loves it"))
        assertEquals(review.id, index.search("h1", "birthday gift").first().review.id)
    }

    @Test
    fun `invalid input is rejected`() = runTest {
        val index = ReviewIndex(embedder).apply { addProduct(Product("h1", "Test Product", "Bangkok")) }
        assertFailsWith<IllegalArgumentException> { index.addReview("h1", NewReview(6, "x", "text")) }
        assertFailsWith<IllegalArgumentException> { index.addReview("h1", NewReview(3, "x", "  ")) }
        assertFailsWith<NotFoundException> { index.search("nope", "bass") }
    }
}
