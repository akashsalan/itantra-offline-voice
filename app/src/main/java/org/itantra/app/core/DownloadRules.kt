package org.itantra.app.core

import java.net.URI

object DownloadRules {
    const val RESERVE_BYTES = 32L * 1024 * 1024
    fun https(url: String): Boolean = runCatching {
        val value = URI(url)
        value.scheme == "https" && !value.host.isNullOrBlank() && value.userInfo == null && value.fragment == null
    }.getOrDefault(false)
    fun requiredSpace(total: Long, partial: Long, installed: Long) =
        (total - partial.coerceIn(0, total)) + installed + RESERVE_BYTES
    /** Never append a 200 response to a partial file. Reject overlapping/changed range bodies. */
    fun rangeStart(code: Int, header: String?, partial: Long, total: Long): Long {
        if (code == 200) return 0
        require(code == 206) { "Download unavailable. Retry later or import by USB." }
        val match = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(header.orEmpty())
        require(match != null) { "Server returned an invalid download range." }
        val (start, end, size) = match.destructured
        require(start.toLong() == partial && end.toLong() == total - 1 && size.toLong() == total) {
            "Download changed on the server. Cancel and retry."
        }
        return partial
    }
}
