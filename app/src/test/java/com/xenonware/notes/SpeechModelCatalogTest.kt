package com.xenonware.notes

import com.xenonware.notes.util.audio.SpeechDownloadState
import com.xenonware.notes.util.audio.SpeechModelArtifact
import com.xenonware.notes.util.audio.SpeechModelSpec
import com.xenonware.notes.util.audio.SpeechModelStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SpeechModelCatalogTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `voz artifact list matches pinned total bytes and valid SHA-256 digests`() {
        assertEquals(6, SpeechModelSpec.ARTIFACTS.size)
        assertEquals(SpeechModelSpec.TOTAL_BYTES, SpeechModelSpec.ARTIFACTS.sumOf { it.sizeBytes })

        val hexRegex = Regex("^[0-9a-f]{64}$")
        for (artifact in SpeechModelSpec.ARTIFACTS) {
            assertTrue("Expected HTTPS url for ${artifact.relativePath}", artifact.url.startsWith("https://"))
            assertTrue("Expected positive size for ${artifact.relativePath}", artifact.sizeBytes > 0L)
            assertTrue(
                "Expected 64-char hex sha256 for ${artifact.relativePath}",
                hexRegex.matches(artifact.sha256),
            )
        }
        assertEquals(25, SpeechModelSpec.SUPPORTED_LANGUAGES.size)
        assertTrue("en" in SpeechModelSpec.SUPPORTED_LANGUAGES)
        assertTrue("de" in SpeechModelSpec.SUPPORTED_LANGUAGES)
    }

    @Test
    fun `directory completeness rejects missing or partial files and succeeds when complete`() {
        val modelDir = tempFolder.newFolder("voz-v0.4.0")
        val testArtifacts = listOf(
            SpeechModelArtifact("a.tflite", "https://example.com/a", 5L, "a".repeat(64)),
            SpeechModelArtifact("b.json", "https://example.com/b", 3L, "b".repeat(64)),
        )

        assertFalse(SpeechModelStorage.isModelDirectoryComplete(modelDir, testArtifacts))

        File(modelDir, "a.tflite").writeBytes(ByteArray(5))
        File(modelDir, "b.json").writeBytes(ByteArray(3))
        File(modelDir, "b.json.part").writeBytes(ByteArray(1))
        assertFalse(SpeechModelStorage.isModelDirectoryComplete(modelDir, testArtifacts))

        File(modelDir, "b.json.part").delete()
        assertTrue(SpeechModelStorage.isModelDirectoryComplete(modelDir, testArtifacts))
        assertTrue(File(modelDir, ".dal-meta/manifest").exists())
    }

    @Test
    fun `manifest and verification stamp round-trip verifies artifacts`() {
        val modelDir = tempFolder.newFolder("voz-verified")
        val testArtifacts = listOf(
            SpeechModelArtifact("vocab.json", "https://example.com/vocab.json", 4L, "c".repeat(64)),
        )
        File(modelDir, "vocab.json").writeBytes(byteArrayOf(1, 2, 3, 4))

        assertFalse(SpeechModelStorage.areAllArtifactsVerified(modelDir, testArtifacts))

        SpeechModelStorage.writeModelManifestAndStamp(modelDir, testArtifacts)

        assertTrue(SpeechModelStorage.areAllArtifactsVerified(modelDir, testArtifacts))
        val manifestText = File(modelDir, ".dal-meta/manifest").readText()
        assertTrue(manifestText.startsWith("DAL1\nR\tvocab.json\nF\tvocab.json\t4\t"))
    }

    @Test
    fun `download state formats bytes, speed, and ETA accurately`() {
        assertEquals("50.0 MB", SpeechDownloadState.formatBytesMb(50L * 1024L * 1024L))
        assertEquals("1187 MB", SpeechDownloadState.formatBytesMb(SpeechModelSpec.TOTAL_BYTES))
        assertEquals("Connecting...", SpeechDownloadState.formatSpeed(0L))
        assertEquals("512 KB/s", SpeechDownloadState.formatSpeed(512L * 1024L))
        assertEquals("2.00 MB/s", SpeechDownloadState.formatSpeed(2L * 1024L * 1024L))
        assertEquals("~1m 05s left", SpeechDownloadState.formatEta(65L))
        assertEquals("~45s left", SpeechDownloadState.formatEta(45L))
    }
}
