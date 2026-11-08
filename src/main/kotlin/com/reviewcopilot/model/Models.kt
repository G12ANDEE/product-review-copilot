package com.reviewcopilot.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Kotlin note: a `data class` gets equals/hashCode/toString/copy for free.
// `@Serializable` lets kotlinx.serialization turn it into JSON and back.

@Serializable
data class Product(
    val id: String,
    val name: String,
    val category: String,
)

@Serializable
data class Review(
    val id: String,
    val productId: String,
    val rating: Int,
    val title: String,
    val text: String,
    val verifiedPurchase: Boolean = false,
    /** What the vision model saw in the photos attached to this review. */
    val photoInsights: List<PhotoInsight> = emptyList(),
) {
    /**
     * The text we embed for retrieval: title, body, and the photo descriptions. Because photo
     * descriptions are embedded too, a question like "did anything arrive damaged?" can retrieve
     * a review whose text says little but whose photo shows a cracked part.
     */
    fun embeddingText(): String =
        (listOf("$title. $text") + photoInsights.map { "Customer photo: ${it.description}" }).joinToString(" ")
}

/** A photo attached to a new review, as base64 so it fits in a JSON body. */
@Serializable
data class Photo(
    val mediaType: String, // image/jpeg, image/png, image/webp or image/gif
    val data: String, // base64, without the "data:...;base64," prefix
)

/** Output of the vision model (VLM) for one photo. */
@Serializable
data class PhotoInsight(
    val description: String,
    val showsDefect: Boolean,
)

/** Body of `POST /products/{id}/reviews`. */
@Serializable
data class NewReview(
    val rating: Int,
    val title: String,
    val text: String,
    val verifiedPurchase: Boolean = false,
    val photos: List<Photo> = emptyList(),
)

/** One retrieval result: a review and its cosine similarity to the query. */
@Serializable
data class ReviewHit(
    val review: Review,
    val score: Double,
)

@Serializable
enum class AnswerSource {
    /** Written by the LLM, grounded in retrieved reviews. */
    @SerialName("llm")
    LLM,

    /** Built without an LLM (offline mode, or the LLM call failed). */
    @SerialName("extractive")
    EXTRACTIVE,
}

@Serializable
data class ProductSummary(
    val productId: String,
    val productName: String,
    val reviewCount: Int,
    val averageRating: Double,
    val overview: String,
    val pros: List<String>,
    val cons: List<String>,
    val citedReviewIds: List<String>,
    val source: AnswerSource,
    val reviewsWithPhotos: Int = 0,
    val photosShowingDefects: Int = 0,
)

@Serializable
data class AskRequest(
    val question: String,
    val topK: Int = 5,
)

@Serializable
data class AskResponse(
    val productId: String,
    val question: String,
    val answer: String,
    val citations: List<ReviewHit>,
    val source: AnswerSource,
)

@Serializable
data class ErrorResponse(val error: String)

/** Shape of the bundled sample data file. */
@Serializable
data class Dataset(
    val products: List<Product>,
    val reviews: List<Review>,
)
