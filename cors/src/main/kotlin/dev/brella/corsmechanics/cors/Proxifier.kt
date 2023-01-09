package dev.brella.corsmechanics.cors

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.util.*
import kotlinx.coroutines.future.await
import java.util.concurrent.ConcurrentHashMap

typealias ProxyTransformation=suspend (request: ApplicationRequest, path: String) -> ProxyRequest?
typealias ProxyFilter=suspend (request: ApplicationRequest, path: String) -> Boolean
class Proxifier(val primaryCache: RequestCache, val transform: ProxyTransformation) {
    companion object {
        public fun https(host: String): ProxyTransformation {
            val baseUrl = "https://$host/"
            return { request, route ->
                ProxyRequest(
                    host,
                    baseUrl,
                    route,
                    request.queryParameters
                        .flattenEntries()
                        .sortedBy(Pair<String, String>::first)
                )
            }
        }

        public fun https(host: String, filter: ProxyFilter): ProxyTransformation {
            val baseUrl = "https://$host/"
            return { request, route ->
                if (filter(request, route)) {
                    ProxyRequest(
                        host,
                        baseUrl,
                        route,
                        request.queryParameters
                            .flattenEntries()
                            .sortedBy(Pair<String, String>::first)
                    )
                } else {
                    null
                }
            }
        }
    }

    val routeCaches: MutableMap<String, RequestCache> = ConcurrentHashMap()

    suspend fun handle(call: ApplicationCall) {
        val path = call.parameters.getAll("route")?.joinToString("/") ?: "/"
        val cache =  (routeCaches[path] ?: primaryCache)
        val request = transform(call.request, path) ?: return call.respondText("No request could be made for $path", status = HttpStatusCode.NotFound)

        val job = cache[request].await()

        call.respondProxied(job)
    }
}