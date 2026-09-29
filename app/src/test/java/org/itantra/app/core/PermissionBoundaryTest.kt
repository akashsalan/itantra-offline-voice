package org.itantra.app.core

import org.junit.Assert.*
import org.junit.Test

class PermissionBoundaryTest {
    @Test fun missingPermissionPreventsThePlatformCall() {
        var calls = 0; var invalidations = 0
        val guard = PermissionBoundary({ false }) { invalidations++; IllegalStateException("Permission needed") }
        try { guard.call { calls++ }; fail("Expected denial") } catch (_: IllegalStateException) { }
        assertEquals(0, calls); assertEquals(1, invalidations)
    }
    @Test fun callbackRechecksPermissionInsteadOfUsingAnEarlierGrant() {
        var granted = true; var calls = 0
        val guard = PermissionBoundary({ granted }) { IllegalStateException("Revoked") }
        guard.requireGranted()
        val callback = { guard.call { calls++ } }
        granted = false
        try { callback(); fail("Expected denial") } catch (_: IllegalStateException) { }
        assertEquals(0, calls)
    }
    @Test fun revocationBetweenCheckAndApiCallBecomesARecoverableFailure() {
        var invalidations = 0
        val guard = PermissionBoundary({ true }) { invalidations++; IllegalStateException("Retry after granting") }
        try { guard.call { throw SecurityException("Revoked") }; fail("Expected denial") }
        catch (error: IllegalStateException) { assertEquals("Retry after granting", error.message) }
        assertEquals(1, invalidations)
    }
    @Test fun permissionCanBeGrantedAndRetriedWithoutAProcessRestart() {
        var granted = false
        val guard = PermissionBoundary({ granted }) { IllegalStateException("Denied") }
        try { guard.requireGranted(); fail("Expected denial") } catch (_: IllegalStateException) { }
        granted = true
        assertEquals(42, guard.call { 42 })
    }
}
