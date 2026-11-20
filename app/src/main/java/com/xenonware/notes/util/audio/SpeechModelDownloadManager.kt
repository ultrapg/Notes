package com.xenonware.notes.util.audio

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.xenonware.notes.service.SpeechModelDownloadService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min

/**
 * Download lifecycle state for the on-device `Voz` speech recognition model.
 */
sealed interface SpeechDownloadState {
    data object Idle : SpeechDownloadState

    data class Downloading(
        val progress: Float,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val currentFileName: String = "",
        val speedBytesPerSec: Long = 0L,
        val etaSeconds: Long? = null,
        val statusNote: String? = null,
    ) : SpeechDownloadState {
        val clampedProgress: Float get() = progress.coerceIn(0f, 1f)
        val formattedBytes: String
            get() = "${formatBytesMb(downloadedBytes)} / ${formatBytesMb(totalBytes)}"
        val formattedSpeed: String get() = formatSpeed(speedBytesPerSec)
        val formattedEta: String get() = formatEta(etaSeconds)
    }

    data class Paused(
        val progress: Float,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val statusNote: String = "Download paused — progress saved on disk.",
    ) : SpeechDownloadState {
        val formattedBytes: String
            get() = "${formatBytesMb(downloadedBytes)} / ${formatBytesMb(totalBytes)}"
    }

    data class Completed(val modelName: String) : SpeechDownloadState

    data class Failed(
        val message: String,
        val downloadedBytes: Long = 0L,
        val totalBytes: Long = SpeechModelSpec.TOTAL_BYTES,
    ) : SpeechDownloadState

    companion object {
        fun formatBytesMb(bytes: Long): String {
            val mb = bytes.toDouble() / (1024.0 * 1024.0)
            return if (mb >= 100.0) {
                String.format(Locale.US, "%.0f MB", mb)
            } else {
                String.format(Locale.US, "%.1f MB", mb)
            }
        }

        fun formatSpeed(bytesPerSec: Long): String {
            if (bytesPerSec <= 0L) return "Connecting..."
            val kb = bytesPerSec.toDouble() / 1024.0
            return if (kb >= 1024.0) {
                String.format(Locale.US, "%.2f MB/s", kb / 1024.0)
            } else {
                String.format(Locale.US, "%.0f KB/s", kb)
            }
        }

        fun formatEta(seconds: Long?): String {
            if (seconds == null || seconds < 0L) return "Calculating time left..."
            if (seconds == 0L) return "Almost done..."
            val hours = seconds / 3600L
            val mins = (seconds % 3600L) / 60L
            val secs = seconds % 60L
            return when {
                hours > 0L -> String.format(Locale.US, "~%dh %02dm left", hours, mins)
                mins > 0L -> String.format(Locale.US, "~%dm %02ds left", mins, secs)
                else -> String.format(Locale.US, "~%ds left", secs)
            }
        }
    }
}

/**
 * App-scoped manager for resumable, background `Voz` model downloads with streaming SHA-256
 * verification, HTTP Range resume, rolling speed/ETA telemetry, and retry backoff.
 */
object SpeechModelDownloadManager {

    private const val TAG = "SpeechModelDownload"
    private const val MAX_STALL_RETRIES = 15
    private const val MAX_INTEGRITY_RETRIES = 3
    private const val CONNECT_TIMEOUT_MS = 45_000
    private const val READ_TIMEOUT_MS = 90_000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<SpeechDownloadState>(SpeechDownloadState.Idle)
    val state: StateFlow<SpeechDownloadState> = _state.asStateFlow()

    private var activeJob: Job? = null

    @Volatile
    private var pauseRequested = false

    @Volatile
    private var cancelAndResetRequested = false

    /** Optional hook mirrored by [SpeechModelDownloadService] for system notifications. */
    var progressListener: ((SpeechDownloadState) -> Unit)? = null

    val isDownloading: Boolean get() = _state.value is SpeechDownloadState.Downloading
    val isPaused: Boolean get() = _state.value is SpeechDownloadState.Paused

    private fun publish(newState: SpeechDownloadState) {
        _state.value = newState
        progressListener?.invoke(newState)
    }

