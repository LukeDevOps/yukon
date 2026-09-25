package io.github.lukedevops.yukon.instrumentation.branch

/**
 * One resolved Kotlin `$default` method: a compiler-generated method that fills in the default
 * value for every optional parameter a caller omitted, then calls the real target.
 *
 * [optionalBits] has one set bit per optional parameter, at the parameter's zero-based value
 * index (receivers not counted), read from the `mask & bit` tests found in [defaultName]'s body.
 * [maskParameterIndex] is the position of the first mask `int` among [defaultName]'s own declared
 * parameters, ready to bind through ByteBuddy's parameter offsets.
 *
 * [overridable] is read from [targetName]'s own access flags and its declaring class's: false for
 * a constructor, a static or private or final method, or a method on a final class. An interface
 * target is overridable, since none of those flags apply to it.
 *
 * [parameterNames] maps a set bit in [optionalBits] to the target's own parameter name, read from
 * its `LocalVariableTable`, or to an empty string when the target has no debug info.
 *
 * [defaultLines] maps a set bit in [optionalBits] to its default value's line: the line in effect at
 * the first instruction of that bit's fill block, the code after its `mask & bit` test. That is
 * the last line-number entry at or before the instruction. kotlinc writes no new entry when two
 * defaults share a line, and this rule still gives both that line. A bit maps to -1 when
 * [defaultName] has no line-number table. See ADR 0044.
 *
 * [higherMaskTested] is true when [defaultName]'s body tests a mask `int` beyond the first, which
 * happens only past the 32nd value parameter. Only the first mask `int` is ever bound, so a caller
 * finding this true should log that any bit past the 32nd goes uncounted.
 */
data class DefaultSite(
    val defaultName: String,
    val defaultDescriptor: String,
    val targetName: String,
    val targetDescriptor: String,
    val optionalBits: Int,
    val higherMaskTested: Boolean,
    val overridable: Boolean,
    val maskParameterIndex: Int,
    val parameterNames: Map<Int, String> = emptyMap(),
    val defaultLines: Map<Int, Int> = emptyMap(),
)
