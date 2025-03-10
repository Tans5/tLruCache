package com.tans.tlrucache.memory

class LruIntArrayPool(maxSize: Long) : LruMemoryPool<ArrayKey, LruIntArrayPool.Companion.IntArrayValue>(
    maxSize = maxSize,
    createNewValue = { key -> IntArrayValue(IntArray(key.size)) }
) {

    private val keyPool: LruSimpleKeyPool<ArrayKey> by lazy {
        LruSimpleKeyPool(10) { size -> ArrayKey(size) }
    }

    fun get(byteArraySize: Int): IntArrayValue {
        return get(keyPool.get(byteArraySize))
    }

    fun put(value: IntArrayValue) {
        put(key = keyPool.get(value.size()), value = value)
    }

    companion object {

        class IntArrayValue(val value: IntArray) : IValue {

            override fun size(): Int = value.size * 4

            override fun clear() {
                repeat(value.size) { value[it] = 0 }
            }

            override fun type(): Any  = value.size

            override fun recycle() {  }
        }
    }
}