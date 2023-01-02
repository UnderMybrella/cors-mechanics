package dev.brella.corsmechanics

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.compression.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlin.time.ExperimentalTime
import kotlin.time.TimeSource

object JsonPatch {
    @Serializable
    @JsonClassDiscriminator("op")
    sealed class Operation {
        abstract val path: String

        @SerialName("add")
        @Serializable
        data class Add(override val path: String, val value: JsonElement) : Operation()

        @SerialName("remove")
        @Serializable
        data class Remove(override val path: String) : Operation()

        @SerialName("replace")
        @Serializable
        data class Replace(override val path: String, val value: JsonElement) : Operation()

        @SerialName("move")
        @Serializable
        data class Move(val from: String, override val path: String) : Operation()

        @SerialName("copy")
        @Serializable
        data class Copy(val from: String, override val path: String) : Operation()

        @SerialName("test")
        @Serializable
        data class Test(override val path: String, val value: JsonElement) : Operation()
    }

    sealed class MissedPacketException(message: String?) : IllegalStateException(message) {
        data class CantNavigateToKey(
            val key: String,
            val delta: Operation
            // of ${deltaPath.joinToString("#>") { it::class.simpleName ?: "null" }}
        ) : MissedPacketException("Can't navigate to key $key of ${delta.path}") {
            override fun toString(): String = super.toString()
        }

        data class CantNavigateToIndex(
            val index: Int,
            val delta: Operation
            // ${deltaPath.joinToString("#>") { it::class.simpleName ?: "null" }}
        ) : MissedPacketException("Can't navigate to index $index of ${delta.path}") {
            override fun toString(): String = super.toString()
        }

//        data class CantNavigateToPath(
//            val path: String,
//            val deltaPath: List<MutableJsonElement<*>>,
//            val delta: Operation
//        ) : MissedPacketException("Can't navigate to ${path.joinToString("/") { it.keyPath ?: it.indexPath.toString() }} of ${deltaPath.joinToString("#>") { it::class.simpleName ?: "null" }}") {
//            override fun toString(): String = super.toString()
//        }
    }

