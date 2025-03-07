package com.tans.tlrucache.memory.internal

internal class GroupedLinkedMap<K : Any, V : Any> {
    private val head = LinkedEntry<K, V>()
    private val keyToEntry: MutableMap<K, LinkedEntry<K, V>> = HashMap()

    fun put(key: K, value: V) {
        var entry = keyToEntry[key]

        if (entry == null) {
            entry = LinkedEntry(key)
            makeTail(entry)
            keyToEntry[key] = entry
        }

        entry.add(value)
    }

    fun get(key: K): V? {
        var entry = keyToEntry[key]
        if (entry == null) {
            entry = LinkedEntry(key)
            keyToEntry[key] = entry
        }

        makeHead(entry)

        return entry.removeLast()
    }

    fun removeLast(): V? {
        var last = head.prev

        while (last != head) {
            val removed = last.removeLast()
            if (removed != null) {
                return removed
            } else {
                // We will clean up empty lru entries since they are likely to have been one off or
                // unusual sizes and
                // are not likely to be requested again so the gc thrash should be minimal. Doing so will
                // speed up our
                // removeLast operation in the future and prevent our linked list from growing to
                // arbitrarily large
                // sizes.
                removeEntry(last)
                keyToEntry.remove(last.key)
            }

            last = last.prev
        }

        return null
    }

    override fun toString(): String {
        val sb = StringBuilder("GroupedLinkedMap( ")
        var current = head.next
        var hadAtLeastOneItem = false
        while (current != head) {
            hadAtLeastOneItem = true
            sb.append('{').append(current.key).append(':').append(current.size()).append("}, ")
            current = current.next
        }
        if (hadAtLeastOneItem) {
            sb.delete(sb.length - 2, sb.length)
        }
        return sb.append(" )").toString()
    }

    // Make the entry the most recently used item.
    private fun makeHead(entry: LinkedEntry<K, V>) {
        removeEntry(entry)
        entry.prev = head
        entry.next = head.next
        updateEntry(entry)
    }

    // Make the entry the least recently used item.
    private fun makeTail(entry: LinkedEntry<K, V>) {
        removeEntry(entry)
        entry.prev = head.prev
        entry.next = head
        updateEntry(entry)
    }

    private class LinkedEntry<K, V> constructor(val key: K? = null) {

        private var values: MutableList<V>? = null
        var next: LinkedEntry<K, V>
        var prev: LinkedEntry<K, V> = this

        // Used only for the first item in the list which we will treat specially and which will not
        // contain a value.
        init {
            next = prev
        }

        fun removeLast(): V? {
            val valueSize = size()
            return if (valueSize > 0) values!!.removeAt(valueSize - 1) else null
        }

        fun size(): Int {
            return if (values != null) values!!.size else 0
        }

        fun add(value: V) {
            if (values == null) {
                values = ArrayList()
            }
            values!!.add(value)
        }
    }

    companion object {
        private fun <K, V> updateEntry(entry: LinkedEntry<K, V>) {
            entry.next.prev = entry
            entry.prev.next = entry
        }

        private fun <K, V> removeEntry(entry: LinkedEntry<K, V>) {
            entry.prev.next = entry.next
            entry.next.prev = entry.prev
        }
    }
}