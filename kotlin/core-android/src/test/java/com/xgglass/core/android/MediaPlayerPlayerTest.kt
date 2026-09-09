package com.xgglass.core.android

import android.media.MediaPlayer
import com.xgglass.core.GlassesError
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MediaPlayerPlayerTest {
    @After fun resetDispatcher() = Dispatchers.resetMain()

    private class Harness {
        var current: MediaPlayer? = null
        val files = mutableListOf<File>()
        suspend fun play(player: MediaPlayer, ready: CompletableDeferred<Unit>, interrupt: Boolean = false): Result<Unit> {
            return playEncodedWithPlayer(
                data = byteArrayOf(1, 2, 3), usageAttributes = 1, interrupt = interrupt,
                tempFileFactory = { File.createTempFile("playback-test", ".tmp").also { files.add(it) } },
                currentPlayer = { current },
                setCurrentPlayer = { current = it; if (it != null) ready.complete(Unit) },
                playerFactory = { player }, attributesFactory = { _, _ -> mockk(relaxed = true) },
            )
        }
    }

    @Test fun `interrupt finishes old caller and late callback cannot clear new player`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val harness = Harness()
        val first = mockk<MediaPlayer>(relaxed = true)
        val second = mockk<MediaPlayer>(relaxed = true)
        var firstCompletion: MediaPlayer.OnCompletionListener? = null
        var secondCompletion: MediaPlayer.OnCompletionListener? = null
        every { first.setOnCompletionListener(any()) } answers {
            firstArg<MediaPlayer.OnCompletionListener?>()?.let { firstCompletion = it }
        }
        every { second.setOnCompletionListener(any()) } answers {
            firstArg<MediaPlayer.OnCompletionListener?>()?.let { secondCompletion = it }
        }
        val ready1 = CompletableDeferred<Unit>()
        val call1 = async { harness.play(first, ready1) }
        ready1.await()
        val ready2 = CompletableDeferred<Unit>()
        val call2 = async { harness.play(second, ready2, interrupt = true) }
        ready2.await()
        assertTrue(call1.await().isFailure)
        firstCompletion!!.onCompletion(first)
        assertSame(second, harness.current)
        secondCompletion!!.onCompletion(second)
        assertTrue(call2.await().isSuccess)
        verify(exactly = 1) { first.release() }
        verify(exactly = 1) { second.release() }
        assertTrue(harness.files.none { it.exists() })
    }

    @Test fun `disconnect ends pending preparation without a vendor callback`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val harness = Harness()
        val player = mockk<MediaPlayer>(relaxed = true)
        val ready = CompletableDeferred<Unit>()
        val call = async { harness.play(player, ready) }
        ready.await()
        stopEncodedPlayback(player)
        assertTrue(call.await().isFailure)
        verify(exactly = 1) { player.release() }
        assertTrue(harness.files.none { it.exists() })
    }

    @Test fun `noninterrupting call returns Busy and preserves the session for disconnect`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val harness = Harness()
        val first = mockk<MediaPlayer>(relaxed = true)
        val second = mockk<MediaPlayer>(relaxed = true)
        val ready = CompletableDeferred<Unit>()
        val call = async { harness.play(first, ready) }
        ready.await()
        assertSame(GlassesError.Busy, harness.play(second, CompletableDeferred()).exceptionOrNull())
        assertSame(first, harness.current)
        verify(exactly = 0) { second.prepareAsync() }
        stopEncodedPlayback(first)
        assertTrue(call.await().isFailure)
        assertTrue(harness.files.none { it.exists() })
    }

    @Test fun `temporary file creation failure is returned without creating a player`() = runTest {
        val result = playEncodedWithPlayer(
            data = byteArrayOf(1), usageAttributes = 1, interrupt = false,
            tempFileFactory = { throw java.io.IOException("No writable cache") },
            playerFactory = { error("Must not create a player") },
        )
        assertTrue(result.exceptionOrNull() is java.io.IOException)
    }

    @Test fun `cancellation and synchronous prepare failure release all resources`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val harness = Harness()
        val player = mockk<MediaPlayer>(relaxed = true)
        val ready = CompletableDeferred<Unit>()
        val call = async { harness.play(player, ready) }
        ready.await()
        call.cancel()
        runCurrent()
        assertTrue(call.isCancelled)
        verify(exactly = 1) { player.release() }
        val failing = mockk<MediaPlayer>(relaxed = true)
        every { failing.prepareAsync() } throws IllegalStateException("bad media")
        val failed = harness.play(failing, CompletableDeferred())
        assertTrue(failed.isFailure)
        verify(exactly = 1) { failing.release() }
        assertFalse(harness.files.any { it.exists() })
    }
}
