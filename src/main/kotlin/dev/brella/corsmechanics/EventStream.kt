package dev.brella.corsmechanics

import dev.brella.kornea.toolkit.coroutines.ReadWriteSemaphore
import dev.brella.kornea.toolkit.coroutines.withReadPermit
import dev.brella.kornea.toolkit.coroutines.withWritePermit
import dev.brella.ktornea.common.cleanup
import dev.brella.ktornea.common.executeStatement
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.features.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.*
import kotlin.collections.HashMap
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

sealed class EventStream(val id: String, val json: Json, val http: HttpClient, val scope: CoroutineScope, val context: CoroutineContext = scope.coroutineContext, val endpoint: HttpRequestBuilder.() -> Unit = { url("https://api.blaseball.com/events/streamData") }) {
    companion object {
        val HASH_REGEX = "@[0-9a-f]{1,8}".toRegex()

        private fun JsonElement.deepSort(): JsonElement =
            when (this) {
                is JsonObject -> JsonObject(mapValuesTo(TreeMap()) { (_, v) -> v.deepSort() })
                is JsonArray -> JsonArray(map { it.deepSort() }.sortedWith { a, b -> compare(a, b) })
                is JsonPrimitive -> this
            }

        private fun compare(a: JsonElement, b: JsonElement): Int =
            when (a) {
                is JsonObject -> {
                    val comparingA = a["name"] ?: a["fullName"] ?: a["homeTeam"] ?: a["id"] ?: JsonNull

                    when (b) {
                        is JsonObject -> compare(comparingA, b["name"] ?: b["fullName"] ?: b["homeTeam"] ?: b["id"] ?: JsonNull)
                        is JsonArray -> compare(comparingA, b.firstOrNull() ?: JsonNull)
                        is JsonPrimitive -> compare(comparingA, b)
                    }
                }
                is JsonArray -> when (b) {
                    is JsonObject -> compare(a.firstOrNull() ?: JsonNull, b["name"] ?: b["fullName"] ?: b["homeTeam"] ?: b["id"] ?: JsonNull)
                    is JsonArray -> compare(a.firstOrNull() ?: JsonNull, b.firstOrNull() ?: JsonNull)
                    is JsonPrimitive -> compare(a.firstOrNull() ?: JsonNull, b)
                }
                is JsonPrimitive -> when (b) {
                    is JsonObject -> compare(a, b["name"] ?: b["fullName"] ?: b["homeTeam"] ?: b["id"] ?: JsonNull)
                    is JsonArray -> compare(a, b.firstOrNull() ?: JsonNull)
                    is JsonPrimitive -> a.content.compareTo(b.content)
                }
            }
    }
    //    val simulationData: MutableList<BlaseballStreamData> = ArrayList()
//    val games: MutableMap<GameID, BlaseballUpdatingGame> = HashMap()
//    val chroniclerGames: MutableMap<GameID, Map<Int, BlaseballDatabaseGame>> = HashMap()

    class FromEventSource(id: String, json: Json, http: HttpClient, scope: CoroutineScope, context: CoroutineContext = scope.coroutineContext, endpoint: HttpRequestBuilder.() -> Unit = { url("https://api.blaseball.com/events/streamData") }): EventStream(id, json, http, scope, context, endpoint)
    class FromChronicler(id: String, json: Json, http: HttpClient, scope: CoroutineScope, context: CoroutineContext = scope.coroutineContext, val time: () -> String, endpoint: HttpRequestBuilder.() -> Unit = {}): EventStream(id, json, http, scope, context, endpoint) {
        @OptIn(ExperimentalTime::class)
        override suspend fun getLiveDataStream(): Flow<JsonObject> =
            flow {
                //Chronicler is a bit funky with streamData sometimes, so we need to set up a base element, and then populate that
                val core: MutableMap<String, JsonElement> = HashMap()
                val coreJson = JsonObject(core)

                for (i in 0 until 8) {
                    try {
                        yield()

                        http.getChroniclerVersionsBefore("stream", time(), endpoint)
                            ?.forEach { streamData ->
                                streamData
                                    .getJsonObjectOrNull("value")
                                    ?.forEach { (k, v) -> core.putIfAbsent(k, v) }
                            } ?: continue

                        break
                    } catch (th: Throwable) {
                        th.printStackTrace()
                        delay((2.0.pow(i) * 1000).toLong() + Random.nextLong(1000))
                        continue
                    }
                }

                //loopEvery(league.updateInterval
                loopEvery(Duration.seconds(5), { coroutineContext.isActive }) {
                    (http.getChroniclerEntity("stream", time(), endpoint) as? JsonObject)
                        ?.getJsonObjectOrNull("value")
                        ?.forEach { (k, v) -> core[k] = v }

                    emit(coreJson.deepSort() as? JsonObject ?: coreJson)
                }
            }
    }

