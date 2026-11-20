package com.xenonware.notes.util.audio

import ai.desertant.voz.Voz
import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xenonware.notes.data.SharedPreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class AudioTrimResult(
    val samples: FloatArray,
    val startOffsetSamples: Int,
)

data class AudioChunkSlice(
    val samples: FloatArray,
    val startOffsetSamples: Int,
)

data class TimedWord(
    val text: String,
    val startMs: Long,
    val endMs: Long,
)

/**
 * Pure audio preprocessing & word-to-segment grouping helpers (unit-testable without native libs).
 */
object SpeechAudioProcessing {
    const val SAMPLE_RATE_HZ: Int = 16_000
    const val DEFAULT_SILENCE_THRESHOLD: Float = 0.0025f
    const val DEFAULT_SILENCE_PAD_SAMPLES: Int = 4_000 // 250 ms @ 16 kHz
    const val DEFAULT_MAX_CHUNK_SAMPLES: Int = 18 * SAMPLE_RATE_HZ
    const val DEFAULT_MIN_CHUNK_SAMPLES: Int = 10 * SAMPLE_RATE_HZ
    private const val PAUSE_BREAK_MS: Long = 1_500L
    private const val MAX_SEGMENT_WORDS: Int = 22

    fun trimSilenceWithOffset(
        samples: FloatArray,
        threshold: Float = DEFAULT_SILENCE_THRESHOLD,
        padSamples: Int = DEFAULT_SILENCE_PAD_SAMPLES,
    ): AudioTrimResult {
        if (samples.isEmpty()) return AudioTrimResult(FloatArray(0), 0)
        var first = -1
        var last = -1
        for (i in samples.indices) {
            if (abs(samples[i]) >= threshold) {
                if (first == -1) first = i
                last = i
            }
        }
        if (first == -1 || last == -1) return AudioTrimResult(FloatArray(0), 0)
        val start = max(0, first - padSamples)
        val end = min(samples.size, last + padSamples + 1)
        val sliced = if (start == 0 && end == samples.size) samples else samples.copyOfRange(start, end)
        return AudioTrimResult(sliced, start)
    }

    fun splitIntoSmartChunks(
        samples: FloatArray,
        maxChunkSamples: Int = DEFAULT_MAX_CHUNK_SAMPLES,
        minChunkSamples: Int = DEFAULT_MIN_CHUNK_SAMPLES,
    ): List<AudioChunkSlice> {
        if (samples.isEmpty()) return emptyList()
        if (samples.size <= maxChunkSamples) {
            return listOf(AudioChunkSlice(samples, 0))
        }
        val result = mutableListOf<AudioChunkSlice>()
        var cursor = 0
        val windowSize = 1_280 // 80 ms @ 16 kHz
        while (cursor < samples.size) {
            val remaining = samples.size - cursor
            if (remaining <= maxChunkSamples) {
                result.add(AudioChunkSlice(samples.copyOfRange(cursor, samples.size), cursor))
                break
            }
            val searchStart = cursor + minChunkSamples
            val searchEnd = cursor + maxChunkSamples
            var bestSplit = searchEnd
            var lowestEnergy = Float.MAX_VALUE
            var i = searchStart
            while (i + windowSize <= searchEnd) {
                var energy = 0f
                for (j in 0 until windowSize) {
                    val s = samples[i + j]
                    energy += s * s
                }
                if (energy < lowestEnergy) {
                    lowestEnergy = energy
                    bestSplit = i + windowSize / 2
                }
                i += windowSize / 2
            }
            if (bestSplit <= cursor) bestSplit = searchEnd
            result.add(AudioChunkSlice(samples.copyOfRange(cursor, bestSplit), cursor))
            cursor = bestSplit
        }
        return result
    }

