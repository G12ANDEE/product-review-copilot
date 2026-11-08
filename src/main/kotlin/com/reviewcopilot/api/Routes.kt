package com.reviewcopilot.api

import com.reviewcopilot.AppJson
import com.reviewcopilot.core.ReviewCopilot
import com.reviewcopilot.model.AskRequest
import com.reviewcopilot.model.ErrorResponse
import com.reviewcopilot.model.NewReview
import com.reviewcopilot.rag.ReviewIndex
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

/**
 * Ktor module with the REST API. It is an extension function on [Application], so tests can
 * install exactly the same module with fake dependencies.
 */
fun Application.copilotModule(index: ReviewIndex, copilot: ReviewCopilot) {
    install(ContentNegotiation) { json(AppJson) }

    install(StatusPages) {
        exception<NotFoundException> { call, e ->
            call.respond(HttpStatusCode.NotFound, ErrorResponse(e.message ?: "Not found"))
        }
        exception<BadRequestException> { call, e ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Bad request"))
        }
        exception<IllegalArgumentException> { call, e ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Bad request"))
        }
    }

    routing {
        get("/health") { call.respond(mapOf("status" to "ok")) }

        route("/products") {
            get { call.respond(index.products()) }

            route("/{id}") {
                get("/reviews") { call.respond(index.reviewsOf(call.productId())) }

                post("/reviews") {
                    val review = copilot.addReview(call.productId(), call.receive<NewReview>())
                    call.respond(HttpStatusCode.Created, review)
                }

                get("/search") {
                    val query = call.request.queryParameters["q"] ?: throw BadRequestException("missing query parameter 'q'")
                    val topK = call.request.queryParameters["topK"]?.toIntOrNull() ?: 5
                    call.respond(index.search(call.productId(), query, topK))
                }

                get("/summary") { call.respond(copilot.summarize(call.productId())) }

                post("/ask") {
                    val request = call.receive<AskRequest>()
                    call.respond(copilot.ask(call.productId(), request.question, request.topK))
                }
            }
        }
    }
}

private fun io.ktor.server.application.ApplicationCall.productId(): String =
    parameters["id"] ?: throw BadRequestException("missing product id")
