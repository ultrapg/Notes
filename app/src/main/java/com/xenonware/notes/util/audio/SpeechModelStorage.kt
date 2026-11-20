package com.xenonware.notes.util.audio

import android.content.Context
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

data class SpeechModelArtifact(
    val relativePath: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
)

/**
 * Specification for Desert Ant Labs `Voz` (NVIDIA Parakeet TDT 0.6B v3 on LiteRT).
 *
 * Covers 25 European languages with automatic language handling, punctuation & capitalization,
 * and 80 ms word-level timestamps.
 */
object SpeechModelSpec {
    const val MODEL_ID = "voz"
    const val DISPLAY_NAME = "Voz"
    const val FULL_NAME = "Voz (Parakeet TDT 0.6B v3)"
    const val REVISION = "v0.4.0"
    const val TOTAL_BYTES: Long = 1_244_258_225L

    val SIZE_MB: Int = (TOTAL_BYTES / (1024L * 1024L)).toInt()
    val SIZE_LABEL: String = "~$SIZE_MB MB"

    val SUPPORTED_LANGUAGES: Set<String> = linkedSetOf(
        "bg", "cs", "da", "de", "el",
        "en", "es", "et", "fi", "fr",
        "hr", "hu", "it", "lt", "lv",
        "mt", "nl", "pl", "pt", "ro",
        "ru", "sk", "sl", "sv", "uk",
    )

    val ARTIFACTS: List<SpeechModelArtifact> = listOf(
        SpeechModelArtifact(
            relativePath = "encoder.tflite",
            url = "https://huggingface.co/desert-ant-labs/voz/resolve/$REVISION/encoder.tflite",
            sizeBytes = 1_207_525_456L,
            sha256 = "1152abb5fce2cf50b57b1cd80a1bf70320c32b20656f835bfe08c7faa5c3f885",
        ),
        SpeechModelArtifact(
            relativePath = "mel.tflite",
            url = "https://huggingface.co/desert-ant-labs/voz/resolve/$REVISION/mel.tflite",
            sizeBytes = 1_663_424L,
            sha256 = "505d80fc1be255c115c715a6cffdb55e51ff22465bca820e5feb59f0cccabe09",
        ),
        SpeechModelArtifact(
            relativePath = "decoder.tflite",
            url = "https://huggingface.co/desert-ant-labs/voz/resolve/$REVISION/decoder.tflite",
            sizeBytes = 24_503_072L,
            sha256 = "429ff2b574455733b388f1667d05d6764ac6086417b94d4cc24edabd25d52591",
        ),
        SpeechModelArtifact(
            relativePath = "meta.litert.json",
            url = "https://huggingface.co/desert-ant-labs/voz/resolve/$REVISION/meta.litert.json",
            sizeBytes = 579L,
            sha256 = "a8ad38f4144d12445dce0e32a723859816596c1c667906c062df97c94a81e741",
        ),
        SpeechModelArtifact(
            relativePath = "vocab.json",
            url = "https://huggingface.co/desert-ant-labs/voz/resolve/$REVISION/vocab.json",
            sizeBytes = 78_654L,
            sha256 = "3eb2f73e8853dce5156c2ed97c0b1bb84eb4ebd201eb1761d15977fc1ea31490",
        ),
        SpeechModelArtifact(
            relativePath = "embedding.f16",
            url = "https://huggingface.co/desert-ant-labs/voz/resolve/$REVISION/embedding.f16",
            sizeBytes = 10_487_040L,
            sha256 = "07163e5f6f8a68f9ffeed7a8ff770c68098c2db216232321912188bd614b9a72",
        ),
    )
}

/**
 * Manages on-disk storage, O(1) completeness checks, SHA-256 verification stamps,
 * and `.dal-meta/manifest` metadata for the downloaded `Voz` STT model.
 */
object SpeechModelStorage {

    private const val MODELS_ROOT = "speech_models"
    private const val LEGACY_WHISPER_DIR = "whisper_models"
    private const val VERIFIED_STAMP_FILE = ".notes_verified"
    private const val DAL_META_DIR = ".dal-meta"
    private const val DAL_MANIFEST_FILE = "manifest"

