package dev.brella.corsmechanics

import com.github.benmanes.caffeine.cache.Caffeine
import dev.brella.kornea.BuildConstants
import dev.brella.kornea.errors.common.KorneaResult
import dev.brella.kornea.errors.common.getOrThrow
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.utils.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.callid.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.plugins.conditionalheaders.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.event.Level
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.time.ExperimentalTime
import io.ktor.client.plugins.HttpTimeout.Plugin as ClientHttpTimeout
import io.ktor.client.plugins.compression.ContentEncoding.Companion as ClientContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation.Plugin as ClientContentNegotiation
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation1

fun main(args: Array<String>) = io.ktor.server.netty.EngineMain.main(args)

object CorsMechanics : CoroutineScope {
    override val coroutineContext: CoroutineContext =
        SupervisorJob() + Executors.newCachedThreadPool(NamedThreadFactory("CorsMechanics"))
            .asCoroutineDispatcher()
}

//val blaseballRequestCache = Caffeine.newBuilder()
//    .expireAfterWrite(1, TimeUnit.SECONDS)
//    .installResultEvictionLeader<String, ProxiedResponse>()
//    .buildImplied()

data class ProxiedResponse(val body: Any, val status: HttpStatusCode, val headers: StringValues) {
    companion object {
        suspend inline fun proxyFrom(response: HttpResponse, passCookies: Boolean = false) =
            ProxiedResponse(
                response.body<ByteArray>(),
                response.status,
                if (passCookies) response.headers else response.headers.filter { k, _ ->
                    !k.equals(HttpHeaders.SetCookie, true)
                })
    }
}

suspend inline fun HttpResponse.proxy(passCookies: Boolean = false): ProxiedResponse =
    ProxiedResponse.proxyFrom(this, passCookies)

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
    if (etag != null && etag == proxiedResponse.headers[HttpHeaders.ETag]) respond(
        HttpStatusCode.NotModified,
        EmptyContent
    )
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
    if (etag != null && etag == proxiedResponse.headers[HttpHeaders.ETag]) respond(
        HttpStatusCode.NotModified,
        EmptyContent
    )
    else respond(proxiedResponse.status, proxiedResponse.body)
}

val http = HttpClient(CIO) {
    install(ClientContentEncoding) {
        gzip()
        deflate()
        identity()
    }

    install(ClientContentNegotiation) {
        json(Serialisation.json)
    }

    install(ClientHttpTimeout) {
        connectTimeoutMillis = 20_000L
    }

    expectSuccess = false

    defaultRequest {
        userAgent("CorsMechanics/${BuildConstants.GRADLE_VERSION} (+https://github.com/UnderMybrella/cors-mechanics)")
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
//            http.get<HttpResponse>("https://api.blaseball.com/$key")
//                .let { ProxiedResponse.proxyFrom(it) }
//        }
//    }

val requestCacheBuckets: RequestCacheBuckets = ConcurrentHashMap()
val dataSources = BlaseballDataSource.Instances(Serialisation.json, http, CorsMechanics)

inline fun forRequest(
    source: BlaseballDataSource?,
    host: String,
    path: String,
    queryParameters: String,
    cookies: List<Cookie>,
): Deferred<KorneaResult<ProxiedResponse>>? {
    val (fallback, buckets) = requestCacheBuckets[host] ?: return null
    return (buckets[path] ?: fallback).get(
        ProxyRequest(
            source,
            host,
            if (queryParameters.isNotBlank()) "$path?$queryParameters" else path,
            cookies
        )
    )
}

data class ProxyRequest(val source: BlaseballDataSource?, val host: String, val path: String, val cookies: List<Cookie>)

val DISABLE_EVENT_STREAM = System.getProperty("CORS_MECHANICS_DISABLE_EVENT_STREAM")?.toBooleanStrictOrNull()
    ?: System.getenv("CORS_MECHANICS_DISABLE_EVENT_STREAM")?.toBooleanStrictOrNull()
    ?: false

@OptIn(ExperimentalTime::class, ExperimentalStdlibApi::class)
fun Application.module(testing: Boolean = false) {
    install(ServerContentNegotiation1) {
        json(Serialisation.json)
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

    install(CallId) {
        header(HttpHeaders.XRequestId)
        generate(10)
    }
    install(CallLogging) {
        level = Level.INFO

        callIdMdc("call-id")
    }

//    install(WebSockets) {
//        pingPeriod = Duration.ofSeconds(60) // Disabled (null) by default
//        timeout = Duration.ofSeconds(15)
//        masking = false
//    }

    val config = environment.config.config("cors-mechanics")
    val proxyConfig = config.config("proxy")

    val globalDefaultCacheSpec = proxyConfig.propertyOrNull("default_cache")?.getString()

    val healthyBody = buildJsonObject {
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

        put("build_time_epoch_ms", BuildConstants.BUILD_TIME_EPOCH)
        put("build_time_epoch", Instant.ofEpochMilli(BuildConstants.BUILD_TIME_EPOCH).toString())
        put("tag", BuildConstants.TAG)
    }

    proxyConfig.configList("hosts")
        .forEach { proxyConfig ->
            val aliasNames = proxyConfig.property("alias").getList()
            val proxyPass = proxyConfig.property("proxy_pass").getString()
            val proxyPassHost = proxyConfig.propertyOrNull("proxy_pass_host")?.getString()

            val passCookies = proxyConfig.propertyOrNull("pass_cookies")?.getString()?.toBooleanStrictOrNull() ?: false

            val defaultCacheSpec = proxyConfig.propertyOrNull("default_cache")?.getString() ?: globalDefaultCacheSpec

            val defaultCacheBuilder = (defaultCacheSpec?.let { Caffeine.from(it) } ?: Caffeine.newBuilder()
                .expireAfterWrite(1, TimeUnit.SECONDS))

            val cache: RequestCache =
                defaultCacheBuilder
                    .buildLoadingKotlin(CorsMechanics) { key ->
                        if (key.source != null && proxyPass == "https://api.blaseball.com")
                            key.source.buildRequest(key, proxyPassHost, passCookies)
                        else {
                            KorneaResult.proxy(true) {
                                http.get("$proxyPass/${key.path}") {
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
                                }
                            }
                        }
                    }

            val pathCaches: MutableMap<String, RequestCache> = ConcurrentHashMap()

            proxyConfig.propertyOrNull("path_caches")?.getList()?.forEach {
                val (path, spec) = it.split(':', limit = 2)

                pathCaches[path] = if (proxyPassHost != null) {
                    Caffeine.from(spec).buildLoadingKotlin(CorsMechanics) { key ->
                        KorneaResult.proxy {
                            http.get("$proxyPass/$key") {
                                header("Host", proxyPassHost)
                            }
                        }
                    }
                } else {
                    Caffeine.from(spec).buildLoadingKotlin(CorsMechanics) { key ->
                        KorneaResult.proxy { http.get("$proxyPass/$key") }
                    }
                }
            }

            aliasNames.forEach { name -> requestCacheBuckets[name] = Pair(cache, pathCaches) }
        }

    setupConvenienceRoutes(http, dataSources)

    routing {
        get("/health") {
            call.respond(HttpStatusCode.OK, healthyBody)
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
                    )?.await()?.getOrThrow() ?: return@get
                )
            }
        }

        route("/api.blaseball.com") { blaseballEventStreamHandler(dataSources) }
        route("/www.blaseball.com") { blaseballEventStreamHandler(dataSources) }
        route("/blaseball.com") { blaseballEventStreamHandler(dataSources) }
        route("/blaseball") { blaseballEventStreamHandler(dataSources) }
    }
}

