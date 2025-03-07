package com.tans.tlrucache.memory.internal

import com.tans.tlrucache.memory.IKey
import com.tans.tlrucache.memory.IValue
import java.util.NavigableMap
import java.util.TreeMap

internal class SizeStrategy<Key : IKey, Value : IValue>  {

    private val groupedMap: GroupedLinkedMap<Key, Value> = GroupedLinkedMap()
    private val sortedSizes: MutableMap<Any, NavigableMap<Int, Int>> = HashMap<Any, NavigableMap<Int, Int>>()

    fun put(key: Key, value: Value) {
        val size: Int = value.size()
        groupedMap.put(key, value)

        val sizes = getSizesForType(value.type())
        val current = sizes[size]
        sizes[size] = if (current == null) 1 else current + 1
    }

    fun get(key: Key): Value? {

        val result = groupedMap.get(key)
        if (result != null) {
            // Decrement must be called before reconfigure.
            decrementSize(result)
        }
        return result
    }

    fun removeLast(): Value? {
        val removed: Value? = groupedMap.removeLast()
        if (removed != null) {
            decrementSize(removed)
        }
        return removed
    }

    private fun decrementSize(removed: IValue) {
        val sizes = getSizesForType(removed.type())
        val size = removed.size()
        val current = sizes[removed.size()]
            ?: throw NullPointerException(
                ("Tried to decrement empty size"
                        + ", size: "
                        + size
                        + ", removed"
                        + ", this: "
                        + this)
            )

        if (current == 1) {
            sizes.remove(size)
        } else {
            sizes[size] = current - 1
        }
    }

    private fun getSizesForType(type: Any): NavigableMap<Int, Int> {
        var sizes = sortedSizes[type]
        if (sizes == null) {
            sizes = TreeMap()
            sortedSizes[type] = sizes
        }
        return sizes
    }

    override fun toString(): String {
        val sb: StringBuilder =
            StringBuilder()
                .append("SizeConfigStrategy{groupedMap=")
                .append(groupedMap)
                .append(", sortedSizes=(")
        for ((key, value) in sortedSizes) {
            sb.append(key).append('[').append(value).append("], ")
        }
        if (sortedSizes.isNotEmpty()) {
            sb.replace(sb.length - 2, sb.length, "")
        }
        return sb.append(")}").toString()
    }
}