    private val directoryCompleteCache = ConcurrentHashMap<String, Pair<Long, Long>>()

    @Volatile
    private var legacyCleanupDone = false

    fun modelRootDir(context: Context): File {
        return File(context.applicationContext.filesDir, "$MODELS_ROOT/${SpeechModelSpec.MODEL_ID}")
    }

    fun modelRevisionDir(context: Context): File {
        return File(modelRootDir(context), SpeechModelSpec.REVISION)
    }

    /** Removes obsolete Whisper model weights from older builds to reclaim user storage. */
    fun cleanupLegacyWhisperModels(context: Context) {
        if (legacyCleanupDone) return
        legacyCleanupDone = true
        runCatching {
            val legacyDir = File(context.applicationContext.filesDir, LEGACY_WHISPER_DIR)
            if (legacyDir.exists()) {
                legacyDir.deleteRecursively()
            }
        }
    }

    /**
     * Fast O(1) check that verifies all required `Voz` artifacts exist with exact expected byte
     * sizes and no incomplete `.part` files, without initializing native JNI handles.
     */
    fun isModelDownloaded(context: Context): Boolean {
        val appContext = context.applicationContext
        cleanupLegacyWhisperModels(appContext)
        return isModelDirectoryComplete(modelRevisionDir(appContext), SpeechModelSpec.ARTIFACTS)
    }

    fun isModelVerified(context: Context): Boolean {
        val dir = modelRevisionDir(context)
        return areAllArtifactsVerified(dir, SpeechModelSpec.ARTIFACTS)
    }

    /** Returns bytes already present on disk (complete files + `.part` files) for resume UI. */
    fun getCachedOrPartialBytes(context: Context): Long {
        val dir = modelRevisionDir(context)
        if (!dir.exists()) return 0L
        var sum = 0L
        for (artifact in SpeechModelSpec.ARTIFACTS) {
            val finalFile = File(dir, artifact.relativePath)
            val partFile = File(dir, "${artifact.relativePath}.part")
            sum += when {
                finalFile.exists() && finalFile.length() == artifact.sizeBytes -> artifact.sizeBytes
                partFile.exists() -> min(partFile.length(), artifact.sizeBytes)
                else -> 0L
            }
        }
        return sum
    }

