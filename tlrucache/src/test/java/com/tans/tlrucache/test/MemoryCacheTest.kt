package com.tans.tlrucache.test

import com.tans.tlrucache.memory.LruByteArrayPool
import org.junit.Test

class MemoryCacheTest {

    @Test
    fun testByteArrayCache() {
        val singleBufferSize = 10
        val maxBufferSize = 100
        val allBuffers: MutableList<ByteArray> = mutableListOf()

        val bufferPool = LruByteArrayPool(maxSize = maxBufferSize.toLong()) {
            val buffer = ByteArray(it.size)
            println("Create new buffer: ${buffer.hashCode()}")
            allBuffers.add(buffer)
            LruByteArrayPool.Companion.ByteArrayValue(buffer)
        }
        val bufferValue1 = bufferPool.get(singleBufferSize)
        assert(bufferValue1.value === allBuffers[0])

        bufferPool.put(bufferValue1)
        val bufferValue2 = bufferPool.get(singleBufferSize)
        assert(bufferValue1 === bufferValue2)

        val bufferValue3 = bufferPool.get(singleBufferSize)
        assert(bufferValue3 !== bufferValue1 && bufferValue3.value === allBuffers[1])
        bufferPool.put(bufferValue2)
        bufferPool.put(bufferValue3)
        val bufferValue4 = bufferPool.get(singleBufferSize)
        assert(bufferValue4 === bufferValue3 && bufferValue4 !== bufferValue2 )
        bufferPool.release()

        bufferPool.put(bufferValue4)
        val bufferValue5 = bufferPool.get(singleBufferSize)
        assert(bufferValue5 !== bufferValue4)

        allBuffers.clear()
    }

    @Test
    fun testByteArrayCacheFull() {
        val singleBufferSize = 10
        val maxBufferSize = 100
        val allBuffers: MutableList<LruByteArrayPool.Companion.ByteArrayValue> = mutableListOf()
        repeat(maxBufferSize * 2 / singleBufferSize) {
            allBuffers.add(LruByteArrayPool.Companion.ByteArrayValue(ByteArray(singleBufferSize)))
        }

        val bufferPool = LruByteArrayPool(maxBufferSize.toLong())
        for (b in allBuffers) {
            bufferPool.put(b)
        }

        repeat(allBuffers.size / 2) {
            val index = allBuffers.size / 2 - 1 - it
            val b1 = allBuffers[index]
            val b2 = bufferPool.get(singleBufferSize)
            assert(b1 === b2)
        }

        repeat(allBuffers.size / 2) {
            val index = allBuffers.size - 1 -it
            val b1 = allBuffers[index]
            val b2 = bufferPool.get(singleBufferSize)
            assert(b1 !== b2)
        }
    }
}