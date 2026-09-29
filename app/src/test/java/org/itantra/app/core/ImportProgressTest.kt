package org.itantra.app.core

import org.junit.Assert.*
import org.junit.Test

class ImportProgressTest {
    @Test fun progressCountsActualCopyAndBothChecksumPasses() {
        val events = mutableListOf<PackImportProgress>()
        val tracker = ImportProgressTracker(LanguageCode.OR, 100, events::add)
        tracker.copied(100, "model")
        assertEquals(33, events.last().percent)
        tracker.verified(100, "model")
        assertEquals(66, events.last().percent)
        tracker.verified(100, "model", true)
        assertEquals(99, events.last().percent)
        tracker.committed()
        assertEquals(100, events.last().percent)
        assertEquals(ImportStage.IMPORTED, events.last().stage)
        assertEquals(100L, events.last().copiedBytes)
        assertTrue(events.zipWithNext().all { (a, b) -> a.percent!! <= b.percent!! })
    }
    @Test fun multipleFilesAndLanguagesUseTheSameCounter() {
        LanguageCode.entries.forEach { language ->
            val events = mutableListOf<PackImportProgress>()
            val tracker = ImportProgressTracker(language, 100, events::add)
            tracker.copied(80, "model"); tracker.verified(80, "model")
            tracker.copied(20, "tokens"); tracker.verified(20, "tokens")
            tracker.verified(80, "model", true); tracker.verified(20, "tokens", true)
            tracker.committed()
            assertEquals(language, events.last().language)
            assertEquals(100, events.last().percent)
        }
    }
    @Test(expected = IllegalStateException::class) fun cannotFinishBeforeChecks() {
        ImportProgressTracker(LanguageCode.HI, 100) {}.committed()
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsExtraCopiedBytes() {
        ImportProgressTracker(LanguageCode.EN, 100) {}.copied(101, "model")
    }
    @Test fun smallReadsDoNotFloodTheUi() {
        val events = mutableListOf<PackImportProgress>()
        val tracker = ImportProgressTracker(LanguageCode.EN, 10000, events::add)
        repeat(10000) { tracker.copied(1, "model") }
        assertTrue(events.size <= 35)
        assertEquals(33, events.last().percent)
    }
}
