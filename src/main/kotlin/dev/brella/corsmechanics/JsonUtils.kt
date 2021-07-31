package dev.brella.corsmechanics

import io.ktor.client.*
import io.ktor.client.request.*
import kotlinx.coroutines.delay
import kotlinx.datetime.Instant
import kotlinx.serialization.json.*
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.measureTime

public inline val JsonElement.jsonObjectOrNull get() = this as? JsonObject
public inline val JsonElement.jsonArrayOrNull get() = this as? JsonArray
public inline val JsonElement.jsonPrimitiveOrNull get() = this as? JsonPrimitive


public inline operator fun JsonObjectBuilder.set(key: String, value: String?) =
    put(key, value)

public inline operator fun JsonObjectBuilder.set(key: String, value: Number?) =
    put(key, value)

public inline operator fun JsonObjectBuilder.set(key: String, value: Boolean?) =
    put(key, value)

public inline operator fun JsonObjectBuilder.set(key: String, value: JsonElement) =
    put(key, value)


public inline fun JsonObject.getJsonObject(key: String) =
    getValue(key).jsonObject

public inline fun JsonObject.getJsonArray(key: String) =
    getValue(key).jsonArray

public inline fun JsonObject.getJsonPrimitive(key: String) =
    getValue(key).jsonPrimitive

public inline fun JsonObject.getString(key: String) =
    getValue(key).jsonPrimitive.content

public inline fun JsonObject.getLong(key: String) =
    getValue(key).jsonPrimitive.long


public inline fun JsonObject.getJsonObjectOrNull(key: String) =
    get(key)?.jsonObjectOrNull

public inline fun JsonObject.getJsonArrayOrNull(key: String) =
    get(key)?.jsonArrayOrNull

public inline fun JsonObject.getJsonPrimitiveOrNull(key: String) =
    get(key)?.jsonPrimitiveOrNull

public inline fun JsonObject.getStringOrNull(key: String) =
    get(key)?.jsonPrimitiveOrNull?.contentOrNull

public inline fun JsonObject.getLongOrNull(key: String) =
    get(key)?.jsonPrimitive?.longOrNull

public inline fun JsonObject.mapKeys(block: (key: String, value: JsonElement) -> String) =
    JsonObject((this as Map<String, JsonElement>).mapKeys { block(it.key, it.value) })

public inline fun JsonObject.mapValues(block: (key: String, value: JsonElement) -> JsonElement) =
    JsonObject((this as Map<String, JsonElement>).mapValues { block(it.key, it.value) })

public inline fun buildJsonObjectFrom(base: JsonObject, block: JsonObjectBuilder.(key: String, value: JsonElement) -> Boolean) =
    buildJsonObject {
        base.forEach { (k, v) ->
            if (block(k, v)) put(k, v)
        }
    }

public inline fun JsonArray.forEachString(block: (String) -> Unit) =
    forEach { it.jsonPrimitiveOrNull?.contentOrNull?.let(block) }


public suspend inline fun HttpClient.getChroniclerVersionsBefore(type: String, at: Instant, builder: HttpRequestBuilder.() -> Unit = {}) =
    getChroniclerVersionsBefore(type, at.toString(), builder)

public suspend inline fun HttpClient.getChroniclerVersionsBefore(type: String, at: String, builder: HttpRequestBuilder.() -> Unit = {}) =
    (get<JsonObject>("https://api.sibr.dev/chronicler/v2/versions") {
        parameter("type", type)
        parameter("before", at)
        parameter("order", "desc")
        parameter("count", 50)

        builder()
    }["items"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.getJsonObjectOrNull("data") }

public suspend inline fun HttpClient.getChroniclerEntity(type: String, at: Instant, builder: HttpRequestBuilder.() -> Unit = {}) =
    getChroniclerEntity(type, at.toString(), builder)

public suspend inline fun HttpClient.getChroniclerEntity(type: String, at: String, builder: HttpRequestBuilder.() -> Unit = {}) =
    ((get<JsonObject>("https://api.sibr.dev/chronicler/v2/entities") {
        parameter("type", type)
        parameter("at", at)

        builder()
    }["items"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("data")

public suspend inline fun HttpClient.getChroniclerEntityList(type: String, at: Instant, builder: HttpRequestBuilder.() -> Unit = {}) =
    getChroniclerEntityList(type, at.toString(), builder)

public suspend inline fun HttpClient.getChroniclerEntityList(type: String, at: String, builder: HttpRequestBuilder.() -> Unit = {}) =
    (get<JsonObject>("https://api.sibr.dev/chronicler/v2/entities") {
        parameter("type", type)
        parameter("at", at)

        builder()
    }["items"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.get("data") }

@OptIn(ExperimentalTime::class)
suspend fun <T> T.loopEvery(time: Duration, `while`: suspend T.() -> Boolean, block: suspend () -> Unit) {
    var count = 0
    while (`while`()) {
        val timeTaken = measureTime {
            try {
                block()
                count = (count - 1).coerceAtLeast(0)
            } catch (th: Throwable) {
                th.printStackTrace()

                delay(
                    2.0.pow(count++)
                        .coerceAtMost(64.0)
                        .times(1000)
                        .toLong() + Random.nextLong(1000)
                )
            }
        }
//        println("Took ${timeTaken.inSeconds}s, waiting ${(time - timeTaken).inSeconds}s")
        delay((time - timeTaken).inWholeMilliseconds.coerceAtLeast(0L))
    }
}