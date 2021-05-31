package dev.brella.corsmechanics

import com.github.benmanes.caffeine.cache.Caffeine
import io.ktor.application.*
import io.ktor.client.utils.*
import io.ktor.http.*
import io.ktor.response.*
import io.ktor.routing.*
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.encodeToJsonElement
import java.util.concurrent.TimeUnit

val OPERATION_CACHE = Caffeine.newBuilder()
    .maximumSize(100)
    .expireAfterAccess(5, TimeUnit.MINUTES)
    .buildCoroutines<String, JsonExplorerOperation> { op ->
        return@buildCoroutines when {
            op.contains("!=") -> {
                val key = op.substringBefore("!=")
                val value = op.substringAfter("!=")

                JsonExplorerOperation.NotEquals(key, value)
            }
            op.contains("^=") -> {
                val key = op.substringBefore("^=")
                val value = op.substringAfter("^=")

                JsonExplorerOperation.StartsWith(key, value)
            }
            op.contains("$=") -> {
                val key = op.substringBefore("$=")
                val value = op.substringAfter("$=")

                JsonExplorerOperation.EndsWith(key, value)
            }
            op.contains("!*=") -> {
                val key = op.substringBefore("!*=")
                val value = op.substringAfter("!*=")

                JsonExplorerOperation.NotContains(key, value)
            }
            op.contains("*=") -> {
                val key = op.substringBefore("*=")
                val value = op.substringAfter("*=")

                JsonExplorerOperation.Contains(key, value)
            }
            op.contains('=') -> {
                val key = op.substringBefore('=')
                val value = op.substringAfter('=')

                JsonExplorerOperation.Equals(key, value)
            }
            op.contains("==") -> {
                val key = op.substringBefore("==")
                val value = op.substringAfter("==")

                JsonExplorerOperation.Equals(key, value)
            }

            /** Word Ops */
            op.contains("neq") -> {
                val key = op.substringBefore("neq").trim()
                val value = op.substringAfter("neq").trim()

                JsonExplorerOperation.NotEquals(key, value)
            }
            op.contains("eq") -> {
                val key = op.substringBefore("eq")
                val value = op.substringAfter("eq")

                JsonExplorerOperation.Equals(key, value)
            }
            op.contains("starts with") -> {
                val key = op.substringBefore("starts with")
                val value = op.substringAfter("starts with")

                JsonExplorerOperation.StartsWith(key, value)
            }
            op.contains("ends with") -> {
                val key = op.substringBefore("ends with")
                val value = op.substringAfter("ends with")

                JsonExplorerOperation.EndsWith(key, value)
            }
            op.contains("!contains") -> {
                val key = op.substringBefore("!contains")
                val value = op.substringAfter("!contains")

                JsonExplorerOperation.Contains(key, value)
            }
            op.contains("contains") -> {
                val key = op.substringBefore("contains")
                val value = op.substringAfter("contains")

                JsonExplorerOperation.Contains(key, value)
            }

            /* Numerical Ops */

            op.contains(">=") -> {
                val key = op.substringBefore(">=")
                val value = op.substringAfter(">=")

                JsonExplorerOperation.GreaterThanOrEqual(key, value.toDoubleOrNull() ?: return@buildCoroutines JsonExplorerOperation.True)
            }
            op.contains("<=") -> {
                val key = op.substringBefore("<=")
                val value = op.substringAfter("<=")

                JsonExplorerOperation.LessThanOrEqual(key, value.toDoubleOrNull() ?: return@buildCoroutines JsonExplorerOperation.True)
            }

            op.contains(">") -> {
                val key = op.substringBefore(">")
                val value = op.substringAfter(">")

                JsonExplorerOperation.GreaterThan(key, value.toDoubleOrNull() ?: return@buildCoroutines JsonExplorerOperation.True)
            }
            op.contains("<") -> {
                val key = op.substringBefore("<")
                val value = op.substringAfter("<")

                JsonExplorerOperation.LessThan(key, value.toDoubleOrNull() ?: return@buildCoroutines JsonExplorerOperation.True)
            }

            else -> JsonExplorerOperation.True
        }
    }

sealed class JsonExplorerOperation {
    data class And(val a: JsonExplorerOperation, val b: JsonExplorerOperation) : JsonExplorerOperation() {
        override fun test(element: JsonElement): Boolean =
            a.test(element) && b.test(element)
    }

    data class Or(val a: JsonExplorerOperation, val b: JsonExplorerOperation) : JsonExplorerOperation() {
        override fun test(element: JsonElement): Boolean =
            a.test(element) || b.test(element)
    }

    data class Xor(val a: JsonExplorerOperation, val b: JsonExplorerOperation) : JsonExplorerOperation() {
        override fun test(element: JsonElement): Boolean =
            a.test(element) xor b.test(element)
    }

    data class Equals(val key: String, val expected: String) : JsonExplorerOperation() {
        override fun test(element: JsonElement): Boolean =
            element.jsonObjectOrNull?.get(key)?.jsonPrimitiveOrNull?.content == expected
    }

