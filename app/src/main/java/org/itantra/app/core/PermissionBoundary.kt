package org.itantra.app.core

/** Check at invocation/callback time, and handle revocation between check and API call. */
class PermissionBoundary(private val granted: () -> Boolean, private val lost: () -> IllegalStateException) {
    fun requireGranted() { if (!granted()) throw lost() }
    fun <T> call(operation: () -> T): T {
        requireGranted()
        return try { operation() } catch (_: SecurityException) { throw lost() }
    }
}
