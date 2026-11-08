package com.reviewcopilot.rag

/** Tiny text utilities shared by the hashing embedder and the extractive summarizer. */
object Text {
    private val STOPWORDS = setOf(
        "a", "an", "the", "and", "or", "but", "if", "of", "to", "in", "on", "at", "for", "with",
        "is", "was", "were", "are", "be", "been", "it", "its", "this", "that", "we", "our", "i",
        "my", "me", "you", "they", "there", "very", "so", "as", "by", "from", "had", "has", "have",
        "do", "did", "does", "not", "no", "just", "also", "too", "than", "then", "what", "how",
        "which", "who", "about", "would", "could", "will", "can", "any", "all", "some", "us",
    )

    private val SPLIT = Regex("[^\\p{L}\\p{N}]+")

    /** Lowercase, split on anything that is not a letter or digit, drop stopwords, light stemming. */
    fun tokenize(text: String): List<String> =
        text.lowercase()
            .split(SPLIT)
            .filter { it.length > 1 && it !in STOPWORDS }
            .map(::stem)

    /** A deliberately small stemmer: "rooms" -> "room", "staffed" stays as is. */
    fun stem(word: String): String = when {
        word.length > 4 && word.endsWith("ies") -> word.dropLast(3) + "y"
        word.length > 3 && word.endsWith("s") && !word.endsWith("ss") -> word.dropLast(1)
        else -> word
    }

    fun sentences(text: String): List<String> =
        text.split(Regex("(?<=[.!?])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
}
