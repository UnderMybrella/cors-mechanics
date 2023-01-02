package dev.brella.corsmechanics

import dev.brella.corsmechanics.Serialisation.json
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*
import java.io.File

object DeepDiff {
    @Serializable(Kind.Serialiser::class)
    enum class Kind(val keycode: Char) {
        NEWLY_ADDED('N'),
        DELETED('D'),
        EDITED('E'),
        ARRAY('A');

        companion object Serialiser : KSerializer<Kind> {
            override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("DeepDiff.Kind", PrimitiveKind.CHAR)

            override fun serialize(encoder: Encoder, value: Kind) =
                encoder.encodeChar(value.keycode)

            override fun deserialize(decoder: Decoder): Kind =
                decoder.decodeChar().let { char -> values().first { it.keycode == char } }
        }
    }

    @Serializable(PathIndex.Serialiser::class)
    data class PathIndex(val keyPath: String?, val indexPath: Int?) {
        companion object Serialiser : KSerializer<PathIndex> {
            override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("DeepDiff.PathIndex", PrimitiveKind.STRING)

            override fun serialize(encoder: Encoder, value: PathIndex) =
                if (value.keyPath != null) encoder.encodeString(value.keyPath)
                else if (value.indexPath != null) encoder.encodeInt(value.indexPath)
                else encoder.encodeNull()

            override fun deserialize(decoder: Decoder): PathIndex {
                if (decoder is JsonDecoder) {
                    val element = decoder.decodeJsonElement()

                    if (element !is JsonPrimitive) error("Unknown path index $element")
                    return PathIndex(null, element.intOrNull ?: return PathIndex(element.content, null))
                } else {
                    try {
                        val str = decoder.decodeString()
                        return PathIndex(null, str.toIntOrNull() ?: return PathIndex(str, null))
                    } catch (de: SerializationException) {
                        return PathIndex(null, decoder.decodeInt())
                    }
                }
            }
        }
    }

    @Serializable
    data class DeltaRecord(
        val kind: Kind,
        val path: List<PathIndex> = emptyList(),
        val lhs: JsonElement? = null,
        val rhs: JsonElement? = null,
        val index: Int? = null,
        val item: DeltaRecord? = null
    )

    sealed class MissedPacketException(message: String?): IllegalStateException(message) {
        data class CantNavigateToKey(val key: String, val deltaPath: List<MutableJsonElement<*>>, val delta: DeltaRecord): MissedPacketException("Can't navigate to key $key of ${deltaPath.joinToString("#>") { it::class.simpleName ?: "null" }}") {
            override fun toString(): String = super.toString()
        }
        data class CantNavigateToIndex(val index: Int, val deltaPath: List<MutableJsonElement<*>>, val delta: DeltaRecord): MissedPacketException("Can't navigate to index $index of ${deltaPath.joinToString("#>") { it::class.simpleName ?: "null" }}") {
            override fun toString(): String = super.toString()
        }
        data class CantNavigateToPath(val path: List<PathIndex>, val deltaPath: List<MutableJsonElement<*>>, val delta: DeltaRecord): MissedPacketException("Can't navigate to ${path.joinToString("/") { it.keyPath ?: it.indexPath.toString() }} of ${deltaPath.joinToString("#>") { it::class.simpleName ?: "null" }}") {
            override fun toString(): String = super.toString()
        }
    }

