package dev.brella.sibr.cors

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class PusherEvent(val event: String, val data: JsonElement? = null, val channel: String? = null) {
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

    val isConnectionEstablished get() = event == PUSHER_CONNECTION_ESTABLISHED
    val isSubscribe get() = event == PUSHER_SUBSCRIBE
    val isSubscriptionSuccess get() = event == PUSHER_INTERNAL_SUBSCRIPTION_SUCCESS
    val isUnsubscribe get() = event == PUSHER_UNSUBSCRIBE
    val isPing get() = event == PUSHER_PING
    val isPong get() = event == PUSHER_PONG
}