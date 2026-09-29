package org.itantra.app.core

import org.itantra.app.transport.RadioMode

/** UI selection is not a connection attempt. Restoring it never starts a radio. */
data class ConnectionChoice(val group: Boolean, val method: RadioMode) {
    companion object {
        fun methods(group: Boolean): List<RadioMode> = if (group)
            listOf(RadioMode.WIFI_DIRECT_GROUP, RadioMode.SAME_WIFI, RadioMode.HOTSPOT)
        else listOf(RadioMode.WIFI_DIRECT, RadioMode.BLUETOOTH, RadioMode.SAME_WIFI, RadioMode.HOTSPOT)

        fun restore(group: Boolean, method: String): ConnectionChoice {
            val parsed = RadioMode.entries.firstOrNull { it.name == method }
            return ConnectionChoice(group, parsed?.takeIf { it in methods(group) } ?: methods(group).first())
        }

        fun canChange(connected: Boolean, hosting: Boolean, connecting: Boolean, busy: Boolean) =
            !connected && !hosting && !connecting && !busy
    }
}
