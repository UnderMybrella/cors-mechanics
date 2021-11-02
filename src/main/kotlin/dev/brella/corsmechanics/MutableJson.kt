package dev.brella.corsmechanics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.longOrNull

sealed interface MutableJsonElement<T: JsonElement> {
    fun toJson(): T
}

class MutableJsonObject(val map: MutableMap<String, MutableJsonElement<*>>): MutableJsonElement<JsonObject>, MutableMap<String, MutableJsonElement<*>> by map {
    override fun toJson(): JsonObject = JsonObject(map.mapValues { (_, value) -> value.toJson() })
}

class MutableJsonArray(val list: MutableList<MutableJsonElement<*>>): MutableJsonElement<JsonArray>, MutableList<MutableJsonElement<*>> by list {
    override fun toJson(): JsonArray = JsonArray(list.map { it.toJson() })
}

class MutableJsonString(var string: String): MutableJsonElement<JsonPrimitive> {
    override fun toJson(): JsonPrimitive = JsonPrimitive(string)
}

class MutableJsonNumber(var number: Number): MutableJsonElement<JsonPrimitive> {
    override fun toJson(): JsonPrimitive = JsonPrimitive(number)
}

class MutableJsonBoolean(var boolean: Boolean): MutableJsonElement<JsonPrimitive> {
    override fun toJson(): JsonPrimitive = JsonPrimitive(boolean)
}

object MutableJsonNull: MutableJsonElement<JsonNull> {
    override fun toJson(): JsonNull = JsonNull
}

object MutableJsonEmpty: MutableJsonElement<JsonNull> {
    override fun toJson(): JsonNull = JsonNull
}

fun JsonElement.toMutable(): MutableJsonElement<out JsonElement> =
    when (this) {
        is JsonObject -> MutableJsonObject(HashMap<String, MutableJsonElement<*>>().apply { this@toMutable.forEach { (k, v) -> put(k, v.toMutable()) } })
        is JsonArray -> MutableJsonArray(ArrayList<MutableJsonElement<*>>().apply { this@toMutable.forEach { add(it.toMutable()) } })
        is JsonPrimitive -> longOrNull?.let(::MutableJsonNumber)
                            ?: floatOrNull?.let(::MutableJsonNumber)
                            ?: booleanOrNull?.let(::MutableJsonBoolean)
                            ?: contentOrNull?.let(::MutableJsonString)
                            ?: MutableJsonNull

        else -> error("Unknown json element $this")
    }
