package dev.brella.corsmechanics

import dev.brella.kornea.errors.common.KorneaResult
import dev.brella.kornea.errors.common.doOnFailure
import dev.brella.kornea.errors.common.doOnSuccess
import dev.brella.kornea.errors.common.doOnThrown
import dev.brella.ktornea.common.KorneaHttpResult
import io.ktor.application.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.response.*
import io.ktor.utils.io.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.reflect.jvm.jvmName

sealed class KorneaResponseResult : KorneaResult.Failure {
    abstract val httpResponseCode: HttpStatusCode
    abstract val contentType: ContentType?

    class UserErrorJson(override val httpResponseCode: HttpStatusCode, val json: JsonElement) : KorneaResponseResult() {
        override val contentType: ContentType = ContentType.Application.Json

        override suspend fun writeTo(call: ApplicationCall) =
            call.respond(httpResponseCode, json)

        override fun copyOf(): KorneaResult<Nothing> =
            this
    }

    override fun get(): Nothing = throw IllegalStateException("Failed Response @ $this")

    abstract suspend fun writeTo(call: ApplicationCall)
}

suspend inline fun KorneaResult<*>.respondOnFailure(call: ApplicationCall) =
    this.doOnFailure { failure ->
        when (failure) {
            is KorneaHttpResult<*> -> {
                call.response.header("X-Call-URL", failure.response.request.url.toURI().toASCIIString())
                call.respondBytesWriter(failure.response.contentType(), failure.response.status) {
                    failure.response.content.copyTo(this)
                }
            }
            is KorneaResponseResult -> failure.writeTo(call)
            else -> {
                call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                    put("error_type", failure::class.jvmName)
                    put("error", failure.toString())

                    failure.doOnThrown { withException -> put("stack_trace", withException.exception.stackTraceToString()) }
                })
            }
        }
    }

suspend inline fun <reified T : Any> KorneaResult<T>.respond(call: ApplicationCall) =
    this.doOnSuccess { call.respond(it) }
        .respondOnFailure(call)

suspend inline fun <T, reified R : Any> KorneaResult<T>.respond(call: ApplicationCall, transform: (T) -> R) =
    this.doOnSuccess { call.respond(transform(it)) }
        .respondOnFailure(call)