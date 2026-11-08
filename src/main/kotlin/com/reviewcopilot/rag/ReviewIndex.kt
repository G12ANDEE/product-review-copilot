package com.reviewcopilot.rag

import com.reviewcopilot.model.Dataset
import com.reviewcopilot.model.Product
import com.reviewcopilot.model.NewReview
import com.reviewcopilot.model.PhotoInsight
import com.reviewcopilot.model.Review
import com.reviewcopilot.model.ReviewHit
import io.ktor.server.plugins.NotFoundException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Holds products and their reviews, and keeps the vector store in sync with them. */
class ReviewIndex(private val embedder: Embedder) {
    private val products = ConcurrentHashMap<String, Product>()
    private val reviewsByProduct = ConcurrentHashMap<String, CopyOnWriteArrayList<Review>>()
    private val store = InMemoryVectorStore()

    fun addProduct(product: Product) {
        products[product.id] = product
        reviewsByProduct.putIfAbsent(product.id, CopyOnWriteArrayList())
    }

    suspend fun addReviews(reviews: List<Review>) {
        reviews.forEach { requireProduct(it.productId) }
        // One batched call: real embedding APIs charge per request, so batching matters.
        val vectors = embedder.embed(reviews.map { it.embeddingText() })
        reviews.zip(vectors).forEach { (review, vector) ->
            reviewsByProduct.getValue(review.productId).add(review)
            store.add(review, vector)
        }
    }

    suspend fun addReview(productId: String, input: NewReview, photoInsights: List<PhotoInsight> = emptyList()): Review {
        require(input.rating in 1..5) { "rating must be between 1 and 5" }
        require(input.text.isNotBlank()) { "text must not be blank" }
        val review = Review(
            id = "r-" + UUID.randomUUID().toString().take(8),
            productId = requireProduct(productId).id,
            rating = input.rating,
            title = input.title.trim(),
            text = input.text.trim(),
            verifiedPurchase = input.verifiedPurchase,
            photoInsights = photoInsights,
        )
        addReviews(listOf(review))
        return review
    }

    suspend fun loadDataset(dataset: Dataset) {
        dataset.products.forEach(::addProduct)
        addReviews(dataset.reviews)
    }

    fun products(): List<Product> = products.values.sortedBy { it.id }

    fun product(id: String): Product? = products[id]

    fun requireProduct(id: String): Product = products[id] ?: throw NotFoundException("Product '$id' not found")

    fun reviewsOf(productId: String): List<Review> = reviewsByProduct[requireProduct(productId).id].orEmpty()

    /** Semantic search restricted to one product's reviews. */
    suspend fun search(productId: String, query: String, topK: Int = 5): List<ReviewHit> {
        requireProduct(productId)
        require(query.isNotBlank()) { "query must not be blank" }
        val queryVector = embedder.embed(listOf(query)).single()
        return store.search(queryVector, topK.coerceIn(1, 20)) { it.productId == productId }
    }
}
