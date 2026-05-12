package com.luxury.mobile.launcher.core

import com.luxury.mobile.launcher.model.ManifestEntry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Locks in the "skip-existing files" behaviour that makes the launcher
 * detect pre-installed game data and not re-download it.
 */
class FileVerifierTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun root(): File = tmp.root

    @Test
    fun `plain file missing is not satisfied`() {
        val entry = ManifestEntry(
            url = "https://x/y.bin",
            relativePath = "y.bin",
            size = 10L,
            sha256 = null,
        )
        assertFalse(FileVerifier.isSatisfied(root(), entry))
    }

    @Test
    fun `plain file with correct size and hash is satisfied`() {
        val content = "hello-havana".toByteArray()
        val sha = Hasher.sha256(File(root(), "tmp").apply { writeBytes(content) })
        val target = File(root(), "y.bin")
        target.writeBytes(content)
        val entry = ManifestEntry(
            url = "https://x/y.bin",
            relativePath = "y.bin",
            size = content.size.toLong(),
            sha256 = sha,
        )
        assertTrue(FileVerifier.isSatisfied(root(), entry))
    }

    @Test
    fun `plain file with wrong size is not satisfied`() {
        val target = File(root(), "y.bin")
        target.writeBytes(ByteArray(5))
        val entry = ManifestEntry(
            url = "https://x/y.bin",
            relativePath = "y.bin",
            size = 10L,
            sha256 = null,
        )
        assertFalse(FileVerifier.isSatisfied(root(), entry))
    }

    @Test
    fun `plain file with size match but wrong hash is not satisfied`() {
        val target = File(root(), "y.bin")
        target.writeBytes("xxxxxxxxxx".toByteArray())
        val entry = ManifestEntry(
            url = "https://x/y.bin",
            relativePath = "y.bin",
            size = 10L,
            sha256 = "0000000000000000000000000000000000000000000000000000000000000000",
        )
        assertFalse(FileVerifier.isSatisfied(root(), entry))
    }

    @Test
    fun `zip entry without installed marker is not satisfied`() {
        val entry = ManifestEntry(
            url = "https://x/y.zip",
            relativePath = "data",
            size = 100L,
            sha256 = "abc",
            extractFromZip = true,
        )
        File(root(), "data").mkdirs()
        assertFalse(FileVerifier.isSatisfied(root(), entry))
    }

    @Test
    fun `zip entry with matching marker is satisfied`() {
        val entry = ManifestEntry(
            url = "https://x/y.zip",
            relativePath = "data",
            size = 100L,
            sha256 = "abc",
            extractFromZip = true,
        )
        File(root(), "data").mkdirs()
        FileVerifier.installedMarker(root(), entry).apply {
            parentFile?.mkdirs()
            writeText("abc")
        }
        assertTrue(FileVerifier.isSatisfied(root(), entry))
    }

    @Test
    fun `zip entry with stale marker is not satisfied`() {
        val entry = ManifestEntry(
            url = "https://x/y.zip",
            relativePath = "data",
            size = 100L,
            sha256 = "new-hash",
            extractFromZip = true,
        )
        File(root(), "data").mkdirs()
        FileVerifier.installedMarker(root(), entry).apply {
            parentFile?.mkdirs()
            writeText("old-hash")
        }
        assertFalse(FileVerifier.isSatisfied(root(), entry))
    }
}
