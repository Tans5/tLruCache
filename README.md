
A Disk & Memory LRU Cache Library for JVM/Android.  
Inspired by [Glide](https://github.com/bumptech/glide)'s implementation.  
  
[![](https://img.shields.io/maven-metadata/v.svg?label=maven-central&metadataUrl=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Fio%2Fgithub%2Ftans5%2Ftlrucache%2Fmaven-metadata.xml)](https://central.sonatype.com/artifact/io.github.tans5/tlrucache)
  
## Installation

Add the dependency to your build.gradle:
```Groovy
dependencies {
	 // ...
    implementation 'io.github.tans5:tlrucache:1.0.0'
    // ...
}
```

## Usage

### Memory Cache

Initialization
```Kotlin
// Create a pool with maximum size of 1024 bytes
val bytesPool = LruByteArrayPool(1024L)
```

Obtaining and Releasing Buffers

```Kotlin
// Request a 10-byte buffer
val buffer = bytesPool.get(10)
val byteArray = buffer.value

// Use the byte array
// ...

// Return the buffer to the pool
bytesPool.put(buffer)
```

Cleanup

```Kotlin
// Release resources when no longer needed
bytesPool.release()
```

### Disk Cache

Initialization

```Kotlin
val diskCache = DiskLruCache.open(
    directory = baseDir,    // Base directory for cache files
    appVersion = 1,        // App version (used for cache invalidation)
    valueCount = 1,         // Number of files per entry
    maxSize = 5 * 1024L    // Maximum cache size (5KB)
)
```

Writing to Cache

```Kotlin
diskCache.edit(key)?.let { editor ->
    val file = editor.getFile(0).apply {
        if (!exists()) createNewFile()
    }

    try {
        file.outputStream().use { stream ->
            // Write data to the file
        }
        editor.commit()  // Persist changes
    } catch (e: Throwable) {
        editor.abort()   // Discard on failure
    }
}
```

Reading from Cache

```Kotlin
diskCache.get(key)?.let { snapshot ->
    val file = snapshot.getFile(0)
    val bytes = file.readBytes()
    // Process cached data
}
```
Closing the Cache

```Kotlin
diskCache.close()
```



