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
        /** Current download rate in bytes/sec; only meaningful while DOWNLOADING. */
        val speedBps: Long = 0L,
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
            out.add(entryOf(o) ?: continue)
        }
        // The index is authoritative only for what it knows about. Anything on disk it
        // has never heard of is still the user's download, so it is shown too — that is
        // what makes a title survive an index that was lost or written by an older build.
        val known = out.map { it.key }.toSet()
        out.addAll(recoverFromDisk().filterNot { it.key in known })
        return out.sortedByDescending { it.starredAtMs }
    }

    private fun entryOf(o: JSONObject): Entry? {
        val key = o.optString("key").takeIf { it.isNotBlank() } ?: return null
        return Entry(
            key = key,
            origin = o.optString("origin"),
            title = o.optString("title").takeIf { it.isNotBlank() } ?: key,
            state = runCatching { State.valueOf(o.optString("state")) }.getOrDefault(State.QUEUED),
            downloadedBytes = o.optLong("downloadedBytes", 0L),
            totalBytes = o.optLong("totalBytes", 0L),
            mediaPath = o.optString("mediaPath").takeIf { it.isNotBlank() },
            starredAtMs = o.optLong("starredAtMs", 0L),
            speedBps = o.optLong("speedBps", 0L),
        )
    }

    fun isStarred(key: String?): Boolean = key != null && list().any { it.key == key }

    fun find(key: String?): Entry? = key?.let { k -> list().firstOrNull { it.key == k } }

    /** Add or replace an entry. */
    fun put(entry: Entry) {
        val next = JSONArray()
        list().filterNot { it.key == entry.key }.forEach { next.put(it.toJson()) }
        next.put(entry.toJson())
        writeEntries(next)
        writeMeta(entry)
    }

    /**
     * A copy of the record inside the title's own directory.
     *
     * The index is a single file and therefore a single point of failure: lose it to a
     * crash mid-rename, a corrupt write or a cleared-data mishap, and every finished
     * download became an unreferenced directory that [reconcile] then deleted — hours of
     * transfer gone with no way back. The title's own directory is the one place that
     * cannot go missing while the media is still there, so the record lives beside the
     * media as well, and the index can always be rebuilt from it.
     */
    private fun writeMeta(entry: Entry) {
        runCatching {
            val dir = dirFor(entry.key).apply { mkdirs() }
            File(dir, META_FILE).writeText(entry.toJson().toString())
        }
    }

    /**
     * Rebuild records straight off the disk, ignoring the index entirely.
     *
     * Prefers the sidecar written by [writeMeta]. Failing even that, a directory holding
     * a real media file is still a download the user paid for in bandwidth, so it is
     * adopted under its directory name rather than thrown away.
     */
    private fun recoverFromDisk(): List<Entry> {
        val dirs = libraryRoot().listFiles()?.filter { it.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { dir ->
            val fromMeta = runCatching {
                val f = File(dir, META_FILE)
                if (f.exists()) entryOf(JSONObject(f.readText())) else null
            }.getOrNull()
            if (fromMeta != null) return@mapNotNull fromMeta
            val media = largestMediaFile(dir) ?: return@mapNotNull null
            Entry(
                key = dir.name,
                origin = "",
                title = media.nameWithoutExtension,
                state = State.COMPLETE,
                downloadedBytes = media.length(),
                totalBytes = media.length(),
                mediaPath = media.absolutePath,
                starredAtMs = dir.lastModified(),
            )
        }
    }

    private fun largestMediaFile(dir: File): File? {
        val out = mutableListOf<File>()
        fun walk(f: File) {
            if (f.isFile) {
                if (f.name != META_FILE && f.length() > MIN_MEDIA_BYTES) out.add(f)
            } else {
                f.listFiles()?.forEach(::walk)
            }
        }
        walk(dir)
        return out.maxByOrNull { it.length() }
    }

    /** Patch progress/state without disturbing the rest of the record. */
    fun update(
        key: String,
        state: State? = null,
        downloadedBytes: Long? = null,
        totalBytes: Long? = null,
        mediaPath: String? = null,
        speedBps: Long? = null,
    ) {
        val current = find(key) ?: return
        put(
            current.copy(
                state = state ?: current.state,
                downloadedBytes = downloadedBytes ?: current.downloadedBytes,
                totalBytes = totalBytes ?: current.totalBytes,
                mediaPath = mediaPath ?: current.mediaPath,
                speedBps = speedBps ?: current.speedBps,
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
     * Bring the index back in line with what is actually on disk, and repair the sidecars.
     *
     * This deliberately does **not** delete a directory it has no record for. It used to,
     * and that made the index the single thing standing between a finished download and
     * deletion: one bad index write and the next launch quietly removed the movie. Disk
     * is now the stronger authority — [list] adopts unknown directories, so all this has
     * to do is write the adopted set back and make sure every title carries its sidecar.
     * The only thing dropped is a record whose directory is genuinely gone.
     */
    fun reconcile() {
        val known = list()
        // Only a COMPLETE record is expected to have files on disk. A queued or
        // in-flight download may legitimately have nothing yet (the service creates the
        // directory in another process), and dropping it here deleted the card seconds
        // after starring.
        val surviving = known.filter {
            it.state != State.COMPLETE || dirFor(it.key).exists()
        }
        val next = JSONArray()
        surviving.forEach { next.put(it.toJson()) }
        writeEntries(next)
        // Backfill sidecars for anything starred by a build that predates them, so the
        // next recovery has a real record to work from rather than a guessed title.
        surviving.forEach { if (dirFor(it.key).exists()) writeMeta(it) }
    }

    fun totalBytesOnDisk(): Long = dirSize(libraryRoot())

    /** Bytes this title occupies, for telling the user what a delete will reclaim. */
    fun bytesOnDisk(key: String): Long = dirSize(dirFor(key))

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
        .put("speedBps", speedBps)

    private fun entries(): JSONArray = try {
        val f = indexFile()
        if (f.exists()) JSONArray(f.readText()) else JSONArray()
    } catch (_: Exception) {
        JSONArray()
    }

    companion object {
        private const val INDEX_FILE = "index.json"

        /** Per-title copy of the record, kept beside its media. */
        private const val META_FILE = "keen-title.json"

        /** Below this, a file in a title directory is bookkeeping, not the movie. */
        private const val MIN_MEDIA_BYTES = 1L * 1024 * 1024
        const val LIBRARY_DIR = "library"
    }
}
