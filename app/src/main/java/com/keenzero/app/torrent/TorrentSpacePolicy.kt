package com.keenzero.app.torrent

object TorrentSpacePolicy {
    const val RESERVE_BYTES: Long = 2L * 1024 * 1024 * 1024

    fun canDownloadWholeFile(usableBytes: Long, fileSizeBytes: Long): Boolean {
        if (usableBytes < 0 || fileSizeBytes < 0) return false
        return usableBytes >= fileSizeBytes && usableBytes - fileSizeBytes >= RESERVE_BYTES
    }
}
