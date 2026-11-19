package com.example.solver

/**
 * Immutable 256-bit bitset backed by 4 Longs for O(1) board mask & DAG parent checks.
 */
data class BitSet256(
    val w0: Long = 0L,
    val w1: Long = 0L,
    val w2: Long = 0L,
    val w3: Long = 0L,
) {
    fun isEmpty(): Boolean = w0 == 0L && w1 == 0L && w2 == 0L && w3 == 0L

    fun hasBit(index: Int): Boolean {
        return when (index ushr 6) {
            0 -> (w0 and (1L shl (index and 63))) != 0L
            1 -> (w1 and (1L shl (index and 63))) != 0L
            2 -> (w2 and (1L shl (index and 63))) != 0L
            3 -> (w3 and (1L shl (index and 63))) != 0L
            else -> false
        }
    }

    fun setBit(index: Int): BitSet256 {
        val bit = 1L shl (index and 63)
        return when (index ushr 6) {
            0 -> copy(w0 = w0 or bit)
            1 -> copy(w1 = w1 or bit)
            2 -> copy(w2 = w2 or bit)
            3 -> copy(w3 = w3 or bit)
            else -> this
        }
    }

    fun clearBit(index: Int): BitSet256 {
        val inv = (1L shl (index and 63)).inv()
        return when (index ushr 6) {
            0 -> copy(w0 = w0 and inv)
            1 -> copy(w1 = w1 and inv)
            2 -> copy(w2 = w2 and inv)
            3 -> copy(w3 = w3 and inv)
            else -> this
        }
    }

    fun intersects(other: BitSet256): Boolean {
        return (w0 and other.w0) != 0L ||
            (w1 and other.w1) != 0L ||
            (w2 and other.w2) != 0L ||
            (w3 and other.w3) != 0L
    }

    fun popCount(): Int {
        return java.lang.Long.bitCount(w0) +
            java.lang.Long.bitCount(w1) +
            java.lang.Long.bitCount(w2) +
            java.lang.Long.bitCount(w3)
    }

    companion object {
        fun fromIndices(indices: Collection<Int>): BitSet256 {
            var bs = BitSet256()
            for (idx in indices) {
                bs = bs.setBit(idx)
            }
            return bs
        }
    }
}
