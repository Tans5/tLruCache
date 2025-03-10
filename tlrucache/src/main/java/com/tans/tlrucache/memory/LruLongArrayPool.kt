package com.tans.tlrucache.memory

class LruLongArrayPool(
    maxSize: Long,
    createNewValue: (key: ArrayKey) -> LongArrayValue = { key -> LongArrayValue(LongArray(key.size)) }
) : LruMemoryPool<ArrayKey, LruLongArrayPool.Companion.LongArrayValue>(
    maxSize = maxSize,
    createNewValue = createNewValue
) {

    private val keyPool: LruSimpleKeyPool<ArrayKey> by lazy {
        LruSimpleKeyPool(10) { size -> ArrayKey(size) }
    }

    fun get(byteArraySize: Int): LongArrayValue {
        return get(keyPool.get(byteArraySize))
    }

    fun put(value: LongArrayValue) {
        put(key = keyPool.get(value.size()), value = value)
    }

    companion object {

        class LongArrayValue(val value: LongArray) : IValue {

            override fun size(): Int = value.size * 8

            override fun clear() {
                repeat(value.size) { value[it] = 0 }
            }

            override fun type(): Any  = value.size

            override fun recycle() {  }
        }
    }
}