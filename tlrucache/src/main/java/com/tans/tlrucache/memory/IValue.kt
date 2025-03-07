package com.tans.tlrucache.memory

interface IValue {

    fun size(): Int

    fun type(): Any = Unit

    fun clear() {}

    fun recycle()
}