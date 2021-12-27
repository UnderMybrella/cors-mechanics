package dev.brella.sibr.cors

import io.ktor.application.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.request.*
import io.ktor.response.*
import kotlinx.coroutines.future.await
import java.util.concurrent.ConcurrentHashMap

class Proxifier(val primaryCache: RequestCache, val transform: suspend (request: ApplicationRequest, path: String) -> ProxyRequest?) {
    val routeCaches: MutableMap<String, RequestCache> = ConcurrentHashMap()

    suspend fun handle(call: ApplicationCall) {
        val path = call.parameters.getAll("route")?.joinToString("/") ?: "/"
        val cache =  (routeCaches[path] ?: primaryCache)
        val request = transform(call.request, path) ?: return call.respondText("No request could be made for $path", status = HttpStatusCode.NotFound)

        val job = cache[request].await()

        call.respondProxied(job)
    }
}