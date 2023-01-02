package dev.brella.corsmechanics

import dev.brella.kornea.errors.common.KorneaResult
import io.ktor.client.statement.*

public suspend inline fun KorneaResult.Companion.proxy(passCookies: Boolean = false, block: () -> HttpResponse): KorneaResult<ProxiedResponse> =
    KorneaResult.success(ProxiedResponse.proxyFrom(block(), passCookies))