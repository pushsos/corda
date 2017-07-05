@file:Suppress("unused")

package net.corda.core

import com.google.common.base.Throwables
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import org.slf4j.Logger
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.*
import java.nio.file.attribute.FileAttribute
import java.time.Duration
import java.time.temporal.Temporal
import java.util.*
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.stream.IntStream
import java.util.stream.Stream
import java.util.stream.StreamSupport
import kotlin.reflect.KProperty

// This file contains utilities that are only relevant in Kotlin. For stuff that's also applicable to Java put it in Utils.kt

val Int.days: Duration get() = Duration.ofDays(this.toLong())
val Int.hours: Duration get() = Duration.ofHours(this.toLong())
val Int.minutes: Duration get() = Duration.ofMinutes(this.toLong())
val Int.seconds: Duration get() = Duration.ofSeconds(this.toLong())
val Int.millis: Duration get() = Duration.ofMillis(this.toLong())

// Simple infix function to add back null safety that the JDK lacks:  timeA until timeB
infix fun Temporal.until(endExclusive: Temporal): Duration = Duration.between(this, endExclusive)

/** Like the + operator but throws an exception in case of integer overflow. */
infix fun Int.checkedAdd(b: Int) = Math.addExact(this, b)

/** Like the + operator but throws an exception in case of integer overflow. */
infix fun Long.checkedAdd(b: Long) = Math.addExact(this, b)

/** Allows you to write code like: Paths.get("someDir") / "subdir" / "filename" but using the Paths API to avoid platform separator problems. */
operator fun Path.div(other: String): Path = resolve(other)
operator fun String.div(other: String): Path = Paths.get(this) / other

val Throwable.rootCause: Throwable get() = Throwables.getRootCause(this)

// An alias that can sometimes make code clearer to read.
val RunOnCallerThread: Executor = MoreExecutors.directExecutor()

/** Same as [Future.get] but with a more descriptive name, and doesn't throw [ExecutionException], instead throwing its cause */
fun <T> Future<T>.getOrThrow(timeout: Duration? = null): T {
    return try {
        if (timeout == null) get() else get(timeout.toNanos(), TimeUnit.NANOSECONDS)
    } catch (e: ExecutionException) {
        throw e.cause!!
    }
}

fun <T> ListenableFuture<T>.then(executor: Executor, body: () -> Unit) = addListener(Runnable(body), executor)

fun <T> ListenableFuture<T>.success(executor: Executor, body: (T) -> Unit) = then(executor) {
    val r = try {
        get()
    } catch(e: Throwable) {
        return@then
    }
    body(r)
}

fun <T> ListenableFuture<T>.failure(executor: Executor, body: (Throwable) -> Unit) = then(executor) {
    try {
        getOrThrow()
    } catch (t: Throwable) {
        body(t)
    }
}

infix fun <T> ListenableFuture<T>.then(body: () -> Unit): ListenableFuture<T> = apply { then(RunOnCallerThread, body) }
infix fun <T> ListenableFuture<T>.success(body: (T) -> Unit): ListenableFuture<T> = apply { success(RunOnCallerThread, body) }
infix fun <T> ListenableFuture<T>.failure(body: (Throwable) -> Unit): ListenableFuture<T> = apply { failure(RunOnCallerThread, body) }
fun ListenableFuture<*>.andForget(log: Logger) = failure(RunOnCallerThread) { log.error("Background task failed:", it) }
@Suppress("UNCHECKED_CAST") // We need the awkward cast because otherwise F cannot be nullable, even though it's safe.
infix fun <F, T> ListenableFuture<F>.map(mapper: (F) -> T): ListenableFuture<T> = Futures.transform(this, { (mapper as (F?) -> T)(it) })
infix fun <F, T> ListenableFuture<F>.flatMap(mapper: (F) -> ListenableFuture<T>): ListenableFuture<T> = Futures.transformAsync(this) { mapper(it!!) }

/** Executes the given block and sets the future to either the result, or any exception that was thrown. */
inline fun <T> SettableFuture<T>.catch(block: () -> T) {
    try {
        set(block())
    } catch (t: Throwable) {
        setException(t)
    }
}

inline fun <R> Path.list(block: (Stream<Path>) -> R): R = Files.list(this).use(block)
fun Path.createDirectory(vararg attrs: FileAttribute<*>): Path = Files.createDirectory(this, *attrs)
fun Path.createDirectories(vararg attrs: FileAttribute<*>): Path = Files.createDirectories(this, *attrs)
fun Path.exists(vararg options: LinkOption): Boolean = Files.exists(this, *options)
fun Path.moveTo(target: Path, vararg options: CopyOption): Path = Files.move(this, target, *options)
fun Path.isRegularFile(vararg options: LinkOption): Boolean = Files.isRegularFile(this, *options)
fun Path.isDirectory(vararg options: LinkOption): Boolean = Files.isDirectory(this, *options)
val Path.size: Long get() = Files.size(this)
fun Path.deleteIfExists(): Boolean = Files.deleteIfExists(this)
inline fun <R> Path.read(vararg options: OpenOption, block: (InputStream) -> R): R {
    return Files.newInputStream(this, *options).use(block)
}
fun Path.readAll(): ByteArray = Files.readAllBytes(this)
fun Path.readAllLines(charset: Charset = StandardCharsets.UTF_8): List<String> = Files.readAllLines(this, charset)
inline fun <R> Path.readLines(charset: Charset = StandardCharsets.UTF_8, block: (Stream<String>) -> R): R {
    return Files.lines(this, charset).use(block)
}
fun Path.writeLines(lines: Iterable<CharSequence>, charset: Charset = StandardCharsets.UTF_8, vararg options: OpenOption): Path {
    return Files.write(this, lines, charset, *options)
}

fun InputStream.copyTo(target: Path, vararg options: CopyOption): Long = Files.copy(this, target, *options)

/**
 * A simple wrapper that enables the use of Kotlin's "val x by TransientProperty { ... }" syntax. Such a property
 * will not be serialized to disk, and if it's missing (or the first time it's accessed), the initializer will be
 * used to set it up. Note that the initializer will be called with the TransientProperty object locked.
 */
class TransientProperty<out T>(private val initializer: () -> T) {
    @Transient private var v: T? = null

    @Synchronized
    operator fun getValue(thisRef: Any?, property: KProperty<*>) = v ?: initializer().also { v = it }
}

private fun IntProgression.spliteratorOfInt(): Spliterator.OfInt {
    val kotlinIterator = iterator()
    val javaIterator = object : PrimitiveIterator.OfInt {
        override fun nextInt() = kotlinIterator.nextInt()
        override fun hasNext() = kotlinIterator.hasNext()
        override fun remove() = throw UnsupportedOperationException("remove")
    }
    val spliterator = Spliterators.spliterator(
            javaIterator,
            (1 + (last - first) / step).toLong(),
            Spliterator.SUBSIZED or Spliterator.IMMUTABLE or Spliterator.NONNULL or Spliterator.SIZED or Spliterator.ORDERED or Spliterator.SORTED or Spliterator.DISTINCT
    )
    return if (step > 0) spliterator else object : Spliterator.OfInt by spliterator {
        override fun getComparator() = Comparator.reverseOrder<Int>()
    }
}

fun IntProgression.stream(): IntStream = StreamSupport.intStream(spliteratorOfInt(), false)

@Suppress("UNCHECKED_CAST") // When toArray has filled in the array, the component type is no longer T? but T (that may itself be nullable).
inline fun <reified T> Stream<out T>.toTypedArray() = toArray { size -> arrayOfNulls<T>(size) } as Array<T>
