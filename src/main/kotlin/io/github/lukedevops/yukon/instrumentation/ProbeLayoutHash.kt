package io.github.lukedevops.yukon.instrumentation

/**
 * FNV-1a over a class's method and branch-site signatures.
 *
 * [io.github.lukedevops.yukon.instrumentation.YukonInstrumentation] hands the signatures to [of]
 * in one specific order: the same order it uses to assign probe array slots. That order has to
 * matter for the hash. Otherwise, two loads of a class with the same signatures, but a different
 * declaration order, would hash the same. [io.github.lukedevops.yukon.registry.ProbeRegistry]
 * would then treat the layout as unchanged, and hand back the old array and old metadata. But the
 * new bytecode wires different slots to different methods and branches. Reusing the old array
 * would silently swap whose hits get attributed to which probe.
 *
 * Hashing in slot-assignment order avoids this. A reorder then produces a different hash, so the
 * registry correctly treats it as a new layout and allocates a fresh array.
 */
object ProbeLayoutHash {
    private const val OFFSET_BASIS = -3750763034362895579L // FNV-1a 64-bit offset basis
    private const val PRIME = 1099511628211L // FNV-1a 64-bit prime

    fun of(signatures: List<String>): Long {
        var hash = OFFSET_BASIS
        for (signature in signatures) {
            for (byte in signature.toByteArray(Charsets.UTF_8)) {
                hash = hash xor (byte.toLong() and 0xff)
                hash *= PRIME
            }
            hash = hash xor 0xffL
            hash *= PRIME
        }
        return hash
    }
}
