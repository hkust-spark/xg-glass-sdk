package com.xgglass.device.rokid

import com.xgglass.core.AudioChunk
import com.xgglass.core.AudioEncoding
import com.xgglass.core.AudioFormat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RokidMicrophoneStreamTest {
    private val format = AudioFormat(AudioEncoding.PCM_S16_LE)

    @Test fun foreignStreamCannotFinishActiveMicrophone() = runTest {
        var closed = 0
        var finished = 0
        val stream = RokidMicrophoneStream(format, closeRecorder = { closed++ }, onFinished = { finished++ })
        val chunks = mutableListOf<AudioChunk>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { stream.audio.collect(chunks::add) }
        stream.onStartAudioStream(7, 1, 1, "xgglass")
        stream.onAudioStreamFinish(99)
        assertTrue(stream.isRunning)
        val buffer = byteArrayOf(0, 1, 2, 3)
        stream.onAudioStream(7, buffer, 1, 2)
        buffer[1] = 42
        stream.onAudioStreamFinish(7)
        stream.stop()
        assertEquals(2, chunks.size)
        assertContentEquals(byteArrayOf(1, 2), chunks[0].bytes)
        assertTrue(chunks[1].endOfStream)
        assertEquals(0, closed)
        assertEquals(1, finished)
    }

    @Test fun explicitStopClosesOnceAndIgnoresEveryLateCallback() = runTest {
        var closed = 0
        var finished = 0
        val stream = RokidMicrophoneStream(format, closeRecorder = { closed++ }, onFinished = { finished++ })
        val chunks = mutableListOf<AudioChunk>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { stream.audio.collect(chunks::add) }
        stream.onStartAudioStream(7, 1, 1, "xgglass")
        stream.stop()
        stream.stop()
        stream.onStartAudioStream(8, 1, 1, "xgglass")
        stream.onAudioStream(7, byteArrayOf(1), 0, 1)
        stream.onAudioStreamFinish(7)
        assertEquals(1, closed)
        assertEquals(1, finished)
        assertEquals(1, chunks.size)
        assertTrue(chunks.single().endOfStream)
    }

    @Test fun invalidFramesCannotOverflowOrEscapeTheirSlice() = runTest {
        val warnings = mutableListOf<String>()
        val stream = RokidMicrophoneStream(format, closeRecorder = {}, onFinished = {}, warn = warnings::add)
        val chunks = mutableListOf<AudioChunk>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { stream.audio.collect(chunks::add) }
        stream.onStartAudioStream(7, 1, 1, "xgglass")
        stream.onAudioStream(7, null, 0, 1)
        stream.onAudioStream(7, byteArrayOf(1), -1, 1)
        stream.onAudioStream(7, byteArrayOf(1), Int.MAX_VALUE, Int.MAX_VALUE)
        stream.onAudioStream(7, byteArrayOf(1), 0, 2)
        stream.onAudioStream(7, byteArrayOf(5), 0, 1)
        assertEquals(1, chunks.size)
        assertContentEquals(byteArrayOf(5), chunks.single().bytes)
        assertEquals(1, warnings.size)
        stream.stop()
    }

    @Test fun oldConnectionAndRejectedStartCannotCloseAnotherRecorder() = runTest {
        var current = true
        var closed = 0
        var finished = 0
        val stream = RokidMicrophoneStream(format, isCurrentConnection = { current }, closeRecorder = { closed++ }, onFinished = { finished++ })
        val chunks = mutableListOf<AudioChunk>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { stream.audio.collect(chunks::add) }
        stream.onStartAudioStream(7, 1, 1, "xgglass")
        current = false
        stream.onAudioStream(7, byteArrayOf(1), 0, 1)
        stream.onAudioStreamFinish(7)
        assertTrue(chunks.isEmpty())
        stream.cancelStart()
        stream.stop()
        assertFalse(stream.isRunning)
        assertEquals(0, closed)
        assertEquals(1, finished)
    }
}
