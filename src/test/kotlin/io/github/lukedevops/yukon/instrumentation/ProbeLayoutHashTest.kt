package io.github.lukedevops.yukon.instrumentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ProbeLayoutHashTest {
    @Test
    fun `same signatures produce the same hash`() {
        val signatures = listOf("bar()V", "foo(I)Z")

        assertEquals(ProbeLayoutHash.of(signatures), ProbeLayoutHash.of(signatures))
    }

    @Test
    fun `hash is independent of declaration order`() {
        val a = listOf("bar()V", "foo(I)Z", "baz()I")
        val b = listOf("foo(I)Z", "baz()I", "bar()V")

        assertEquals(ProbeLayoutHash.of(a), ProbeLayoutHash.of(b))
    }

    @Test
    fun `adding a method changes the hash`() {
        val before = listOf("bar()V")
        val after = listOf("bar()V", "foo(I)Z")

        assertNotEquals(ProbeLayoutHash.of(before), ProbeLayoutHash.of(after))
    }

    @Test
    fun `an empty signature list hashes to a stable constant`() {
        assertEquals(ProbeLayoutHash.of(emptyList()), ProbeLayoutHash.of(emptyList()))
    }
}
