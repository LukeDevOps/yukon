package io.github.lukedevops.yukon.instrumentation.endpoints

import io.github.lukedevops.yukon.instrumentation.branch.HandlerForwarder
import io.github.lukedevops.yukon.registry.HandlerRef
import java.util.concurrent.ConcurrentHashMap

/**
 * The forwarder table (ADR 0035). It maps a pass-through that a framework can report as a handler
 * to the one probed method it forwards to.
 *
 * The method tier's analysis writes entries, and only for the functional interfaces in
 * [handlerInterfaces]. [RegistryResolver] reads them, so every endpoint module gets the collapse
 * without any change to its advice. The table stays on the agent's own loader and is never sent.
 *
 * Two writes for one pass-through that name different targets leave no entry for it. That happens
 * when two loaders define one class name from different bytes. Neither target is then a fact about
 * the handler, and a miss keeps the name the framework reported.
 */
class HandlerForwarders(
    /** Functional interfaces a framework takes a handler as, by `Class.getName()`. Empty turns the table off. */
    val handlerInterfaces: Set<String> = emptySet(),
) {
    private data class Key(
        val className: String,
        val methodName: String,
        val descriptor: String,
    )

    private val targets = ConcurrentHashMap<Key, HandlerRef>()

    /** Records [forwarder]. See the class comment for what a disagreeing write does. */
    fun record(forwarder: HandlerForwarder) {
        val target = HandlerRef(forwarder.targetClassName, forwarder.targetMethodName, forwarder.targetDescriptor)
        targets.merge(Key(forwarder.className, forwarder.methodName, forwarder.descriptor), target) { old, new ->
            if (old == new) old else CONFLICT
        }
    }

    /**
     * The method [handler] forwards to, when the table has an entry for its class, method and
     * descriptor. Anything else comes back unchanged, including a handler reported without a
     * method or a descriptor.
     */
    fun collapse(handler: HandlerRef): HandlerRef {
        val methodName = handler.methodName ?: return handler
        val descriptor = handler.descriptor ?: return handler
        val target = targets[Key(handler.className, methodName, descriptor)] ?: return handler
        return if (target === CONFLICT) handler else target
    }

    private companion object {
        /** Stands in for the entry of a pass-through whose writes disagreed. */
        val CONFLICT = HandlerRef("", null, null)
    }
}
