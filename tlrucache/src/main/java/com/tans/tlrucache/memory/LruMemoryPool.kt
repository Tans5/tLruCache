package com.tans.tlrucache.memory

import com.tans.tlrucache.memory.internal.SizeStrategy

open class LruMemoryPool<Key : IKey, Value : IValue> constructor (
    val maxSize: Long,
    private val createNewValue: (key: Key) -> Value
) {
    private val strategy: SizeStrategy<Key, Value> = SizeStrategy()

    private var currentSize: Long = 0
    private var hits = 0
    private var misses = 0
    private var puts = 0
    private var evictions = 0
    private var isReleased: Boolean = false
    private val releaseLock: Any = Any()

    fun hitCount(): Long {
        return hits.toLong()
    }

    fun missCount(): Long {
        return misses.toLong()
    }

    fun evictionCount(): Long {
        return evictions.toLong()
    }

    fun getCurrentSize(): Long {
        return currentSize
    }

    @Synchronized
    fun put(key: Key, value: Value) {
        synchronized(releaseLock) {
            if (isReleased) {
                value.recycle()
            } else {
                strategy.put(key, value)
                puts++
                currentSize += value.size()
                evict()
            }
        }
    }

    private fun evict() {
        trimToSize(maxSize)
    }

    fun get(
        key: Key
    ): Value {
        var result: Value? = getDirtyOrNull(key)
        if (result != null) {
            result.clear()
        } else {
            result = createNewValue(key)
        }

        return result
    }

    fun getDirty(
        key: Key
    ): Value {
        var result = getDirtyOrNull(key)
        if (result == null) {
            result = createNewValue(key)
        }
        return result
    }

    @Synchronized
    private fun getDirtyOrNull(
        key: Key
    ): Value? {

        val result: Value? = strategy.get(key)
        if (result == null) {
            misses++
        } else {
            hits++
            currentSize -= result.size()
        }
        return result
    }

    fun clearMemory() {
        trimToSize(0)
    }

    fun release() {
        synchronized(releaseLock) {
            if (!isReleased) {
                isReleased = true
                clearMemory()
            }
        }
    }

    @Synchronized
    private fun trimToSize(size: Long) {
        while (currentSize > size) {
            val removed = strategy.removeLast()

            if (removed == null) {
                currentSize = 0
                return
            }
            currentSize -= removed.size()
            evictions++
            removed.recycle()
        }
    }
}