package dev.brella.corsmechanics

import com.github.benmanes.caffeine.cache.Caffeine
import io.ktor.application.*
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.request.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.shareIn
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

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
        override val eventStream: LiveData = LiveData(json, http, scope, endpoint = { url("https://blasement.brella.dev/leagues/$instance/events/streamData") })
    }

    class Before(json: Json, http: HttpClient, scope: CoroutineScope, offset: Long) : BlaseballDataSource() {
        override val eventStream: LiveData = LiveData(json, http, scope, endpoint = {
            url("https://before.sibr.dev/events/streamData")
            cookie("offset_sec", offset.toString())
        })
    }

    data class Instances(val json: Json, val http: HttpClient, val scope: CoroutineScope) {
        private val live by lazy { Live(json, http, scope) }
        private val blasement = Caffeine
            .newBuilder()
            .expireAfterAccess(1, TimeUnit.MINUTES)
            .build<String, Blasement> { instance -> Blasement(json, http, scope, instance) }
        private val before = Caffeine
            .newBuilder()
            .expireAfterAccess(1, TimeUnit.MINUTES)
            .build<Long, Before> { offset -> Before(json, http, scope, offset) }


        public fun live() = live
        public fun blasement(instance: String) = blasement[instance]
        public fun before(offset: Long) = before[offset]

        infix fun sourceFor(call: ApplicationCall): BlaseballDataSource =
            call.request.header("X-Blasement-Instance")
                ?.let(::blasement)
            ?: call.request.header("X-Before-Offset")
                ?.let { offset -> offset.toLongOrNull() ?: runCatching { Instant.parse(offset) }.getOrNull()?.epochSeconds }
                ?.let(::before)
            ?: live()
    }
}