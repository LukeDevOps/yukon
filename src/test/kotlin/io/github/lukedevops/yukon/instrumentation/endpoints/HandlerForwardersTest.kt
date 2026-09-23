package io.github.lukedevops.yukon.instrumentation.endpoints

import io.github.lukedevops.yukon.instrumentation.branch.HandlerForwarder
import io.github.lukedevops.yukon.registry.HandlerRef
import kotlin.test.Test
import kotlin.test.assertEquals

class HandlerForwardersTest {
    private val handle = "(Lcom/sun/net/httpserver/HttpExchange;)V"

    @Test
    fun `a recorded pass-through collapses to its target, and anything else is returned as it came`() {
        val forwarders = HandlerForwarders()
        forwarders.record(HandlerForwarder("a.Ref", "handle", handle, "a.HandlersKt", "handleOrder", handle))

        assertEquals(HandlerRef("a.HandlersKt", "handleOrder", handle), forwarders.collapse(HandlerRef("a.Ref", "handle", handle)))
        assertEquals(HandlerRef("a.Ref", "invoke", handle), forwarders.collapse(HandlerRef("a.Ref", "invoke", handle)))
        assertEquals(HandlerRef("a.Ref", "handle", "()V"), forwarders.collapse(HandlerRef("a.Ref", "handle", "()V")))
        assertEquals(HandlerRef("a.Ref", "handle"), forwarders.collapse(HandlerRef("a.Ref", "handle")))
        assertEquals(HandlerRef("a.Ref"), forwarders.collapse(HandlerRef("a.Ref")))
    }

    @Test
    fun `the same entry written twice stands, and two entries that disagree leave the name as reported`() {
        val forwarders = HandlerForwarders()
        val toOrder = HandlerForwarder("a.Ref", "handle", handle, "a.HandlersKt", "handleOrder", handle)
        forwarders.record(toOrder)
        forwarders.record(toOrder)
        assertEquals(HandlerRef("a.HandlersKt", "handleOrder", handle), forwarders.collapse(HandlerRef("a.Ref", "handle", handle)))

        // Two loaders can define one class name from different bytes. Neither target is then a fact.
        forwarders.record(HandlerForwarder("a.Ref", "handle", handle, "a.HandlersKt", "handleRefund", handle))
        assertEquals(HandlerRef("a.Ref", "handle", handle), forwarders.collapse(HandlerRef("a.Ref", "handle", handle)))
        forwarders.record(toOrder)
        assertEquals(HandlerRef("a.Ref", "handle", handle), forwarders.collapse(HandlerRef("a.Ref", "handle", handle)))
    }
}