    fun installedDirectoryBytes(context: Context): Long {
        val dir = modelRootDir(context)
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /** Pure completeness check, unit-testable without an Android Context. */
    fun isModelDirectoryComplete(
        targetDir: File,
        artifacts: List<SpeechModelArtifact> = SpeechModelSpec.ARTIFACTS,
    ): Boolean {
        if (!targetDir.exists() || !targetDir.isDirectory || artifacts.isEmpty()) {
            directoryCompleteCache.remove(targetDir.absolutePath)
            return false
        }
        val manifestFile = File(File(targetDir, DAL_META_DIR), DAL_MANIFEST_FILE)
        val dirKey = targetDir.absolutePath
        if (manifestFile.exists() && manifestFile.length() > 0L) {
            val cached = directoryCompleteCache[dirKey]
            val dirLastMod = targetDir.lastModified()
            if (cached != null && dirLastMod > 0L && dirLastMod == cached.first &&
                artifacts.size.toLong() == cached.second
            ) {
                return true
            }
        }
        for (artifact in artifacts) {
            val finalFile = File(targetDir, artifact.relativePath)
            val partFile = File(targetDir, "${artifact.relativePath}.part")
            if (partFile.exists() || !finalFile.exists() || !finalFile.isFile ||
                finalFile.length() != artifact.sizeBytes
            ) {
                directoryCompleteCache.remove(dirKey)
                return false
            }
        }
        if (!manifestFile.exists()) {
            runCatching { writeModelManifest(targetDir, artifacts) }
        }
        val finalDirMod = targetDir.lastModified()
        if (finalDirMod > 0L) {
            directoryCompleteCache[dirKey] = finalDirMod to artifacts.size.toLong()
        }
        return true
    }

    fun areAllArtifactsVerified(
        targetDir: File,
        artifacts: List<SpeechModelArtifact> = SpeechModelSpec.ARTIFACTS,
    ): Boolean {
        if (!isModelDirectoryComplete(targetDir, artifacts)) return false
        return artifacts.all { isArtifactAlreadyVerified(targetDir, it) }
    }

    fun isArtifactAlreadyVerified(targetDir: File, artifact: SpeechModelArtifact): Boolean {
        val finalFile = File(targetDir, artifact.relativePath)
        val partFile = File(targetDir, "${artifact.relativePath}.part")
        if (partFile.exists() || !finalFile.exists() || !finalFile.isFile ||
            finalFile.length() != artifact.sizeBytes
        ) {
            return false
        }
        val stampFile = File(targetDir, VERIFIED_STAMP_FILE)
        if (!stampFile.exists()) return false
        return try {
            val expectedLine = artifactStampLine(finalFile, artifact)
            stampFile.readLines().any { it.trim() == expectedLine }
        } catch (_: Throwable) {
            false
        }
    }

    @Synchronized
    fun markArtifactVerified(targetDir: File, artifact: SpeechModelArtifact) {
        val finalFile = File(targetDir, artifact.relativePath)
        if (!finalFile.exists() || finalFile.length() != artifact.sizeBytes) return
        runCatching {
            val stampFile = File(targetDir, VERIFIED_STAMP_FILE)
            val prefix = "${artifact.relativePath}\t"
            val existing = if (stampFile.exists()) {
                stampFile.readLines().filter { it.isNotBlank() && !it.startsWith(prefix) }
            } else {
                emptyList()
            }
            val updated = existing + artifactStampLine(finalFile, artifact)
            stampFile.writeText(updated.joinToString("\n") + "\n")
            directoryCompleteCache.remove(targetDir.absolutePath)
        }
    }

    @Synchronized
    fun writeModelManifest(
        targetDir: File,
        artifacts: List<SpeechModelArtifact> = SpeechModelSpec.ARTIFACTS,
    ) {
        if (!targetDir.exists()) targetDir.mkdirs()
        val metaDir = File(targetDir, DAL_META_DIR).apply { mkdirs() }
        val manifestFile = File(metaDir, DAL_MANIFEST_FILE)
        val rLines = artifacts.joinToString("\n") { "R\t${it.relativePath}" }
        val fLines = artifacts.joinToString("\n") {
            "F\t${it.relativePath}\t${it.sizeBytes}\t${it.sha256}"
        }
        manifestFile.writeText("DAL1\n$rLines\n$fLines\n")
    }

    @Synchronized
    fun writeModelManifestAndStamp(
        targetDir: File,
        artifacts: List<SpeechModelArtifact> = SpeechModelSpec.ARTIFACTS,
    ) {
        writeModelManifest(targetDir, artifacts)
        val stampLines = artifacts.mapNotNull { artifact ->
            val f = File(targetDir, artifact.relativePath)
            if (f.exists() && f.length() == artifact.sizeBytes) {
                artifactStampLine(f, artifact)
            } else {
                null
            }
        }
        if (stampLines.size == artifacts.size) {
            File(targetDir, VERIFIED_STAMP_FILE).writeText(stampLines.joinToString("\n") + "\n")
        }
        directoryCompleteCache.remove(targetDir.absolutePath)
    }

    private fun artifactStampLine(file: File, artifact: SpeechModelArtifact): String {
        return "${artifact.relativePath}\t${file.length()}\t${file.lastModified()}\t" +
            artifact.sha256.lowercase(Locale.ROOT)
    }

    /** Deletes any `.part` temporary files while keeping already verified files intact. */
    @Synchronized
    fun deletePartialFiles(context: Context) {
        val dir = modelRevisionDir(context)
        if (!dir.exists()) return
        dir.listFiles { f -> f.isFile && f.name.endsWith(".part") }?.forEach { it.delete() }
        directoryCompleteCache.remove(dir.absolutePath)
    }

    /** Deletes the downloaded `Voz` model from disk and returns freed bytes. */
    @Synchronized
    fun deleteModel(context: Context): Long {
        val root = modelRootDir(context)
        val revDir = modelRevisionDir(context)
        directoryCompleteCache.remove(revDir.absolutePath)
        if (!root.exists()) return 0L
        val bytes = installedDirectoryBytes(context)
        root.deleteRecursively()
        return bytes
    }
}
