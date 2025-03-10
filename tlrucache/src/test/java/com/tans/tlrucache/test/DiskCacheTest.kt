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

    private val baseDir = File("./src/test/testCache", )

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
}