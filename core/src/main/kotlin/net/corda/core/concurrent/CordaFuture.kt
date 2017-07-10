package net.corda.core.concurrent

import net.corda.core.ErrorOr
import org.slf4j.Logger
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

fun <V> future(block: () -> V): CordaFuture<V> = CordaFutureImpl(CompletableFuture.supplyAsync(block))
fun <V> openFuture(): OpenFuture<V> = CordaFutureImpl()
fun <V> doneFuture(value: V): CordaFuture<V> = CordaFutureImpl<V>().apply { set(value) }
fun <V> Executor.fork(block: () -> V): CordaFuture<V> = CordaFutureImpl<V>().also { execute { it.catch(block) } }

interface CordaFuture<out V> {
    fun cancel(mayInterruptIfRunning: Boolean): Boolean
    val isCancelled: Boolean
    val isDone: Boolean
    fun get(): V
    fun get(timeout: Duration): V
    fun getOrThrow(): V
    fun getOrThrow(timeout: Duration): V
    fun <W> match(success: (V) -> W, failure: (Throwable) -> W): W
    fun <W> then(block: (CordaFuture<V>) -> W)
    fun <W, X> thenMatch(success: (V) -> W, failure: (Throwable) -> X) = then { match(success, failure) }
    fun andForget(log: Logger) = thenMatch({}, { log.error("Background task failed:", it) })
    fun <W> map(transform: (V) -> W): CordaFuture<W> = CordaFutureImpl<W>().also { g ->
        thenMatch({
            g.catch { transform(it) }
        }, {
            g.setException(it)
        })
    }

    fun <W> flatMap(transform: (V) -> CordaFuture<W>): CordaFuture<W> = CordaFutureImpl<W>().also { g ->
        thenMatch({
            ErrorOr.catch { transform(it) }.match({
                it.then {
                    g.catch { it.getOrThrow() }
                }
            }, {
                g.setException(it)
            })
        }, {
            g.setException(it)
        })
    }
}

interface ValueOrException<in V> {
    fun set(value: V): Boolean
    fun setException(t: Throwable): Boolean
    /** Executes the given block and sets the future to either the result, or any exception that was thrown. */
    fun catch(block: () -> V): Boolean {
        return set(try {
            block()
        } catch (t: Throwable) {
            return setException(t)
        })
    }
}

interface OpenFuture<V> : ValueOrException<V>, CordaFuture<V>

private class CordaFutureImpl<V>(private val impl: CompletableFuture<V> = CompletableFuture()) : OpenFuture<V> {
    override fun cancel(mayInterruptIfRunning: Boolean) = impl.cancel(mayInterruptIfRunning)
    override val isCancelled get() = impl.isCancelled
    override val isDone get() = impl.isDone
    override fun get(): V = impl.get()
    override fun get(timeout: Duration): V = impl.get(timeout)
    override fun getOrThrow(): V = impl.getOrThrow()
    override fun getOrThrow(timeout: Duration): V = impl.getOrThrow(timeout)
    override fun <W> match(success: (V) -> W, failure: (Throwable) -> W) = impl.match(success, failure)
    override fun set(value: V) = impl.complete(value)
    override fun setException(t: Throwable) = impl.completeExceptionally(t)
    override fun <W> then(block: (CordaFuture<V>) -> W) {
        impl.whenComplete { _, _ -> block(this) }
    }
}

fun <V> Collection<CordaFuture<V>>.transpose(): CordaFuture<List<V>> = CordaFutureImpl<List<V>>().also { g ->
    val stateLock = Any()
    var failure: Throwable? = null
    var remaining = size
    forEach {
        it.then {
            synchronized(stateLock) {
                it.match({}, {
                    failure?.addSuppressed(it) ?: run { failure = it }
                })
                if (--remaining == 0) {
                    failure?.let { g.setException(it) } ?: run { g.set(map { it.getOrThrow() }) }
                }
            }
        }
    }
}
