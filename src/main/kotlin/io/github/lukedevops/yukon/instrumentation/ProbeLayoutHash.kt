package io.github.lukedevops.yukon.instrumentation

/** FNV-1a over a class's sorted method signatures, so declaration-order shuffles don't count as a layout change. */
object ProbeLayoutHash {
    private const val OFFSET_BASIS = -3750763034362895579L // FNV-1a 64-bit offset basis
    private const val PRIME = 1099511628211L // FNV-1a 64-bit prime

    fun of(signatures: List<String>): Long {
        var hash = OFFSET_BASIS
        for (signature in signatures.sorted()) {
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
