package org.itantra.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.itantra.app.core.HandsFreeState
import org.itantra.app.data.MessageEntity
import org.itantra.app.data.MessageStore
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Isolated fixture DB only; never clears application history or model packs. */
class HandsFreePersistenceTest {
    @Test fun consentIsRecheckedInsideThePersistenceBoundary() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "hands-free-fixture-${UUID.randomUUID()}.db"
        val store = MessageStore(context, name)
        try {
            var consent = HandsFreeState().start("wifi:fixture")
            val generation = consent.generation
            val row = MessageEntity().apply { key = "fixture-voice"; direction = "OUT"; text = "Test message"; language = "en" }
            consent = consent.end()
            try {
                store.insert(row) { consent.canSend(generation, "wifi:fixture") }
                fail("Ended hands-free session must not insert a transcript")
            } catch (_: IllegalStateException) { }
            assertNull(store.find(row.key))
            assertTrue(store.insert(row)) // Explicit/manual insertion remains supported.
            assertEquals("Test message", store.find(row.key)?.text)
        } finally { store.close(); context.deleteDatabase(name) }
    }
}
