package dev.brella.corsmechanics.baal

import com.github.benmanes.caffeine.cache.AsyncCacheLoader
import com.github.benmanes.caffeine.cache.AsyncLoadingCache
import com.github.benmanes.caffeine.cache.Caffeine
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.r2dbc.spi.Row
import kotlinx.coroutines.*
import kotlinx.coroutines.future.future
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec
import java.util.*
import java.util.concurrent.Executor
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.measureTime

inline fun property(key: String) =
    System.getProperty(key) ?: System.getenv(key)


public inline fun JsonObject.getJsonObject(key: String) =
    getValue(key).jsonObject

public inline fun JsonObject.getJsonArray(key: String) =
    getValue(key).jsonArray

public inline fun JsonObject.getJsonPrimitive(key: String) =
    getValue(key).jsonPrimitive

public inline fun JsonObject.getString(key: String) =
    getValue(key).jsonPrimitive.content

public inline fun JsonObject.getInt(key: String) =
    getValue(key).jsonPrimitive.int

public inline fun JsonObject.getLong(key: String) =
    getValue(key).jsonPrimitive.long


public inline fun JsonObject.getJsonObjectOrNull(key: String) =
    get(key) as? JsonObject

public inline fun JsonObject.getJsonArrayOrNull(key: String) =
    get(key) as? JsonArray

public inline fun JsonObject.getJsonPrimitiveOrNull(key: String) =
    (get(key) as? JsonPrimitive)

public inline fun JsonObject.getStringOrNull(key: String) =
    (get(key) as? JsonPrimitive)?.contentOrNull

public inline fun JsonObject.getIntOrNull(key: String) =
    (get(key) as? JsonPrimitive)?.intOrNull

public inline fun JsonObject.getLongOrNull(key: String) =
    (get(key) as? JsonPrimitive)?.longOrNull

public inline fun JsonObject.getBooleanOrNull(key: String) =
    (get(key) as? JsonPrimitive)?.booleanOrNull

public inline fun <reified T> Row.get(name: String): T? =
    get(name, T::class.java)

public inline fun <reified T> Row.getValue(name: String): T =
    get(name, T::class.java)


public inline fun <reified T> Row.getValue(index: Int): T =
    get(index, T::class.java)

public inline fun GenericExecuteSpec.bindJson(name: String, value: String): GenericExecuteSpec =
    bind(name, io.r2dbc.postgresql.codec.Json.of(value))

public fun <T: JsonElement> sortJson(element: T): T =
    when (element) {
        is JsonObject -> JsonObject(element.mapValuesTo(TreeMap()) { (_, v) -> sortJson(v) }) as T
        is JsonArray -> JsonArray(element.map { sortJson(it) }) as T
        else -> element
    }

public inline fun Json.parseAndMutateJsonObject(str: String, block: MutableMap<String, JsonElement>.() -> Unit): JsonObject =
    sortJson(JsonObject(decodeFromString<MutableMap<String, JsonElement>>(str).apply(block)))

public suspend inline fun ApplicationCall.respondWithJson(status: HttpStatusCode, builder: JsonObjectBuilder.() -> Unit) {
    response.status(status)
    respond(buildJsonObject(builder))
}

@OptIn(ExperimentalTime::class)
public inline fun debugTime(prefix: String, block: () -> Unit) {
    println("$prefix: ${measureTime(block)}")
}

@OptIn(ExperimentalTime::class)
public inline fun debugTime(transform: (Duration) -> String, block: () -> Unit) {
    println(transform(measureTime(block)))
}

@OptIn(ExperimentalTime::class)
public inline fun debugTimeLive(vararg pairs: Pair<String, () -> Unit>) {
    pairs.forEach { (prefix, block) -> println("$prefix: ${measureTime(block)}") }
}

@OptIn(ExperimentalTime::class)
public inline fun debugTimeAtOnce(vararg pairs: Pair<String, () -> Unit>) {
    pairs.map { (prefix, block) -> prefix to measureTime(block) }
        .forEach { (prefix, time) -> println("$prefix: $time") }
}

public suspend fun <R> supervisorScopeWithContext(context: CoroutineContext, block: suspend CoroutineScope.() -> R): R =
    supervisorScope {
        withContext(context, block)
    }