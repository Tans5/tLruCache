package com.tans.tlrucache.memory

import java.util.Arrays

class LruByteArrayPool(
    maxSize: Long,
    createNewValue: (key: ArrayKey) -> ByteArrayValue = { key -> ByteArrayValue(ByteArray(key.size)) }
) : LruMemoryPool<ArrayKey, LruByteArrayPool.Companion.ByteArrayValue>(
    maxSize = maxSize,
    createNewValue = createNewValue
) {

    private val keyPool: LruSimpleKeyPool<ArrayKey> by lazy {
        LruSimpleKeyPool(10) { size -> ArrayKey(size) }
    }

    fun get(byteArraySize: Int): ByteArrayValue {
        return get(keyPool.get(byteArraySize))
    }

    fun put(value: ByteArrayValue) {
        put(key = keyPool.get(value.size()), value = value)
    }

    companion object {

        class ByteArrayValue(val value: ByteArray) : IValue {

            override fun size(): Int = value.size

            override fun clear() { Arrays.fill(value, 0x00) }

            override fun type(): Any  = value.size

            override fun recycle() {  }
        }
    }
}