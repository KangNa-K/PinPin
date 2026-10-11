package com.pinpin.app.update

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class PinPinUpdaterTest {
    @Test fun manifestRequestBypassesStaleCdnEntries() {
        assertEquals("$MANIFEST_URL?check=123", PinPinUpdater.manifestRequestUrl(123))
        assertFalse(PinPinUpdater.manifestRequestUrl(123) == PinPinUpdater.manifestRequestUrl(124))
    }

    private fun manifest(sha: String, size: Long) = UpdateManifest(
        28, "0.10.56",
        "https://api.github.com/repos/KangNa-K/PinPin-updates/releases/assets/123456",
        sha, size, "",
    )

    @Test fun comparesVersionCodes() {
        assertTrue(PinPinUpdater.newer(28, 27))
        assertFalse(PinPinUpdater.newer(27, 27))
        assertFalse(PinPinUpdater.newer(26, 27))
    }

    @Test fun currentManifestNeedsNoApk() {
        val current = PinPinUpdater.parseManifest(
            """{"versionCode":27,"versionName":"0.10.55"}""",
        )
        assertFalse(PinPinUpdater.newer(current.versionCode, 27))
    }

    @Test fun onlyAcceptsPinPinAssetAndValidHash() {
        val valid = manifest("ab".repeat(32), 100)
        PinPinUpdater.validate(valid)
        listOf(
            valid.copy(size = 0),
            valid.copy(versionCode = 0),
            valid.copy(versionName = " "),
            valid.copy(size = 251L * 1024 * 1024),
            valid.copy(sha256 = "invalid"),
            valid.copy(apkUrl = "https://example.org/update.apk"),
            valid.copy(apkUrl = "https://api.github.com/repos/KangNa-K/PockRef-updates/releases/assets/123"),
            valid.copy(apkUrl = "${valid.apkUrl}/redirect"),
            valid.copy(apkUrl = "${valid.apkUrl}?redirect=1"),
            valid.copy(apkUrl = valid.apkUrl.replace("https:", "http:")),
        ).forEach { assertTrue(runCatching { PinPinUpdater.validate(it) }.isFailure) }
    }

    @Test fun detectsTruncatedOrTamperedFile() {
        val file = File.createTempFile("pinpin-update", ".apk")
        try {
            val bytes = "signed apk bytes".toByteArray()
            file.writeBytes(bytes)
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            val valid = manifest(hash, bytes.size.toLong())
            assertTrue(PinPinUpdater.verifyFile(file, valid))
            assertFalse(PinPinUpdater.verifyFile(file, valid.copy(size = bytes.size + 1L)))
            assertFalse(PinPinUpdater.verifyFile(file, valid.copy(sha256 = "00".repeat(32))))
        } finally {
            file.delete()
        }
    }
}
