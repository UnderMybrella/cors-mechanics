package dev.brella.corsmechanics.baal

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
import io.ktor.client.plugins.cookies.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import org.slf4j.event.Level
import java.io.File
import java.security.MessageDigest
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

typealias BaalTransform<T> = suspend (ProxyResponse<T>) -> ProxyResponse<T>?

object Baal : CoroutineScope {
    val executor = Executors.newCachedThreadPool(NamedThreadFactory("CorsMechanics"))
    val coroutineDispatcher = executor.asCoroutineDispatcher()
    val altar by lazy { Altar(JSON.decodeFromString(File("r2dbc.json").readText())) }

    override val coroutineContext: CoroutineContext =
        SupervisorJob() + coroutineDispatcher

    val requestCacheBuckets: RequestCacheBuckets = ConcurrentHashMap()
    val proxyTransforms: MutableList<BaalTransform<ByteArray>> = ArrayList()

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
            val host = call.parameters.getOrFail("host")
            if (host != "www.blaseball.com") {
                requestCacheBuckets[host]
                    ?.handle(call)
                    ?: call.redirectInternally("www.blaseball.com", call.request.uri)
            } else {
                requestCacheBuckets[host]
                    ?.handle(call)
                    ?: call.respond(HttpStatusCode.NotFound)
            }
        }

    public suspend fun transform(initialResponse: ProxyResponse<ByteArray>): ProxyResponse<ByteArray> =
        proxyTransforms.fold(initialResponse) { response, transform -> transform(response) ?: response }

    public fun addTransform(transform: BaalTransform<ByteArray>) {
        proxyTransforms.add(transform)
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

    install(ClientWebSockets) {
        pingInterval = 20_000
    }

    install(HttpCookies) {
        val cookieStorage = AcceptAllCookiesStorage()
        storage = cookieStorage
    }

    expectSuccess = false

    defaultRequest {
        userAgent("CorsMechanics (Baal)/${BuildConstants.GRADLE_VERSION} (+https://github.com/UnderMybrella/cors-mechanics)")
    }
}

var SELF = "baal.sibr"

