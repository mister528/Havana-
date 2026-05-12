package com.luxury.mobile.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HasherTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `sha256 of empty file is the canonical empty digest`() {
        val file = tmp.newFile("empty.bin")
        // SHA-256 of zero bytes — well known constant.
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Hasher.sha256(file),
        )
    }

    @Test
    fun `sha256 of the string 'abc' bytes matches the FIPS test vector`() {
        val file = tmp.newFile("abc.bin")
        file.writeBytes("abc".toByteArray())
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Hasher.sha256(file),
        )
    }
}
