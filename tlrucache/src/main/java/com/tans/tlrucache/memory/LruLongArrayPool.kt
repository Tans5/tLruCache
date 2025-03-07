package com.tans.tlrucache.memory

class LruLongArrayPool(maxSize: Long) : LruMemoryPool<ArrayKey, LruLongArrayPool.Companion.LongArrayValue>(
    maxSize = maxSize,
    createNewValue = { key -> LongArrayValue(LongArray(key.size)) }
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

            override fun clear() {  }

            override fun type(): Any  = value.size

            override fun recycle() {  }
        }
    }
}