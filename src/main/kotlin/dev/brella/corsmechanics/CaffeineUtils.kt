package dev.brella.corsmechanics

import com.github.benmanes.caffeine.cache.*
import com.sksamuel.aedile.core.caffeineBuilder
import dev.brella.kornea.base.common.getOrNull
import dev.brella.kornea.errors.common.KorneaResult
import dev.brella.kornea.errors.common.consume
import dev.brella.kornea.errors.common.doOnFailure
import dev.brella.kornea.errors.common.getOrThrow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.future.await
import kotlinx.coroutines.future.future
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

typealias KotlinCache<K, V> = Cache<K, Deferred<V>>
typealias KotlinLoadingCache<K, V> = LoadingCache<K, Deferred<V>>

inline fun <K, V> Caffeine<Any, Any>.buildKotlin(): KotlinCache<K, V> = build()
inline fun <K, V> Caffeine<Any, Any>.buildLoadingKotlin(
    scope: CoroutineScope,
    context: CoroutineContext = EmptyCoroutineContext,
    crossinline block: suspend (K) -> V,
): KotlinLoadingCache<K, V> = build { key -> scope.async(context) { block(key) } }

inline fun <K : Any, V : Any> Caffeine<K, V>.buildImplied() = build<K, V>()
inline fun <K, V> Caffeine<Any, Any>.evictionListenerKotlin(noinline func: (key: K?, value: Deferred<V>?, cause: RemovalCause) -> Unit) =
    evictionListener(RemovalListener(func))

suspend fun <K : Any, V> Cache<K, Deferred<V>>.awaitIfPresent(key: K): V? =
    this.getIfPresent(key)?.await()

suspend fun <K : Any, V> Cache<K, Deferred<V>>.await(key: K, mappingFunction: (key: K) -> Deferred<V>?): V =
    this.get(key, mappingFunction).await()

suspend fun <K : Any, V> LoadingCache<K, Deferred<V>>.await(key: K): V =
    this.get(key).await()

suspend fun <K1, K2, V> LoadingCache<Pair<K1, K2>, Deferred<V>>.await(primary: K1, secondary: K2): V =
    this.get(Pair(primary, secondary)).await()

typealias RequestCache = KotlinLoadingCache<ProxyRequest, KorneaResult<ProxiedResponse>>
typealias RequestCacheBuckets = MutableMap<String, Pair<RequestCache, MutableMap<String, RequestCache>>>

val test = caffeineBuilder<String, String> {  }
    .build()
