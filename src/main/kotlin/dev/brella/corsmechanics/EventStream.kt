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
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.io.File
import java.util.*
import kotlin.collections.HashMap
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

sealed class EventStream(
    val id: String,
    val json: Json,
    val http: HttpClient,
    val scope: CoroutineScope,
    val context: CoroutineContext = scope.coroutineContext,
    val endpoint: HttpRequestBuilder.() -> Unit = { url("https://api.blaseball.com/events/streamData") }
) {
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

    class FromEventSource(
        id: String,
        json: Json,
        http: HttpClient,
        scope: CoroutineScope,
        context: CoroutineContext = scope.coroutineContext,
        endpoint: HttpRequestBuilder.() -> Unit = { url("https://api.blaseball.com/events/streamData") }
    ) : EventStream(id, json, http, scope, context, endpoint)

    class FromChronicler(id: String, json: Json, http: HttpClient, scope: CoroutineScope, context: CoroutineContext = scope.coroutineContext, val time: () -> String, endpoint: HttpRequestBuilder.() -> Unit = {}) : EventStream(
        id,
        json,
        http,
        scope,
        context,
        endpoint
    ) {
        @OptIn(ExperimentalTime::class)
        override suspend fun getLiveDataStream(): Flow<JsonObject> =
            flow {
                //Chronicler is a bit funky with streamData sometimes, so we need to set up a base element, and then populate that
                val core: MutableMap<String, JsonElement> = HashMap()
                val coreJson = buildJsonObject {
                    put("value", JsonObject(core))
                }

                var lastTime: String = time()

                for (i in 0 until 8) {
                    try {
                        yield()
                        lastTime = time()

                        val versions = http.getChroniclerVersionsBefore("stream", lastTime, endpoint)?.reversed() ?: continue

                        if (versions.any { it.contains("delta") }) {
                            val lastIndex = versions.indexOfLast { it.contains("value") }
                            for (index in lastIndex until versions.size) {
                                val streamData = versions[index]
                                val value = streamData.getJsonObjectOrNull("value")

                                if (value != null) {
                                    value.forEach { (k, v) -> core[k] = v }

                                    emit(coreJson.deepSort() as? JsonObject ?: coreJson)
                                } else {
                                    emit(streamData)
                                }
                            }
                        } else {
                            versions.forEach { streamData ->
                                streamData
                                    .getJsonObjectOrNull("value")
                                    ?.forEach { (k, v) -> core.putIfAbsent(k, v) }
                            }

                            emit(coreJson.deepSort() as? JsonObject ?: coreJson)
                        }

                        break
                    } catch (th: Throwable) {
                        th.printStackTrace()
                        delay((2.0.pow(i) * 1000).toLong() + Random.nextLong(1000))
                        continue
                    }
                }

                //loopEvery(league.updateInterval
                loopEvery(5.seconds, { currentCoroutineContext().isActive }) {
                    val now = time()

                    try {
                        http.getChroniclerVersionsBetween("stream", before = now, after = lastTime, endpoint)?.forEach { streamData ->
                            val value = streamData.getJsonObjectOrNull("value")

                            if (value != null) {
                                value.forEach { (k, v) -> core[k] = v }

                                emit(coreJson.deepSort() as? JsonObject ?: coreJson)
                            } else {
                                emit(streamData)
                            }
                        }
                    } finally {
                        lastTime = now
                    }
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
                connectTimeoutMillis = 30_000L
            }
            endpoint()
        }
        if (!call.response.status.isSuccess()) return emptyFlow()
        if (call.response.contentLength() == 0L) {
            delay(20_000)
            return emptyFlow()
        }

        return channelFlow {
            val content = if (call.response.headers[HttpHeaders.ContentEncoding] == "gzip")
                call.response.receive()
            else
                call.response.content

            var origin: MutableJsonObject? = null

            while (isActive && !content.isClosedForRead) {
                val line = buildString {
                    while (isActive && !content.isClosedForRead) {
                        content.awaitContent()

                        val line = content.readUTF8Line() ?: break
                        if (line.startsWith("data:"))
                            appendLine(line.substringAfter("data:"))
                        else if (line.isBlank())
                            break
                    }
                }.trim()

                if (line.isNotBlank()) {
                    send(json.parseToJsonElement(line).jsonObject)
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
                            var origin: MutableJsonObject? = null

                            getLiveDataStream()
                                .onEach { element ->
                                    val value = element.getJsonObjectOrNull("value")
                                    val delta = element.getJsonArrayOrNull("delta")

                                    if (value != null) {
                                        origin = value.toMutable() as? MutableJsonObject
                                    }

                                    origin?.let {
                                        if (delta != null) {
//                                            DeepDiff.mutateFromDiff(null, it, json.decodeFromJsonElement(delta), null)
                                            JsonPatch.mutateFromPatch(it, json.decodeFromJsonElement(delta))
                                        }
                                    }

//                                    event.forEach { (k, v) -> updateData[k] = v }

                                    origin?.let {
                                        liveData.emit(buildJsonObject {
                                            put("value", it.toJson())
                                        })
                                    }

                                    liveUpdates.emit(element)
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

        scope.launch(context) {
            val gamesDir = File("games")
            gamesDir.mkdirs()

            val map: MutableMap<String, Int> = HashMap()

            liveData.onEach { json ->
                json.getJsonObject("value")
                    .getJsonObject("games")
                    .getJsonArray("schedule")
                    .filterIsInstance<JsonObject>()
                    .forEach { game ->
                        val gameID = game.getString("id")
                        val playCount = game.getJsonPrimitive("playCount").int
                        if ((map[gameID] ?: 0) < playCount) File(gamesDir, "$gameID.txt").appendText("${playCount.toString().padStart(3, ' ')}: ${game.getString("lastUpdate")}")
                    }
            }.launchIn(this).join()
        }
    }
}