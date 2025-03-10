package com.tans.tlrucache.memory

class LruDoubleArrayPool(
    maxSize: Long,
    createNewValue: (key: ArrayKey) -> DoubleArrayValue = { key -> DoubleArrayValue(DoubleArray(key.size)) }
) : LruMemoryPool<ArrayKey, LruDoubleArrayPool.Companion.DoubleArrayValue>(
    maxSize = maxSize,
    createNewValue = createNewValue
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

            override fun clear() {
                repeat(value.size) { value[it] = 0.0 }
            }

            override fun type(): Any  = value.size

            override fun recycle() {  }
        }
    }
}