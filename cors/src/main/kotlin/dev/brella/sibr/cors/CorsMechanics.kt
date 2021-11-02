package dev.brella.sibr.cors

import com.github.benmanes.caffeine.cache.AsyncLoadingCache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Scheduler
import dev.brella.corsmechanics.NamedThreadFactory
import dev.brella.ktornea.common.installGranularHttp
import io.ktor.application.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.features.*
import io.ktor.client.features.compression.*
import io.ktor.client.features.json.*
import io.ktor.client.features.json.serializer.*
import io.ktor.client.request.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.http.cio.websocket.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.serialization.*
import io.ktor.util.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import org.slf4j.event.Level
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.math.sqrt
import kotlin.random.Random

object CorsMechanics : CoroutineScope {
    val executor = Executors.newCachedThreadPool(NamedThreadFactory("CorsMechanics"))
    val coroutineDispatcher = executor.asCoroutineDispatcher()

    override val coroutineContext: CoroutineContext =
        SupervisorJob() + coroutineDispatcher
}

val JSON = Json.Default
val HTTP = HttpClient(CIO) {
    installGranularHttp()

    install(ContentEncoding) {
        gzip()
        deflate()
        identity()
    }

    install(JsonFeature) {
        serializer = KotlinxSerializer(JSON)
    }

    install(HttpTimeout) {
        connectTimeoutMillis = 20_000L
    }

    expectSuccess = false

    defaultRequest {
        userAgent("Mozilla/5.0 (X11; Linux x86_64; rv:85.0) Gecko/20100101 Firefox/85.0")
    }
}

typealias RequestCache = AsyncLoadingCache<ProxyRequest, ProxyJob>
typealias RequestCacheBuckets = MutableMap<String, Proxifier>

val requestCacheBuckets: RequestCacheBuckets = ConcurrentHashMap()

fun main(args: Array<String>) = io.ktor.server.netty.EngineMain.main(args)
fun Application.module(testing: Boolean = false) {
    install(ContentNegotiation) {
        json(JSON)
    }

    install(CORS) {
        anyHost()
    }

    install(ConditionalHeaders)
    install(StatusPages) {
        exception<Throwable> { cause -> call.respond(HttpStatusCode.InternalServerError, cause.stackTraceToString()) }
//        exception<KorneaResultException> { cause ->
//            val result = cause.result
//            if (result is KorneaHttpResult) call.response.header("X-Response-Source", result.response.request.url.toString())
//            result.respondOnFailure(call)
//        }
    }
    install(CallLogging) {
        level = Level.INFO
    }

    install(io.ktor.websocket.WebSockets) {
        pingPeriod = Duration.ofSeconds(60) // Disabled (null) by default
        timeout = Duration.ofSeconds(15)
        masking = false
    }

    requestCacheBuckets["blaseball"] =
        Proxifier(
            Caffeine.newBuilder()
                .expireAfterAccess(5, TimeUnit.SECONDS)
                .scheduler(Scheduler.systemScheduler())
                .executor(CorsMechanics.executor)
                .evictionListener<ProxyRequest, ProxyJob> { _, job, _ -> job?.cancel() }
                .buildAsync(ProxyJob::forRequest)
        ) { request, route ->
            ProxyRequest(
                "api.blaseball.com",
                "https://api.blaseball.com/",
                route,
                request.queryParameters
                    .flattenEntries()
                    .sortedBy(Pair<String, String>::first)
            )
        }

    routing {
        get("/random") {
            call.respond(buildString {
                repeat(10) {
                    append("${sqrt(Random.nextLong().toDouble())},")
                    delay(Random.nextLong(1_000))
                }
            })
        }

        get("/{route...}") {
            requestCacheBuckets["blaseball"]?.handle(call) ?: call.respond(HttpStatusCode.NotFound)
        }
    }
}