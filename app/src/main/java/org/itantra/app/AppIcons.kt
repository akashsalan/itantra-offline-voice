package org.itantra.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Small app-authored vector; no image/font download or extended icon dependency. */
internal object AppIcons {
    private fun outline(name: String, draw: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = draw)
        }.build()
    val Connections = outline("Connections") {
        moveTo(3f, 8f); quadTo(12f, 0f, 21f, 8f)
        moveTo(6f, 12f); quadTo(12f, 6f, 18f, 12f)
        moveTo(9f, 16f); quadTo(12f, 13f, 15f, 16f)
        moveTo(12f, 20f); lineTo(12f, 20.1f)
    }
    val Bluetooth = outline("Bluetooth") {
        moveTo(7f, 6f); lineTo(17f, 16f); lineTo(12f, 21f); lineTo(12f, 3f)
        lineTo(17f, 8f); lineTo(7f, 18f)
    }
    val Hotspot = outline("Phone hotspot") {
        moveTo(5f, 6f); quadTo(-1f, 12f, 5f, 18f)
        moveTo(19f, 6f); quadTo(25f, 12f, 19f, 18f)
        moveTo(8f, 9f); quadTo(5f, 12f, 8f, 15f)
        moveTo(16f, 9f); quadTo(19f, 12f, 16f, 15f)
        moveTo(12f, 10f); lineTo(12f, 17f); moveTo(10f, 20f); lineTo(14f, 20f)
    }
    val Messages = outline("Messages") {
        moveTo(5f, 4f); lineTo(19f, 4f); quadTo(21f, 4f, 21f, 6f)
        lineTo(21f, 16f); quadTo(21f, 18f, 19f, 18f); lineTo(9f, 18f)
        lineTo(3f, 22f); lineTo(3f, 6f); quadTo(3f, 4f, 5f, 4f); close()
        moveTo(7f, 9f); lineTo(17f, 9f); moveTo(7f, 13f); lineTo(14f, 13f)
    }
    val Models = outline("Language models") {
        moveTo(5f, 3f); lineTo(17f, 3f); quadTo(20f, 3f, 20f, 6f)
        lineTo(20f, 21f); lineTo(6f, 21f); quadTo(3f, 21f, 3f, 18f)
        lineTo(3f, 6f); quadTo(3f, 3f, 5f, 3f); close()
        moveTo(3f, 17f); lineTo(20f, 17f); moveTo(8f, 7f); lineTo(15f, 7f)
        moveTo(8f, 11f); lineTo(13f, 11f)
    }
    val Settings = outline("Settings") {
        moveTo(4f, 6f); lineTo(9f, 6f); moveTo(13f, 6f); lineTo(20f, 6f)
        moveTo(4f, 12f); lineTo(15f, 12f); moveTo(19f, 12f); lineTo(20f, 12f)
        moveTo(4f, 18f); lineTo(5f, 18f); moveTo(9f, 18f); lineTo(20f, 18f)
        moveTo(11f, 4f); lineTo(11f, 8f); moveTo(17f, 10f); lineTo(17f, 14f)
        moveTo(7f, 16f); lineTo(7f, 20f)
    }
    val Diagnostics = outline("Diagnostics") {
        moveTo(4f, 3f); lineTo(4f, 21f); lineTo(21f, 21f)
        moveTo(8f, 16f); lineTo(8f, 12f); moveTo(13f, 16f); lineTo(13f, 6f)
        moveTo(18f, 16f); lineTo(18f, 9f)
    }
    val Call = outline("Hands-free conversation") {
        moveTo(5f, 3f); lineTo(8f, 3f); lineTo(10f, 8f); lineTo(7f, 10f)
        quadTo(9f, 15f, 14f, 17f); lineTo(16f, 14f); lineTo(21f, 16f)
        lineTo(21f, 19f); quadTo(21f, 22f, 17f, 21f)
        curveTo(9f, 19f, 5f, 15f, 3f, 7f); quadTo(2f, 3f, 5f, 3f); close()
    }
    val EndCall = outline("End hands-free") {
        moveTo(3f, 16f); lineTo(3f, 12f); quadTo(12f, 5f, 21f, 12f)
        lineTo(21f, 16f); lineTo(16f, 16f); lineTo(16f, 12f)
        quadTo(12f, 10f, 8f, 12f); lineTo(8f, 16f); close()
    }
    val MicOff = outline("Microphone muted") {
        moveTo(3f, 3f); lineTo(21f, 21f)
        moveTo(9f, 5f); curveTo(9f, 1f, 15f, 1f, 15f, 5f); lineTo(15f, 11f)
        moveTo(9f, 9f); lineTo(9f, 11f); quadTo(9f, 15f, 13f, 14f)
        moveTo(5f, 10f); lineTo(5f, 11f); quadTo(5f, 19f, 14f, 18f)
        moveTo(19f, 10f); quadTo(19f, 13f, 18f, 15f)
        moveTo(12f, 18f); lineTo(12f, 22f); moveTo(8f, 22f); lineTo(16f, 22f)
    }
    val Speaker = outline("Speech playback") {
        moveTo(3f, 9f); lineTo(7f, 9f); lineTo(12f, 5f); lineTo(12f, 19f)
        lineTo(7f, 15f); lineTo(3f, 15f); close()
        moveTo(16f, 8f); quadTo(20f, 12f, 16f, 16f)
        moveTo(19f, 5f); quadTo(25f, 12f, 19f, 19f)
    }
    val Accessibility = outline("Accessibility") {
        moveTo(10f, 4f); curveTo(10f, 1f, 14f, 1f, 14f, 4f)
        curveTo(14f, 7f, 10f, 7f, 10f, 4f); close()
        moveTo(3f, 8f); quadTo(12f, 12f, 21f, 8f)
        moveTo(12f, 10f); lineTo(12f, 15f); moveTo(8f, 22f); lineTo(12f, 15f); lineTo(16f, 22f)
    }
    /** Original handheld-radio mark. It implies local radio, never satellite service. */
    val Radio = ImageVector.Builder("iTantra radio", 24.dp, 24.dp, 24f, 24f).apply {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(7f, 8f); lineTo(7f, 2f)
            moveTo(9f, 8f); lineTo(16f, 8f); quadTo(18f, 8f, 18f, 10f)
            lineTo(18f, 20f); quadTo(18f, 22f, 16f, 22f); lineTo(7f, 22f)
            quadTo(5f, 22f, 5f, 20f); lineTo(5f, 10f); quadTo(5f, 8f, 7f, 8f); close()
            moveTo(9f, 12f); lineTo(14f, 12f); moveTo(9f, 16f); lineTo(14f, 16f)
            moveTo(9f, 19f); lineTo(12f, 19f)
            moveTo(11f, 3f); quadTo(14f, 3f, 14f, 6f)
            moveTo(14f, 1f); quadTo(19f, 1f, 19f, 6f)
        }
    }.build()
    val Microphone = ImageVector.Builder("Microphone", 24.dp, 24.dp, 24f, 24f).apply {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(9f, 5f); curveTo(9f, 1f, 15f, 1f, 15f, 5f)
            lineTo(15f, 11f); curveTo(15f, 15f, 9f, 15f, 9f, 11f); close()
            moveTo(5f, 10f); lineTo(5f, 11f); curveTo(5f, 20f, 19f, 20f, 19f, 11f); lineTo(19f, 10f)
            moveTo(12f, 18f); lineTo(12f, 22f); moveTo(8f, 22f); lineTo(16f, 22f)
        }
    }.build()
}
