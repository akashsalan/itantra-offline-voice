package org.itantra.app.core

import org.itantra.app.transport.RadioMode
import org.junit.Assert.*
import org.junit.Test

class ConnectionChoiceTest {
    @Test fun groupDefaultsToDirectWithoutRouter() {
        assertEquals(RadioMode.WIFI_DIRECT_GROUP, ConnectionChoice.restore(true, "BLUETOOTH").method)
        assertFalse(ConnectionChoice.methods(true).contains(RadioMode.BLUETOOTH))
    }
    @Test fun rememberedSelectionIsValidated() {
        assertEquals(RadioMode.HOTSPOT, ConnectionChoice.restore(true, "HOTSPOT").method)
        assertEquals(RadioMode.WIFI_DIRECT, ConnectionChoice.restore(false, "unknown").method)
    }
    @Test fun activeConversationCannotBeChangedBySelector() {
        assertFalse(ConnectionChoice.canChange(true, false, false, false))
        assertFalse(ConnectionChoice.canChange(false, true, false, false))
        assertFalse(ConnectionChoice.canChange(false, false, true, false))
        assertFalse(ConnectionChoice.canChange(false, false, false, true))
        assertTrue(ConnectionChoice.canChange(false, false, false, false))
    }
}
