package dev.brella.corsmechanics

import dev.brella.ktornea.common.cleanup
import dev.brella.ktornea.common.executeStatement
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import java.nio.ByteBuffer
import kotlin.coroutines.CoroutineContext

class LiveData(val json: Json, val http: HttpClient, val scope: CoroutineScope, val context: CoroutineContext = scope.coroutineContext) {
    //    val simulationData: MutableList<BlaseballStreamData> = ArrayList()
//    val games: MutableMap<GameID, BlaseballUpdatingGame> = HashMap()
//    val chroniclerGames: MutableMap<GameID, Map<Int, BlaseballDatabaseGame>> = HashMap()

    val liveData = MutableSharedFlow<JsonObject>(1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val liveUpdates = MutableSharedFlow<JsonObject>(1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    //            url("http://localhost:9897/blaseball/accelerated/live_bait/events/streamData")
    suspend fun getLiveDataStream(): Flow<JsonObject> {
        val call = http.executeStatement {
            url("https://www.blaseball.com/events/streamData")
            method = HttpMethod.Get
        }
        if (!call.response.status.isSuccess()) return emptyFlow()

        return channelFlow {
            val content = call.response.content

            while (isActive && !content.isClosedForRead) {

                val line = content.readUTF8Line() ?: return@channelFlow

                if (line.startsWith("data:")) {
                    send(json.parseToJsonElement(line.substringAfter("data:").trim()).jsonObject.getValue("value").jsonObject)
                }
            }

            call.response.cleanup()
        }
    }

    val updateJob = scope.launch(context) {
        var last: JsonObject? = null
        val failures: MutableMap<String?, Int> = HashMap()

        while (isActive) {
            try {
                getLiveDataStream()
                    .onEach { event ->
                        val update = JsonObject(HashMap(event).also { map ->
                            last?.forEach { (k, v) -> map.putIfAbsent(k, v) }
                        })

                        liveData.emit(buildJsonObject {
                            put("value", update)
                        })

                        liveUpdates.emit(buildJsonObject {
                            put("value", event)
                        })

                        last = update
                    }.launchIn(this)
                    .join()
            } catch (th: Throwable) {
                th.printStackTrace()
                val seen = failures.compute(th.message) { _, i -> i?.plus(1) ?: 1 }

                println("Starting back up (Seen this error $seen times)...")
            }
        }
    }
}