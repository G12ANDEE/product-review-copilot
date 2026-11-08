package com.reviewcopilot

import kotlinx.serialization.json.Json

/** One shared JSON configuration for the server, the MCP transport and the API clients. */
val AppJson: Json = Json {
    ignoreUnknownKeys = true // external APIs add fields over time; don't break on them
    encodeDefaults = true
    explicitNulls = false
}
