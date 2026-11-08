package com.reviewcopilot

import com.reviewcopilot.api.copilotModule
import com.reviewcopilot.core.PhotoAnalyzer
import com.reviewcopilot.core.ReviewCopilot
import com.reviewcopilot.model.NewReview
import com.reviewcopilot.model.Photo
import com.reviewcopilot.model.Review
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PhotoAnalyzerTest {
    private val crackedHinge = FakeVision {
        """{"description": "The charging case lid is snapped off at the hinge.", "showsDefect": true}"""
    }

    @Test
    fun `photo description is stored, searchable and counted in the summary`() = runTest {
        val index = sampleIndex()
        val copilot = ReviewCopilot(index, llm = null, photos = PhotoAnalyzer(crackedHinge))

        // The text alone says nothing about damage; only the photo does.
        val review = copilot.addReview("aurora-earbuds", NewReview(2, "Not happy", "See photo.", photos = listOf(TINY_PNG)))

        assertEquals(1, crackedHinge.calls)
        assertEquals(true, review.photoInsights.single().showsDefect)

        val hits = index.search("aurora-earbuds", "case hinge snapped", topK = 1)
        assertEquals(review.id, hits.single().review.id)

        val summary = copilot.summarize("aurora-earbuds")
        assertEquals(1, summary.reviewsWithPhotos)
        assertEquals(1, summary.photosShowingDefects)
    }

    @Test
    fun `photo evidence is included in the llm prompt`() = runTest {
        val index = sampleIndex()
        val llm = FakeLlm { "A buyer's photo shows the case lid snapped off [x]." }
        val copilot = ReviewCopilot(index, llm, PhotoAnalyzer(crackedHinge))
        copilot.addReview("aurora-earbuds", NewReview(2, "Not happy", "See photo.", photos = listOf(TINY_PNG)))

        copilot.ask("aurora-earbuds", "Did the case hinge snap for anyone?")

        assertTrue(llm.prompts.single().contains("Customer photo, shows a defect: The charging case lid"))
    }

    @Test
    fun `a failing vision call skips the photo but keeps the review`() = runTest {
        val copilot = ReviewCopilot(sampleIndex(), llm = null, photos = PhotoAnalyzer(FakeVision { error("timeout") }))
        val review = copilot.addReview("nimbus-chair", NewReview(4, "Nice", "Comfortable chair", photos = listOf(TINY_PNG)))
        assertTrue(review.photoInsights.isEmpty())
    }

    @Test
    fun `invalid photos are rejected before calling the model`() = runTest {
        val vision = FakeVision { "{}" }
        val analyzer = PhotoAnalyzer(vision)
        assertFailsWith<IllegalArgumentException> { analyzer.analyze("x", listOf(Photo("image/bmp", TINY_PNG.data))) }
        assertFailsWith<IllegalArgumentException> { analyzer.analyze("x", listOf(Photo("image/png", "not base64!!"))) }
        assertFailsWith<IllegalArgumentException> { analyzer.analyze("x", List(5) { TINY_PNG }) }
        assertEquals(0, vision.calls)
    }

    @Test
    fun `prose replies are kept and defects guessed from keywords`() {
        val insight = PhotoAnalyzer.parse("The basket coating is peeling near the handle.")!!
        assertEquals(true, insight.showsDefect)
        assertEquals(false, PhotoAnalyzer.parse("""{"description": "A new chair in its box.", "showsDefect": false}""")!!.showsDefect)
    }

    @Test
    fun `api accepts a review with a photo and rejects a bad one`() = testApplication {
        val index = sampleIndex()
        application { copilotModule(index, ReviewCopilot(index, llm = null, photos = PhotoAnalyzer(crackedHinge))) }

        val ok = client.post("/products/aurora-earbuds/reviews") {
            contentType(ContentType.Application.Json)
            setBody("""{"rating": 1, "title": "Broken", "text": "Look at this.", "photos": [{"mediaType": "image/png", "data": "${TINY_PNG.data}"}]}""")
        }
        assertEquals(HttpStatusCode.Created, ok.status)
        assertEquals(true, AppJson.decodeFromString<Review>(ok.bodyAsText()).photoInsights.single().showsDefect)

        val bad = client.post("/products/aurora-earbuds/reviews") {
            contentType(ContentType.Application.Json)
            setBody("""{"rating": 1, "title": "Broken", "text": "Look.", "photos": [{"mediaType": "image/tiff", "data": "AAAA"}]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, bad.status)
    }
}
