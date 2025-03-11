package com.tans.tlrucache.test

import com.tans.tlrucache.disk.DiskLruCache
import org.junit.Test
import java.io.File

class DiskCacheTest {

    private val fileKeys = listOf(
        "file1",
        "file2",
        "file3",
        "file4",
        "file5",
        "file6",
        "file7",
        "file8",
        "file9",
        "file10",
    )

    private val baseDir = File("./src/test/testCache")

    private val baseDirtyDir = File("./src/test/testDirtyCache")
    private val dirtyFileKey = "dirtyfile"

    @Test
    fun testDiskCacheWrite() {
        val diskCache = DiskLruCache.open(
            directory = baseDir,
            appVersion = 1,
            valueCount = 1,
            maxSize = 1024L * 5L
        )
        for (key in fileKeys) {
            diskCache.edit(key)?.let { editor ->
                val f = editor.getFile(0)
                if (!f.exists()) {
                    f.createNewFile()
                }
                try {
                    f.outputStream().use { outputStream ->
                        repeat(10) {
                            outputStream.write("${key}.${it},".toByteArray(Charsets.UTF_8))
                        }
                    }
                    editor.commit()
                } catch (e: Throwable) {
                    e.printStackTrace()
                    editor.abort()
                }
            }
        }
        diskCache.close()
    }

    @Test
    fun testDiskCacheRead() {
        val diskCache = DiskLruCache.open(
            directory = baseDir,
            appVersion = 1,
            valueCount = 1,
            maxSize = 1024L * 5L
        )
        for (key in fileKeys) {
            val value = diskCache.get(key)
            if (value != null) {
                println("${key}: ${value.getFile(0).readText(Charsets.UTF_8)}")
            }
        }
        diskCache.close()
    }

    @Test
    fun testMakeDirtyFile() {
        val diskCache = DiskLruCache.open(
            directory = baseDirtyDir,
            appVersion = 1,
            valueCount = 1,
            maxSize = 1024L * 5L,
            deleteDirtyFile = false
        )
        val editor = diskCache.edit(dirtyFileKey)!!
        val file = editor.getFile(0)
        if (!file.exists()) {
            file.createNewFile()
        }
        file.outputStream().use {
            it.write("This is a dirty file test".toByteArray(Charsets.UTF_8))
        }
        // Do not commit to make a dirty file.
    }

    @Test
    fun testInitNotDeleteDirtyFile() {
        val diskCache = DiskLruCache.open(
            directory = baseDirtyDir,
            appVersion = 1,
            valueCount = 1,
            maxSize = 1024L * 5L,
            deleteDirtyFile = false
        )
        val value = diskCache.get(dirtyFileKey)
        if (value != null) {
            val text = value.getFile(0).inputStream().reader(Charsets.UTF_8).use { it.readText() }
            println("Read from dirty file: $text")
        } else {
            println("No dirty file to read.")
        }
    }

    @Test
    fun testInitDeleteDirtyFile() {
        val diskCache = DiskLruCache.open(
            directory = baseDirtyDir,
            appVersion = 1,
            valueCount = 1,
            maxSize = 1024L * 5L
        )
        val value = diskCache.get(dirtyFileKey)
        if (value == null) {
            println("No dirty file.")
        } else {
            println("Contain dirty file.")
        }
        diskCache.close()
    }
}