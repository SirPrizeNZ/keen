package com.keenzero.app.torrent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentSpacePolicyTest {
    @Test
    fun requiresFileSizePlusTwoGiBReserve() {
        val fileSize = 4L * 1024 * 1024 * 1024
        assertTrue(
            TorrentSpacePolicy.canDownloadWholeFile(
                fileSize + TorrentSpacePolicy.RESERVE_BYTES,
                fileSize,
            ),
        )
        assertFalse(
            TorrentSpacePolicy.canDownloadWholeFile(
                fileSize + TorrentSpacePolicy.RESERVE_BYTES - 1,
                fileSize,
            ),
        )
    }
}
