package dev.brella.corsmechanics.cors

import io.ktor.client.request.*
import io.ktor.client.request.request
import io.ktor.client.statement.*
import io.ktor.client.utils.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.util.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.security.MessageDigest

val BAD_HEADERS = listOf(
    HttpHeaders.StrictTransportSecurity,
    HttpHeaders.TransferEncoding,
    HttpHeaders.ContentEncoding,
    HttpHeaders.ContentLength,
    HttpHeaders.AcceptRanges,
    HttpHeaders.AccessControlAllowHeaders,
    HttpHeaders.AccessControlAllowMethods,
    HttpHeaders.AccessControlAllowOrigin,
    "Content-Security-Policy",
    "X-XSS-Protection",
    "X-Frame-Options"
).mapTo(HashSet(), String::lowercase)

class ProxyJob(val request: HttpRequestBuilder) {
    companion object {
        fun forRequest(proxyRequest: ProxyRequest): ProxyJob =
            ProxyJob(request {
                url(proxyRequest.baseUrl)
                url { path(proxyRequest.path) }
                proxyRequest.parameters.forEach { (k, v) -> url.parameters.append(k, v) }

                header("Host", proxyRequest.host)
            })
    }

    private val _state: MutableStateFlow<ProxyResponse<ByteArray>?> = MutableStateFlow(null)
    val state: StateFlow<ProxyResponse<ByteArray>?> = _state.asStateFlow()

    var hasRead: Boolean = false

    suspend inline fun read() =
        try {
            state.firstOrNull { it != null }!!
        } finally {
            hasRead = true
        }

    val job = CorsMechanics.launch {
        var etag: String? = null

        do {
            hasRead = false

            val response = HTTP.request {
                takeFrom(request)

                if (etag != null) header(HttpHeaders.IfNoneMatch, etag)
            }

            if (response.status != HttpStatusCode.NotModified) {
                val serverETag = response.headers[HttpHeaders.ETag]
                val body = response.bodyAsChannel().toByteArray()
                _state.emit(ProxyResponse(body, response.status, response.headers, serverETag?.let(::lazyOf) ?: lazy {
                    MessageDigest.getInstance("SHA-256")
                        .digest(body)
                        .encodeBase64()
                        .let { "W/\"$it\"" }
                }))
                etag = serverETag
            }

            delay(15_000L)
        } while (isActive && hasRead)
    }

    fun cancel() {
        job.cancel()
        _state.tryEmit(null)
    }
}

data class ProxyRequest(
    val host: String,
    val baseUrl: String,
    val path: String,
    val parameters: List<Pair<String, String>>,
)

data class ProxyResponse<T>(
    val body: T,
    val status: HttpStatusCode,
    val headers: StringValues,
    val etag: Lazy<String>,
)

suspend inline fun ApplicationCall.respondProxied(proxyJob: ProxyJob) =
    respondProxied(proxyJob.read())

suspend inline fun ApplicationCall.respondProxied(proxyJob: ProxyJob, extraHeaders: Headers) =
    respondProxied(proxyJob.read(), extraHeaders)

suspend inline fun <reified T : Any> ApplicationCall.respondProxied(proxiedResponse: ProxyResponse<T>) {
    with(this.response.headers) {
        proxiedResponse.headers.forEach { name, values ->
            if (BAD_HEADERS.any { it.equals(name, true) }) return@forEach
            values.forEach { append(name, it, false) }
        }
    }

    val etag = request.header(HttpHeaders.IfNoneMatch)
    if (etag != null && etag == proxiedResponse.etag.value) {
        respond(HttpStatusCode.NotModified, EmptyContent)
    } else {
        if (HttpHeaders.ETag !in response.headers) response.header(HttpHeaders.ETag, proxiedResponse.etag.value)
        respond(proxiedResponse.status, proxiedResponse.body)
    }
}

suspend inline fun <reified T : Any> ApplicationCall.respondProxied(
    proxiedResponse: ProxyResponse<T>,
    extraHeaders: Headers,
) {
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