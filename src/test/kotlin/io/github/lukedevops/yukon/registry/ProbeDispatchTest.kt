package io.github.lukedevops.yukon.registry

import kotlin.test.Test
import kotlin.test.assertEquals

class ProbeDispatchTest {
    @Test
    fun `hit increments the registered array slot`() {
        val dispatch = ProbeDispatch()
        val counts = LongArray(3)
        dispatch.register("com.example.Foo#bar()V", counts, 1)

        dispatch.hit("com.example.Foo#bar()V")
        dispatch.hit("com.example.Foo#bar()V")

        assertEquals(longArrayOf(0, 2, 0).toList(), counts.toList())
    }

    @Test
    fun `hit on an unregistered key is a no-op`() {
        val dispatch = ProbeDispatch()

        dispatch.hit("com.example.Unknown#missing()V")
    }

    @Test
    fun `re-registering a key points hits at the new slot`() {
        val dispatch = ProbeDispatch()
        val oldCounts = LongArray(1)
        val newCounts = LongArray(1)
        dispatch.register("com.example.Foo#bar()V", oldCounts, 0)
        dispatch.register("com.example.Foo#bar()V", newCounts, 0)

        dispatch.hit("com.example.Foo#bar()V")

        assertEquals(0L, oldCounts[0])
        assertEquals(1L, newCounts[0])
    }
}