    data class NotEquals(val key: String, val expected: String) : JsonExplorerOperation() {
        override fun test(element: JsonElement): Boolean =
            element.jsonObjectOrNull?.get(key)?.jsonPrimitiveOrNull?.content != expected
    }

    data class StartsWith(val key: String, val expected: String) : JsonExplorerOperation() {
        override fun test(element: JsonElement): Boolean =
            element.jsonObjectOrNull?.get(key)?.jsonPrimitiveOrNull?.content?.startsWith(expected) == true
    }

    data class EndsWith(val key: String, val expected: String) : JsonExplorerOperation() {
        override fun test(element: JsonElement): Boolean =
            element.jsonObjectOrNull?.get(key)?.jsonPrimitiveOrNull?.content?.endsWith(expected) == true
    }

    data class Contains(val key: String, val expected: String) : JsonExplorerOperation() {
        override fun test(element: JsonElement): Boolean {
            return when (val arg = element.jsonObjectOrNull?.get(key) ?: return false) {
                is JsonPrimitive -> arg.content.contains(expected)
                is JsonArray -> arg.any { it.jsonPrimitiveOrNull?.content?.contains(expected) == true }
                else -> false
            }
        }
    }

    data class NotContains(val key: String, val expected: String) : JsonExplorerOperation() {
        override fun test(element: JsonElement): Boolean {
            return when (val arg = element.jsonObjectOrNull?.get(key) ?: return false) {
                is JsonPrimitive -> !arg.content.contains(expected)
                is JsonArray -> arg.none { it.jsonPrimitiveOrNull?.content?.contains(expected) == true }
                else -> false
            }
        }
    }

    data class GreaterThanOrEqual(val key: String, val expected: Double) : JsonExplorerOperation() {
        override fun test(element: JsonElement): Boolean {
            return (element.jsonObjectOrNull?.get(key)?.jsonPrimitiveOrNull?.doubleOrNull ?: return false) >= expected
        }
    }

    data class LessThanOrEqual(val key: String, val expected: Double) : JsonExplorerOperation() {
        override fun test(element: JsonElement): Boolean {
            return (element.jsonObjectOrNull?.get(key)?.jsonPrimitiveOrNull?.doubleOrNull ?: return false) <= expected
        }
    }

    data class GreaterThan(val key: String, val expected: Double) : JsonExplorerOperation() {
        override fun test(element: JsonElement): Boolean {
            return (element.jsonObjectOrNull?.get(key)?.jsonPrimitiveOrNull?.doubleOrNull ?: return false) > expected
        }
    }

    data class LessThan(val key: String, val expected: Double) : JsonExplorerOperation() {
        override fun test(element: JsonElement): Boolean {
            return (element.jsonObjectOrNull?.get(key)?.jsonPrimitiveOrNull?.doubleOrNull ?: return false) < expected
        }
    }

    object True : JsonExplorerOperation() {
        override fun test(element: JsonElement): Boolean = true
    }

    object False : JsonExplorerOperation() {
        override fun test(element: JsonElement): Boolean = true
    }

    abstract fun test(element: JsonElement): Boolean
}

inline fun <reified T : Any> Route.withJsonExplorer(route: String, encoder: Json = json, crossinline getJson: suspend ApplicationCall.() -> T) {
    route(route) {
        get("/") {
            call.respond(call.getJson())
        }

        jsonExplorer { encoder.encodeToJsonElement(getJson()) }
    }
}

inline fun Route.jsonExplorer(route: String, crossinline getJson: suspend ApplicationCall.() -> JsonElement) =
    route(route) {
        jsonExplorer(getJson)
    }

inline fun Route.jsonExplorer(crossinline getJson: suspend ApplicationCall.() -> JsonElement) {
    get("/{path...}") {
        val jsonPath = call.parameters.getAll("path") ?: emptyList()
        var json: JsonElement? = call.getJson()
        jsonPath.forEach { component ->
            val components = component.split('[').map { it.trimEnd(']') }
            json = if (components[0] == "this" || components[0] == "self") json else if (json is JsonObject) (json as JsonObject)[components[0]] else json

            if (components.size > 1) {
                components
                    .drop(1)
                    .forEach { argument ->
                        val arrayIndex = argument.toIntOrNull()

                        if (arrayIndex != null) {
                            json =
                                if (json is JsonArray)
                                    (json as JsonArray).getOrNull(arrayIndex)
                                else
                                    return@get call.respond(HttpStatusCode.BadRequest)
                        } else {
                            val operation = OPERATION_CACHE[argument].await()
                            json =
                                if (json is JsonArray)
                                    (json as JsonArray).filter(operation::test)
                                        .let { list ->
                                            when (list.size) {
                                                0 -> return@get call.respond(HttpStatusCode.BadRequest)
                                                1 -> list[0]
                                                else -> JsonArray(list)
                                            }
                                        }
                                else
                                    return@get call.respond(HttpStatusCode.BadRequest)
                        }
                    }
            }
        }

        json?.let { call.respond(it) } ?: call.respond(EmptyContent)
    }
}