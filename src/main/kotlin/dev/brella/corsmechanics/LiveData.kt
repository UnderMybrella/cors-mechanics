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
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.coroutines.CoroutineContext
import kotlin.math.pow
import kotlin.random.Random

class LiveData(val id: String, val json: Json, val http: HttpClient, val scope: CoroutineScope, val context: CoroutineContext = scope.coroutineContext, val endpoint: HttpRequestBuilder.() -> Unit = { url("https://www.blaseball.com/events/streamData") }) {
    companion object {
        val HASH_REGEX = "@[0-9a-f]{1,8}".toRegex()
    }
    //    val simulationData: MutableList<BlaseballStreamData> = ArrayList()
//    val games: MutableMap<GameID, BlaseballUpdatingGame> = HashMap()
//    val chroniclerGames: MutableMap<GameID, Map<Int, BlaseballDatabaseGame>> = HashMap()

    val liveData = MutableSharedFlow<JsonObject>(1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val liveUpdates = MutableSharedFlow<JsonObject>(1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    //            url("http://localhost:9897/blaseball/accelerated/live_bait/events/streamData")
    suspend fun getLiveDataStream(): Flow<JsonObject> {
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

                if (line.isNotBlank())
                    send(json.parseToJsonElement(line).jsonObject.getValue("value").jsonObject)
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
                        withTimeout(120_000) {
                            getLiveDataStream()
                                .onEach { event ->
                                    event.forEach { (k, v) -> updateData[k] = v }

                                    liveData.emit(buildJsonObject {
                                        put("value", updateJson)
                                    })

                                    liveUpdates.emit(buildJsonObject {
                                        put("value", event)
                                    })
                                }.launchIn(this)
                                .join()
                        }
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