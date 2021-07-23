package dev.brella.corsmechanics

import io.ktor.application.*
import io.ktor.client.*
import io.ktor.request.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.shareIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap

sealed class BlaseballDataSource {
    abstract val eventStream: LiveData
    val eventStringStream by lazy {
        eventStream.liveData
            .mapNotNull(JsonObject::toString)
            .shareIn(CorsMechanics, SharingStarted.Eagerly, 1)
    }

    class Live(json: Json, http: HttpClient, scope: CoroutineScope) : BlaseballDataSource() {
        override val eventStream: LiveData = LiveData(json, http, scope)
    }

    class Blasement(json: Json, http: HttpClient, scope: CoroutineScope, instance: String) : BlaseballDataSource() {
        override val eventStream: LiveData = LiveData(json, http, scope, endpoint = "https://blasement.brella.dev/leagues/$instance/events/streamData")
    }

    data class Instances(val json: Json, val http: HttpClient, val scope: CoroutineScope) {
        private val live by lazy { Live(json, http, scope) }
        private val blasement: MutableMap<String, Blasement> = ConcurrentHashMap()

        public fun live() = live
        public fun blasement(instance: String) = blasement.computeIfAbsent(instance) { Blasement(json, http, scope, instance) }

        infix fun sourceFor(call: ApplicationCall): BlaseballDataSource =
            call.request.header("X-Blasement-Instance")
                ?.let(::blasement)
            ?: live()
    }
}