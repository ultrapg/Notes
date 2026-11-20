package com.xenonware.notes

import com.xenonware.notes.util.audio.SpeechAudioProcessing
import com.xenonware.notes.util.audio.TimedWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechAudioProcessingTest {

    @Test
    fun `trimSilenceWithOffset strips leading and trailing silence while preserving exact offset`() {
        val samples = FloatArray(16_000) { 0f }
        for (i in 6_000 until 10_000) {
            samples[i] = 0.25f
        }

        val trimmed = SpeechAudioProcessing.trimSilenceWithOffset(
            samples = samples,
            threshold = 0.01f,
            padSamples = 500,
        )

        assertEquals(5_500, trimmed.startOffsetSamples)
        assertEquals(5_000, trimmed.samples.size)
        assertEquals(0.25f, trimmed.samples[500], 0.0001f)
    }

    @Test
    fun `trimSilenceWithOffset returns empty when audio is pure silence`() {
        val silence = FloatArray(8_000) { 0.0001f }
        val trimmed = SpeechAudioProcessing.trimSilenceWithOffset(silence, threshold = 0.01f)
        assertEquals(0, trimmed.samples.size)
    }

    @Test
    fun `splitIntoSmartChunks keeps short audio unchunked and splits long audio at silence`() {
        val shortAudio = FloatArray(5_000) { 0.1f }
        val shortChunks = SpeechAudioProcessing.splitIntoSmartChunks(
            samples = shortAudio,
            maxChunkSamples = 10_000,
            minChunkSamples = 4_000,
        )
        assertEquals(1, shortChunks.size)
        assertEquals(0, shortChunks.first().startOffsetSamples)

        // 20,000 samples with a quiet valley around sample 11,500
        val longAudio = FloatArray(20_000) { 0.3f }
        for (i in 10_500 until 12_500) {
            longAudio[i] = 0.0f
        }
        val chunks = SpeechAudioProcessing.splitIntoSmartChunks(
            samples = longAudio,
            maxChunkSamples = 14_000,
            minChunkSamples = 8_000,
        )
        assertEquals(2, chunks.size)
        assertEquals(0, chunks[0].startOffsetSamples)
        val splitPoint = chunks[1].startOffsetSamples
        assertTrue("Expected split inside silence valley (was $splitPoint)", splitPoint in 10_500..12_500)
        assertEquals(longAudio.size, chunks[0].samples.size + chunks[1].samples.size)
    }

    @Test
    fun `groupWordsIntoSegments splits on punctuation, long pauses, and max words`() {
        val words = listOf(
            TimedWord("Hello", 200L, 450L),
            TimedWord("world.", 480L, 800L),
            TimedWord("Next", 900L, 1_100L),
            TimedWord("thought", 1_150L, 1_400L),
            TimedWord("after", 3_200L, 3_450L),
            TimedWord("pause", 3_500L, 3_800L),
        )

        val segments = SpeechAudioProcessing.groupWordsIntoSegments(words, fallbackText = "")
        assertEquals(3, segments.size)
        assertEquals("Hello world.", segments[0].text)
        assertEquals(200L, segments[0].timestampMillis)
        assertEquals("Next thought", segments[1].text)
        assertEquals(900L, segments[1].timestampMillis)
        assertEquals("after pause", segments[2].text)
        assertEquals(3_200L, segments[2].timestampMillis)
    }

    @Test
    fun `groupWordsIntoSegments caps oversized run-on sentences and handles fallback text`() {
        val runOn = (1..25).map { idx ->
            TimedWord("w$idx", idx * 100L, idx * 100L + 50L)
        }
        val splitSegments = SpeechAudioProcessing.groupWordsIntoSegments(runOn, fallbackText = "")
        assertEquals(2, splitSegments.size)
        assertEquals(22, splitSegments[0].text.split(" ").size)
        assertEquals(3, splitSegments[1].text.split(" ").size)

        val fallback = SpeechAudioProcessing.groupWordsIntoSegments(emptyList(), "  Fallback line  ", 1_234L)
        assertEquals(1, fallback.size)
        assertEquals("Fallback line", fallback[0].text)
        assertEquals(1_234L, fallback[0].timestampMillis)
    }
}