fun main(args: Array<String>) = io.ktor.server.netty.EngineMain.main(args)
fun Application.module(testing: Boolean = false) {
    environment.config.propertyOrNull("ktor.deployment.baal")
        ?.getString()
        ?.let { SELF = it }
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
            .executor(Baal.executor)
            .evictionListener<ProxyRequest, ProxyJob> { _, job, _ -> job?.cancel() }
            .buildAsync(ProxyJob::forRequest)
    }

    Baal.registerCacheBucket(
        "api.blaseball.com", "blaseball",
        primaryCache = baseCacheBuilder(),
    ) { https("api.blaseball.com") { _, route -> !route.startsWith("events/", true) } }

    Baal.registerCacheBucket(
        "api2.blaseball.com", "fallball",
        primaryCache = baseCacheBuilder(),
    ) { https("api2.blaseball.com") { _, route -> !route.startsWith("events/", true) } }

    Baal.registerCacheBucket(
        "www.blaseball.com", "web",
        primaryCache = baseCacheBuilder(),
    ) { https("www.blaseball.com") }

    Baal.registerCacheBucket(
        "blaseball-configs.s3.us-west-2.amazonaws.com", "blaseball-configs",
        primaryCache = baseCacheBuilder(),
    ) { https("blaseball-configs.s3.us-west-2.amazonaws.com") }

    Baal.registerCacheBucket(
        "blaseball-icons.s3.us-west-2.amazonaws.com", "blaseball-icons",
        primaryCache = baseCacheBuilder(),
    ) { https("blaseball-icons.s3.us-west-2.amazonaws.com") }

    Baal.registerCacheBucket(
        "pusher.com",
        primaryCache = baseCacheBuilder(),
    ) { https("pusher.com") }

    val textContentTypes = listOf("text/", "/html", "/json", "/javascript")

    Baal.addTransform { response ->
        val contentType = response.headers[HttpHeaders.ContentType] ?: return@addTransform null
        if (textContentTypes.any { contentType.contains(it, true) }) {
            val body = buildString {
                append(response.body.decodeToString())

                //hostNonTLS:t.wsHost+":"+t.wsPort,hostTLS:t.wsHost+":"+t.wssPort
                replaceAll("(.{1,5}?\\.(?:https?|wss?)Host)\\s*\\+\\s*\":\"\\s*\\+\\s*.{1,5}?\\.(?:https?|wss?)Port".toRegex()) { result -> result.groupValues[1] }

                replaceAll("\"ws-\"\\s*\\+\\s*(.+?)\\s*\\+\\s*\".pusher.com\"".toRegex()) { result ->
                    "\"$SELF/ws-\"+${result.groupValues[1]}"
                }

                replaceBaalHost("api.blaseball.com")
                replaceBaalHost("api2.blaseball.com")
                replaceBaalHost("www.blaseball.com")
                replaceBaalHost("blaseball-configs.s3.us-west-2.amazonaws.com")
                replaceBaalHost("blaseball-icons.s3.us-west-2.amazonaws.com")
                replaceBaalHost("pusher.com")
            }.encodeToByteArray()

            return@addTransform ProxyResponse(body, response.status, response.headers, lazy {
                MessageDigest.getInstance("SHA-256")
                    .digest(body)
                    .encodeBase64()
                    .let { "W/\"$it\"" }
            })
        }

        return@addTransform null
    }

    routing {
        route("/ws-{cluster}") {
            webSocket("/app/{app_id}") {
                val cluster = call.parameters.getOrFail("cluster")
                val appID = call.parameters.getOrFail("app_id")
                val serverSession = this
                val path = "app/$appID?${call.request.queryString()}"

                HTTP.webSocket("wss://ws-$cluster.pusher.com/app/$appID", request = {
                    call.request.queryParameters
                        .flattenEntries()
                        .sortedBy(Pair<String, String>::first)
                        .forEach { (k, v) -> url.parameters.append(k, v) }

                    call.request.headers.forEach { name, values ->
                        if (name.equals("Host", true)) return@forEach
                        if (BAD_HEADERS.any { it.equals(name, true) }) return@forEach
                        values.forEach { header(name, it) }
                    }
                }) {
                    val clientSession = this

                    val clientID = Baal.altar.accessLog(
                        "ws-$cluster.pusher.com",
                        path,
                        "WSS",
                        "",
                        call.response.version.toString(),
                        call.response.status.value,
                        call.response.headers,
                        ByteArray(0),
                        call.response.responseTime.timestamp
                    )

                    coroutineScope {
                        clientSession.incoming
                            .receiveAsFlow()
                            .onEach { frame -> Baal.altar.websocketMessage(clientID, frame.data, true) }
                            .onEach(serverSession.outgoing::send)
                            .launchIn(this)

                        serverSession.incoming
                            .receiveAsFlow()
                            .onEach { frame -> Baal.altar.websocketMessage(clientID, frame.data, false) }
                            .onEach(clientSession.outgoing::send)
                            .launchIn(this)
                    }
                }
            }
        }

        route("/{host}") {
            get("/{route...}") { Baal.handle(this) }
            post("/{route...}") { proxy() }
            put("/{route...}") { proxy() }

            patch("/{route...}") { proxy() }
            delete("/{route...}") { proxy() }
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

private suspend fun PipelineContext<Unit, ApplicationCall>.proxy() {
    val host = call.parameters.getOrFail("host")
    val route = call.parameters.getAll("route")
        ?.joinToString("/") ?: "/"

    val response = HTTP.request {
        url("https://$host")
        url { path(route) }
        method = call.request.httpMethod

        call.request.queryParameters
            .flattenEntries()
            .sortedBy(Pair<String, String>::first)
            .forEach { (k, v) -> url.parameters.append(k, v) }

        with(call.response.headers) {
            call.request.headers.forEach { name, values ->
                if (name.equals("Host", true)) return@forEach
                if (dev.brella.corsmechanics.baal.BAD_HEADERS.any { it.equals(name, true) }) return@forEach
                values.forEach { append(name, it, false) }
            }
        }

        header("Host", host)

        setBody(call.receiveChannel().toByteArray())
    }
    val body = response.bodyAsChannel().toByteArray()

    Baal.altar.accessLog(
        response.request.url.host,
        response.request.url.encodedPath,
        response.request.method.value,
        response.contentType()?.toString() ?: "*/*",
        response.version.toString(),
        response.status.value,
        response.headers,
        body,
        response.responseTime.timestamp
    )

    val proxyResponse = Baal.transform(ProxyResponse(body, response.status, response.headers, lazy {
        MessageDigest.getInstance("SHA-256")
            .digest(body)
            .encodeBase64()
            .let { "W/\"$it\"" }
    }))

    call.respondProxied(proxyResponse)
}

private inline fun StringBuilder.replaceBaalHost(host: String) =
    replaceAll(host, "$SELF/$host")

private inline fun StringBuilder.replaceAll(from: String, to: String) {
    var index = indexOf(from)
    while (index != -1) {
        replace(index, index + from.length, to)
        index = indexOf(from, index + to.length)
    }
}

private inline fun StringBuilder.replaceAll(from: Regex, to: (MatchResult) -> String) {
    var result = from.find(this)
    while (result != null) {
        val replacement = to(result)
        replace(result.range.first, result.range.last + 1, replacement)
        result = from.find(this, result.range.first + 1 + replacement.length)
    }
}