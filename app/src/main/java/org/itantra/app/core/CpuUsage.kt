package org.itantra.app.core

data class CpuSample(val mode: String, val wallMs: Long, val processCpuMs: Long) {
    /** One fully occupied CPU core = 100%; a multi-threaded process can exceed 100%. */
    val oneCorePercent get() = 100.0 * processCpuMs / wallMs
}

class CpuUsage {
    private var mode = "ACTIVE"
    private var previousWall: Long? = null
    private var previousCpu = 0L
    fun mode(value: String) {
        if (mode != value) { mode = value; reset() }
    }
    fun reset() { previousWall = null }
    fun sample(wallMs: Long, processCpuMs: Long): CpuSample? {
        val previous = previousWall
        val cpuDelta = processCpuMs - previousCpu
        previousWall = wallMs; previousCpu = processCpuMs
        if (previous == null || wallMs <= previous || cpuDelta < 0 || mode == "ACTIVE") return null
        return CpuSample(mode, wallMs - previous, cpuDelta)
    }
}
