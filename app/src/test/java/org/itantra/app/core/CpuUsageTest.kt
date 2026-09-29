package org.itantra.app.core

import org.junit.Assert.*
import org.junit.Test

class CpuUsageTest {
    @Test fun measuresCpuAgainstOneCoreNotNumberOfDeviceCores() {
        val meter = CpuUsage(); meter.mode("IDLE_CONNECTED")
        assertNull(meter.sample(1000, 500))
        assertEquals(5.0, meter.sample(6000, 750)!!.oneCorePercent, .001)
        assertEquals(200.0, meter.sample(7000, 2750)!!.oneCorePercent, .001)
    }
    @Test fun modeTransitionsExcludeMixedActiveIdleWindows() {
        val meter = CpuUsage(); meter.mode("IDLE_CONNECTED"); meter.sample(1000, 100)
        meter.mode("ACTIVE"); assertNull(meter.sample(1500, 400))
        meter.mode("IDLE_CONNECTED"); assertNull(meter.sample(2000, 500))
        assertEquals(5.0, meter.sample(3000, 550)!!.oneCorePercent, .001)
    }
    @Test fun rejectsResetCountersAndNonpositiveDurations() {
        val meter = CpuUsage(); meter.mode("HANDS_FREE_WAITING"); meter.sample(1000, 100)
        assertNull(meter.sample(1000, 110)); assertNull(meter.sample(2000, 0))
        meter.reset(); assertNull(meter.sample(3000, 10))
    }
}
