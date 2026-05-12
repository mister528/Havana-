package com.luxury.mobile.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DownloadMetaTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `read returns null when file is missing`() {
        assertNull(DownloadMeta.read(File(tmp.root, "missing.meta")))
    }

    @Test
    fun `write then read round-trips every field`() {
        val meta = DownloadMeta(
            url = "https://example.test/luxury.zip",
            targetRelativePath = ".",
            totalBytes = 1_000_000L,
            downloadedBytes = 250_000L,
            etag = "\"abc-123\"",
            lastModified = "Wed, 12 May 2026 12:00:00 GMT",
            expectedSha256 = "deadbeef",
        )
        val file = File(tmp.root, "x.meta")
        meta.write(file)

        val read = DownloadMeta.read(file)
        assertNotNull(read)
        assertEquals(meta.url, read!!.url)
        assertEquals(meta.targetRelativePath, read.targetRelativePath)
        assertEquals(meta.totalBytes, read.totalBytes)
        assertEquals(meta.downloadedBytes, read.downloadedBytes)
        assertEquals(meta.etag, read.etag)
        assertEquals(meta.lastModified, read.lastModified)
        assertEquals(meta.expectedSha256, read.expectedSha256)
    }

    @Test
    fun `read returns null for a corrupt file`() {
        val file = File(tmp.root, "corrupt.meta")
        file.writeText("{not valid json")
        assertNull(DownloadMeta.read(file))
    }
}
