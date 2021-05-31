package dev.brella.corsmechanics

import com.github.benmanes.caffeine.cache.Caffeine
import dev.brella.kotlinx.serialisation.kvon.Kvon
import dev.brella.kotlinx.serialisation.kvon.KvonData
import dev.brella.ktornea.common.installGranularHttp
import io.ktor.application.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.features.*
import io.ktor.client.features.compression.*
import io.ktor.client.features.json.*
import io.ktor.client.features.json.serializer.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.utils.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.http.cio.websocket.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.serialization.*
import io.ktor.utils.io.core.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.future.await
import kotlinx.coroutines.future.future
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.event.Level
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.time.ExperimentalTime

fun main(args: Array<String>) = io.ktor.server.netty.EngineMain.main(args)

object CorsMechanics : CoroutineScope {
    override val coroutineContext: CoroutineContext = SupervisorJob()
}


/**
 * Compress this element with [KvonData]
 *
 * This method checks the type of the element, then applies a deduplication strategy to it appropriately
 * - if this element is an array, each element is recursively compressed, using the previous element as a base. [baseElement] is assumed to be a starting element, if provided
 * - if this element is an object, each value is compressed against [baseElement], if provided.
 * - Otherwise, nothing happens
 */
public fun JsonElement.compressElementWithKvon(baseElement: JsonElement? = null, arrayVersioning: Boolean = false): JsonElement {
    return when (this) {
        is JsonArray -> {
            if (arrayVersioning) {
                buildJsonArray {
                    fold(baseElement) { previous, element ->
                        add(element.compressElementWithKvon(previous, arrayVersioning))
                        element
                    }
                }
            } else {
                val previous = baseElement as? JsonArray ?: return this

                buildJsonArray {
                    forEachIndexed { index, element ->
                        add(element.compressElementWithKvon(if (index in previous.indices) previous[index] else null, arrayVersioning))
                    }
                }
            }
        }

        is JsonObject -> {
            val previous = baseElement as? JsonObject ?: return this

            buildJsonObject {
                val changedKeys = filter { (key, value) -> previous[key] != value }
                val missingKeys = previous.keys.filterNot(::containsKey)

                changedKeys.forEach { (key, value) -> put(key, value.compressElementWithKvon(previous[key], arrayVersioning)) }
                missingKeys.forEach { key -> put(key, KvonData.KVON_MISSING) }
            }
        }

        else -> this
    }
}

//val blaseballRequestCache = Caffeine.newBuilder()
//    .expireAfterWrite(1, TimeUnit.SECONDS)
//    .installResultEvictionLeader<String, ProxiedResponse>()
//    .buildImplied()

data class ProxiedResponse(val body: Any, val status: HttpStatusCode, val headers: Headers) {
    companion object {
        suspend inline fun proxyFrom(response: HttpResponse) =
            ProxiedResponse(response.receive<Input>().readBytes(), response.status, response.headers)
    }
}

suspend inline fun HttpResponse.proxy(): ProxiedResponse =
    ProxiedResponse(receive<Input>().readBytes(), status, headers)

val BAD_HEADERS = listOf(
    HttpHeaders.StrictTransportSecurity,
    HttpHeaders.ContentEncoding,
    HttpHeaders.ContentLength,
    HttpHeaders.AcceptRanges,
    HttpHeaders.AccessControlAllowHeaders,
    HttpHeaders.AccessControlAllowMethods,
    HttpHeaders.AccessControlAllowOrigin,
    "Content-Security-Policy",
    "X-XSS-Protection",
    "X-Frame-Options"
)

suspend inline fun ApplicationCall.respondProxied(proxiedResponse: ProxiedResponse) {
    with(this.response.headers) {
        proxiedResponse.headers.forEach { name, values ->
            if (BAD_HEADERS.any { it.equals(name, true) }) return@forEach
            values.forEach { append(name, it, false) }
        }
    }

    respond(proxiedResponse.status, proxiedResponse.body)
}

suspend inline fun ApplicationCall.respondProxied(proxiedResponse: ProxiedResponse, extraHeaders: Headers) {
    with(this.response.headers) {
        proxiedResponse.headers.forEach { name, values ->
            if (BAD_HEADERS.any { it.equals(name, true) }) return@forEach
            values.forEach { append(name, it, false) }
        }

        extraHeaders.forEach { s, values ->
            values.forEach { append(s, it, false) }
        }
    }

    respond(proxiedResponse.status, proxiedResponse.body)
}

val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

val http = HttpClient(OkHttp) {
    installGranularHttp()

    install(ContentEncoding) {
        gzip()
        deflate()
        identity()
    }

    install(JsonFeature) {
        serializer = KotlinxSerializer(json)
    }

    expectSuccess = false

    defaultRequest {
        userAgent("Mozilla/5.0 (X11; Linux x86_64; rv:85.0) Gecko/20100101 Firefox/85.0")
    }
}

fun ApplicationCall.buildProxiedRoute(): String? {
    val route = parameters.getAll("route") ?: return null
    val queryParams = request.queryString()
    return route.joinToString("/", postfix = if (queryParams.isNotBlank()) "?$queryParams" else "")
}

//val blaseballRequestCache = Caffeine.newBuilder()
//    .expireAfterWrite(1, TimeUnit.SECONDS)
//    .buildAsync<String, ProxiedResponse> { key, executor ->
//        CorsMechanics.future {
//            http.get<HttpResponse>("https://www.blaseball.com/$key")
//                .let { ProxiedResponse.proxyFrom(it) }
//        }
//    }

val requestCacheBuckets: RequestCacheBuckets = ConcurrentHashMap()

