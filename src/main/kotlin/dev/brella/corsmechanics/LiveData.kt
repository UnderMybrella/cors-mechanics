package dev.brella.corsmechanics

import dev.brella.kornea.errors.common.KorneaResult
import dev.brella.kornea.errors.common.doOnFailure
import dev.brella.kornea.errors.common.doOnSuccess
import dev.brella.kornea.errors.common.doOnThrown
import dev.brella.kornea.errors.common.flatMap
import dev.brella.kornea.errors.common.map
import dev.brella.kornea.errors.common.successPooled
import dev.brella.ktornea.common.streamAsResult
import io.ktor.client.*
import io.ktor.client.features.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.network.sockets.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ConflatedBroadcastChannel
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.channels.receiveOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random

class LiveData(val json: Json, val http: HttpClient, val scope: CoroutineScope, val context: CoroutineContext = scope.coroutineContext) {
    //    val simulationData: MutableList<BlaseballStreamData> = ArrayList()
//    val games: MutableMap<GameID, BlaseballUpdatingGame> = HashMap()
//    val chroniclerGames: MutableMap<GameID, Map<Int, BlaseballDatabaseGame>> = HashMap()

    val liveData = MutableSharedFlow<JsonObject>(1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val liveUpdates = MutableSharedFlow<JsonObject>(1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    suspend fun getLiveDataStream(): KorneaResult<Flow<JsonObject>> =
        http.streamAsResult {
            method = HttpMethod.Get
            url("https://www.blaseball.com/events/streamData")
//            url("http://localhost:9897/blaseball/accelerated/live_bait/events/streamData")
        }.flatMap { flow ->
            try {
                KorneaResult.successPooled(
                    flow.mapNotNull { str ->
                        if (str.startsWith("data:"))
                            json.parseToJsonElement(str.substringAfter("data:").trim()).jsonObject.getValue("value").jsonObject
                        else null
                    }
                )
            } catch (th: Throwable) {
                KorneaResult.thrown(th)
            }
        }

    val updateJob = scope.launch(context) {
        var last: JsonObject? = null

        while (isActive) {
            getLiveDataStream()
                .doOnSuccess { events ->
                    events.collect { event ->
                        val update = JsonObject(event.toMutableMap().also { map ->
                            last?.forEach { (k, v) -> map.putIfAbsent(k, v) }
                        })

                        liveData.emit(buildJsonObject {
                            put("value", update)
                        })

                        liveUpdates.emit(buildJsonObject {
                            put("value", event)
                        })

                        last = update
                    }
                }
                .doOnThrown { error ->
                    if (error.exception !is SocketTimeoutException) {
                        println("*Not* a SocketTimeout, right?")
                        error.exception.printStackTrace()
                    }
                }
                .doOnFailure { delay(100L + Random.nextLong(100)) }
        }
    }
}