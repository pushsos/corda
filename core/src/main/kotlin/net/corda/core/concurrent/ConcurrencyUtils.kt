package net.corda.core.concurrent

import com.google.common.annotations.VisibleForTesting
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * As soon as a given future becomes done, the handler is invoked with that future as its argument.
 * The result of the handler is copied into the result future, and the handler isn't invoked again.
 * If a given future errors after the result future is done, the error is automatically logged.
 */
fun <S, T> firstOf(vararg futures: CordaFuture<S>, handler: (CordaFuture<S>) -> T) = firstOf(futures, defaultLog, handler)

private val defaultLog = LoggerFactory.getLogger("net.corda.core.concurrent")
@VisibleForTesting
internal val shortCircuitedTaskFailedMessage = "Short-circuited task failed:"

internal fun <S, T> firstOf(futures: Array<out CordaFuture<S>>, log: Logger, handler: (CordaFuture<S>) -> T): CordaFuture<T> {
    val resultFuture = openFuture<T>()
    val winnerChosen = AtomicBoolean()
    futures.forEach {
        it.then {
            if (winnerChosen.compareAndSet(false, true)) {
                resultFuture.catch { handler(it) }
            } else if (it.isCancelled) {
                // Do nothing.
            } else {
                it.match({}, { log.error(shortCircuitedTaskFailedMessage, it) })
            }
        }
    }
    return resultFuture
}

fun <V> Future<V>.get(timeout: Duration? = null): V = if (timeout == null) get() else get(timeout.toNanos(), TimeUnit.NANOSECONDS)

/** Same as [Future.get] except that [ExecutionException] is unwrapped. */
fun <V> Future<V>.getOrThrow(timeout: Duration? = null): V = try {
    get(timeout)
} catch (e: ExecutionException) {
    throw e.cause!!
}

fun <U, V> Future<U>.match(success: (U) -> V, failure: (Throwable) -> V): V {
    return success(try {
        getOrThrow()
    } catch (t: Throwable) {
        return failure(t)
    })
}