    fun mutateFromPatch(origin: MutableJsonElement<*>, operations: List<Operation>): MutableJsonElement<*> {
        operations.forEach { delta ->
            val path = delta.path
                .trimStart('/')
                .split('/')
                .dropLast(1)

            val targetPath = delta.path.substringAfterLast('/')
            var targetElement: MutableJsonElement<*> = origin

            for (component in path) {
                val i = component.toIntOrNull()

                targetElement = if (i != null) {
                    if (targetElement is MutableJsonArray) {
                        targetElement[i]
                    } else {
                        throw MissedPacketException.CantNavigateToIndex(i, delta)
                    }
                } else {
                    if (targetElement is MutableJsonObject) {
                        targetElement[component] ?: throw MissedPacketException.CantNavigateToKey(component, delta)
                    } else {
                        throw MissedPacketException.CantNavigateToKey(component, delta)
                    }
                }
            }

            when (delta) {
                is Operation.Add -> {
                    val targetIndex = targetPath.toIntOrNull()

                    if (targetIndex != null) {
                        if (targetElement is MutableJsonArray) {
                            targetElement.add(targetIndex, delta.value.toMutable())
                        } else {
                            throw MissedPacketException.CantNavigateToIndex(targetIndex, delta)
                        }
                    } else if (targetPath == "-") {
                        if (targetElement is MutableJsonArray) {
                            targetElement.add(delta.value.toMutable())
                        } else {
                            throw MissedPacketException.CantNavigateToIndex(-1, delta)
                        }
                    } else {
                        if (targetElement is MutableJsonObject) {
                            targetElement.put(targetPath, delta.value.toMutable())
                        } else {
                            throw MissedPacketException.CantNavigateToKey(targetPath, delta)
                        }
                    }
                }
                is Operation.Remove -> {
                    val targetIndex = targetPath.toIntOrNull()

                    if (targetIndex != null) {
                        if (targetElement is MutableJsonArray) {
                            targetElement.removeAt(targetIndex)
                        } else {
                            throw MissedPacketException.CantNavigateToIndex(targetIndex, delta)
                        }
                    } else {
                        if (targetElement is MutableJsonObject) {
                            targetElement.remove(targetPath)
                        } else {
                            throw MissedPacketException.CantNavigateToKey(targetPath, delta)
                        }
                    }
                }
                is Operation.Replace -> {
                    val targetIndex = targetPath.toIntOrNull()

                    if (targetIndex != null) {
                        if (targetElement is MutableJsonArray) {
                            targetElement[targetIndex] = delta.value.toMutable()
                        } else {
                            throw MissedPacketException.CantNavigateToIndex(targetIndex, delta)
                        }
                    } else {
                        if (targetElement is MutableJsonObject) {
                            targetElement[targetPath] = delta.value.toMutable()
                        } else {
                            throw MissedPacketException.CantNavigateToKey(targetPath, delta)
                        }
                    }
                }
                is Operation.Move -> {
                    val from = delta.from
                        .trimStart('/')
                        .split('/')
                        .dropLast(1)

                    val targetPathFrom = delta.from.substringAfterLast('/')
                    var targetElementFrom: MutableJsonElement<*> = origin

                    for (component in from) {
                        val i = component.toIntOrNull()

                        targetElementFrom = if (i != null) {
                            if (targetElementFrom is MutableJsonArray) {
                                targetElementFrom[i]
                            } else {
                                throw MissedPacketException.CantNavigateToIndex(i, delta)
                            }
                        } else {
                            if (targetElementFrom is MutableJsonObject) {
                                targetElementFrom[component] ?: throw MissedPacketException.CantNavigateToKey(component, delta)
                            } else {
                                throw MissedPacketException.CantNavigateToKey(component, delta)
                            }
                        }
                    }

                    val targetIndexFrom = targetPathFrom.toIntOrNull()

                    val value = if (targetIndexFrom != null) {
                        if (targetElementFrom is MutableJsonArray) {
                            targetElementFrom.removeAt(targetIndexFrom)
                        } else {
                            throw MissedPacketException.CantNavigateToIndex(targetIndexFrom, delta)
                        }
                    } else {
                        if (targetElementFrom is MutableJsonObject) {
                            targetElementFrom.remove(targetPathFrom) ?: throw MissedPacketException.CantNavigateToKey(targetPathFrom, delta)
                        } else {
                            throw MissedPacketException.CantNavigateToKey(targetPathFrom, delta)
                        }
                    }

                    val targetIndex = targetPath.toIntOrNull()

                    if (targetIndex != null) {
                        if (targetElement is MutableJsonArray) {
                            targetElement.add(targetIndex, value)
                        } else {
                            throw MissedPacketException.CantNavigateToIndex(targetIndex, delta)
                        }
                    } else {
                        if (targetElement is MutableJsonObject) {
                            targetElement[targetPath] = value
                        } else {
                            throw MissedPacketException.CantNavigateToKey(targetPath, delta)
                        }
                    }
                }
                is Operation.Copy -> {
                    val from = delta.from
                        .trimStart('/')
                        .split('/')
                        .dropLast(1)

                    val targetPathFrom = delta.from.substringAfterLast('/')
                    var targetElementFrom: MutableJsonElement<*> = origin

                    for (component in from) {
                        val i = component.toIntOrNull()

                        targetElementFrom = if (i != null) {
                            if (targetElementFrom is MutableJsonArray) {
                                targetElementFrom[i]
                            } else {
                                throw MissedPacketException.CantNavigateToIndex(i, delta)
                            }
                        } else {
                            if (targetElementFrom is MutableJsonObject) {
                                targetElementFrom[component] ?: throw MissedPacketException.CantNavigateToKey(component, delta)
                            } else {
                                throw MissedPacketException.CantNavigateToKey(component, delta)
                            }
                        }
                    }

                    val targetIndexFrom = targetPathFrom.toIntOrNull()

                    val value = if (targetIndexFrom != null) {
                        if (targetElementFrom is MutableJsonArray) {
                            targetElementFrom[targetIndexFrom]
                        } else {
                            throw MissedPacketException.CantNavigateToIndex(targetIndexFrom, delta)
                        }
                    } else {
                        if (targetElementFrom is MutableJsonObject) {
                            targetElementFrom[targetPathFrom] ?: throw MissedPacketException.CantNavigateToKey(targetPathFrom, delta)
                        } else {
                            throw MissedPacketException.CantNavigateToKey(targetPathFrom, delta)
                        }
                    }

                    val targetIndex = targetPath.toIntOrNull()

                    if (targetIndex != null) {
                        if (targetElement is MutableJsonArray) {
                            targetElement.add(targetIndex, value)
                        } else {
                            throw MissedPacketException.CantNavigateToIndex(targetIndex, delta)
                        }
                    } else {
                        if (targetElement is MutableJsonObject) {
                            targetElement[targetPath] = value
                        } else {
                            throw MissedPacketException.CantNavigateToKey(targetPath, delta)
                        }
                    }
                }
                is Operation.Test -> {
                    val targetIndex = targetPath.toIntOrNull()

                    if (targetIndex != null) {
                        if (targetElement is MutableJsonArray) {
                            require(targetElement[targetIndex].toJson() == delta.value)
                        } else {
                            throw MissedPacketException.CantNavigateToIndex(targetIndex, delta)
                        }
                    } else {
                        if (targetElement is MutableJsonObject) {
                            require((targetElement[targetPath]?.toJson() ?: JsonNull) == delta.value)
                        } else {
                            throw MissedPacketException.CantNavigateToKey(targetPath, delta)
                        }
                    }
                }
            }
        }

        return origin
    }

    @OptIn(ExperimentalTime::class)
    @JvmStatic
    fun main(args: Array<String>): Unit = runBlocking {
        println("Testing JsonPath")
        val start = Instant.parse("2021-11-12T00:00:00Z")
        val now = TimeSource.Monotonic.markNow()

        val eventStream = EventStream.FromChronicler(
            "CHRONICLER",
            Json,
            HttpClient(CIO) {
                install(ContentEncoding) {
                    gzip()
                    deflate()
                    identity()
                }

                install(ContentNegotiation) {
                    json(Serialisation.json)
                }

                install(HttpTimeout) {
                    connectTimeoutMillis = 20_000L
                }

                expectSuccess = false

                defaultRequest {
                    userAgent("Mozilla/5.0 (X11; Linux x86_64; rv:85.0) Gecko/20100101 Firefox/85.0")
                }
            },
            this,
            time = { (start + now.elapsedNow()).toString() }
        )

        eventStream.liveData.collect { println(it) }
    }
}