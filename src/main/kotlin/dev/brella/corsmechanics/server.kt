package dev.brella.corsmechanics

import com.github.benmanes.caffeine.cache.AsyncLoadingCache
import com.github.benmanes.caffeine.cache.Caffeine
import dev.brella.kotlinx.serialisation.kvon.Kvon
import dev.brella.kotlinx.serialisation.kvon.KvonData
import dev.brella.ktornea.common.installGranularHttp
import io.ktor.application.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
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
import io.ktor.util.*
import io.ktor.utils.io.core.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.time.ExperimentalTime

fun main(args: Array<String>) = io.ktor.server.netty.EngineMain.main(args)

object CorsMechanics : CoroutineScope {
    override val coroutineContext: CoroutineContext =
        SupervisorJob() + Executors.newCachedThreadPool(NamedThreadFactory("CorsMechanics"))
            .asCoroutineDispatcher()
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

data class ProxiedResponse(val body: Any, val status: HttpStatusCode, val headers: StringValues) {
    companion object {
        suspend inline fun proxyFrom(response: HttpResponse, passCookies: Boolean = false) =
            ProxiedResponse(response.receive<Input>().readBytes(), response.status, if (passCookies) response.headers else response.headers.filter { k, v -> !k.equals(HttpHeaders.SetCookie, true) })
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

    val etag = request.header(HttpHeaders.IfNoneMatch)
    if (etag != null && etag == proxiedResponse.headers[HttpHeaders.ETag]) respond(HttpStatusCode.NotModified, EmptyContent)
    else respond(proxiedResponse.status, proxiedResponse.body)
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

    val etag = request.header(HttpHeaders.IfNoneMatch)
    if (etag != null && etag == proxiedResponse.headers[HttpHeaders.ETag]) respond(HttpStatusCode.NotModified, EmptyContent)
    else respond(proxiedResponse.status, proxiedResponse.body)
}

val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

val http = HttpClient(CIO) {
    installGranularHttp()

    install(ContentEncoding) {
        gzip()
        deflate()
        identity()
    }

    install(JsonFeature) {
        serializer = KotlinxSerializer(json)
    }

    install(HttpTimeout) {
        connectTimeoutMillis = 20_000L
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
val dataSources = BlaseballDataSource.Instances(json, http, CorsMechanics)

inline fun forRequest(source: BlaseballDataSource?, host: String, path: String, queryParameters: String, cookies: List<Cookie>): CompletableFuture<ProxiedResponse>? {
    val (fallback, buckets) = requestCacheBuckets[host] ?: return null
    return (buckets[path] ?: fallback).get(ProxyRequest(source, host, if (queryParameters.isNotBlank()) "$path?$queryParameters" else path, cookies))
}

data class ProxyRequest(val source: BlaseballDataSource?, val host: String, val path: String, val cookies: List<Cookie>)

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

            val passCookies = proxyConfig.propertyOrNull("pass_cookies")?.getString()?.toBooleanStrictOrNull() ?: false

            val defaultCacheSpec = proxyConfig.propertyOrNull("default_cache")?.getString() ?: globalDefaultCacheSpec

            val defaultCacheBuilder = (defaultCacheSpec?.let { Caffeine.from(it) } ?: Caffeine.newBuilder().expireAfterWrite(1, TimeUnit.SECONDS))

            val cache: AsyncLoadingCache<ProxyRequest, ProxiedResponse> =
                defaultCacheBuilder
                    .buildAsync { key, executor ->
                        if (key.source != null && proxyPass == "https://www.blaseball.com")
                            key.source.buildRequest(key, executor, proxyPassHost, passCookies)
                        else
                            CorsMechanics.future {
                                http.get<HttpResponse>("$proxyPass/${key.path}") {
                                    if (proxyPassHost != null) header("Host", proxyPassHost)
                                    if (passCookies) key.cookies.forEach { cookie ->
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

    setupConvenienceRoutes(http, dataSources)

    routing {
        get("/health") {
            call.respond(HttpStatusCode.OK, EmptyContent)
        }

        route("/{host}") {
            get("/{route...}") {
                call.respondProxied(
                    forRequest(
                        dataSources sourceFor call,
                        call.parameters["host"] ?: return@get,
                        call.parameters.getAll("route")?.joinToString("/") ?: return@get,
                        call.request.queryString(),
                        call.request
                            .headers
                            .getAll(HttpHeaders.Cookie)
                            ?.map(::parseServerSetCookieHeader)
                        ?: emptyList()
                    )?.await() ?: return@get
                )
            }
        }

        route("/www.blaseball.com") { blaseballEventStreamHandler(dataSources) }
        route("/blaseball.com") { blaseballEventStreamHandler(dataSources) }
        route("/blaseball") { blaseballEventStreamHandler(dataSources) }
    }
}

fun Route.blaseballEventStreamHandler(dataSources: BlaseballDataSource.Instances) {
    route("/events") {
        get("/streamData") {
            val dataSource = dataSources sourceFor call
            call.respondTextWriter(ContentType.Text.EventStream) {
                if (dataSource.eventStream.updateJob?.isActive != true) dataSource.eventStream.relaunchJob()

                val job = dataSource.eventStringStream.onEach { data ->
                    withContext(Dispatchers.IO) {
                        append("data:")
                        append(data)
                        appendLine()
                        appendLine()
                        flush()
                    }
                }.launchIn(this@get)

                for (i in 0 until 20) {
                    delay(5000)
                    if (!job.isActive) break
                }

                if (job.isActive) job.cancelAndJoin()
            }
        }

        get("/streamData/{path...}") {
            val dataSource = dataSources sourceFor call
            val path = call.parameters.getAll("path") ?: emptyList()

            call.respondTextWriter(ContentType.Text.EventStream) {
                if (dataSource.eventStream.updateJob?.isActive != true) dataSource.eventStream.relaunchJob()

                val job = dataSource.eventStream.liveData.onEach { data ->
                    withContext(Dispatchers.IO) {
                        append("data:")
                        append(data.filterByPath(path)?.toString())
                        appendLine()
                        appendLine()
                        flush()
                    }
                }.launchIn(this@get)

                for (i in 0 until 20) {
                    delay(5000)
                    if (!job.isActive) break
                }

                if (job.isActive) job.cancelAndJoin()
            }
        }

        post<String>("/streamData/jq") { filter ->
            val dataSource = dataSources sourceFor call
            if (dataSource.eventStream.updateJob?.isActive != true) dataSource.eventStream.relaunchJob()

            call.respondTextWriter(ContentType.Text.EventStream) {
                val job = dataSource.eventStringStream.onEach { data ->
                    withContext(Dispatchers.IO) {
                        val request = Jq.executeRequest(data, filter)
                        if (request.hasErrors()) {
                            appendLine("event: error")
                            request.errors.forEach { line ->
                                append("data:")
                                appendLine(line)
                            }
                            appendLine()
                            flush()
                            close()
                        } else {
                            append("data:")
                            appendLine(request.output)
                            appendLine()
                            flush()
                        }
                    }
                }.launchIn(this@post)

                for (i in 0 until 20) {
                    delay(5000)
                    if (!job.isActive) break
                }

                if (job.isActive) job.cancelAndJoin()
            }
        }

        webSocket("/streamSocket") {
            val dataSource = dataSources sourceFor call
            if (dataSource.eventStream.updateJob?.isActive != true) dataSource.eventStream.relaunchJob()

            val format = call.request.queryParameters["format"]
            val wait = call.request.queryParameters["wait"]?.toBooleanStrictOrNull() ?: false

            if (format?.equals("kvon", true) == true) {
                var previous: JsonObject? = null

                dataSource.eventStream.liveData.onEach { data ->
                    val kvon = data.compressElementWithKvon(previous, false)
                    send(json.encodeToString(kvon))

                    previous = data
                }.launchIn(this).join()
            } else {
//                        streamData.liveData
//                            .onEach { data -> send(json.encodeToString(data)) }
//                            .launchIn(this)
//                            .join()

                var filter: String? = null

                incoming.receiveAsFlow()
                    .filterIsInstance<Frame.Text>()
                    .onEach { frame -> filter = frame.readText() }
                    .launchIn(this)

                if (wait) while (filter == null) delay(1_000)

                dataSource.eventStringStream
                    .onEach { data ->
                        if (filter == null) {
                            send(data)
                        } else {
                            val response = Jq.executeRequest(data, filter!!)
                            if (response.hasErrors()) {
                                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, buildString {
                                    append("Jq request with filter ($filter) had errors: ")
                                    response.errors.joinTo(this)
                                }))
                            } else {
                                send(response.output)
                            }
                        }
                    }
                    .launchIn(this)
                    .join()
            }
        }
    }
}