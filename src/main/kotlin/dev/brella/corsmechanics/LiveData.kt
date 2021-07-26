package dev.brella.corsmechanics

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
import kotlinx.coroutines.channels.BufferOverflow
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

class LiveData(val json: Json, val http: HttpClient, val scope: CoroutineScope, val context: CoroutineContext = scope.coroutineContext, val endpoint: HttpRequestBuilder.() -> Unit = { url("https://www.blaseball.com/events/streamData") }) {
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
                    append(content.readUTF8Line() ?: return@buildString)
                    append(content.readUTF8Line() ?: return@buildString)
                }.trim()

                if (line.startsWith("data:")) {
                    send(json.parseToJsonElement(line.substringAfter("data:").trim()).jsonObject.getValue("value").jsonObject)
                }
            }

            call.response.cleanup()
        }
    }

    var updateJob: Job? = null

    fun relaunchJob() {
        println("RELAUNCHING")

        liveData.resetReplayCache()
        liveUpdates.resetReplayCache()

        updateJob?.cancel()
        updateJob = scope.launch(context) {
            val updateData: MutableMap<String, JsonElement> = HashMap()
            val updateJson = JsonObject(updateData)
            val failures: MutableMap<String?, Int> = HashMap()

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
                    th.printStackTrace()
                    val seen = failures.compute(th.message) { _, i -> i?.plus(1) ?: 1 }

                    println("Starting back up (Seen this error $seen times)...")
                }
            }
        }
    }

    init {
        relaunchJob()
    }
}