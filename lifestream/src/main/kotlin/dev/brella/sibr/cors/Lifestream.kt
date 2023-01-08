package dev.brella.sibr.cors

import dev.brella.corsmechanics.common.NamedThreadFactory
import dev.brella.kornea.BuildConstants
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.compression.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.plugins.conditionalheaders.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import org.slf4j.event.Level
import java.security.NoSuchAlgorithmException
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlin.time.ExperimentalTime
import kotlin.time.measureTime
import io.ktor.client.plugins.compression.ContentEncoding as ClientContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation

import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.websocket.WebSockets.Plugin as ServerWebSockets


object Lifestream : CoroutineScope {
    val executor = Executors.newCachedThreadPool(NamedThreadFactory("Lifestream"))
    val coroutineDispatcher = executor.asCoroutineDispatcher()

    override val coroutineContext: CoroutineContext =
        SupervisorJob() + coroutineDispatcher
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

    expectSuccess = false

    defaultRequest {
        userAgent("CorsMechanics/${BuildConstants.GRADLE_VERSION} (+https://github.com/UnderMybrella/cors-mechanics)")
    }
}

@OptIn(ExperimentalTime::class)
fun main(args: Array<String>) {
    if (args.contains("--calibrate") && try {
            SecureRandom.getInstance("NativePRNGNonBlocking")
        } catch (notFound: NoSuchAlgorithmException) {
            null
        } == null
    ) {
        measureTime {
            println("Finding the fastest provider...")
            val randoms = Security.getProviders()
                .flatMap(Provider::getServices)
                .filter { service -> service.type == "SecureRandom" }

            val randomTimes: MutableMap<Provider.Service, kotlin.time.Duration> = HashMap()
            val times = 10
            for (i in 0 until times) {
                randoms.shuffled().forEach { service ->
                    print("Testing ${service.provider.name}#${service.algorithm}...")
                    val taken = measureTime { SecureRandom.getInstance(service.algorithm, service.provider.name).nextBytes(ByteArray(20)) }
                    println(" Done! Took $taken")
                    randomTimes.compute(service) { _, duration -> duration?.plus(taken) ?: taken }
                }
            }

            println("Averages: ")
            val sorted = randomTimes.mapValues { (k, v) -> v / times }
                .entries
                .sortedBy { it.value }
            sorted.forEach { (k, v) -> println("${k.provider.name}#${k.algorithm}: $v") }

            println("Using ${sorted.first().key.algorithm}")

            System.setProperty("io.ktor.random.secure.random.provider", sorted.first().key.algorithm)
//        Security.setProperty("securerandom.drbg.config", "HMAC_DRBG,SHA-512,256,pr_and_reseed")
        }.also { println("Calibration took $it") }
    }

    io.ktor.server.netty.EngineMain.main(args)
}
fun Application.module(testing: Boolean = false) {
    install(ServerContentNegotiation) {
        json(JSON)
    }

    install(CORS) {
        anyHost()
    }

    install(ConditionalHeaders)
    install(StatusPages) {
        exception<Throwable> { call, cause -> call.respond(HttpStatusCode.InternalServerError, cause.stackTraceToString()) }
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

    launch {
//        HTTP.webSocket("wss://ws-us3.pusher.com/app/ddb8c477293f80ee9c63?protocol=7&client=corsmechanics&version=7.0.3&flash=false") {
//            println("Connected!")
//
////            outgoing.send(PusherEvent.subscribe("", "sim-data"))
////            outgoing.send(PusherEvent.subscribe("", "game-feed-2da22698-70c2-4358-a8c2-dec817bd1190"))
//            //{"event":"pusher:subscribe","data":{"auth":"","channel":}}
//            //{"event":"pusher:subscribe","data":{"auth":"","channel":"game-feed-26c46fd6-fafa-428c-be54-3606b0e4e74e"}}
//            //{"event":"pusher:subscribe","data":{"auth":"","channel":"game-feed-024596e9-8991-40a3-b9ce-966e42a9a383"}}
//            //{"event":"pusher:subscribe","data":{"auth":"","channel":"game-feed-ae266907-eafe-42bf-9ac1-39124badd625"}}
//            //{"event":"pusher:subscribe","data":{"auth":"","channel":"game-feed-b86c620f-a185-429c-9ee6-6d0cc6e42581"}}
//            //{"event":"pusher:subscribe","data":{"auth":"","channel":"game-feed-fe869470-e975-48af-8a7b-e1194bc922bf"}}
//            //{"event":"pusher:subscribe","data":{"auth":"","channel":"game-feed-0e60d95b-4465-437c-95bf-dc6f800c7369"}}
//            //{"event":"pusher:subscribe","data":{"auth":"","channel":"game-feed-afb062ea-7d5f-44cb-9ed4-5edc815ee6b0"}}
//            //{"event":"pusher:subscribe","data":{"auth":"","channel":"game-feed-3d5de00b-4b20-4ff5-9d2d-d3a7be1f7ec6"}}
//            //{"event":"pusher:subscribe","data":{"auth":"","channel":"game-feed-b309fe95-9e24-4613-aaef-421fee26eb5f"}}
//            //{"event":"pusher:subscribe","data":{"auth":"","channel":"game-feed-8acc8f87-88d2-43f0-8031-0e0485074bf4"}}
//            //{"event":"pusher:subscribe","data":{"auth":"","channel":"game-feed-55efd89f-04ac-46fc-82fb-f81fa0892d2c"}}
//
//            frameJob.join()
//
//            println("Outta here")
//        }
    }

    routing {
        get("pusher") {

        }
    }
}