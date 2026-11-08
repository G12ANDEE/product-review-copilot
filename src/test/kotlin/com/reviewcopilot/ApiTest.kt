package com.reviewcopilot

import com.reviewcopilot.api.copilotModule
import com.reviewcopilot.core.ReviewCopilot
import com.reviewcopilot.model.AskResponse
import com.reviewcopilot.model.Product
import com.reviewcopilot.model.ProductSummary
import com.reviewcopilot.model.Review
import com.reviewcopilot.model.ReviewHit
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApiTest {
    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        val index = sampleIndex()
        application { copilotModule(index, ReviewCopilot(index, llm = null)) }
        block()
    }

    private inline fun <reified T> String.decode(): T = AppJson.decodeFromString(this)

    @Test
    fun `lists products`() = withApp {
        val response = client.get("/products")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(3, response.bodyAsText().decode<List<Product>>().size)
    }

    @Test
    fun `summary endpoint returns a summary`() = withApp {
        val summary = client.get("/products/breeze-airfryer/summary").bodyAsText().decode<ProductSummary>()
        assertEquals("Breeze 5L Air Fryer", summary.productName)
        assertEquals(8, summary.reviewCount)
    }

    @Test
    fun `unknown product is 404`() = withApp {
        assertEquals(HttpStatusCode.NotFound, client.get("/products/nope/summary").status)
    }

    @Test
    fun `blank question and malformed json are 400`() = withApp {
        val blank = client.post("/products/breeze-airfryer/ask") {
            contentType(ContentType.Application.Json)
            setBody("""{"question": "  "}""")
        }
        assertEquals(HttpStatusCode.BadRequest, blank.status)

        val malformed = client.post("/products/breeze-airfryer/ask") {
            contentType(ContentType.Application.Json)
            setBody("""{"question": """)
        }
        assertEquals(HttpStatusCode.BadRequest, malformed.status)
    }

    @Test
    fun `posted review can be found by search and ask`() = withApp {
        val created = client.post("/products/nimbus-chair/reviews") {
            contentType(ContentType.Application.Json)
            setBody("""{"rating": 5, "title": "Footrest", "text": "The hidden footrest slides out and is great for naps."}""")
        }
        assertEquals(HttpStatusCode.Created, created.status)
        val review = created.bodyAsText().decode<Review>()

        val hits = client.get("/products/nimbus-chair/search?q=footrest").bodyAsText().decode<List<ReviewHit>>()
        assertEquals(review.id, hits.first().review.id)

        val answer = client.post("/products/nimbus-chair/ask") {
            contentType(ContentType.Application.Json)
            setBody("""{"question": "Does it have a footrest?"}""")
        }.bodyAsText().decode<AskResponse>()
        assertTrue(answer.answer.contains(review.id))
    }
}
