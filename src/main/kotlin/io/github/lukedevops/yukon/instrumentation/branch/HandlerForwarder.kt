package io.github.lukedevops.yukon.instrumentation.branch

/**
 * One entry of the forwarder table (ADR 0035): a pass-through that a framework can report as a
 * handler, and the one probed method it forwards to.
 *
 * The first three fields name the pass-through. The last three name the target. Class names are
 * dotted, as `Class.getName()` spells them. Names and descriptors are exactly as the class file
 * has them.
 */
data class HandlerForwarder(
    val className: String,
    val methodName: String,
    val descriptor: String,
    val targetClassName: String,
    val targetMethodName: String,
    val targetDescriptor: String,
)
