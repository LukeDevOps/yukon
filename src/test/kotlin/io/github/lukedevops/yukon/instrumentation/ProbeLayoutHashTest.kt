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
    fun `reordering the same signatures changes the hash`() {
        // Probe slots are assigned positionally, in this same list's order (see
        // YukonInstrumentation), so a reorder must count as a layout change. Otherwise a
        // retransformed class with methods declared in a different order would reuse the old
        // array and metadata, even though its bytecode wires different slots to different
        // probes - silently swapping whose hits land where.
        val a = listOf("bar()V", "foo(I)Z", "baz()I")
        val b = listOf("foo(I)Z", "baz()I", "bar()V")

        assertNotEquals(ProbeLayoutHash.of(a), ProbeLayoutHash.of(b))
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

    @Test
    fun `a changed optional parameter set changes the hash`() {
        // YukonInstrumentation folds one entry per default site into the layout hash, of the
        // shape "<defaultName><defaultDescriptor>#optional<bits>". A bit added or removed must
        // count as a layout change, the same as a method or branch site being added or removed.
        val before = listOf("f\$default(IILjava/lang/Object;)I#optional2")
        val after = listOf("f\$default(IILjava/lang/Object;)I#optional6")

        assertNotEquals(ProbeLayoutHash.of(before), ProbeLayoutHash.of(after))
    }
}
