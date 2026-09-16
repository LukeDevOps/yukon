package io.github.lukedevops.yukon.instrumentation.branch

/**
 * One resolved Scala default getter: a compiler-generated, public, non-synthetic method named
 * `f$default$N` that returns the default value for one optional parameter of `f`, called by every
 * omitting call before `f` itself. See ADR 0023.
 *
 * [parameterIndex] is `N - 1`, the target's own zero-based JVM parameter index; unlike Kotlin's
 * omission probes, this counts a Scala extension receiver, since it is an ordinary first JVM
 * parameter and the getter's own `N` counts it too.
 *
 * [parameterName] is read from [targetName]'s `LocalVariableTable` at that parameter's local
 * variable slot, or the empty string when the target carries no debug info.
 *
 * [overridable] is read from [targetName]'s own access flags and its declaring class's: false for
 * a static, private, or final method, or a method on a final class. An interface target is
 * overridable, since none of those flags apply to it.
 *
 * The getter itself keeps its ordinary method-tier probe slot; only its manifest row changes to
 * report this omission instead of an ordinary method hit. No new slot is planted for it.
 */
data class ScalaGetterSite(
    val getterName: String,
    val getterDescriptor: String,
    val targetName: String,
    val targetDescriptor: String,
    val parameterIndex: Int,
    val parameterName: String,
    val overridable: Boolean,
)
