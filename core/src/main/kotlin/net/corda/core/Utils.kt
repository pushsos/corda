@file:JvmName("Utils")

package net.corda.core

import com.google.common.util.concurrent.AbstractFuture
import com.google.common.util.concurrent.ListenableFuture
import net.corda.core.crypto.SecureHash
import net.corda.core.crypto.newSecureRandom
import net.corda.core.crypto.sha256
import org.slf4j.Logger
import rx.Observable
import rx.Observer
import rx.subjects.PublishSubject
import rx.subjects.UnicastSubject
import java.io.*
import java.math.BigDecimal
import java.nio.file.CopyOption
import java.nio.file.Files
import java.nio.file.OpenOption
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Future
import java.util.function.BiConsumer
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.reflect.KClass

fun String.abbreviate(maxWidth: Int): String = if (length <= maxWidth) this else take(maxWidth - 1) + "…"

/**
 * Returns a random positive long generated using a secure RNG. This function sacrifies a bit of entropy in order to
 * avoid potential bugs where the value is used in a context where negative numbers are not expected.
 */
fun random63BitValue(): Long = Math.abs(newSecureRandom().nextLong())

fun <T> future(block: () -> T): ListenableFuture<T> = CompletableToListenable(CompletableFuture.supplyAsync(block))

private class CompletableToListenable<T>(private val base: CompletableFuture<T>) : Future<T> by base, ListenableFuture<T> {
    override fun addListener(listener: Runnable, executor: Executor) {
        base.whenCompleteAsync(BiConsumer { _, _ -> listener.run() }, executor)
    }
}

fun <A> ListenableFuture<out A>.toObservable(): Observable<A> {
    return Observable.create { subscriber ->
        success {
            subscriber.onNext(it)
            subscriber.onCompleted()
        } failure {
            subscriber.onError(it)
        }
    }
}

fun Path.copyToDirectory(targetDir: Path, vararg options: CopyOption): Path {
    require(targetDir.isDirectory()) { "$targetDir is not a directory" }
    val targetFile = targetDir.resolve(fileName)
    Files.copy(this, targetFile, *options)
    return targetFile
}

inline fun Path.write(createDirs: Boolean = false, vararg options: OpenOption = emptyArray(), block: (OutputStream) -> Unit) {
    if (createDirs) {
        normalize().parent?.createDirectories()
    }
    Files.newOutputStream(this, *options).use(block)
}

/** Returns the index of the given item or throws [IllegalArgumentException] if not found. */
fun <T> List<T>.indexOfOrThrow(item: T): Int {
    val i = indexOf(item)
    require(i != -1)
    return i
}

/**
 * Returns the single element matching the given [predicate], or `null` if element was not found,
 * or throws if more than one element was found.
 */
fun <T> Iterable<T>.noneOrSingle(predicate: (T) -> Boolean): T? {
    var single: T? = null
    for (element in this) {
        if (predicate(element)) {
            if (single == null) {
                single = element
            } else throw IllegalArgumentException("Collection contains more than one matching element.")
        }
    }
    return single
}

/** Returns single element, or `null` if element was not found, or throws if more than one element was found. */
fun <T> Iterable<T>.noneOrSingle(): T? {
    var single: T? = null
    for (element in this) {
        if (single == null) {
            single = element
        } else throw IllegalArgumentException("Collection contains more than one matching element.")
    }
    return single
}

/** Returns a random element in the list, or null if empty */
fun <T> List<T>.randomOrNull(): T? {
    if (size <= 1) return firstOrNull()
    val randomIndex = (Math.random() * size).toInt()
    return get(randomIndex)
}

/** Returns a random element in the list matching the given predicate, or null if none found */
fun <T> List<T>.randomOrNull(predicate: (T) -> Boolean) = filter(predicate).randomOrNull()

inline fun elapsedTime(block: () -> Unit): Duration {
    val start = System.nanoTime()
    block()
    val end = System.nanoTime()
    return Duration.ofNanos(end - start)
}

// TODO: Add inline back when a new Kotlin version is released and check if the java.lang.VerifyError
// returns in the IRSSimulationTest. If not, commit the inline back.
fun <T> logElapsedTime(label: String, logger: Logger? = null, body: () -> T): T {
    // Use nanoTime as it's monotonic.
    val now = System.nanoTime()
    try {
        return body()
    } finally {
        val elapsed = Duration.ofNanos(System.nanoTime() - now).toMillis()
        if (logger != null)
            logger.info("$label took $elapsed msec")
        else
            println("$label took $elapsed msec")
    }
}

