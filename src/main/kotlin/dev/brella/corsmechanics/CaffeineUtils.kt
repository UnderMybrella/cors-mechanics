package dev.brella.corsmechanics

import com.github.benmanes.caffeine.cache.AsyncCache
import com.github.benmanes.caffeine.cache.AsyncLoadingCache
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.RemovalCause
import com.github.benmanes.caffeine.cache.RemovalListener
import dev.brella.kornea.base.common.getOrNull
import dev.brella.kornea.errors.common.KorneaResult
import dev.brella.kornea.errors.common.consume
import dev.brella.kornea.errors.common.doOnFailure
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

val DISPATCHER_CACHE = Caffeine.newBuilder()
    .expireAfterAccess(10, TimeUnit.MINUTES)
    .buildAsync<Executor, CoroutineDispatcher>(Executor::asCoroutineDispatcher)

typealias KotlinCache<K, V> = Cache<K, Deferred<Pair<KorneaResult<V>, Int?>>>

inline fun <K, V> Caffeine<Any, Any>.buildKotlin(): KotlinCache<K, V> = build()

inline fun <K, V> Caffeine<Any, Any>.buildCoroutines(scope: CoroutineScope = CorsMechanics, context: CoroutineContext = scope.coroutineContext, crossinline loader: suspend (key: K) -> V) =
    buildAsync<K, V> { key, executor ->
        DISPATCHER_CACHE[executor].thenCompose { dispatcher ->
            scope.future(context + dispatcher) {
                try {
                    loader(key)
                } catch (th: Throwable) {
                    th.printStackTrace()
                    throw th
                }
            }
        }
    }

inline fun <K, V> Caffeine<Any, Any>.buildCoroutinesCompounding(scope: CoroutineScope = CorsMechanics, context: CoroutineContext = scope.coroutineContext, crossinline loader: suspend (key: K) -> V) =
    buildAsync<K, V> { key, executor ->
        DISPATCHER_CACHE[executor].thenCompose { dispatcher ->
            scope.future(context + dispatcher) { loader(key) }
        }
    }

inline fun <K : Any, V : Any> Caffeine<K, V>.buildImplied() = build<K, V>()

inline fun <K, V> Caffeine<Any, Any>.installResultEvictionLeader() =
    evictionListenerKotlin<K, V> { k, v, cause ->
        if (v == null) return@evictionListenerKotlin
        if (v.isCompleted) {
            println("Gobbling up $v")
            val (result, code) = v.getCompleted()
            result.consume(code)
        }
    }

inline fun <K, V> Caffeine<Any, Any>.evictionListenerKotlin(noinline func: (key: K?, value: Deferred<Pair<KorneaResult<V>, Int?>>?, cause: RemovalCause) -> Unit) =
    evictionListener(RemovalListener(func))

suspend fun <K : Any, V> KotlinCache<K, V>.getAsync(key: K, scope: CoroutineScope = GlobalScope, context: CoroutineContext = scope.coroutineContext, mappingFunction: suspend (key: K) -> KorneaResult<V>): KorneaResult<V> {
    try {
        val (base, data) = get(key) { k ->
            scope.async(context) {
                val result = mappingFunction(k)

                result to result.dataHashCode().getOrNull()
            }
        }.await()

        val newResult = base.copyOf()

        if (data == null || newResult.isAvailable(data) != true) {
            invalidate(key)

            return getAsync(key, scope, context, mappingFunction)
        }

        return newResult.doOnFailure { invalidate(key) }
    } catch (th: Throwable) {
        invalidate(key)
        throw th
    }
}


suspend fun <K : Any, V : Any> AsyncCache<K, V>.getAsync(key: K, scope: CoroutineScope = GlobalScope, mappingFunction: suspend (key: K) -> V): V {
    return get(key) { key: K, executor: Executor ->
        DISPATCHER_CACHE[executor].thenCompose { dispatcher ->
            scope.future(dispatcher) { mappingFunction(key) }
        }
    }.await()
}

suspend fun <K, V> AsyncCache<K, V>.getAsyncResult(key: K, scope: CoroutineScope = GlobalScope, mappingFunction: suspend (key: K) -> KorneaResult<V>): V =
    get(key) { key: K, executor: Executor ->
        DISPATCHER_CACHE[executor].thenCompose { dispatcher ->
            scope.future(dispatcher) { mappingFunction(key) }
                .thenCompose(KorneaResult<V>::getAsStage)
        }
    }.await()


inline fun <T> KorneaResult<T>.getOrThrow(): T =
    if (this is KorneaResult.Success<T>) get()
    else throw KorneaResultException(this)

inline fun <T> KorneaResult<T>.getAsStage(): CompletionStage<T> =
    consume {
        if (this is KorneaResult.Success<T>) CompletableFuture.completedStage(get())
        else CompletableFuture.failedStage(KorneaResultException(this))
    }

class KorneaResultException(val result: KorneaResult<*>) : Throwable((result as? KorneaResult.WithException<*>)?.exception)

typealias RequestCache = AsyncLoadingCache<ProxyRequest, ProxiedResponse>
typealias RequestCacheBuckets = MutableMap<String, Pair<RequestCache, MutableMap<String, RequestCache>>>

