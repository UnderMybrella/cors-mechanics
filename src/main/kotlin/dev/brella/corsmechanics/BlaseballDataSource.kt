package dev.brella.corsmechanics

import com.github.benmanes.caffeine.cache.Caffeine
import io.ktor.application.*
import io.ktor.client.*
import io.ktor.client.features.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.request.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.future.future
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.minus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.time.ExperimentalTime

sealed class BlaseballDataSource {
    abstract val eventStream: EventStream?
    val eventStringStream by lazy {
        eventStream?.liveData
            ?.mapNotNull(JsonObject::toString)
            ?.shareIn(CorsMechanics, SharingStarted.Eagerly, 1)
    }

    class Live(val json: Json, val http: HttpClient, val scope: CoroutineScope) : BlaseballDataSource() {
        override val eventStream: EventStream? by lazy {
            if (DISABLE_EVENT_STREAM) null
            else EventStream.FromEventSource("Live", json, http, scope)
        }

        override fun buildRequest(proxyRequest: ProxyRequest, executor: Executor, proxyPassHost: String?, passCookies: Boolean): CompletableFuture<ProxiedResponse> =
            scope.future {
                http.get<HttpResponse>("https://api.blaseball.com/${proxyRequest.path}") {
                    if (proxyPassHost != null) header("Host", proxyPassHost)
                    if (passCookies) proxyRequest.cookies.forEach { cookie ->
                        cookie(
                            name = cookie.name,
                            value = cookie.value,
                            maxAge = cookie.maxAge,
                            expires = cookie.expires,
                            domain = cookie.domain,
                            path = cookie.path,
                            secure = cookie.secure,
                            httpOnly = cookie.httpOnly,
                            extensions = cookie.extensions
                        )
                    }
                }.let { ProxiedResponse.proxyFrom(it, true) }
            }
    }

    class Blasement(val json: Json, val http: HttpClient, val scope: CoroutineScope, val instance: String) : BlaseballDataSource() {
        override val eventStream: EventStream? by lazy {
            if (DISABLE_EVENT_STREAM) null
            else EventStream.FromEventSource("Blasement @ $instance", json, http, scope, endpoint = { url("https://blasement.brella.dev/leagues/$instance/events/streamData") })
        }

        override fun buildRequest(proxyRequest: ProxyRequest, executor: Executor, proxyPassHost: String?, passCookies: Boolean): CompletableFuture<ProxiedResponse> =
            scope.future {
                http.get<HttpResponse>("https://blasement.brella.dev/leagues/$instance/${proxyRequest.path}") {
                    if (proxyPassHost != null) header("Host", proxyPassHost)
                    if (passCookies) proxyRequest.cookies.forEach { cookie ->
                        cookie(
                            name = cookie.name,
                            value = cookie.value,
                            maxAge = cookie.maxAge,
                            expires = cookie.expires,
                            domain = cookie.domain,
                            path = cookie.path,
                            secure = cookie.secure,
                            httpOnly = cookie.httpOnly,
                            extensions = cookie.extensions
                        )
                    }
                }.let { ProxiedResponse.proxyFrom(it, true) }
            }
    }

    class Before(val json: Json, val http: HttpClient, val scope: CoroutineScope, val offset: Long) : BlaseballDataSource() {
        override val eventStream: EventStream? by lazy {
            if (DISABLE_EVENT_STREAM) null
            else EventStream.FromChronicler("Before @ $offset", json, http, scope, time = { Clock.System.now().minus(offset, DateTimeUnit.SECOND).toString() })
        }

        override fun buildRequest(proxyRequest: ProxyRequest, executor: Executor, proxyPassHost: String?, passCookies: Boolean): CompletableFuture<ProxiedResponse> =
            scope.future {
                http.get<HttpResponse>("https://before.sibr.dev/${proxyRequest.path}") {
                    cookie("offset_sec", offset.toString())
                    timeout {
                        connectTimeoutMillis = 20_000L
                    }

                    if (proxyPassHost != null) header("Host", proxyPassHost)
                    if (passCookies) proxyRequest.cookies.forEach { cookie ->
                        cookie(
                            name = cookie.name,
                            value = cookie.value,
                            maxAge = cookie.maxAge,
                            expires = cookie.expires,
                            domain = cookie.domain,
                            path = cookie.path,
                            secure = cookie.secure,
                            httpOnly = cookie.httpOnly,
                            extensions = cookie.extensions
                        )
                    }
                }.let { ProxiedResponse.proxyFrom(it, true) }
            }
    }

    data class Instances(val json: Json, val http: HttpClient, val scope: CoroutineScope) {
        private val live by lazy { Live(json, http, scope) }
        private val blasement = Caffeine
            .newBuilder()
            .expireAfterAccess(1, TimeUnit.MINUTES)
            .evictionListener<String, Blasement> { key, value, cause -> value?.eventStream?.cancelUpdateJob() }
            .build<String, Blasement> { instance -> Blasement(json, http, scope, instance) }
        private val before = Caffeine
            .newBuilder()
            .expireAfterAccess(1, TimeUnit.MINUTES)
            .evictionListener<Long, Before> { key, value, cause -> value?.eventStream?.cancelUpdateJob() }
            .build<Long, Before> { offset -> Before(json, http, scope, offset) }


        public fun live() = live
        public fun blasement(instance: String) = blasement[instance]
        public fun before(offset: Long) = before[offset]

        @OptIn(ExperimentalTime::class)
        infix fun sourceFor(call: ApplicationCall): BlaseballDataSource {
            val queryParams = call.request.queryParameters

            return (call.request.header("X-Blasement-Instance") ?: queryParams["blasement_instance"])
                       ?.let(::blasement)

                   ?: (call.request.header("X-Before-Offset") ?: queryParams["before_offset"])
                       ?.toLongOrNull()
                       ?.let(::before)

                   ?: live()
        }
    }

    abstract fun buildRequest(proxyRequest: ProxyRequest, executor: Executor, proxyPassHost: String?, passCookies: Boolean): CompletableFuture<ProxiedResponse>
}