package dev.brella.corsmechanics

import kotlinx.serialization.json.*

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