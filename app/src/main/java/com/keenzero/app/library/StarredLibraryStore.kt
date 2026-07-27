package com.keenzero.app.library

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Titles the user starred in the player, kept on the box until they unstar them.
 *
 * This is deliberately a *library*, not a cache. Streaming writes to `cacheDir`, which
 * Android may evict without warning and which [com.keenzero.app.torrent.TorrentStreamingService]
 * deletes wholesale on teardown — either would silently destroy a finished 8 GB download.
 * Starred content therefore lives under `filesDir/library/<key>/`, a tree nothing else
 * touches.
 *
 * One directory per title, named by the resume key (info-hash), so "delete this title"
 * is a single recursive delete of a directory containing nothing else.
 */
class StarredLibraryStore(context: Context) {

    /** Where a starred title stands. Only [State.COMPLETE] is safe to play offline. */
    enum class State { QUEUED, DOWNLOADING, COMPLETE, FAILED }

    data class Entry(
        /** TorrentResumeStore.keyOf(origin) — the info-hash, and the directory name. */
        val key: String,
        /** The magnet / .torrent URL, so a repair can re-add it to a session. */
        val origin: String,
        val title: String,
        val state: State,
        /** Bytes on disk so far; equals [totalBytes] once COMPLETE. */
        val downloadedBytes: Long,
        val totalBytes: Long,
        /** Absolute path of the media file once known, else null. */
        val mediaPath: String?,
        val starredAtMs: Long,
    ) {
        val progress: Float
            get() = if (totalBytes <= 0L) 0f else (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
    }

    private val appContext = context.applicationContext

    /**
     * Records live in a JSON file, not SharedPreferences.
     *
     * The download service runs in its own process (`:library`) and writes progress,
     * while the activity reads it to paint the row. SharedPreferences is explicitly not
     * coherent across processes — the UI would have shown a permanently stale record and
     * a completed download would never have appeared. A file read on demand, written
     * atomically via a temp-and-rename, is correct for both readers.
     */
    private fun indexFile(): File = File(libraryRoot(), INDEX_FILE)

    private fun writeEntries(arr: JSONArray) {
        val root = libraryRoot()
        val tmp = File(root, "$INDEX_FILE.tmp")
        try {
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(indexFile())) {
                indexFile().writeText(arr.toString())
                tmp.delete()
            }
        } catch (_: Throwable) {
            tmp.delete()
        }
    }

    /** Root of the library tree. Never inside cacheDir — see the class comment. */
    fun libraryRoot(): File = File(appContext.filesDir, LIBRARY_DIR).apply { mkdirs() }

    /** This title's own directory; deleting it removes the title and nothing else. */
    fun dirFor(key: String): File = File(libraryRoot(), key)

    fun list(): List<Entry> {
        val out = mutableListOf<Entry>()
        val arr = entries()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val key = o.optString("key").takeIf { it.isNotBlank() } ?: continue
            out.add(
                Entry(
                    key = key,
                    origin = o.optString("origin"),
                    title = o.optString("title").takeIf { it.isNotBlank() } ?: key,
                    state = runCatching { State.valueOf(o.optString("state")) }.getOrDefault(State.QUEUED),
                    downloadedBytes = o.optLong("downloadedBytes", 0L),
                    totalBytes = o.optLong("totalBytes", 0L),
                    mediaPath = o.optString("mediaPath").takeIf { it.isNotBlank() },
                    starredAtMs = o.optLong("starredAtMs", 0L),
                ),
            )
        }
        return out.sortedByDescending { it.starredAtMs }
    }

    fun isStarred(key: String?): Boolean = key != null && list().any { it.key == key }

    fun find(key: String?): Entry? = key?.let { k -> list().firstOrNull { it.key == k } }

    /** Add or replace an entry. */
    fun put(entry: Entry) {
        val next = JSONArray()
        list().filterNot { it.key == entry.key }.forEach { next.put(it.toJson()) }
        next.put(entry.toJson())
        writeEntries(next)
    }

    /** Patch progress/state without disturbing the rest of the record. */
    fun update(
        key: String,
        state: State? = null,
        downloadedBytes: Long? = null,
        totalBytes: Long? = null,
        mediaPath: String? = null,
    ) {
        val current = find(key) ?: return
        put(
            current.copy(
                state = state ?: current.state,
                downloadedBytes = downloadedBytes ?: current.downloadedBytes,
                totalBytes = totalBytes ?: current.totalBytes,
                mediaPath = mediaPath ?: current.mediaPath,
            ),
        )
    }

    /**
     * Unstar: drop the record AND delete every byte on disk. The user's instruction is
     * that unstarring removes the download completely, so this is not a soft delete and
     * there is no orphan left behind — the whole per-title directory goes.
     *
     * @return bytes reclaimed (best effort, for reporting).
     */
    fun remove(key: String): Long {
        val dir = dirFor(key)
        val freed = dirSize(dir)
        dir.deleteRecursively()
        val next = JSONArray()
        list().filterNot { it.key == key }.forEach { next.put(it.toJson()) }
        writeEntries(next)
        return freed
    }

    /**
     * Delete any library directory with no matching record, and drop any record whose
     * directory has vanished. Guards against a process kill between the two writes.
     */
    fun reconcile() {
        val known = list()
        val keys = known.map { it.key }.toSet()
        libraryRoot().listFiles()?.forEach { child ->
            if (child.isDirectory && child.name !in keys) child.deleteRecursively()
        }
        val surviving = known.filter { dirFor(it.key).exists() }
        if (surviving.size != known.size) {
            val next = JSONArray()
            surviving.forEach { next.put(it.toJson()) }
            writeEntries(next)
        }
    }

    fun totalBytesOnDisk(): Long = dirSize(libraryRoot())

    private fun dirSize(f: File): Long = when {
        !f.exists() -> 0L
        f.isFile -> f.length()
        else -> f.listFiles()?.sumOf { dirSize(it) } ?: 0L
    }

    private fun Entry.toJson(): JSONObject = JSONObject()
        .put("key", key)
        .put("origin", origin)
        .put("title", title)
        .put("state", state.name)
        .put("downloadedBytes", downloadedBytes)
        .put("totalBytes", totalBytes)
        .put("mediaPath", mediaPath.orEmpty())
        .put("starredAtMs", starredAtMs)

    private fun entries(): JSONArray = try {
        val f = indexFile()
        if (f.exists()) JSONArray(f.readText()) else JSONArray()
    } catch (_: Exception) {
        JSONArray()
    }

    companion object {
        private const val INDEX_FILE = "index.json"
        const val LIBRARY_DIR = "library"
    }
}
