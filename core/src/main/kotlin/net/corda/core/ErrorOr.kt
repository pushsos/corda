package net.corda.core

import net.corda.core.serialization.CordaSerializable

/** Representation of an operation that may have thrown an error. */
@Suppress("DataClassPrivateConstructor")
@CordaSerializable
data class ErrorOr<out A> private constructor(val value: A?, val error: Throwable?) {
    // The ErrorOr holds a value iff error == null
    constructor(value: A) : this(value, null)

    companion object {
        /** Runs the given lambda and wraps the result. */
        inline fun <T> catch(body: () -> T): ErrorOr<T> {
            return try {
                ErrorOr(body())
            } catch (t: Throwable) {
                of(t)
            }
        }

        fun of(t: Throwable) = ErrorOr(null, t)
    }

    fun <T> match(onValue: (A) -> T, onError: (Throwable) -> T): T {
        if (error == null) {
            return onValue(value as A)
        } else {
            return onError(error)
        }
    }

    fun getOrThrow(): A {
        if (error == null) {
            return value as A
        } else {
            throw error
        }
    }

    // Functor
    fun <B> map(function: (A) -> B) = ErrorOr(value?.let(function), error)

    // Applicative
    fun <B, C> combine(other: ErrorOr<B>, function: (A, B) -> C): ErrorOr<C> {
        val newError = error ?: other.error
        return ErrorOr(if (newError != null) null else function(value as A, other.value as B), newError)
    }

    // Monad
    fun <B> bind(function: (A) -> ErrorOr<B>): ErrorOr<B> {
        return if (error == null) {
            function(value as A)
        } else {
            of(error)
        }
    }

    fun mapError(function: (Throwable) -> Throwable) = ErrorOr(value, error?.let(function))
}