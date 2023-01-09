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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
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

    public inline fun registerCacheBucket(
        host: String,
        primaryCache: RequestCache,
        supplier: Proxifier.Companion.() -> ProxyTransformation,
    ) = registerCacheBucket(host, Proxifier(primaryCache, Proxifier.supplier()))

    public inline fun registerCacheBucket(
        vararg hosts: String,
        primaryCache: RequestCache,
        supplier: Proxifier.Companion.() -> ProxyTransformation,
    ) = registerCacheBucket(hosts = hosts, Proxifier(primaryCache, Proxifier.supplier()))

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
        primaryCache = baseCacheBuilder(),
    ) { https("api.blaseball.com") { _, route -> !route.startsWith("events/", true) } }

    CorsMechanics.registerCacheBucket(
        "api2.blaseball.com", "fallball",
        primaryCache = baseCacheBuilder(),
    ) { https("api2.blaseball.com") { _, route -> !route.startsWith("events/", true) } }

    CorsMechanics.registerCacheBucket(
        "www.blaseball.com", "web",
        primaryCache = baseCacheBuilder(),
    ) { https("www.blaseball.com") }

    CorsMechanics.registerCacheBucket(
        "blaseball-configs.s3.us-west-2.amazonaws.com", "blaseball-configs",
        primaryCache = baseCacheBuilder(),
    ) { https("blaseball-configs.s3.us-west-2.amazonaws.com") }

    CorsMechanics.registerCacheBucket(
        "blaseball-icons.s3.us-west-2.amazonaws.com", "blaseball-icons",
        primaryCache = baseCacheBuilder(),
    ) { https("blaseball-icons.s3.us-west-2.amazonaws.com") }

    routing {
        route("/{host}") {
            get("/{route...}") { CorsMechanics.handle(this) }
        }

        val healthObject = buildJsonObject {
            putJsonObject("git") {
                put("commit_short_hash", BuildConstants.GIT_COMMIT_SHORT_HASH)
                put("commit_long_hash", BuildConstants.GIT_COMMIT_LONG_HASH)
                put("branch", BuildConstants.GIT_BRANCH)
                put("commit_message", BuildConstants.GIT_COMMIT_MESSAGE)
            }

            putJsonObject("gradle") {
                put("version", BuildConstants.GRADLE_VERSION)
                put("group", BuildConstants.GRADLE_GROUP)
                put("name", BuildConstants.GRADLE_NAME)
                put("display_name", BuildConstants.GRADLE_DISPLAY_NAME)
                put("description", BuildConstants.GRADLE_DESCRIPTION)
            }

            put("build_time", BuildConstants.BUILD_TIME_EPOCH)
            put("build_time_utc", BuildConstants.BUILD_TIME_UTC_EPOCH)
            put("tag", BuildConstants.TAG)
        }

        get("/health") {
            call.respond(healthObject)
        }
    }
}