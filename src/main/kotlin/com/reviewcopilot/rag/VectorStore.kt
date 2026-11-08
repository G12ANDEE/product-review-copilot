package com.reviewcopilot.rag

import com.reviewcopilot.model.Review
import com.reviewcopilot.model.ReviewHit
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A brute-force in-memory vector store. Search is O(n), which is fine for thousands of reviews;
 * a production system would swap this for pgvector, OpenSearch k-NN, Qdrant, etc.
 */
class InMemoryVectorStore {
    private class Entry(val review: Review, val vector: FloatArray)

    // Thread-safe list: reads (searches) are frequent, writes (new reviews) are rare.
    private val entries = CopyOnWriteArrayList<Entry>()

    fun add(review: Review, vector: FloatArray) {
        entries.add(Entry(review, vector))
    }

    fun size(): Int = entries.size

    fun search(
        query: FloatArray,
        topK: Int,
        minScore: Double = 0.0,
        filter: (Review) -> Boolean = { true },
    ): List<ReviewHit> =
        entries.asSequence()
            .filter { filter(it.review) }
            .map { ReviewHit(it.review, cosine(query, it.vector)) }
            .filter { it.score > minScore }
            .sortedByDescending { it.score }
            .take(topK)
            .toList()
}
