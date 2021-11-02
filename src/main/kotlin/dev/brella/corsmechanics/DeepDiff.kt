package dev.brella.corsmechanics

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

    fun mutateFromDiff(parent: MutableJsonElement<*>? = null, origin: MutableJsonElement<*>, records: List<DeltaRecord>, index: PathIndex? = null) {
        records.forEach { delta ->
            var deltaParent: MutableJsonElement<*>? = parent
            var deltaOrigin: MutableJsonElement<*>? = origin
            var deltaOriginIndex: PathIndex? = null

            if (index != null) {
                if (index.keyPath != null) {
                    if (deltaOrigin is MutableJsonObject) {
                        deltaParent = deltaOrigin
                        deltaOrigin = deltaOrigin[index.keyPath]
                        deltaOriginIndex = index
                    } else {
                        println("Can't navigate to keypath of $deltaOrigin")
                    }
                } else if (index.indexPath != null) {
                    if (deltaOrigin is MutableJsonArray) {
                        deltaParent = deltaOrigin
                        deltaOrigin = deltaOrigin.getOrNull(index.indexPath)
                        deltaOriginIndex = index
                    }
                }
            }

            for (index in delta.path) {
                val (k, i) = index
                if (k != null) {
                    if (deltaOrigin is MutableJsonObject) {
                        deltaParent = deltaOrigin
                        deltaOrigin = deltaOrigin[k]
                        deltaOriginIndex = index
                    } else {
                        println("Can't navigate to keypath of $deltaOrigin")
                    }
                } else if (i != null) {
                    if (deltaOrigin is MutableJsonArray) {
                        deltaParent = deltaOrigin
                        while (i !in deltaOrigin.indices) deltaOrigin.add(MutableJsonEmpty)
                        deltaOrigin = deltaOrigin[i]
                        deltaOriginIndex = index
                    } else {
                        println("Can't navigate to keypath of $deltaOrigin")
                    }
                }
            }

            when (delta.kind) {
                Kind.NEWLY_ADDED -> {
//                    println("NEW $delta")

                    if (deltaParent == null) {
                        println("Can't add rhs to null parent for $deltaOrigin")
                    } else if (deltaOriginIndex == null) {
                        println("Can't add rhs to parent $deltaParent with null index")
                    } else {
                        val (keyPath, indexPath) = deltaOriginIndex
                        if (keyPath != null) {
                            if (deltaParent is MutableJsonObject) {
                                deltaParent[keyPath] = delta.rhs!!.toMutable()
                            } else {
                                println("Can't navigate to key path of $deltaParent")
                            }
                        } else if (indexPath != null) {
                            if (deltaParent is MutableJsonArray) {
                                while (indexPath !in deltaParent.indices) deltaParent.add(MutableJsonEmpty)

                                if (deltaParent[indexPath] == MutableJsonEmpty)
                                    deltaParent[indexPath] = delta.rhs!!.toMutable()
                                else
                                    deltaParent.add(indexPath, delta.rhs!!.toMutable())
                            } else {
                                println("Can't navigate to index path of $deltaOrigin")
                            }
                        }
                    }
                }
                Kind.DELETED -> {
//                    println("DELETED $delta")

                    if (deltaParent == null) {
                        println("Can't delete from null parent for $deltaOrigin")
                    } else if (deltaOriginIndex == null) {
                        println("Can't delete from parent $deltaParent with null index")
                    } else {
                        val (keyPath, indexPath) = deltaOriginIndex
                        if (keyPath != null) {
                            if (deltaParent is MutableJsonObject) {
                                deltaParent.remove(keyPath)
                            } else {
                                println("Can't navigate to key path of $deltaParent")
                            }
                        } else if (indexPath != null) {
                            if (deltaParent is MutableJsonArray) {
                                if (indexPath in deltaParent.indices) deltaParent.removeAt(indexPath)
                            } else {
                                println("Can't navigate to index path of $deltaOrigin")
                            }
                        }
                    }
                }
                Kind.EDITED -> {
//                    println("EDITED $delta")

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
                            println("Can't edit from null parent for $deltaOrigin")
                        } else if (deltaOriginIndex == null) {
                            println("Can't edit from parent $deltaParent with null index")
                        } else {
                            val (keyPath, indexPath) = deltaOriginIndex
                            if (keyPath != null) {
                                if (deltaParent is MutableJsonObject) {
                                    deltaParent[keyPath] = rhs
                                } else {
                                    println("Can't navigate to key path of $deltaParent")
                                }
                            } else if (indexPath != null) {
                                if (deltaParent is MutableJsonArray) {
                                    deltaParent[indexPath] = rhs
                                } else {
                                    println("Can't navigate to index path of $deltaOrigin")
                                }
                            }
                        }
                    }
                }
                Kind.ARRAY -> {
//                    println("ARRAY $delta")

                    if (deltaOrigin !is MutableJsonArray) println("Can't do an array operation ($delta) on $deltaOrigin")
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