fun Route.blaseballEventStreamHandler(dataSources: BlaseballDataSource.Instances) {
/*    route("/events") {
        if (DISABLE_EVENT_STREAM) {
            get("/streamData") {
                call.respondText(
                    ": Event Stream temporarily disabled. Contact UnderMybrella#1084 if you need stream data at the moment",
                    contentType = ContentType.Text.EventStream
                )
            }
        } else {
            get("/streamData") {
                val dataSource = dataSources sourceFor call
                call.respondTextWriter(ContentType.Text.EventStream) {
                    dataSource.eventStream!!.relaunchJobIfNeeded().join()

                    val job = dataSource.eventStringStream!!.onEach { data ->
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
        }

        if (DISABLE_EVENT_STREAM) {
            get("/streamData/{path...}") {
                call.respondText(
                    ": Event Stream temporarily disabled. Contact UnderMybrella#1084 if you need stream data at the moment",
                    contentType = ContentType.Text.EventStream
                )
            }
        } else {
            get("/streamData/{path...}") {
                val dataSource = dataSources sourceFor call
                val path = call.parameters.getAll("path") ?: emptyList()

                call.respondTextWriter(ContentType.Text.EventStream) {
                    dataSource.eventStream!!.relaunchJobIfNeeded().join()

                    val job = dataSource.eventStream!!.liveData.onEach { data ->
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
        }

        if (DISABLE_EVENT_STREAM) {
            post("/streamData/jq") {
                call.respondText(
                    ": Event Stream temporarily disabled. Contact UnderMybrella#1084 if you need stream data at the moment",
                    contentType = ContentType.Text.EventStream
                )
            }
        } else {
            post<String>("/streamData/jq") { filter ->
                val dataSource = dataSources sourceFor call
                dataSource.eventStream!!.relaunchJobIfNeeded().join()

                call.respondTextWriter(ContentType.Text.EventStream) {
                    val job = dataSource.eventStringStream!!.onEach { data ->
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
        }

        if (DISABLE_EVENT_STREAM) {
            webSocket("/streamSocket") {}
        } else {
            webSocket("/streamSocket") {
                val dataSource = dataSources sourceFor call
                dataSource.eventStream!!.relaunchJobIfNeeded().join()

                val format = call.request.queryParameters["format"]
                val wait = call.request.queryParameters["wait"]?.toBooleanStrictOrNull() ?: false

                if (format?.equals("kvon", true) == true) {
                    var previous: JsonObject? = null

                    dataSource.eventStream!!.liveData.onEach { data ->
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

                    dataSource.eventStringStream!!
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
    }*/
}