fun <T> Logger.logElapsedTime(label: String, body: () -> T): T = logElapsedTime(label, this, body)

/**
 * Get a valid InputStream from an in-memory zip as required for tests.
 * Note that a slightly bigger than numOfExpectedBytes size is expected.
 */
@Throws(IllegalArgumentException::class)
fun sizedInputStreamAndHash(numOfExpectedBytes: Int): InputStreamAndHash {
    if (numOfExpectedBytes <= 0) throw IllegalArgumentException("A positive number of numOfExpectedBytes is required.")
    val baos = ByteArrayOutputStream()
    ZipOutputStream(baos).use({ zos ->
        val arraySize = 1024
        val bytes = ByteArray(arraySize)
        val n = (numOfExpectedBytes - 1) / arraySize + 1 // same as Math.ceil(numOfExpectedBytes/arraySize).
        zos.setLevel(Deflater.NO_COMPRESSION)
        zos.putNextEntry(ZipEntry("z"))
        for (i in 0 until n) {
            zos.write(bytes, 0, arraySize)
        }
        zos.closeEntry()
    })
    return getInputStreamAndHashFromOutputStream(baos)
}

/** Convert a [ByteArrayOutputStream] to [InputStreamAndHash]. */
fun getInputStreamAndHashFromOutputStream(baos: ByteArrayOutputStream): InputStreamAndHash {
    // TODO: Consider converting OutputStream to InputStream without creating a ByteArray, probably using piped streams.
    val bytes = baos.toByteArray()
    // TODO: Consider calculating sha256 on the fly using a DigestInputStream.
    return InputStreamAndHash(ByteArrayInputStream(bytes), bytes.sha256())
}

data class InputStreamAndHash(val inputStream: InputStream, val sha256: SecureHash.SHA256)

/**
 * Returns an Observable that buffers events until subscribed.
 * @see UnicastSubject
 */
fun <T> Observable<T>.bufferUntilSubscribed(): Observable<T> {
    val subject = UnicastSubject.create<T>()
    val subscription = subscribe(subject)
    return subject.doOnUnsubscribe { subscription.unsubscribe() }
}

/**
 * Copy an [Observer] to multiple other [Observer]s.
 */
fun <T> Observer<T>.tee(vararg teeTo: Observer<T>): Observer<T> {
    val subject = PublishSubject.create<T>()
    subject.subscribe(this)
    teeTo.forEach { subject.subscribe(it) }
    return subject
}

/**
 * Returns a [ListenableFuture] bound to the *first* item emitted by this Observable. The future will complete with a
 * NoSuchElementException if no items are emitted or any other error thrown by the Observable. If it's cancelled then
 * it will unsubscribe from the observable.
 */
fun <T> Observable<T>.toFuture(): ListenableFuture<T> = ObservableToFuture(this)

private class ObservableToFuture<T>(observable: Observable<T>) : AbstractFuture<T>(), Observer<T> {
    private val subscription = observable.first().subscribe(this)
    override fun onNext(value: T) {
        set(value)
    }

    override fun onError(e: Throwable) {
        setException(e)
    }

    override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
        subscription.unsubscribe()
        return super.cancel(mayInterruptIfRunning)
    }

    override fun onCompleted() {}
}

/** Return the sum of an Iterable of [BigDecimal]s. */
fun Iterable<BigDecimal>.sum(): BigDecimal = fold(BigDecimal.ZERO) { a, b -> a + b }

fun codePointsString(vararg codePoints: Int): String {
    val builder = StringBuilder()
    codePoints.forEach { builder.append(Character.toChars(it)) }
    return builder.toString()
}

fun <T> Class<T>.checkNotUnorderedHashMap() {
    if (HashMap::class.java.isAssignableFrom(this) && !LinkedHashMap::class.java.isAssignableFrom(this)) {
        throw NotSerializableException("Map type $this is unstable under iteration. Suggested fix: use LinkedHashMap instead.")
    }
}

interface DeclaredField<T> {
    companion object {
        inline fun <reified T> Any?.declaredField(clazz: KClass<*>, name: String): DeclaredField<T> = declaredField(clazz.java, name)
        inline fun <reified T> Any.declaredField(name: String): DeclaredField<T> = declaredField(javaClass, name)
        inline fun <reified T> Any?.declaredField(clazz: Class<*>, name: String): DeclaredField<T> {
            val javaField = clazz.getDeclaredField(name).apply { isAccessible = true }
            val receiver = this
            return object : DeclaredField<T> {
                override var value
                    get() = javaField.get(receiver) as T
                    set(value) = javaField.set(receiver, value)
            }
        }
    }

    var value: T
}