    fun mutateFromDiff(parent: MutableJsonElement<*>? = null, origin: MutableJsonElement<*>, records: List<DeltaRecord>, index: PathIndex? = null) {
        records.forEach { delta ->
            val deltaPath: MutableList<MutableJsonElement<*>> = ArrayList<MutableJsonElement<*>>().apply {
                parent?.let { add(it) }
                add(origin)
            }
            var deltaOriginIndex: PathIndex? = null

            if (index != null) {
                val deltaOrigin = deltaPath.last()
                if (index.keyPath != null) {
                    if (deltaOrigin is MutableJsonObject) {
                        deltaPath.add(deltaOrigin[index.keyPath]!!)
                        deltaOriginIndex = index
                    } else {
                        throw MissedPacketException.CantNavigateToKey(index.keyPath, deltaPath, delta)
                    }
                } else if (index.indexPath != null) {
                    if (deltaOrigin is MutableJsonArray) {
                        while (index.indexPath !in deltaOrigin.indices) deltaOrigin.add(MutableJsonEmpty)
                        deltaPath.add(deltaOrigin[index.indexPath])
                        deltaOriginIndex = index
                    } else {
                        throw MissedPacketException.CantNavigateToIndex(index.indexPath, deltaPath, delta)
                    }
                }
            }

            for (index in delta.path) {
                val (k, i) = index
                val deltaOrigin = deltaPath.last()

                if (k != null) {
                    if (deltaOrigin is MutableJsonObject) {
                        deltaPath.add(deltaOrigin.computeIfAbsent(k) { MutableJsonEmpty })
                        deltaOriginIndex = index
                    } else {
                        throw MissedPacketException.CantNavigateToPath(delta.path, deltaPath, delta)
                    }
                } else if (i != null) {
                    if (deltaOrigin is MutableJsonArray) {
                        while (i !in deltaOrigin.indices) deltaOrigin.add(MutableJsonEmpty)
                        deltaPath.add(deltaOrigin[i])
                        deltaOriginIndex = index
                    } else {
                        throw MissedPacketException.CantNavigateToPath(delta.path, deltaPath, delta)
                    }
                }
            }

            when (delta.kind) {
                Kind.NEWLY_ADDED -> {
//                    println("NEW $delta")

                    val deltaParent = deltaPath.getOrNull(deltaPath.size - 2)
                    if (deltaParent == null) {
                        println("Can't add rhs to ${deltaPath.joinToString("#>") { it::class.simpleName ?: "null" }}")
                    } else if (deltaOriginIndex == null) {
                        println("Can't add rhs to ${deltaPath.joinToString("#>") { it::class.simpleName ?: "null" }} with null index")
                    } else {
                        val (keyPath, indexPath) = deltaOriginIndex
                        if (keyPath != null) {
                            if (deltaParent is MutableJsonObject) {
                                deltaParent[keyPath] = delta.rhs!!.toMutable()
                            } else {
                                throw MissedPacketException.CantNavigateToPath(delta.path, deltaPath, delta)
                            }
                        } else if (indexPath != null) {
                            if (deltaParent is MutableJsonArray) {
                                while (indexPath !in deltaParent.indices) deltaParent.add(MutableJsonEmpty)

                                if (deltaParent[indexPath] == MutableJsonEmpty)
                                    deltaParent[indexPath] = delta.rhs!!.toMutable()
                                else
                                    deltaParent.add(indexPath, delta.rhs!!.toMutable())
                            } else {
                                throw MissedPacketException.CantNavigateToPath(delta.path, deltaPath, delta)
                            }
                        }
                    }
                }
                Kind.DELETED -> {
//                    println("DELETED $delta")

                    val deltaParent = deltaPath.getOrNull(deltaPath.size - 2)
                    if (deltaParent == null) {
                        println("Can't delete from null parent for ${deltaPath.joinToString("#>") { it::class.simpleName ?: "null" }}")
                    } else if (deltaOriginIndex == null) {
                        println("Can't delete from parent ${deltaPath.joinToString("#>") { it::class.simpleName ?: "null" }} with null index")
                    } else {
                        val (keyPath, indexPath) = deltaOriginIndex
                        if (keyPath != null) {
                            if (deltaParent is MutableJsonObject) {
                                deltaParent.remove(keyPath)
                            } else {
                                throw MissedPacketException.CantNavigateToPath(delta.path, deltaPath, delta)
                            }
                        } else if (indexPath != null) {
                            if (deltaParent is MutableJsonArray) {
                                if (indexPath in deltaParent.indices) deltaParent.removeAt(indexPath)
                            } else {
                                throw MissedPacketException.CantNavigateToPath(delta.path, deltaPath, delta)
                            }
                        }
                    }
                }
                Kind.EDITED -> {
//                    println("EDITED $delta")

                    val deltaParent = deltaPath.getOrNull(deltaPath.size - 2)
                    val deltaOrigin = deltaPath.lastOrNull()
                    val rhs = delta.rhs?.toMutable() ?: MutableJsonNull
                    if (deltaOrigin is MutableJsonObject && rhs is MutableJsonObject) {
                        deltaOrigin.clear()
                        deltaOrigin.putAll(rhs)
                    } else if (deltaOrigin is MutableJsonArray && rhs is MutableJsonArray) {
                        deltaOrigin.clear()
                        deltaOrigin.addAll(rhs)
                    } else if (deltaOrigin is MutableJsonString && rhs is MutableJsonString) {
                        deltaOrigin.string = rhs.string
                    } else if (deltaOrigin is MutableJsonNumber && rhs is MutableJsonNumber) {
                        deltaOrigin.number = rhs.number
                    } else if (deltaOrigin is MutableJsonBoolean && rhs is MutableJsonBoolean) {
                        deltaOrigin.boolean = rhs.boolean
                    } else {
                        if (deltaParent == null) {
                            println("Can't edit from null parent for ${deltaPath.joinToString("#>") { it::class.simpleName ?: "null" }}")
                        } else if (deltaOriginIndex == null) {
                            println("Can't edit from parent ${deltaPath.joinToString("#>") { it::class.simpleName ?: "null" }} with null index")
                        } else {
                            val (keyPath, indexPath) = deltaOriginIndex
                            if (keyPath != null) {
                                if (deltaParent is MutableJsonObject) {
                                    deltaParent[keyPath] = rhs
                                } else {
                                    throw MissedPacketException.CantNavigateToPath(delta.path, deltaPath, delta)
                                }
                            } else if (indexPath != null) {
                                if (deltaParent is MutableJsonArray) {
                                    deltaParent[indexPath] = rhs
                                } else {
                                    throw MissedPacketException.CantNavigateToPath(delta.path, deltaPath, delta)
                                }
                            }
                        }
                    }
                }
                Kind.ARRAY -> {
//                    println("ARRAY $delta")

                    val deltaParent = deltaPath.getOrNull(deltaPath.size - 2)
                    val deltaOrigin = deltaPath.lastOrNull()
                    if (deltaOrigin !is MutableJsonArray) {
                        println("Can't do an array operation ${delta.path.joinToString("/") { it.keyPath ?: it.indexPath.toString() }} on ${deltaPath.joinToString("#>") { it::class.simpleName ?: "null" }}")
                    }
                    else {
                        val item = delta.item
                        val index = delta.index

                        if (item != null && index != null) mutateFromDiff(deltaParent, deltaOrigin, listOf(item), PathIndex(null, index))
                        else println("$item or $index is null!")
                    }
                }
            }
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("Testing DeepDiff")

        var origin: MutableJsonObject? = null
        val diffedMap: MutableMap<String, JsonElement> = HashMap()
        val diffed = JsonObject(diffedMap)

        for (i in 0..26) {
            println("Running $i")
            val element = json.decodeFromString<JsonObject>(File("eventStream/$i.json").readText())

            val value = element.getJsonObjectOrNull("value")
            val delta = element.getJsonArrayOrNull("delta")

            if (value != null) origin = value.toMutable() as? MutableJsonObject
            if (delta != null && origin != null) mutateFromDiff(null, origin, json.decodeFromJsonElement(delta), null)

            origin?.let { diffedMap["value"] = it.toJson() }

            File("eventStream/${i}_diffed.json").writeText(diffed.toString())
        }


    }
}