    fun groupWordsIntoSegments(
        words: List<TimedWord>,
        fallbackText: String,
        fallbackStartMs: Long = 0L,
    ): List<TranscriptSegment> {
        val cleanedWords = words.mapNotNull { w ->
            val t = w.text.trim()
            if (t.isEmpty()) null else w.copy(text = t)
        }
        if (cleanedWords.isEmpty()) {
            val trimmed = fallbackText.trim()
            return if (trimmed.isEmpty()) {
                emptyList()
            } else {
                listOf(TranscriptSegment(text = trimmed, timestampMillis = max(0L, fallbackStartMs)))
            }
        }

        val segments = mutableListOf<TranscriptSegment>()
        val bucket = mutableListOf<TimedWord>()

        fun flushBucket() {
            if (bucket.isEmpty()) return
            val text = bucket.joinToString(" ") { it.text }.trim()
            if (text.isNotEmpty()) {
                segments.add(
                    TranscriptSegment(
                        text = text,
                        timestampMillis = max(0L, bucket.first().startMs),
                    )
                )
            }
            bucket.clear()
        }

        for (word in cleanedWords) {
            if (bucket.isNotEmpty()) {
                val prev = bucket.last()
                val gapMs = word.startMs - prev.endMs
                val prevEndsSentence = prev.text.endsWith('.') ||
                    prev.text.endsWith('?') ||
                    prev.text.endsWith('!')
                if (prevEndsSentence || gapMs >= PAUSE_BREAK_MS || bucket.size >= MAX_SEGMENT_WORDS) {
                    flushBucket()
                }
            }
            bucket.add(word)
        }
        flushBucket()
        return segments
    }
}

/**
 * Process-wide singleton holding the native Desert Ant `Voz` session so the model isn't reloaded
 * on every note sheet open/close, while automatically unloading after 5 minutes idle (`0 MB` standby).
 */
class SpeechEngine private constructor(context: Context) {

    companion object {
        private const val TAG = "SpeechEngine"
        private const val IDLE_UNLOAD_MS = 5 * 60_000L

        @Volatile
        private var instance: SpeechEngine? = null

        fun getInstance(context: Context): SpeechEngine {
            return instance ?: synchronized(this) {
                instance ?: SpeechEngine(context.applicationContext).also { instance = it }
            }
        }
    }

    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private val engineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var voz: Voz? = null
    private var appliedAccelerator: HardwareAccelerator? = null
    private var activeAcceleratorLabel: String = NpuHardwareManager.cachedHardwareSummaryOrQuick()
    private var idleUnloadJob: Job? = null

    fun currentAcceleratorLabel(): String = activeAcceleratorLabel

    suspend fun unloadModel() {
        idleUnloadJob?.cancel()
        idleUnloadJob = null
        mutex.withLock {
            closeLocked()
        }
    }

    private fun closeLocked() {
        runCatching { voz?.close() }
        voz = null
        appliedAccelerator = null
    }

    private fun scheduleIdleUnload() {
        idleUnloadJob?.cancel()
        idleUnloadJob = engineScope.launch {
            delay(IDLE_UNLOAD_MS)
            mutex.withLock {
                if (voz != null) {
                    Log.i(TAG, "Unloading idle Voz model to free RAM")
                    closeLocked()
                }
            }
        }
    }

    private fun isLowMemoryPressure(): Boolean {
        return runCatching {
            val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val info = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(info)
            val availMb = info.availMem / (1024L * 1024L)
            info.lowMemory || availMb < 900L
        }.getOrDefault(false)
    }

