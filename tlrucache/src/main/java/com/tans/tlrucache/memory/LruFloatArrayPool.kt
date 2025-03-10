package com.tans.tlrucache.memory

class LruFloatArrayPool(
    maxSize: Long,
    createNewValue: (key: ArrayKey) -> FloatArrayValue = { key -> FloatArrayValue(FloatArray(key.size)) }
) : LruMemoryPool<ArrayKey, LruFloatArrayPool.Companion.FloatArrayValue>(
    maxSize = maxSize,
    createNewValue = createNewValue
) {

    private val keyPool: LruSimpleKeyPool<ArrayKey> by lazy {
        LruSimpleKeyPool(10) { size -> ArrayKey(size) }
    }

    fun get(byteArraySize: Int): FloatArrayValue {
        return get(keyPool.get(byteArraySize))
    }

    fun put(value: FloatArrayValue) {
        put(key = keyPool.get(value.size()), value = value)
    }

    companion object {

        class FloatArrayValue(val value: FloatArray) : IValue {

            override fun size(): Int = value.size * 4

            override fun clear() {
                repeat(value.size) { value[it] = 0.0f }
            }

            override fun type(): Any = value.size

            override fun recycle() {}
        }
    }
}