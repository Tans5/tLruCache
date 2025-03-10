package com.tans.tlrucache.disk

import com.tans.tlrucache.disk.DiskLruCache.Editor
import com.tans.tlrucache.disk.internal.StrictLineReader
import com.tans.tlrucache.disk.internal.Util
import java.io.BufferedWriter
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.util.concurrent.Callable
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * A cache that uses a bounded amount of space on a filesystem. Each cache
 * entry has a string key and a fixed number of values. Each key must match
 * the regex **[a-z0-9_-]{1,120}**. Values are byte sequences,
 * accessible as streams or files. Each value must be between `0` and
 * `Integer.MAX_VALUE` bytes in length.
 *
 *
 * The cache stores its data in a directory on the filesystem. This
 * directory must be exclusive to the cache; the cache may delete or overwrite
 * files from its directory. It is an error for multiple processes to use the
 * same cache directory at the same time.
 *
 *
 * This cache limits the number of bytes that it will store on the
 * filesystem. When the number of stored bytes exceeds the limit, the cache will
 * remove entries in the background until the limit is satisfied. The limit is
 * not strict: the cache may temporarily exceed it while waiting for files to be
 * deleted. The limit does not include filesystem overhead or the cache
 * journal so space-sensitive applications should set a conservative limit.
 *
 *
 * Clients call [.edit] to create or update the values of an entry. An
 * entry may have only one editor at one time; if a value is not available to be
 * edited then [.edit] will return null.
 *
 *  * When an entry is being **created** it is necessary to
 * supply a full set of values; the empty value should be used as a
 * placeholder if necessary.
 *  * When an entry is being **edited**, it is not necessary
 * to supply data for every value; values default to their previous
 * value.
 *
 * Every [.edit] call must be matched by a call to [Editor.commit]
 * or [Editor.abort]. Committing is atomic: a read observes the full set
 * of values as they were before or after the commit, but never a mix of values.
 *
 *
 * Clients call [.get] to read a snapshot of an entry. The read will
 * observe the value at the time that [.get] was called. Updates and
 * removals after the call do not impact ongoing reads.
 *
 *
 * This class is tolerant of some I/O errors. If files are missing from the
 * filesystem, the corresponding entries will be dropped from the cache. If
 * an error occurs while writing a cache value, the edit will fail silently.
 * Callers should handle other problems by catching `IOException` and
 * responding appropriately.
 */