inline fun forRequest(host: String, path: String, queryParameters: String): CompletableFuture<ProxiedResponse>? {
    val (fallback, buckets) = requestCacheBuckets[host] ?: return null
    return (buckets[path] ?: fallback).get(if (queryParameters.isNotBlank()) "$path?$queryParameters" else path)
}

@OptIn(ExperimentalTime::class, ExperimentalStdlibApi::class)
fun Application.module(testing: Boolean = false) {
    install(ContentNegotiation) {
        json(json)
        serialization(ContentType.parse("application/kvon"), Kvon.Default)
    }

    install(CORS) {
        anyHost()
    }

    install(ConditionalHeaders)
    install(StatusPages) {
//        exception<Throwable> { cause -> call.respond(HttpStatusCode.InternalServerError, cause.stackTraceToString()) }
//        exception<KorneaResultException> { cause ->
//            val result = cause.result
//            if (result is KorneaHttpResult) call.response.header("X-Response-Source", result.response.request.url.toString())
//            result.respondOnFailure(call)
//        }
    }
    install(CallLogging) {
        level = Level.INFO
    }

    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(60) // Disabled (null) by default
        timeout = Duration.ofSeconds(15)
        masking = false
    }

    val config = environment.config.config("cors-mechanics")
    val proxyConfig = config.config("proxy")

    val globalDefaultCacheSpec = proxyConfig.propertyOrNull("default_cache")?.getString()

    proxyConfig.configList("hosts")
        .forEach { proxyConfig ->
            val aliasNames = proxyConfig.property("alias").getList()
            val proxyPass = proxyConfig.property("proxy_pass").getString()
            val proxyPassHost = proxyConfig.propertyOrNull("proxy_pass_host")?.getString()

            val defaultCacheSpec = proxyConfig.propertyOrNull("default_cache")?.getString() ?: globalDefaultCacheSpec

            val defaultCacheBuilder = (defaultCacheSpec?.let { Caffeine.from(it) } ?: Caffeine.newBuilder().expireAfterWrite(1, TimeUnit.SECONDS))

            val cache = if (proxyPassHost != null) {
                defaultCacheBuilder
                    .buildAsync<String, ProxiedResponse> { key, executor ->
                        CorsMechanics.future {
                            http.get<HttpResponse>("$proxyPass/$key") {
                                header("Host", proxyPassHost)
                            }.let { ProxiedResponse.proxyFrom(it) }
                        }
                    }
            } else {
                defaultCacheBuilder
                    .buildAsync { key, executor ->
                        CorsMechanics.future {
                            http.get<HttpResponse>("$proxyPass/$key")
                                .let { ProxiedResponse.proxyFrom(it) }
                        }
                    }
            }

            val pathCaches: MutableMap<String, RequestCache> = ConcurrentHashMap()

            proxyConfig.propertyOrNull("path_caches")?.getList()?.forEach {
                val (path, spec) = it.split(':', limit = 2)

                pathCaches[path] = if (proxyPassHost != null) {
                    Caffeine.from(spec)
                        .buildAsync { key, executor ->
                            CorsMechanics.future {
                                http.get<HttpResponse>("$proxyPass/$key") {
                                    header("Host", proxyPassHost)
                                }.let { ProxiedResponse.proxyFrom(it) }
                            }
                        }
                } else {
                    Caffeine.from(spec)
                        .buildAsync { key, executor ->
                            CorsMechanics.future {
                                http.get<HttpResponse>("$proxyPass/$key")
                                    .let { ProxiedResponse.proxyFrom(it) }
                            }
                        }
                }
            }

            aliasNames.forEach { name -> requestCacheBuckets[name] = Pair(cache, pathCaches) }
        }

    val streamData = LiveData(json, http, CorsMechanics)
    val liveDataJsonString = streamData.liveData.map(json::encodeToString)
        .shareIn(CorsMechanics, SharingStarted.Eagerly, 1)

    setupConvenienceRoutes(http, streamData, liveDataJsonString)

    routing {
        get("/healthy") {
            call.respond(HttpStatusCode.OK, EmptyContent)
        }
        route("/{host}") {
            get("/{route...}") {
                call.respondProxied(
                    forRequest(
                        call.parameters["host"] ?: return@get,
                        call.parameters.getAll("route")?.joinToString("/") ?: return@get,
                        call.request.queryString()
                    )?.await() ?: return@get
                )
            }
        }

        route("/www.blaseball.com") { blaseballEventStreamHandler(streamData, liveDataJsonString) }
        route("/blaseball.com") { blaseballEventStreamHandler(streamData, liveDataJsonString) }
        route("/blaseball") { blaseballEventStreamHandler(streamData, liveDataJsonString) }
    }
}

fun Route.blaseballEventStreamHandler(streamData: LiveData, liveDataJsonString: SharedFlow<String>) {
    route("/events") {
        get("/streamData") {
            call.respondTextWriter(ContentType.Text.EventStream) {
                liveDataJsonString.take(5).onEach { data ->
                    withContext(Dispatchers.IO) {
                        append("data:")
                        append(data)
                        appendLine()
                        appendLine()
                        flush()
                    }
                }.launchIn(this@get).join()
            }
        }

        webSocket("/streamData") {
            val format = call.request.queryParameters["format"]

            if (format?.equals("kvon", true) == true) {
                var previous: JsonObject? = null
                streamData.liveData.onEach { data ->
                    val kvon = data.compressElementWithKvon(previous, false)
                    send(json.encodeToString(kvon))

                    previous = data
                }.launchIn(this).join()
            } else {
//                        streamData.liveData
//                            .onEach { data -> send(json.encodeToString(data)) }
//                            .launchIn(this)
//                            .join()

                liveDataJsonString
                    .onEach { data -> send(data) }
                    .launchIn(this)
                    .join()
            }
        }
    }
}