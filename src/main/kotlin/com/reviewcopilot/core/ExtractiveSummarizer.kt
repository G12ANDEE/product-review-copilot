package com.reviewcopilot.core

import com.reviewcopilot.model.AnswerSource
import com.reviewcopilot.model.Product
import com.reviewcopilot.model.ProductSummary
import com.reviewcopilot.model.Review
import com.reviewcopilot.rag.Text

/**
 * Summarizes reviews without an LLM by counting positive and negative mentions of each aspect
 * (battery, build quality, comfort, ...). Used in offline mode and as the fallback whenever the
 * LLM call fails, so the endpoint always answers.
 */
object ExtractiveSummarizer {
    private val ASPECTS: Map<String, Set<String>> = mapOf(
        "Battery life" to setOf("battery", "charge", "charging", "charger", "hours"),
        "Sound quality" to setOf("sound", "bass", "audio", "music", "mic", "microphone", "calls"),
        "Build quality" to setOf("build", "quality", "sturdy", "plastic", "flimsy", "broke", "broken", "durable", "cheap", "cracked"),
        "Comfort" to setOf("comfortable", "comfort", "fit", "ears", "back", "lumbar", "armrest", "armrests", "cushion", "seat"),
        "Ease of use" to setOf("easy", "setup", "app", "controls", "instructions", "assemble", "assembly", "simple", "intuitive"),
        "Cooking results" to setOf("crispy", "fries", "chicken", "cook", "cooks", "cooked", "evenly", "food"),
        "Cleaning" to setOf("clean", "cleaning", "dishwasher", "basket", "coating", "wash"),
        "Noise" to setOf("noise", "noisy", "quiet", "loud", "squeak", "squeaks", "squeaky", "fan"),
        "Connectivity" to setOf("bluetooth", "pairing", "pair", "connection", "disconnect", "disconnects", "lag"),
        "Value for money" to setOf("price", "value", "money", "expensive", "worth", "overpriced", "deal"),
        "Delivery and packaging" to setOf("delivery", "delivered", "shipping", "arrived", "package", "packaging", "box", "dented"),
        "Customer support" to setOf("customer", "warranty", "refund", "replacement", "seller"),
    )

    private val POSITIVE = setOf(
        "great", "good", "excellent", "amazing", "helpful", "comfortable", "quiet", "perfect", "love",
        "loved", "lovely", "fantastic", "convenient", "nice", "best", "fast", "worth", "recommend", "easy",
        "solid", "sturdy", "crispy", "evenly", "clear", "durable", "impressive", "quick", "simple", "intuitive",
    )

    private val NEGATIVE = setOf(
        "bad", "noisy", "loud", "slow", "terrible", "awful", "broken", "broke", "smell", "expensive",
        "overpriced", "disappointing", "disappointed", "poor", "worst", "flimsy", "cracked", "dented", "squeaks",
        "squeaky", "unhelpful", "problem", "issue", "peeling", "disconnects", "lag", "hard", "difficult", "returned",
    )

    private val NEGATORS = setOf("not", "no", "never", "wasn", "isn", "didn", "nothing")

    private data class Tally(var positive: Int = 0, var negative: Int = 0, val reviewIds: MutableSet<String> = linkedSetOf())

    fun summarize(product: Product, reviews: List<Review>): ProductSummary {
        val average = reviews.map { it.rating }.average().let { if (it.isNaN()) 0.0 else it }
        val tallies = ASPECTS.keys.associateWith { Tally() }

        for (review in reviews) {
            for (sentence in Text.sentences(review.embeddingText())) {
                val words = sentence.lowercase().split(Regex("[^a-z']+")).filter { it.isNotEmpty() }
                val tone = sentenceTone(words, review.rating)
                ASPECTS.forEach { (aspect, keywords) ->
                    if (words.any { it in keywords }) {
                        val tally = tallies.getValue(aspect)
                        if (tone > 0) tally.positive++ else if (tone < 0) tally.negative++
                        tally.reviewIds += review.id
                    }
                }
            }
        }

        val pros = tallies.filter { (_, t) -> t.positive >= 2 && t.positive > t.negative }
            .entries.sortedByDescending { it.value.positive }.take(4)
        val cons = tallies.filter { (_, t) -> t.negative >= 1 && t.negative >= t.positive / 2 }
            .entries.sortedByDescending { it.value.negative }.take(4)

        val overview = buildString {
            append("Based on ${reviews.size} reviews (average ${"%.1f".format(average)}/5)")
            if (pros.isNotEmpty()) append(", reviewers most often praise ${pros.take(2).joinToString(" and ") { it.key.lowercase() }}")
            if (cons.isNotEmpty()) append("; the most common complaint is ${cons.first().key.lowercase()}")
            append(".")
        }

        return ProductSummary(
            productId = product.id,
            productName = product.name,
            reviewCount = reviews.size,
            averageRating = average.round1(),
            overview = overview,
            pros = pros.map { "${it.key} (${mentions(it.value.positive, "positive")})" },
            cons = cons.map { "${it.key} (${mentions(it.value.negative, "negative")})" },
            citedReviewIds = (pros + cons).flatMap { it.value.reviewIds }.distinct().take(8),
            source = AnswerSource.EXTRACTIVE,
            reviewsWithPhotos = reviews.count { it.photoInsights.isNotEmpty() },
            photosShowingDefects = reviews.sumOf { r -> r.photoInsights.count { it.showsDefect } },
        )
    }

    /** +1 positive, -1 negative, 0 neutral. Falls back to the star rating when the words are neutral. */
    private fun sentenceTone(words: List<String>, rating: Int): Int {
        var score = 0
        words.forEachIndexed { i, word ->
            val negated = words.subList(maxOf(0, i - 3), i).any { it in NEGATORS || it.endsWith("n't") }
            when (word) {
                in POSITIVE -> score += if (negated) -1 else 1
                in NEGATIVE -> score += if (negated) 1 else -1
            }
        }
        return when {
            score > 0 -> 1
            score < 0 -> -1
            rating >= 4 -> 1
            rating <= 2 -> -1
            else -> 0
        }
    }
}

private fun mentions(n: Int, tone: String) = if (n == 1) "1 $tone mention" else "$n $tone mentions"

internal fun Double.round1(): Double = Math.round(this * 10) / 10.0
