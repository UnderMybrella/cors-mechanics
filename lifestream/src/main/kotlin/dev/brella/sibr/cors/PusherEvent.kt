package dev.brella.sibr.cors

import io.ktor.util.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*

@Serializable
data class PusherEvent(val event: String, val data: String? = null, val channel: String? = null) {
    constructor(event: String, data: JsonElement? = null, channel: String? = null) : this(
        event,
        data?.toString(),
        channel
    )

    companion object {
        const val PUSHER_CONNECTION_ESTABLISHED = "pusher:connection_established"
        const val PUSHER_SUBSCRIBE = "pusher:subscribe"
        const val PUSHER_INTERNAL_SUBSCRIPTION_SUCCESS = "pusher_internal:subscription_success"
        const val PUSHER_UNSUBSCRIBE = "pusher:unsubscribe"
        const val PUSHER_PING = "pusher:ping"
        const val PUSHER_PONG = "pusher:pong"

        inline fun subscribe(auth: String, channel: String) =
            PusherEvent(PUSHER_SUBSCRIBE, buildJsonObject {
                put("auth", auth)
                put("channel", channel)
            })
    }

    @Serializable
    data class ConnectionEstablished(
        @SerialName("socket_id")
        val socketID: String,
        @SerialName("activity_timeout")
        val activityTimeout: Double,
    )

    val isConnectionEstablished get() = event == PUSHER_CONNECTION_ESTABLISHED
    val isSubscribe get() = event == PUSHER_SUBSCRIBE
    val isSubscriptionSuccess get() = event == PUSHER_INTERNAL_SUBSCRIPTION_SUCCESS
    val isUnsubscribe get() = event == PUSHER_UNSUBSCRIBE
    val isPing get() = event == PUSHER_PING
    val isPong get() = event == PUSHER_PONG

    inline fun <reified T> dataFromJson(): T? =
        data?.let { JSON.decodeFromString(it) }

    suspend inline fun <reified T> dataFromBase64GZipped(scope: CoroutineScope): T? =
        data?.let { JSON.decodeFromString<JsonObject>(it)["message"] }
            ?.jsonPrimitive
            ?.content
            ?.decodeBase64Bytes()
            ?.let { data ->
                with(scope) {
                    with(GZip) {
                        JSON.decodeFromString<T>(
                            decode(ByteReadChannel(data))
                                .toByteArray()
                                .decodeToString()
                        )
                    }
                }
            }
}