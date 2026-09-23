package com.example.target

import kotlin.jvm.JvmSerializableLambda

/**
 * The Kotlin body-class shapes ADR 0034 names that [FunctionReferenceTarget],
 * [SuspendLambdaTarget] and [ObjectExpressionTarget] do not already cover. Each method builds one
 * body class. kotlinc names each class after the method and the order the bodies appear in it.
 * kotlinc marks every reference class here synthetic, so each is a pass-through to the function
 * or accessor it names.
 */
class BodyKindTarget {
    val base = 10
    var counter = 0

    fun twice(value: Int): Int = value * 2

    /** kotlinc compiles a reference to a Kotlin function type to a class, not to an `invokedynamic`. */
    fun functionReference(): (Int) -> Int = ::twice

    /** A reference whose `Int` result is dropped to fit a `Unit` function type. */
    fun adaptedFunctionReference(): (Int) -> Unit = ::twice

    fun boundPropertyReference(): () -> Int = this::base

    fun unboundPropertyReference(): (BodyKindTarget) -> Int = BodyKindTarget::base

    fun mutablePropertyReference(): () -> Int = this::counter

    fun localClass(): Runnable {
        class Local : Runnable {
            override fun run() {
                twice(1)
            }
        }
        return Local()
    }

    /** `@JvmSerializableLambda` makes kotlinc compile the lambda to a class, as `-Xlambdas=class` would. */
    fun serializableLambda(): () -> Int = @JvmSerializableLambda { twice(2) }

    fun suspendLambda(): suspend () -> Int = { twice(3) }

    /** `sequence`'s block is a restricted suspend lambda, since `SequenceScope` restricts suspension. */
    fun restrictedSuspendLambda(): Sequence<Int> = sequence { yield(twice(4)) }
}

/** A `fun interface`, so a reference to its constructor has a shape of its own. */
fun interface BodyKindIntOp {
    fun apply(value: Int): Int
}

/**
 * kotlinc compiles this reference to a class extending `FunInterfaceConstructorReference`. Its
 * `invoke` wraps the function value in a second class, named `$sam$` after the interface.
 */
fun funInterfaceConstructorReference(): ((Int) -> Int) -> BodyKindIntOp = ::BodyKindIntOp
