package dev.brella.sibr.cors

import io.ktor.application.*
import io.ktor.client.request.*
import io.ktor.request.*
import kotlinx.coroutines.future.await
import java.util.concurrent.ConcurrentHashMap

class Proxifier(val primaryCache: RequestCache, val transform: suspend (request: ApplicationRequest, path: String) -> ProxyRequest?) {
    val routeCaches: MutableMap<String, RequestCache> = ConcurrentHashMap()

    suspend fun handle(call: ApplicationCall) {
        val path = call.parameters.getAll("route")?.joinToString("/") ?: "/"
        val job = (routeCaches[path] ?: primaryCache)
            .get(transform(call.request, path))
            .await()

        call.respondProxied(job)
    }
}