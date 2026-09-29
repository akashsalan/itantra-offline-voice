package org.itantra.app.core

import org.junit.Assert.*
import org.junit.Test

class StreamingUtteranceTest {
    private class Fake : StreamingDecoder {
        var stopped = 0; var closed = 0; var finals = 0; var partials = 0
        override fun accept(samples: FloatArray) { assertTrue(samples.all { it in -1f..1f }) }
        override fun decode(final: Boolean): List<StreamingText> {
            if (final) finals++ else partials++
            return listOf(StreamingText(1, if (final) "help is here" else "help"))
        }
        override fun stop() { stopped++ }
        override fun close() { closed++ }
    }
    @Test fun streamsBeforeFinishAndUpdatesOneLineWithoutDuplicates() {
        val native = Fake(); val stream = StreamingUtterance(native)
        stream.accept(ShortArray(8000))
        assertEquals(1, native.partials); assertEquals("help", stream.partialText())
        val result = stream.finish()
        assertEquals("help is here", result.text); assertEquals(0.5, result.audioSeconds, 0.0)
    }
    @Test fun finishAndRepeatedReleaseFlushOnlyOnce() {
        val native = Fake(); val stream = StreamingUtterance(native)
        stream.accept(shortArrayOf(Short.MIN_VALUE, Short.MAX_VALUE))
        assertSame(stream.finish(), stream.finish())
        stream.close()
        assertEquals(1, native.stopped); assertEquals(1, native.finals); assertEquals(1, native.closed)
    }
    @Test fun discardDoesNotRequestFinalTranscription() {
        val native = Fake(); val stream = StreamingUtterance(native)
        stream.accept(ShortArray(320)); stream.close(); stream.close()
        assertEquals(0, native.finals); assertEquals(1, native.closed)
        assertThrows(IllegalStateException::class.java) { stream.finish() }
    }
    @Test fun preservesMaximumCaptureBudgetAndRejectsWrongRate() {
        val stream = StreamingUtterance(Fake())
        assertThrows(IllegalArgumentException::class.java) { stream.accept(ShortArray(10), 8000) }
        stream.accept(ShortArray(240000))
        assertThrows(IllegalArgumentException::class.java) { stream.accept(ShortArray(1)) }
        assertEquals(15.0, stream.finish().audioSeconds, 0.0)
    }
    @Test fun noAudioCanBeAddedAfterFinish() {
        val stream = StreamingUtterance(Fake()); stream.finish()
        assertThrows(IllegalStateException::class.java) { stream.accept(ShortArray(320)) }
    }
}