class DiskLruCache private constructor(

    /** Returns the directory where this cache stores its data.  */
    /*
          * This cache uses a journal file named "journal". A typical journal file
          * looks like this:
          *     libcore.io.DiskLruCache
          *     1
          *     100
          *     2
          *
          *     CLEAN 3400330d1dfc7f3f7f4b8d4d803dfcf6 832 21054
          *     DIRTY 335c4c6028171cfddfbaae1a9c313c52
          *     CLEAN 335c4c6028171cfddfbaae1a9c313c52 3934 2342
          *     REMOVE 335c4c6028171cfddfbaae1a9c313c52
          *     DIRTY 1ab96a171faeeee38496d8b330771a7a
          *     CLEAN 1ab96a171faeeee38496d8b330771a7a 1600 234
          *     READ 335c4c6028171cfddfbaae1a9c313c52
          *     READ 3400330d1dfc7f3f7f4b8d4d803dfcf6
          *
          * The first five lines of the journal form its header. They are the
          * constant string "libcore.io.DiskLruCache", the disk cache's version,
          * the application's version, the value count, and a blank line.
          *
          * Each of the subsequent lines in the file is a record of the state of a
          * cache entry. Each line contains space-separated values: a state, a key,
          * and optional state-specific values.
          *   o DIRTY lines track that an entry is actively being created or updated.
          *     Every successful DIRTY action should be followed by a CLEAN or REMOVE
          *     action. DIRTY lines without a matching CLEAN or REMOVE indicate that
          *     temporary files may need to be deleted.
          *   o CLEAN lines track a cache entry that has been successfully published
          *     and may be read. A publish line is followed by the lengths of each of
          *     its values.
          *   o READ lines track accesses for LRU.
          *   o REMOVE lines track entries that have been deleted.
          *
          * The journal file is appended to as cache operations occur. The journal may
          * occasionally be compacted by dropping redundant lines. A temporary file named
          * "journal.tmp" will be used during compaction; that file should be deleted if
          * it exists when the cache is opened.
          */val directory: File,
    private val appVersion: Int,
    private val valueCount: Int,
    private var maxSize: Long
) : Closeable {

    private val journalFile: File = File(directory, JOURNAL_FILE)
    private val journalFileTmp: File = File(directory, JOURNAL_FILE_TEMP)
    private val journalFileBackup: File = File(directory, JOURNAL_FILE_BACKUP)

    private var size: Long = 0
    private var journalWriter: Writer? = null
    private val lruEntries = LinkedHashMap<String, Entry>(0, 0.75f, true)
    private var redundantOpCount = 0

    /**
     * To differentiate between old and current snapshots, each entry is given
     * a sequence number each time an edit is committed. A snapshot is stale if
     * its sequence number is not equal to its entry's sequence number.
     */
    private var nextSequenceNumber: Long = 0

    /** This cache uses a single background thread to evict entries.  */
    private val executorService: ThreadPoolExecutor = ThreadPoolExecutor(
        0, 1, 60L, TimeUnit.SECONDS, LinkedBlockingQueue(),
        DiskLruCacheThreadFactory()
    )
    private val cleanupCallable: Callable<Void> = Callable {
        synchronized(this@DiskLruCache) {
            if (journalWriter == null) {
                return@Callable null // Closed.
            }
            trimToSize()
            if (journalRebuildRequired()) {
                rebuildJournal()
                redundantOpCount = 0
            }
        }
        null
    }

    @Throws(IOException::class)
    private fun readJournal() {
        val reader = StrictLineReader(FileInputStream(journalFile), Util.US_ASCII)
        try {
            val magic: String = reader.readLine()
            val version: String = reader.readLine()
            val appVersionString: String = reader.readLine()
            val valueCountString: String = reader.readLine()
            val blank: String = reader.readLine()
            if ((MAGIC != magic) || (VERSION_1 != version) || (appVersion.toString() != appVersionString) || (valueCount.toString() != valueCountString) || ("" != blank)) {
                throw IOException(
                    ("unexpected journal header: [" + magic + ", " + version + ", "
                            + valueCountString + ", " + blank + "]")
                )
            }

            var lineCount = 0
            while (true) {
                try {
                    readJournalLine(reader.readLine())
                    lineCount++
                } catch (endOfJournal: EOFException) {
                    break
                }
            }
            redundantOpCount = lineCount - lruEntries.size

            // If we ended on a truncated line, rebuild the journal before appending to it.
            if (reader.hasUnterminatedLine()) {
                rebuildJournal()
            } else {
                journalWriter = BufferedWriter(
                    OutputStreamWriter(
                        FileOutputStream(journalFile, true), Util.US_ASCII
                    )
                )
            }
        } finally {
            Util.closeQuietly(reader)
        }
    }

    @Throws(IOException::class)
    private fun readJournalLine(line: String) {
        val firstSpace = line.indexOf(' ')
        if (firstSpace == -1) {
            throw IOException("unexpected journal line: $line")
        }

        val keyBegin = firstSpace + 1
        val secondSpace = line.indexOf(' ', keyBegin)
        val key: String
        if (secondSpace == -1) {
            key = line.substring(keyBegin)
            if (firstSpace == REMOVE.length && line.startsWith(REMOVE)) {
                lruEntries.remove(key)
                return
            }
        } else {
            key = line.substring(keyBegin, secondSpace)
        }

        var entry = lruEntries[key]
        if (entry == null) {
            entry = Entry(key)
            lruEntries[key] = entry
        }

        if (secondSpace != -1 && firstSpace == CLEAN.length && line.startsWith(CLEAN)) {
            val parts = line.substring(secondSpace + 1).split(" ".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            entry.readable = true
            entry.currentEditor = null
            entry.setLengths(parts)
        } else if (secondSpace == -1 && firstSpace == DIRTY.length && line.startsWith(DIRTY)) {
            entry.currentEditor = Editor(entry)
        } else if (secondSpace == -1 && firstSpace == READ.length && line.startsWith(READ)) {
            // This work was already done by calling lruEntries.get().
        } else {
            throw IOException("unexpected journal line: $line")
        }
    }

    /**
     * Computes the initial size and collects garbage as a part of opening the
     * cache. Dirty entries are assumed to be inconsistent and will be deleted.
     */
    @Throws(IOException::class)
    private fun processJournal() {
        deleteIfExists(journalFileTmp)
        val i = lruEntries.values.iterator()
        while (i.hasNext()) {
            val entry = i.next()
            if (entry.currentEditor == null) {
                for (t in 0..<valueCount) {
                    size += entry.lengths[t]
                }
            } else {
                // Delete dirty file.
                entry.currentEditor = null
                for (t in 0..<valueCount) {
                    deleteIfExists(entry.getCleanFile(t))
                    deleteIfExists(entry.getDirtyFile(t))
                }
                i.remove()
            }
        }
    }

    /**
     * Creates a new journal that omits redundant information. This replaces the
     * current journal if it exists.
     */
    @Synchronized
    @Throws(IOException::class)
    private fun rebuildJournal() {
        if (journalWriter != null) {
            closeWriter(journalWriter!!)
        }

        val writer: Writer = BufferedWriter(
            OutputStreamWriter(FileOutputStream(journalFileTmp), Util.US_ASCII)
        )
        try {
            writer.write(MAGIC)
            writer.write("\n")
            writer.write(VERSION_1)
            writer.write("\n")
            writer.write(appVersion.toString())
            writer.write("\n")
            writer.write(valueCount.toString())
            writer.write("\n")
            writer.write("\n")

            for (entry in lruEntries.values) {
                if (entry.currentEditor != null) {
                    writer.write(DIRTY + ' ' + entry.key + '\n')
                } else {
                    writer.write(CLEAN + ' ' + entry.key + entry.getLengths() + '\n')
                }
            }
        } finally {
            closeWriter(writer)
        }

        if (journalFile.exists()) {
            renameTo(journalFile, journalFileBackup, true)
        }
        renameTo(journalFileTmp, journalFile, false)
        journalFileBackup.delete()

        journalWriter = BufferedWriter(OutputStreamWriter(FileOutputStream(journalFile, true), Util.US_ASCII))
    }

    /**
     * Returns a snapshot of the entry named `key`, or null if it doesn't
     * exist is not currently readable. If a value is returned, it is moved to
     * the head of the LRU queue.
     */
    @Synchronized
    @Throws(IOException::class)
    fun get(key: String): Value? {
        checkNotClosed()
        val entry = lruEntries[key] ?: return null

        if (!entry.readable) {
            return null
        }

        for (file in entry.cleanFiles) {
            // A file must have been deleted manually!
            if (!file.exists()) {
                return null
            }
        }

        redundantOpCount++
        val journalWriter = this.journalWriter!!
        journalWriter.append(READ)
        journalWriter.append(' ')
        journalWriter.append(key)
        journalWriter.append('\n')
        if (journalRebuildRequired()) {
            executorService.submit(cleanupCallable)
        }

        return Value(key, entry.sequenceNumber, entry.cleanFiles, entry.lengths)
    }

    /**
     * Returns an editor for the entry named `key`, or null if another
     * edit is in progress.
     */
    @Throws(IOException::class)
    fun edit(key: String): Editor? {
        return edit(key, ANY_SEQUENCE_NUMBER)
    }

    @Synchronized
    @Throws(IOException::class)
    private fun edit(key: String, expectedSequenceNumber: Long): Editor? {
        checkNotClosed()
        var entry = lruEntries[key]
        if (expectedSequenceNumber != ANY_SEQUENCE_NUMBER && (entry == null
                    || entry.sequenceNumber != expectedSequenceNumber)
        ) {
            return null // Value is stale.
        }
        if (entry == null) {
            entry = Entry(key)
            lruEntries[key] = entry
        } else if (entry.currentEditor != null) {
            return null // Another edit is in progress.
        }

        val editor = Editor(entry)
        entry.currentEditor = editor

        val journalWriter = this.journalWriter!!
        // Flush the journal before creating files to prevent file leaks.
        journalWriter.append(DIRTY)
        journalWriter.append(' ')
        journalWriter.append(key)
        journalWriter.append('\n')
        flushWriter(journalWriter)
        return editor
    }

    /**
     * Returns the maximum number of bytes that this cache should use to store
     * its data.
     */
    @Synchronized
    fun getMaxSize(): Long {
        return maxSize
    }

    /**
     * Changes the maximum number of bytes the cache can store and queues a job
     * to trim the existing store, if necessary.
     */
    @Synchronized
    fun setMaxSize(maxSize: Long) {
        this.maxSize = maxSize
        executorService.submit(cleanupCallable)
    }

    /**
     * Returns the number of bytes currently being used to store the values in
     * this cache. This may be greater than the max size if a background
     * deletion is pending.
     */
    @Synchronized
    fun size(): Long {
        return size
    }

    @Synchronized
    @Throws(IOException::class)
    private fun completeEdit(editor: Editor, success: Boolean) {
        val entry = editor.entry
        check(entry.currentEditor == editor)

        // If this edit is creating the entry for the first time, every index must have a value.
        if (success && !entry.readable) {
            for (i in 0..< valueCount) {
                if (!editor.written!![i]) {
                    editor.abort()
                    throw IllegalStateException("Newly created entry didn't create value for index $i")
                }
                if (!entry.getDirtyFile(i).exists()) {
                    editor.abort()
                    return
                }
            }
        }

        for (i in 0..< valueCount) {
            val dirty = entry.getDirtyFile(i)
            if (success) {
                if (dirty.exists()) {
                    val clean = entry.getCleanFile(i)
                    dirty.renameTo(clean)
                    val oldLength = entry.lengths[i]
                    val newLength = clean.length()
                    entry.lengths[i] = newLength
                    size = size - oldLength + newLength
                }
            } else {
                deleteIfExists(dirty)
            }
        }

        redundantOpCount++
        entry.currentEditor = null
        val journalWriter = this.journalWriter!!
        if (entry.readable or success) {
            entry.readable = true
            journalWriter.append(CLEAN)
            journalWriter.append(' ')
            journalWriter.append(entry.key)
            journalWriter.append(entry.getLengths())
            journalWriter.append('\n')

            if (success) {
                entry.sequenceNumber = nextSequenceNumber++
            }
        } else {
            lruEntries.remove(entry.key)
            journalWriter.append(REMOVE)
            journalWriter.append(' ')
            journalWriter.append(entry.key)
            journalWriter.append('\n')
        }
        flushWriter(journalWriter)

        if (size > maxSize || journalRebuildRequired()) {
            executorService.submit(cleanupCallable)
        }
    }

    /**
     * We only rebuild the journal when it will halve the size of the journal
     * and eliminate at least 2000 ops.
     */
    private fun journalRebuildRequired(): Boolean {
        val redundantOpCompactThreshold = 2000
        return redundantOpCount >= redundantOpCompactThreshold //
                && redundantOpCount >= lruEntries.size
    }

    /**
     * Drops the entry for `key` if it exists and can be removed. Entries
     * actively being edited cannot be removed.
     *
     * @return true if an entry was removed.
     */
    @Synchronized
    @Throws(IOException::class)
    fun remove(key: String): Boolean {
        checkNotClosed()
        val entry = lruEntries[key]
        if (entry == null || entry.currentEditor != null) {
            return false
        }

        for (i in 0..<valueCount) {
            val file = entry.getCleanFile(i)
            if (file.exists() && !file.delete()) {
                throw IOException("failed to delete $file")
            }
            size -= entry.lengths[i]
            entry.lengths[i] = 0
        }

        redundantOpCount++
        val journalWriter = this.journalWriter!!
        journalWriter.append(REMOVE)
        journalWriter.append(' ')
        journalWriter.append(key)
        journalWriter.append('\n')

        lruEntries.remove(key)

        if (journalRebuildRequired()) {
            executorService.submit(cleanupCallable)
        }

        return true
    }

    @get:Synchronized
    val isClosed: Boolean
        /** Returns true if this cache has been closed.  */
        get() = journalWriter == null

    private fun checkNotClosed() {
        checkNotNull(journalWriter) { "cache is closed" }
    }

    /** Force buffered operations to the filesystem.  */
    @Synchronized
    @Throws(IOException::class)
    fun flush() {
        checkNotClosed()
        trimToSize()
        flushWriter(journalWriter!!)
    }

    /** Closes this cache. Stored values will remain on the filesystem.  */
    @Synchronized
    @Throws(IOException::class)
    override fun close() {
        if (journalWriter == null) {
            return  // Already closed.
        }
        for (entry in ArrayList(lruEntries.values)) {
            if (entry.currentEditor != null) {
                entry.currentEditor!!.abort()
            }
        }
        trimToSize()
        closeWriter(journalWriter!!)
        journalWriter = null
    }

    @Throws(IOException::class)
    private fun trimToSize() {
        while (size > maxSize) {
            val toEvict: Map.Entry<String, Entry> = lruEntries.entries.iterator().next()
            remove(toEvict.key)
        }
    }

    /**
     * Closes the cache and deletes all of its stored values. This will delete
     * all files in the cache directory including files that weren't created by
     * the cache.
     */
    @Throws(IOException::class)
    fun delete() {
        close()
        Util.deleteContents(directory)
    }

    /** A snapshot of the values for an entry.  */
    inner class Value internal constructor(
        private val key: String,
        private val sequenceNumber: Long,
        private val files: Array<File>,
        private val lengths: LongArray
    ) {
        /**
         * Returns an editor for this snapshot's entry, or null if either the
         * entry has changed since this snapshot was created or if another edit
         * is in progress.
         */
        @Throws(IOException::class)
        fun edit(): Editor? {
            return this@DiskLruCache.edit(key, sequenceNumber)
        }

        fun getFile(index: Int): File {
            return files[index]
        }

        /** Returns the string value for `index`.  */
        @Throws(IOException::class)
        fun getString(index: Int): String {
            val `is`: InputStream = FileInputStream(files[index])
            return inputStreamToString(`is`)
        }

        /** Returns the byte length of the value for `index`.  */
        fun getLength(index: Int): Long {
            return lengths[index]
        }
    }

    /** Edits the values for an entry.  */
    inner class Editor internal constructor(val entry: Entry) {

        val written: BooleanArray? =
            if (entry.readable) null else BooleanArray(valueCount)
        private var committed = false

        /**
         * Returns an unbuffered input stream to read the last committed value,
         * or null if no value has been committed.
         */
        @Throws(IOException::class)
        private fun newInputStream(index: Int): InputStream? {
            synchronized(this@DiskLruCache) {
                check(entry.currentEditor == this)
                if (!entry.readable) {
                    return null
                }
                return try {
                    FileInputStream(entry.getCleanFile(index))
                } catch (e: FileNotFoundException) {
                    null
                }
            }
        }

        /**
         * Returns the last committed value as a string, or null if no value
         * has been committed.
         */
        @Throws(IOException::class)
        fun getString(index: Int): String? {
            val `in` = newInputStream(index)
            return if (`in` != null) inputStreamToString(`in`) else null
        }

        @Throws(IOException::class)
        fun getFile(index: Int): File {
            synchronized(this@DiskLruCache) {
                check(entry.currentEditor == this)
                if (!entry.readable) {
                    written!![index] = true
                }
                val dirtyFile = entry.getDirtyFile(index)
                directory.mkdirs()
                return dirtyFile
            }
        }

        /** Sets the value at `index` to `value`.  */
        @Throws(IOException::class)
        fun set(index: Int, value: String) {
            var writer: Writer? = null
            try {
                val os: OutputStream = FileOutputStream(getFile(index))
                writer = OutputStreamWriter(os, Util.UTF_8)
                writer.write(value)
            } finally {
                Util.closeQuietly(writer)
            }
        }

        /**
         * Commits this edit so it is visible to readers.  This releases the
         * edit lock so another edit may be started on the same key.
         */
        @Throws(IOException::class)
        fun commit() {
            // The object using this Editor must catch and handle any errors
            // during the write. If there is an error and they call commit
            // anyway, we will assume whatever they managed to write was valid.
            // Normally they should call abort.
            completeEdit(this, true)
            committed = true
        }

        /**
         * Aborts this edit. This releases the edit lock so another edit may be
         * started on the same key.
         */
        @Throws(IOException::class)
        fun abort() {
            completeEdit(this, false)
        }

        fun abortUnlessCommitted() {
            if (!committed) {
                try {
                    abort()
                } catch (ignored: IOException) {
                }
            }
        }
    }

    inner class Entry internal constructor(val key: String) {
        /** Lengths of this entry's files.  */
        val lengths: LongArray = LongArray(valueCount)

        /** Memoized File objects for this entry to avoid char[] allocations.  */
        // The names are repetitive so re-use the same builder to avoid allocations.
        val cleanFiles: Array<File> = Array(valueCount) { index ->
            File(directory, "${key}.$index")
        }
        val dirtyFiles: Array<File> = Array(valueCount) { index ->
            File(directory, "${key}.${index}.tmp")
        }

        /** True if this entry has ever been published.  */
        var readable: Boolean = false

        /** The ongoing edit or null if this entry is not being edited.  */
        var currentEditor: Editor? = null

        /** The sequence number of the most recently committed edit to this entry.  */
        var sequenceNumber: Long = 0

        @Throws(IOException::class)
        fun getLengths(): String {
            val result = StringBuilder()
            for (size in lengths) {
                result.append(' ').append(size)
            }
            return result.toString()
        }

        /** Set lengths using decimal numbers like "10123".  */
        @Throws(IOException::class)
        fun setLengths(strings: Array<String>) {
            if (strings.size != valueCount) {
                throw invalidLengths(strings)
            }

            try {
                for (i in strings.indices) {
                    lengths[i] = strings[i].toLong()
                }
            } catch (e: NumberFormatException) {
                throw invalidLengths(strings)
            }
        }

        @Throws(IOException::class)
        fun invalidLengths(strings: Array<String>): IOException {
            throw IOException("unexpected journal line: " + strings.contentToString())
        }

        fun getCleanFile(i: Int): File {
            return cleanFiles[i]
        }

        fun getDirtyFile(i: Int): File {
            return dirtyFiles[i]
        }
    }

    /**
     * A [java.util.concurrent.ThreadFactory] that builds a thread with a specific thread name
     * and with minimum priority.
     */
    private class DiskLruCacheThreadFactory : ThreadFactory {
        @Synchronized
        override fun newThread(runnable: Runnable): Thread {
            val result = Thread(runnable, "tlrucache-disk-lru-cache-thread")
            result.priority = Thread.MIN_PRIORITY
            return result
        }
    }

    companion object {
        const val JOURNAL_FILE: String = "journal"
        const val JOURNAL_FILE_TEMP: String = "journal.tmp"
        const val JOURNAL_FILE_BACKUP: String = "journal.bkp"
        const val MAGIC: String = "libcore.io.DiskLruCache"
        const val VERSION_1: String = "1"
        const val ANY_SEQUENCE_NUMBER: Long = -1
        private const val CLEAN = "CLEAN"
        private const val DIRTY = "DIRTY"
        private const val REMOVE = "REMOVE"
        private const val READ = "READ"

        /**
         * Opens the cache in `directory`, creating a cache if none exists
         * there.
         *
         * @param directory a writable directory
         * @param valueCount the number of values per cache entry. Must be positive.
         * @param maxSize the maximum number of bytes this cache should use to store
         * @throws IOException if reading or writing the cache directory fails
         */
        @Throws(IOException::class)
        fun open(directory: File, appVersion: Int, valueCount: Int, maxSize: Long): DiskLruCache {
            require(maxSize > 0) { "maxSize <= 0" }
            require(valueCount > 0) { "valueCount <= 0" }

            // If a bkp file exists, use it instead.
            val backupFile = File(directory, JOURNAL_FILE_BACKUP)
            if (backupFile.exists()) {
                val journalFile = File(directory, JOURNAL_FILE)
                // If journal file also exists just delete backup file.
                if (journalFile.exists()) {
                    backupFile.delete()
                } else {
                    renameTo(backupFile, journalFile, false)
                }
            }

            // Prefer to pick up where we left off.
            var cache = DiskLruCache(directory, appVersion, valueCount, maxSize)
            if (cache.journalFile.exists()) {
                try {
                    cache.readJournal()
                    cache.processJournal()
                    return cache
                } catch (journalIsCorrupt: IOException) {
                    println(
                        ("DiskLruCache "
                                + directory
                                + " is corrupt: "
                                + journalIsCorrupt.message
                                + ", removing")
                    )
                    cache.delete()
                }
            }

            // Create a new empty cache.
            directory.mkdirs()
            cache = DiskLruCache(directory, appVersion, valueCount, maxSize)
            cache.rebuildJournal()
            return cache
        }

        @Throws(IOException::class)
        private fun deleteIfExists(file: File) {
            if (file.exists() && !file.delete()) {
                throw IOException()
            }
        }

        @Throws(IOException::class)
        private fun renameTo(from: File, to: File, deleteDestination: Boolean) {
            if (deleteDestination) {
                deleteIfExists(to)
            }
            if (!from.renameTo(to)) {
                throw IOException()
            }
        }

        @Throws(IOException::class)
        private fun inputStreamToString(`in`: InputStream): String {
            return Util.readFully(InputStreamReader(`in`, Util.UTF_8))
        }

        /**
         * Closes the writer while whitelisting with StrictMode if necessary.
         *
         *
         * Analogous to b/71520172.
         */
        @Throws(IOException::class)
        private fun closeWriter(writer: Writer) {
            writer.close()
        }

        /**
         * Flushes the writer while whitelisting with StrictMode if necessary.
         *
         *
         * See b/71520172.
         */
        @Throws(IOException::class)
        private fun flushWriter(writer: Writer) {
            writer.flush()
        }
    }
}