    suspend fun transcribeSamples(
        samples: FloatArray,
        requestedAccelerator: HardwareAccelerator,
        onStatus: ((String) -> Unit)? = null,
    ): TranscriptionRunResult = withContext(Dispatchers.Default) {
        if (samples.isEmpty()) {
            return@withContext TranscriptionRunResult(
                segments = emptyList(),
                acceleratorLabel = activeAcceleratorLabel,
                inferenceMs = 0L,
                audioDurationMs = 0L,
            )
        }

        val audioDurationMs = (samples.size.toLong() * 1000L) / SpeechAudioProcessing.SAMPLE_RATE_HZ
        val trimmed = SpeechAudioProcessing.trimSilenceWithOffset(samples)
        if (trimmed.samples.isEmpty()) {
            return@withContext TranscriptionRunResult(
                segments = emptyList(),
                acceleratorLabel = activeAcceleratorLabel,
                inferenceMs = 0L,
                audioDurationMs = audioDurationMs,
            )
        }

        idleUnloadJob?.cancel()

        mutex.withLock {
            if (!SpeechModelStorage.isModelDownloaded(appContext)) {
                throw IllegalStateException("Speech model is not downloaded yet.")
            }

            val lowMem = isLowMemoryPressure()
            val needsReload = voz == null || appliedAccelerator != requestedAccelerator
            if (needsReload) {
                closeLocked()
                activeAcceleratorLabel = NpuHardwareManager.configureNativeRuntime(
                    context = appContext,
                    requested = requestedAccelerator,
                    lowMemoryPressure = lowMem,
                )
                onStatus?.invoke("Loading ${SpeechModelSpec.DISPLAY_NAME} on $activeAcceleratorLabel…")
                val modelDir = SpeechModelStorage.modelRevisionDir(appContext).absolutePath
                voz = Voz(appContext, modelDir)
                appliedAccelerator = requestedAccelerator
            }

            val vozInstance = voz ?: throw IllegalStateException("Voz instance unavailable")
            val chunks = SpeechAudioProcessing.splitIntoSmartChunks(trimmed.samples)
            val allWords = mutableListOf<TimedWord>()
            val fallbackTexts = mutableListOf<String>()

            val startMs = SystemClock.elapsedRealtime()
            for ((index, chunk) in chunks.withIndex()) {
                val label = if (chunks.size > 1) {
                    "Transcribing (${index + 1}/${chunks.size})…"
                } else {
                    "Transcribing…"
                }
                onStatus?.invoke(label)

                val result = vozInstance.transcribe(
                    samples = chunk.samples,
                    sampleRate = SpeechAudioProcessing.SAMPLE_RATE_HZ.toDouble(),
                )
                val chunkBaseMs =
                    ((trimmed.startOffsetSamples + chunk.startOffsetSamples).toLong() * 1000L) /
                        SpeechAudioProcessing.SAMPLE_RATE_HZ

                if (result.words.isNotEmpty()) {
                    for (word in result.words) {
                        val wText = word.text.trim()
                        if (wText.isNotEmpty()) {
                            allWords.add(
                                TimedWord(
                                    text = wText,
                                    startMs = chunkBaseMs + (word.start * 1000.0).toLong(),
                                    endMs = chunkBaseMs + (word.end * 1000.0).toLong(),
                                )
                            )
                        }
                    }
                } else if (result.text.isNotBlank()) {
                    fallbackTexts.add(result.text.trim())
                }
            }

            val inferenceMs = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
            val segments = SpeechAudioProcessing.groupWordsIntoSegments(
                words = allWords,
                fallbackText = fallbackTexts.joinToString(" "),
                fallbackStartMs = (trimmed.startOffsetSamples.toLong() * 1000L) /
                    SpeechAudioProcessing.SAMPLE_RATE_HZ,
            )

            scheduleIdleUnload()
            TranscriptionRunResult(
                segments = segments,
                acceleratorLabel = activeAcceleratorLabel,
                inferenceMs = inferenceMs,
                audioDurationMs = audioDurationMs,
            )
        }
    }
}

data class TranscriptionRunResult(
    val segments: List<TranscriptSegment>,
    val acceleratorLabel: String,
    val inferenceMs: Long,
    val audioDurationMs: Long,
) {
    val speedupLabel: String?
        get() {
            if (inferenceMs <= 0L || audioDurationMs <= 0L) return null
            val x = audioDurationMs.toDouble() / inferenceMs.toDouble()
            return String.format(Locale.US, "%.1fx realtime", x)
        }
}

/**
 * UI-friendly on-device STT controller for [NoteAudioSheet], powered by Desert Ant `Voz`
 * (Parakeet TDT 0.6B v3) with hardware NPU / GPU / CPU dispatch.
 */
