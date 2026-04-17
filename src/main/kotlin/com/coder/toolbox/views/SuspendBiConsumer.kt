package com.coder.toolbox.views

/**
 * A suspend variant of [java.util.function.BiConsumer] that supports
 * chaining via [andThen].
 */
@FunctionalInterface
fun interface SuspendBiConsumer<T, U> {
    suspend fun accept(first: T, second: U)

    /**
     * Chains this consumer with [next], returning a new [SuspendBiConsumer]
     * that executes both in sequence.
     */

    fun andThen(next: SuspendBiConsumer<T, U>): SuspendBiConsumer<T, U> = SuspendBiConsumer { first, second ->
        this.accept(first, second)
        next.accept(first, second)
    }
}
