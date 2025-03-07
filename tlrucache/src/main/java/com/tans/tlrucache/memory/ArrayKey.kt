package com.tans.tlrucache.memory

class ArrayKey(
    val size: Int
) : IKey {

    override fun hashCode(): Int {
        return size.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        return if (other is ArrayKey) {
            other.size == size
        } else {
            false
        }
    }
}