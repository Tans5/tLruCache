package com.tans.tlrucache.memory

class LruDoubleArrayPool(maxSize: Long) : LruMemoryPool<ArrayKey, LruDoubleArrayPool.Companion.DoubleArrayValue>(
    maxSize = maxSize,
    createNewValue = { key -> DoubleArrayValue(DoubleArray(key.size)) }
) {

    private val keyPool: LruSimpleKeyPool<ArrayKey> by lazy {
        LruSimpleKeyPool(10) { size -> ArrayKey(size) }
    }

    fun get(byteArraySize: Int): DoubleArrayValue {
        return get(keyPool.get(byteArraySize))
    }

    fun put(value: DoubleArrayValue) {
        put(key = keyPool.get(value.size()), value = value)
    }

    companion object {

        class DoubleArrayValue(val value: DoubleArray) : IValue {

            override fun size(): Int = value.size * 8

            override fun clear() {  }

            override fun type(): Any  = value.size

            override fun recycle() {  }
        }
    }
}