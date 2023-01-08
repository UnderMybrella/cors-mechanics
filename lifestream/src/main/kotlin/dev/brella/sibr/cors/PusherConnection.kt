package dev.brella.sibr.cors

import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.math.roundToLong

class PusherConnection(val session: DefaultClientWebSocketSession) : ClientWebSocketSession by session {
    val incomingEvents = incoming.consumeAsFlow()
        .filterIsInstance<Frame.Text>()
        .map { event -> JSON.decodeFromString<PusherEvent>(event.readText()) }
        .shareIn(this, started = SharingStarted.Eagerly)


    @OptIn(ObsoleteCoroutinesApi::class)
    val outgoingEvents: SendChannel<PusherEvent> = actor {
        incomingEvents.first { event -> event.isConnectionEstablished }
        while (isActive) {
            try {
                val sending = JSON.encodeToString(receive())
                send(sending)
            } catch (th: Throwable) {
                th.printStackTrace()
            }
        }
    }

    private val connectionEstablishedDetails: MutableStateFlow<PusherEvent.ConnectionEstablished?> =
        MutableStateFlow(null)

    private val incomingHandler = incomingEvents
        .onEach { event ->
            if (event.isConnectionEstablished) {
                connectionEstablishedDetails.value =
                    event.dataFromJson<PusherEvent.ConnectionEstablished>()
            }
        }
        .launchIn(this)

    private val pingPong = launch {
        while (isActive) {
            send(Frame.Text("{\"event\": \"pusher:ping\"}"))

            delay(connectionEstablishedDetails.value?.activityTimeout?.times(1_000)?.roundToLong() ?: 60_000)
        }
    }

    public suspend fun send(event: PusherEvent) {
        outgoingEvents.send(event)
    }

    public suspend fun sendPusherSubscribe(auth: String, channel: String) =
        send(PusherEvent.subscribe(auth, channel))
}