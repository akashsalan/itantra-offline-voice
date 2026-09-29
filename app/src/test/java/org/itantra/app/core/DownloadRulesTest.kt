package org.itantra.app.core

import org.junit.Assert.*
import org.junit.Test

class DownloadRulesTest {
    @Test fun resumesOnlyExactRange() { assertEquals(20L, DownloadRules.rangeStart(206, "bytes 20-99/100", 20, 100)) }
    @Test fun ignoredRangeRestartsInsteadOfAppending() { assertEquals(0L, DownloadRules.rangeStart(200, null, 20, 100)) }
    @Test fun changedOrOverlappingRangeIsRejected() {
        assertTrue(runCatching { DownloadRules.rangeStart(206, "bytes 0-99/100", 20, 100) }.isFailure)
        assertTrue(runCatching { DownloadRules.rangeStart(206, "bytes 20-89/90", 20, 100) }.isFailure)
    }
    @Test fun requiresTlsAndSpaceForBothArchiveAndExtraction() {
        assertFalse(DownloadRules.https("http://example.org/model"))
        assertFalse(DownloadRules.https("https://password@example.org/model"))
        assertTrue(DownloadRules.https("https://example.org/model"))
        assertEquals(180L + DownloadRules.RESERVE_BYTES, DownloadRules.requiredSpace(100, 20, 100))
    }
}
