package dev.brella.corsmechanics

import com.github.benmanes.caffeine.cache.Caffeine
import io.ktor.client.utils.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.encodeToJsonElement
import java.util.concurrent.TimeUnit

inline fun <reified T : Any> Route.withJsonExplorer(route: String, encoder: Json = Serialisation.json, crossinline getJson: suspend ApplicationCall.() -> T) {
    route(route) {
        get("/") {
            val json = call.getJson()
            try {
                call.respond(call.getJson())
            } catch (th: Throwable) {
                System.err.println("Error on encoding $json")
                th.printStackTrace()
                throw th
            }
        }

        jsonExplorer {
            val json = getJson()
            try {
                encoder.encodeToJsonElement(json)
            } catch (th: Throwable) {
                System.err.println("Error on encoding $json")
                th.printStackTrace()
                throw th
            }
        }
    }
}

inline fun Route.jsonExplorer(route: String, crossinline getJson: suspend ApplicationCall.() -> JsonElement) =
    route(route) {
        jsonExplorer(getJson)
    }

inline fun Route.jsonExplorer(crossinline getJson: suspend ApplicationCall.() -> JsonElement) {
    get("/{path...}") {
        val json = call.getJson().filterByPath(call.parameters.getAll("path") ?: emptyList())

        json?.let {
            try {
                call.respond(it)
            } catch (th: Throwable) {
                th.printStackTrace()
                throw th
            }
        } ?: call.respond(EmptyContent)
    }
}