package dev.brella.corsmechanics.cors

import com.github.benmanes.caffeine.cache.AsyncLoadingCache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Scheduler
import dev.brella.corsmechanics.common.NamedThreadFactory
import dev.brella.kornea.BuildConstants
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.compression.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.plugins.conditionalheaders.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.util.*
import io.ktor.server.websocket.*
import io.ktor.util.*
import io.ktor.util.pipeline.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.serialization.json.Json
import org.slf4j.event.Level
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import io.ktor.client.plugins.compression.ContentEncoding as ClientContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.websocket.WebSockets as ServerWebSockets

typealias RequestCache = AsyncLoadingCache<ProxyRequest, ProxyJob>
typealias RequestCacheBuckets = MutableMap<String, Proxifier>

object CorsMechanics : CoroutineScope {
    val executor = Executors.newCachedThreadPool(NamedThreadFactory("CorsMechanics"))
    val coroutineDispatcher = executor.asCoroutineDispatcher()

    override val coroutineContext: CoroutineContext =
        SupervisorJob() + coroutineDispatcher

    val requestCacheBuckets: RequestCacheBuckets = ConcurrentHashMap()

    public fun registerCacheBucket(host: String, proxifier: Proxifier) =
        requestCacheBuckets.put(host, proxifier)

    public fun registerCacheBucket(vararg hosts: String, proxifier: Proxifier) {
        hosts.forEach { host -> requestCacheBuckets[host] = proxifier }
    }

    public fun registerCacheBucket(
        host: String,
        primaryCache: RequestCache,
        transform: suspend (request: ApplicationRequest, path: String) -> ProxyRequest?,
    ) = registerCacheBucket(host, Proxifier(primaryCache, transform))

    public fun registerCacheBucket(
        vararg hosts: String,
        primaryCache: RequestCache,
        transform: suspend (request: ApplicationRequest, path: String) -> ProxyRequest?,
    ) = registerCacheBucket(hosts = hosts, Proxifier(primaryCache, transform))

    public suspend fun handle(context: PipelineContext<Unit, ApplicationCall>) =
        with(context) {
            requestCacheBuckets[call.parameters.getOrFail("host")]
                ?.handle(call)
                ?: call.respond(HttpStatusCode.NotFound)
        }
}

val JSON = Json.Default
val HTTP = HttpClient(CIO) {
    install(ClientContentEncoding) {
        gzip()
        deflate()
        identity()
    }

    install(ClientContentNegotiation) {
        json(JSON)
    }

    install(HttpTimeout) {
        connectTimeoutMillis = 20_000L
    }

    expectSuccess = false

    defaultRequest {
        userAgent("CorsMechanics/${BuildConstants.GRADLE_VERSION} (+https://github.com/UnderMybrella/cors-mechanics)")
    }
}

fun main(args: Array<String>) = io.ktor.server.netty.EngineMain.main(args)
fun Application.module(testing: Boolean = false) {
    install(ServerContentNegotiation) {
        json(JSON)
    }

    install(CORS) {
        anyHost()
    }

    install(ConditionalHeaders)
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respond(
                HttpStatusCode.InternalServerError,
                cause.stackTraceToString()
            )
        }
//        exception<KorneaResultException> { cause ->
//            val result = cause.result
//            if (result is KorneaHttpResult) call.response.header("X-Response-Source", result.response.request.url.toString())
//            result.respondOnFailure(call)
//        }
    }
    install(CallLogging) {
        level = Level.INFO
    }

    install(ServerWebSockets) {
        pingPeriod = Duration.ofSeconds(60) // Disabled (null) by default
        timeout = Duration.ofSeconds(15)
        masking = false
    }


    val baseCacheBuilder = {
        Caffeine.newBuilder()
            .expireAfterAccess(5, TimeUnit.SECONDS)
            .scheduler(Scheduler.systemScheduler())
            .executor(CorsMechanics.executor)
            .evictionListener<ProxyRequest, ProxyJob> { _, job, _ -> job?.cancel() }
            .buildAsync(ProxyJob::forRequest)
    }

    CorsMechanics.registerCacheBucket(
        "api.blaseball.com", "blaseball",
        primaryCache = baseCacheBuilder()
    ) { request, route ->
        if (route.startsWith("events/", true))
            null
        else
            ProxyRequest(
                "api.blaseball.com",
                "https://api.blaseball.com/",
                route,
                request.queryParameters
                    .flattenEntries()
                    .sortedBy(Pair<String, String>::first)
            )
    }

    CorsMechanics.registerCacheBucket(
        "api2.blaseball.com", "fallball",
        primaryCache = baseCacheBuilder()
    ) { request, route ->
        if (route.startsWith("events/", true))
            null
        else
            ProxyRequest(
                "api2.blaseball.com",
                "https://api2.blaseball.com/",
                route,
                request.queryParameters
                    .flattenEntries()
                    .sortedBy(Pair<String, String>::first)
            )
    }

    CorsMechanics.registerCacheBucket(
        "www.blaseball.com", "web",
        primaryCache = baseCacheBuilder()
    ) { request, route ->
        ProxyRequest(
            "www.blaseball.com",
            "https://www.blaseball.com/",
            route,
            request.queryParameters
                .flattenEntries()
                .sortedBy(Pair<String, String>::first)
        )
    }

    routing {
        route("/{host}") {
            get("/{route...}") { CorsMechanics.handle(this) }
        }

        get("/health") {
            call.respond("Healthy!")
        }
    }
}