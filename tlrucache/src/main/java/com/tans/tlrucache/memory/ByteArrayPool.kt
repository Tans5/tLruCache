package com.tans.tlrucache.memory

import java.util.Arrays

class ByteArrayPool(maxSize: Long) : LruMemoryPool<ByteArrayPool.Companion.ByteArrayKey, ByteArrayPool.Companion.ByteArrayValue>(
    maxSize = maxSize,
    createNewValue = { key -> ByteArrayValue(ByteArray(key.size)) }
) {

    fun get(byteArraySize: Int): ByteArrayValue {
        return get(ByteArrayKey(byteArraySize))
    }

    fun put(value: ByteArrayValue) {
        return put(key = ByteArrayKey(value.value.size), value = value)
    }

    companion object {

        class ByteArrayKey(
            val size: Int
        ) : IKey {

            override fun hashCode(): Int {
                return size.hashCode()
            }

            override fun equals(other: Any?): Boolean {
                return if (other is ByteArrayKey) {
                    other.size == size
                } else {
                    false
                }
            }
        }

        class ByteArrayValue(val value: ByteArray) : IValue {

            override fun size(): Int = value.size

            override fun clear() { Arrays.fill(value, 0x00) }

            override fun recycle() {  }
        }
    }
}