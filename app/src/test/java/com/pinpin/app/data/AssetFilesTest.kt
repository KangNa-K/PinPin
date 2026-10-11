package com.pinpin.app.data

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files

class AssetFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun failedWriteLeavesNoFinalOrTemporaryFile() {
        val target = File(temporary.root.canonicalFile, "image.png")
        try {
            AssetFiles.writeAtomically(target) {
                it.write(byteArrayOf(1))
                throw IOException("injected")
            }
            fail("Expected failure")
        } catch (_: IOException) {}
        assertFalse(target.exists())
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }

    @Test fun emptyWriteFailsAndSuccessfulWritePublishesBytes() {
        val target = File(temporary.root.canonicalFile, "image.png")
        try {
            AssetFiles.writeAtomically(target) {}
            fail("Expected empty-file failure")
        } catch (_: IOException) {}
        AssetFiles.writeAtomically(target) { it.write(byteArrayOf(1, 2, 3)) }
        assertArrayEquals(byteArrayOf(1, 2, 3), target.readBytes())
    }

    @Test fun ownershipRejectsExternalNestedAndSymlinkFiles() {
        val root = temporary.newFolder("pin_assets").canonicalFile
        val own = File(root, "own.png").apply { writeText("own") }
        val external = temporary.newFile("user.png").canonicalFile
        val nested = File(root, "nested").apply { mkdir() }
        val nestedFile = File(nested, "image.png").apply { writeText("nested") }
        val link = File(root, "link.png")
        Files.createSymbolicLink(link.toPath(), external.toPath())
        assertEquals(own, AssetFiles.ownedFile(root, own))
        assertNull(AssetFiles.ownedFile(root, external))
        assertNull(AssetFiles.ownedFile(root, nestedFile))
        assertNull(AssetFiles.ownedFile(root, link))
        assertNull(AssetFiles.ownedFile(root, root))
    }
}
