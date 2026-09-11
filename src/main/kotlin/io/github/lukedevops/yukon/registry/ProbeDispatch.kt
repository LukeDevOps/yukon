package io.github.lukedevops.yukon.registry

import java.util.concurrent.ConcurrentHashMap

/**
 * The hot-path lookup instrumented bytecode calls into on every probe hit: a
 * single [ConcurrentHashMap] keyed by a string built from the class, method
 * and descriptor, resolving to the exact array slot a matching
 * [ProbeRegistry] entry already owns.
 */
class ProbeDispatch {

    companion object {
        @JvmField
        val INSTANCE = ProbeDispatch()
    }

    private data class Slot(val counts: LongArray, val index: Int) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Slot

            if (index != other.index) return false
            if (!counts.contentEquals(other.counts)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = index
            result = 31 * result + counts.contentHashCode()
            return result
        }
    }

    private val slots = ConcurrentHashMap<String, Slot>()

    fun register(key: String, counts: LongArray, index: Int) {
        slots[key] = Slot(counts, index)
    }

    fun hit(key: String) {
        slots[key]?.let { it.counts[it.index]++ }
    }
}