    /**
     * Refreshes the idle/paused snapshot from disk so UI reopened after process restart displays
     * any partial download progress already saved on disk.
     */
    fun syncFromDiskIfIdle(context: Context) {
        if (activeJob?.isActive == true) return
        val current = _state.value
        if (current is SpeechDownloadState.Downloading || current is SpeechDownloadState.Completed) return
        val appContext = context.applicationContext
        if (SpeechModelStorage.isModelDownloaded(appContext)) {
            if (current !is SpeechDownloadState.Idle) publish(SpeechDownloadState.Idle)
            return
        }
        val partialBytes = SpeechModelStorage.getCachedOrPartialBytes(appContext)
        if (partialBytes > 0L && current !is SpeechDownloadState.Failed) {
            val total = SpeechModelSpec.TOTAL_BYTES
            val frac = (partialBytes.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
            publish(
                SpeechDownloadState.Paused(
                    progress = frac,
                    downloadedBytes = partialBytes,
                    totalBytes = total,
                    statusNote = "Saved ${SpeechDownloadState.formatBytesMb(partialBytes)} — tap Resume to continue.",
                )
            )
        }
    }

    /**
     * Starts or resumes downloading the `Voz` model in a foreground service. Idempotent when
     * already downloading.
     */
    @Synchronized
    fun start(context: Context) {
        if (activeJob?.isActive == true) {
            Log.i(TAG, "Download already in progress; ignoring duplicate start")
            return
        }

        val appContext = context.applicationContext
        pauseRequested = false
        cancelAndResetRequested = false

        if (SpeechModelStorage.isModelVerified(appContext)) {
            publish(SpeechDownloadState.Completed(SpeechModelSpec.DISPLAY_NAME))
            return
        }

        val totalBytes = SpeechModelSpec.TOTAL_BYTES
        val initialBytes = SpeechModelStorage.getCachedOrPartialBytes(appContext)
        val initialProgress = (initialBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)

        publish(
            SpeechDownloadState.Downloading(
                progress = initialProgress,
                downloadedBytes = initialBytes,
                totalBytes = totalBytes,
                statusNote = if (initialBytes > 0L) {
                    "Resuming from ${SpeechDownloadState.formatBytesMb(initialBytes)}..."
                } else {
                    "Connecting to model server..."
                },
            )
        )

        SpeechModelDownloadService.start(appContext)

        activeJob = scope.launch {
            val speedTracker = RollingSpeedTracker()
            try {
                downloadAllArtifactsResumable(appContext, speedTracker)
                publish(SpeechDownloadState.Completed(SpeechModelSpec.DISPLAY_NAME))
            } catch (ce: CancellationException) {
                if (cancelAndResetRequested) {
                    SpeechModelStorage.deletePartialFiles(appContext)
                    publish(SpeechDownloadState.Idle)
                } else {
                    val saved = SpeechModelStorage.getCachedOrPartialBytes(appContext)
                    val frac = (saved.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
                    publish(
                        SpeechDownloadState.Paused(
                            progress = frac,
                            downloadedBytes = saved,
                            totalBytes = totalBytes,
                            statusNote = "Paused at ${SpeechDownloadState.formatBytesMb(saved)} — tap Resume anytime.",
                        )
                    )
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Model download failed: ${t.message}", t)
                val saved = SpeechModelStorage.getCachedOrPartialBytes(appContext)
                publish(
                    SpeechDownloadState.Failed(
                        message = "Connection interrupted (${t.message ?: "Network error"}). " +
                            "Progress saved (${SpeechDownloadState.formatBytesMb(saved)}) — tap Retry to resume.",
                        downloadedBytes = saved,
                        totalBytes = totalBytes,
                    )
                )
            } finally {
                pauseRequested = false
                cancelAndResetRequested = false
                SpeechModelDownloadService.stop(appContext)
            }
        }
    }

    @Synchronized
    fun pause() {
        pauseRequested = true
        activeJob?.cancel()
        activeJob = null
    }

    /** Cancels any active transfer and removes partial `.part` files (keeping intact completed files). */
    @Synchronized
    fun cancel(context: Context) {
        val appContext = context.applicationContext
        cancelAndResetRequested = true
        val job = activeJob
        activeJob = null
        if (job != null && job.isActive) {
            job.cancel()
        } else {
            SpeechModelStorage.deletePartialFiles(appContext)
            publish(SpeechDownloadState.Idle)
        }
    }

    suspend fun cancelForDeletionAndWait(context: Context) {
        val appContext = context.applicationContext
        val job: Job?
        synchronized(this) {
            cancelAndResetRequested = true
            job = activeJob
            activeJob = null
        }
        job?.cancel()
        job?.join()
        SpeechModelStorage.deletePartialFiles(appContext)
        publish(SpeechDownloadState.Idle)
    }

    @Synchronized
    fun acknowledgeTerminalState() {
        val cur = _state.value
        if (cur is SpeechDownloadState.Completed || cur is SpeechDownloadState.Failed) {
            _state.value = SpeechDownloadState.Idle
        }
    }

    private suspend fun downloadAllArtifactsResumable(
        context: Context,
        speedTracker: RollingSpeedTracker,
    ) {
        val targetDir = SpeechModelStorage.modelRevisionDir(context)
        if (!targetDir.exists() && !targetDir.mkdirs() && !targetDir.exists()) {
            throw IOException("Cannot create model directory: ${targetDir.absolutePath}")
        }

        val artifacts = SpeechModelSpec.ARTIFACTS
        val totalBytes = SpeechModelSpec.TOTAL_BYTES
        var completedBytes = 0L

        for (artifact in artifacts) {
            coroutineContext.ensureActive()
            val finalFile = File(targetDir, artifact.relativePath)
            val partFile = File(targetDir, "${artifact.relativePath}.part")

            if (finalFile.exists() && finalFile.length() == artifact.sizeBytes) {
                if (SpeechModelStorage.isArtifactAlreadyVerified(targetDir, artifact)) {
                    completedBytes += artifact.sizeBytes
                    continue
                }
                val isValid = verifySha256Incremental(
                    file = finalFile,
                    expectedHex = artifact.sha256,
                    onVerifyProgress = { pct ->
                        val frac = (completedBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
                        publish(
                            SpeechDownloadState.Downloading(
                                progress = frac,
                                downloadedBytes = completedBytes,
                                totalBytes = totalBytes,
                                currentFileName = artifact.relativePath,
                                statusNote = "Verifying cached ${artifact.relativePath} ($pct%)...",
                            )
                        )
                    },
                )
                if (isValid) {
                    SpeechModelStorage.markArtifactVerified(targetDir, artifact)
                    completedBytes += artifact.sizeBytes
                    continue
                }
                finalFile.delete()
            }

            val completedBeforeFile = completedBytes
            downloadFileWithAutoResume(
                urlStr = artifact.url,
                partFile = partFile,
                expectedSize = artifact.sizeBytes,
                expectedSha256 = artifact.sha256,
                onProgress = { fileCurrentBytes, statusHint ->
                    val overallNow = (completedBeforeFile + fileCurrentBytes).coerceAtMost(totalBytes)
                    val speed = speedTracker.currentSpeedBytesPerSec()
                    val remainingBytes = max(0L, totalBytes - overallNow)
                    val eta = if (speed > 0L) remainingBytes / speed else null
                    val frac = (overallNow.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
                    publish(
                        SpeechDownloadState.Downloading(
                            progress = frac,
                            downloadedBytes = overallNow,
                            totalBytes = totalBytes,
                            currentFileName = artifact.relativePath,
                            speedBytesPerSec = speed,
                            etaSeconds = eta,
                            statusNote = statusHint,
                        )
                    )
                },
                onChunkReceived = { bytes -> speedTracker.recordBytes(bytes) },
            )

            if (finalFile.exists()) finalFile.delete()
            if (!partFile.renameTo(finalFile)) {
                partFile.copyTo(finalFile, overwrite = true)
                partFile.delete()
            }
            SpeechModelStorage.markArtifactVerified(targetDir, artifact)
            completedBytes += artifact.sizeBytes
        }

        SpeechModelStorage.writeModelManifestAndStamp(targetDir, artifacts)
        if (!SpeechModelStorage.isModelDownloaded(context)) {
            throw IOException("Post-download verification failed for ${SpeechModelSpec.DISPLAY_NAME}")
        }
    }

    private class IntegrityMismatchException(message: String) : IOException(message)

    private suspend fun downloadFileWithAutoResume(
        urlStr: String,
        partFile: File,
        expectedSize: Long,
        expectedSha256: String,
        onProgress: (Long, String?) -> Unit,
        onChunkReceived: (Int) -> Unit,
    ) = withContext(Dispatchers.IO) {
        var stallAttempt = 0
        var integrityAttempt = 0
        var lastEmitMs = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        var hashedOffset = 0L

        while (true) {
            coroutineContext.ensureActive()
            val existingBytes = if (partFile.exists()) partFile.length() else 0L
            if (existingBytes > expectedSize) {
                partFile.delete()
                digest.reset()
                hashedOffset = 0L
            }

            val resumeOffset = if (partFile.exists()) partFile.length() else 0L
            if (resumeOffset < hashedOffset) {
                digest.reset()
                hashedOffset = 0L
            }
            if (resumeOffset > hashedOffset) {
                hashedOffset = catchUpDigestToOffset(
                    file = partFile,
                    digest = digest,
                    fromOffset = hashedOffset,
                    toOffset = resumeOffset,
                    onVerifyProgress = { pct ->
                        onProgress(resumeOffset, "Verifying saved progress ($pct%)...")
                    },
                )
            }

            if (resumeOffset == expectedSize && expectedSize > 0L) {
                val actualHex = digest.digest().joinToString("") { "%02x".format(it) }
                if (actualHex.equals(expectedSha256, ignoreCase = true)) {
                    onProgress(expectedSize, null)
                    return@withContext
                } else {
                    integrityAttempt++
                    partFile.delete()
                    digest.reset()
                    hashedOffset = 0L
                    if (integrityAttempt > MAX_INTEGRITY_RETRIES) {
                        throw IntegrityMismatchException("SHA-256 mismatch for ${partFile.name}")
                    }
                    continue
                }
            }

            var connection: HttpURLConnection? = null
            var bytesReadThisConnection = 0L

            try {
                if (resumeOffset > 0L && stallAttempt == 0) {
                    onProgress(
                        resumeOffset,
                        "Resuming ${partFile.name.removeSuffix(".part")} (${SpeechDownloadState.formatBytesMb(resumeOffset)})...",
                    )
                }

                connection = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    instanceFollowRedirects = true
                    requestMethod = "GET"
                    setRequestProperty("Accept-Encoding", "identity")
                    setRequestProperty("Connection", "keep-alive")
                    if (resumeOffset > 0L) {
                        setRequestProperty("Range", "bytes=$resumeOffset-")
                    }
                }

                val responseCode = connection.responseCode
                val isPartial = responseCode == HttpURLConnection.HTTP_PARTIAL
                val isOk = responseCode == HttpURLConnection.HTTP_OK
                if (!isPartial && !isOk) {
                    throw IOException("HTTP $responseCode from server")
                }

                val startOffset = if (isPartial) resumeOffset else 0L
                RandomAccessFile(partFile, "rw").use { raf ->
                    if (!isPartial && resumeOffset > 0L) {
                        raf.setLength(0L)
                        digest.reset()
                        hashedOffset = 0L
                    }
                    raf.seek(startOffset)

                    connection.inputStream.use { input ->
                        val buffer = ByteArray(128 * 1024)
                        var currentSize = startOffset

                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            raf.write(buffer, 0, read)
                            if (hashedOffset == currentSize) {
                                digest.update(buffer, 0, read)
                                hashedOffset += read
                            }
                            currentSize += read
                            bytesReadThisConnection += read
                            onChunkReceived(read)

                            if (bytesReadThisConnection >= 64 * 1024L) {
                                stallAttempt = 0
                            }

                            val now = SystemClock.elapsedRealtime()
                            if (now - lastEmitMs >= 150L || currentSize >= expectedSize) {
                                lastEmitMs = now
                                onProgress(currentSize, null)
                            }
                        }
                    }
                }

                val finalLength = partFile.length()
                if (finalLength == expectedSize) {
                    if (hashedOffset < finalLength) {
                        hashedOffset = catchUpDigestToOffset(
                            file = partFile,
                            digest = digest,
                            fromOffset = hashedOffset,
                            toOffset = finalLength,
                            onVerifyProgress = { pct ->
                                onProgress(finalLength, "Finalizing SHA-256 check ($pct%)...")
                            },
                        )
                    }
                    val actualHex = digest.digest().joinToString("") { "%02x".format(it) }
                    if (!actualHex.equals(expectedSha256, ignoreCase = true)) {
                        integrityAttempt++
                        partFile.delete()
                        digest.reset()
                        hashedOffset = 0L
                        if (integrityAttempt > MAX_INTEGRITY_RETRIES) {
                            throw IntegrityMismatchException("SHA-256 mismatch for ${partFile.name}")
                        }
                        throw IOException("SHA-256 check failed (retry $integrityAttempt/$MAX_INTEGRITY_RETRIES)")
                    }
                    onProgress(finalLength, null)
                    return@withContext
                } else {
                    throw IOException("Incomplete stream ($finalLength / $expectedSize bytes)")
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (ie: IntegrityMismatchException) {
                throw ie
            } catch (e: Exception) {
                stallAttempt++
                if (stallAttempt > MAX_STALL_RETRIES) {
                    throw e
                }
                val backoffMs = min(15_000L, 1_500L * stallAttempt)
                val saved = if (partFile.exists()) partFile.length() else 0L
                Log.w(TAG, "Interrupted $urlStr (saved=$saved, retry $stallAttempt/$MAX_STALL_RETRIES): ${e.message}")
                onProgress(
                    saved,
                    "Connection hiccup — resuming (${stallAttempt}/$MAX_STALL_RETRIES)...",
                )
                delay(backoffMs)
            } finally {
                connection?.disconnect()
            }
        }
    }

    private suspend fun catchUpDigestToOffset(
        file: File,
        digest: MessageDigest,
        fromOffset: Long,
        toOffset: Long,
        onVerifyProgress: ((Int) -> Unit)? = null,
    ): Long = withContext(Dispatchers.IO) {
        if (toOffset <= fromOffset || !file.exists()) return@withContext fromOffset
        val totalToHash = (toOffset - fromOffset).coerceAtLeast(1L)
        var current = fromOffset
        var lastEmitMs = 0L
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(fromOffset)
            val buf = ByteArray(256 * 1024)
            while (current < toOffset) {
                coroutineContext.ensureActive()
                val toRead = min(buf.size.toLong(), toOffset - current).toInt()
                val r = raf.read(buf, 0, toRead)
                if (r <= 0) break
                digest.update(buf, 0, r)
                current += r
                val now = System.nanoTime() / 1_000_000L
                if (onVerifyProgress != null && (now - lastEmitMs >= 150L || current >= toOffset)) {
                    lastEmitMs = now
                    val pct = (((current - fromOffset) * 100L) / totalToHash).toInt().coerceIn(0, 100)
                    onVerifyProgress(pct)
                }
            }
        }
        current
    }

    suspend fun verifySha256Incremental(
        file: File,
        expectedHex: String,
        onVerifyProgress: ((Int) -> Unit)? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!file.exists() || !file.isFile) return@withContext false
        val totalLen = file.length()
        if (totalLen <= 0L) return@withContext false
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            catchUpDigestToOffset(file, digest, 0L, totalLen, onVerifyProgress)
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            actual.equals(expectedHex, ignoreCase = true)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            false
        }
    }

    private class RollingSpeedTracker {
        private val samples = ArrayDeque<Pair<Long, Long>>()
        private var totalBytesRecorded = 0L

        @Synchronized
        fun recordBytes(count: Int) {
            val now = SystemClock.elapsedRealtime()
            totalBytesRecorded += count
            samples.addLast(now to totalBytesRecorded)
            pruneOld(now)
        }

        @Synchronized
        fun currentSpeedBytesPerSec(): Long {
            val now = SystemClock.elapsedRealtime()
            pruneOld(now)
            if (samples.size < 2) return 0L
            val oldest = samples.first()
            val newest = samples.last()
            val dtMs = newest.first - oldest.first
            val dBytes = newest.second - oldest.second
            if (dtMs <= 100L || dBytes <= 0L) return 0L
            return (dBytes * 1000L) / dtMs
        }

        private fun pruneOld(nowMs: Long) {
            while (samples.size > 2 && nowMs - samples.first().first > 4_000L) {
                samples.removeFirst()
            }
        }
    }
}