class SpeechRecognitionManager(
    private val context: Context,
) {
    companion object {
        private const val TAG = "SpeechRecognitionMgr"
    }

    private val appContext = context.applicationContext
    private val prefs = SharedPreferenceManager(appContext)
    private val engine = SpeechEngine.getInstance(appContext)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var isTranscribing by mutableStateOf(false)
        private set

    var isModelLoading by mutableStateOf(false)
        private set

    var statusMessage by mutableStateOf<String?>(null)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    var activeAcceleratorLabel by mutableStateOf(engine.currentAcceleratorLabel())
        private set

    var lastSpeedupLabel by mutableStateOf<String?>(null)
        private set

    var onTranscriptUpdate: ((List<TranscriptSegment>) -> Unit)? = null

    private val segments = mutableListOf<TranscriptSegment>()

    fun isAvailable(): Boolean = SpeechModelStorage.isModelDownloaded(appContext)

    fun selectedAccelerator(): HardwareAccelerator = prefs.speechHardwareAccelerator

    fun setAccelerator(accelerator: HardwareAccelerator) {
        prefs.speechHardwareAccelerator = accelerator
    }

    fun transcribeFile(
        audioId: String,
        onDone: (List<TranscriptSegment>) -> Unit = {},
    ) {
        val file = File(appContext.filesDir, "$audioId.mp3")
        if (!file.exists() || file.length() == 0L) {
            ioScope.launch(Dispatchers.Main) {
                errorMessage = "Audio file not found"
                Toast.makeText(appContext, "Audio file not found", Toast.LENGTH_LONG).show()
            }
            return
        }

        if (!isAvailable()) {
            ioScope.launch(Dispatchers.Main) {
                errorMessage = "Download Voz speech model (~${SpeechModelSpec.SIZE_MB} MB) to transcribe offline"
            }
            return
        }

        if (isTranscribing) {
            Log.w(TAG, "Transcription already in progress — ignoring duplicate trigger")
            return
        }

        ioScope.launch(Dispatchers.Main) {
            isTranscribing = true
            isModelLoading = true
            errorMessage = null
            statusMessage = "Decoding audio…"
        }

        ioScope.launch(Dispatchers.IO) {
            try {
                val audioData = AudioDecoder.decodeToFloat16kMono(file.absolutePath)
                if (audioData == null || audioData.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        errorMessage = "Failed to decode audio"
                        isTranscribing = false
                        isModelLoading = false
                        statusMessage = null
                        Toast.makeText(appContext, "Audio decode failed", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                val runResult = engine.transcribeSamples(
                    samples = audioData,
                    requestedAccelerator = prefs.speechHardwareAccelerator,
                    onStatus = { status ->
                        ioScope.launch(Dispatchers.Main) {
                            statusMessage = status
                            if (!status.startsWith("Loading")) {
                                isModelLoading = false
                            }
                            activeAcceleratorLabel = engine.currentAcceleratorLabel()
                        }
                    },
                )

                saveTranscript(appContext, audioId, runResult.segments)

                withContext(Dispatchers.Main) {
                    segments.clear()
                    segments.addAll(runResult.segments)
                    activeAcceleratorLabel = runResult.acceleratorLabel
                    lastSpeedupLabel = runResult.speedupLabel
                    isModelLoading = false
                    isTranscribing = false
                    statusMessage = null
                    onTranscriptUpdate?.invoke(segments.toList())
                    onDone(segments.toList())
                    if (runResult.segments.isEmpty()) {
                        errorMessage = "No speech detected"
                    }
                }
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Native library error", e)
                withContext(Dispatchers.Main) {
                    errorMessage = "Native speech runtime error"
                    isModelLoading = false
                    isTranscribing = false
                    statusMessage = null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Transcription error", e)
                withContext(Dispatchers.Main) {
                    errorMessage = "Transcription failed: ${e.message}"
                    isModelLoading = false
                    isTranscribing = false
                    statusMessage = null
                }
            }
        }
    }

    fun clearTranscript() {
        segments.clear()
        errorMessage = null
        onTranscriptUpdate?.invoke(emptyList())
    }

    fun loadTranscript(existing: List<TranscriptSegment>) {
        segments.clear()
        segments.addAll(existing)
        errorMessage = null
        onTranscriptUpdate?.invoke(segments.toList())
    }

    /** Leaves the process-wide [SpeechEngine] intact so consecutive notes stay warm until idle timeout. */
    fun dispose() {
        onTranscriptUpdate = null
    }
}