    val liveData = MutableSharedFlow<JsonObject>(1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val liveUpdates = MutableSharedFlow<JsonObject>(1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    var lineCount = 0
    val dir = File("eventStream").also(File::mkdirs)

    //            url("http://localhost:9897/blaseball/accelerated/live_bait/events/streamData")
    open suspend fun getLiveDataStream(): Flow<JsonObject> {
        val call = http.executeStatement {
            method = HttpMethod.Get
            timeout {
                socketTimeoutMillis = 180_000L
                connectTimeoutMillis = 20_000L
            }
            endpoint()
        }
        if (!call.response.status.isSuccess()) return emptyFlow()

        return channelFlow {
            val content = if (call.response.headers[HttpHeaders.ContentEncoding] == "gzip")
                call.response.receive()
            else
                call.response.content

            var origin: MutableJsonObject? = null

            while (isActive && !content.isClosedForRead) {
                val line = buildString {
                    while (isActive && !content.isClosedForRead) {
                        val line = content.readUTF8Line() ?: break
                        if (line.startsWith("data:"))
                            appendLine(line.substringAfter("data:"))
                        else if (line.isBlank())
                            break
                    }
                }.trim()

                if (line.isNotBlank()) {
                    val element = json.parseToJsonElement(line).jsonObject
                    val value = element.getJsonObjectOrNull("value")
                    val delta = element.getJsonArrayOrNull("delta")

                    if (value != null) origin = value.toMutable() as? MutableJsonObject
                    if (delta != null && origin != null) DeepDiff.mutateFromDiff(null, origin, json.decodeFromJsonElement<List<DeepDiff.DeltaRecord>>(delta).reversed(), null)

                    lineCount++

                    origin?.toJson()?.let { send(it) }
//                    send(json.parseToJsonElement(line).jsonObject.getValue("value").jsonObject)
                }
            }

            call.response.cleanup()
        }
    }

    private var updateJob: Job? = null
    private val semaphore: ReadWriteSemaphore = ReadWriteSemaphore(16)

    fun cancelUpdateJob() = scope.launch(context) { semaphore.withWritePermit { updateJob?.cancelAndJoin() } }
    fun relaunchJobIfNeeded() = scope.launch(context) {
        semaphore.withReadPermit { if (updateJob?.isActive == true) return@launch }
        semaphore.withWritePermit {
            println("RELAUNCHING $id")

            liveData.resetReplayCache()
            liveUpdates.resetReplayCache()

            updateJob?.cancelAndJoin()
            updateJob = scope.launch(context) {
                val updateData: MutableMap<String, JsonElement> = HashMap()
                val updateJson = JsonObject(updateData)
                val failures: MutableMap<String?, Int> = HashMap()
                var restartCount: Int = 0

                while (isActive) {
                    try {
                        withTimeoutOrNull(120_000) {
                            getLiveDataStream()
                                .onEach { event ->
                                    event.forEach { (k, v) -> updateData[k] = v }

                                    liveData.emit(buildJsonObject {
                                        put("value", updateJson)
                                    })

                                    liveUpdates.emit(buildJsonObject {
                                        put("value", event)
                                    })
                                }
                                .launchIn(this)
                                .join()
                        }

                        restartCount = (restartCount - 1).coerceAtLeast(0)
                    } catch (th: Throwable) {
                        val seen = failures.compute(th.message?.replace(HASH_REGEX, "@{hash}")) { _, i -> i?.plus(1) ?: 1 }
                        val delay = (2.0.pow(restartCount++).toLong().coerceAtMost(64) * 1000) + Random.nextLong(1000)

                        println("[${id}] Starting back up (Seen this error $seen times); waiting $delay ms...")
                        th.printStackTrace()

                        delay(delay)
                    }
                }
            }
        }
    }

    init {
        relaunchJobIfNeeded()
    }
}