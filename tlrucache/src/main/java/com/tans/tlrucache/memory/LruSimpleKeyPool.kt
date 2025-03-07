package com.tans.tlrucache.memory

import com.tans.tlrucache.memory.internal.GroupedLinkedMap

class LruSimpleKeyPool<Value : IKey>(
    private val maxSize: Long,
    private val createNewValue: (key: Int) -> Value
) {

    private val groupedMap: GroupedLinkedMap<Int, Value> = GroupedLinkedMap()

    private var currentSize = 0

    @Synchronized
    fun put(key: Int, value: Value) {
        groupedMap.put(key, value)
        currentSize++
        trimToSize(currentSize)
    }

    @Synchronized
    fun get(key: Int): Value {
        val result = groupedMap.get(key)
        return if (result != null) {
            currentSize--
            result
        } else {
            createNewValue(key)
        }
    }

    private fun trimToSize(size: Int) {
        while (currentSize > size) {
            val removed = groupedMap.removeLast()
            if (removed == null) {
                currentSize = 0
                return
            }
            currentSize -= 1
        }
    }
}