package org.itantra.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Motion and depth helpers.
 *
 * Every animation here honours the user's reduced-motion setting by collapsing to
 * a still frame rather than simply running faster, and none of them drive state:
 * they read a boolean and draw. Nothing in this file can affect speech, capture
 * or transport behaviour.
 */

/**
 * Concentric pulses that read as "listening for something nearby".
 *
 * Used while discovery is running. With reduced motion it draws static rings so
 * the meaning survives without movement.
 */
@Composable internal fun RadarPulse(
    active: Boolean,
    reducedMotion: Boolean,
    size: Dp = 64.dp,
    icon: ImageVector = AppIcons.Radio
) {
    val accent = MaterialTheme.colorScheme.primary
    val progress = if (active && !reducedMotion) {
        val transition = rememberInfiniteTransition(label = "Nearby search")
        transition.animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(2200), RepeatMode.Restart),
            label = "Search ring"
        ).value
    } else 0f

    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val centre = Offset(this.size.width / 2f, this.size.height / 2f)
            val maxRadius = this.size.minDimension / 2f
            if (active && !reducedMotion) {
                // Three rings, evenly offset, each fading as it expands outward.
                for (index in 0 until 3) {
                    val phase = (progress + index / 3f) % 1f
                    drawCircle(
                        color = accent.copy(alpha = (1f - phase) * 0.45f),
                        radius = maxRadius * (0.35f + phase * 0.65f),
                        center = centre,
                        style = Stroke(width = 2.dp.toPx())
                    )
                }
            } else {
                for (fraction in listOf(0.55f, 0.8f, 1f)) {
                    drawCircle(
                        color = accent.copy(alpha = if (active) 0.28f else 0.14f),
                        radius = maxRadius * fraction, center = centre,
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                }
            }
        }
        Surface(shape = AppDesign.Pill, color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
            androidx.compose.material3.Icon(icon, null, Modifier.padding(9.dp).size(size / 4))
        }
    }
}

/** Searching banner: radar on the left, plain words on the right. */
@Composable internal fun SearchingCard(
    title: String,
    detail: String,
    active: Boolean,
    reducedMotion: Boolean
) {
    Surface(Modifier.fillMaxWidth(), shape = AppDesign.Card,
        color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            RadarPulse(active, reducedMotion, size = 58.dp)
            Column(Modifier.padding(start = 14.dp).weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(detail, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * Card with a coloured icon badge and a soft top-light gradient, which gives a
 * subtle sense of a raised surface without a drop shadow.
 */
@Composable internal fun FeatureCard(
    icon: ImageVector,
    title: String,
    detail: String,
    accent: Color = MaterialTheme.colorScheme.primary,
    trailing: (@Composable () -> Unit)? = null
) {
    Surface(Modifier.fillMaxWidth(), shape = AppDesign.Card,
        color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp, shadowElevation = 1.dp) {
        Box(Modifier.background(
            Brush.verticalGradient(
                listOf(accent.copy(alpha = .07f), Color.Transparent)
            )
        )) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = AppDesign.Control, color = accent.copy(alpha = .14f), contentColor = accent) {
                    androidx.compose.material3.Icon(icon, null, Modifier.padding(9.dp).size(21.dp))
                }
                Column(Modifier.padding(horizontal = 13.dp).weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, fontWeight = FontWeight.SemiBold)
                    Text(detail, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                trailing?.invoke()
            }
        }
    }
}


/**
 * Small rounded icon badge for list rows.
 *
 * [highlighted] tints it with the accent; otherwise it stays neutral, so a glance
 * down a list shows which languages are actually active. The colour change is
 * animated, which is enough motion for a list without being busy.
 */
@Composable internal fun IconBadge(icon: ImageVector, highlighted: Boolean, size: Dp = 38.dp) {
    val container by androidx.compose.animation.animateColorAsState(
        if (highlighted) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        label = "Badge container"
    )
    val content by androidx.compose.animation.animateColorAsState(
        if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "Badge content"
    )
    Surface(shape = AppDesign.Control, color = container, contentColor = content) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            androidx.compose.material3.Icon(icon, null, Modifier.size(size * 0.5f))
        }
    }
}

/**
 * Confirmation shown after an alert is queued.
 *
 * The two routes get different motion on purpose, because they promise different
 * things. A connected alert reaches a known set of phones, so it draws a settled
 * ring that fills once and stops. A public alert travels outward through unknown
 * phones, so it keeps emitting rings while it can still be relayed.
 */
@Composable internal fun SosSentAnimation(
    publicRoute: Boolean,
    reducedMotion: Boolean,
    accent: Color,
    container: Color,
    onContainer: Color,
    size: Dp = 108.dp
) {
    val sweep = if (!reducedMotion && !publicRoute) {
        val transition = rememberInfiniteTransition(label = "Delivered ring")
        transition.animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
            label = "Ring fill"
        ).value
    } else 0f
    val outward = if (!reducedMotion && publicRoute) {
        val transition = rememberInfiniteTransition(label = "Relay hops")
        transition.animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1800), RepeatMode.Restart),
            label = "Hop ring"
        ).value
    } else 0f

    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val centre = Offset(this.size.width / 2f, this.size.height / 2f)
                val radius = this.size.minDimension / 2f - 6.dp.toPx()
                if (publicRoute && !reducedMotion) {
                    // Three rings travelling outward: one per possible hop.
                    for (hop in 0 until 3) {
                        val phase = (outward + hop / 3f) % 1f
                        drawCircle(
                            color = accent.copy(alpha = (1f - phase) * 0.5f),
                            radius = radius * (0.4f + phase * 0.6f),
                            center = centre, style = Stroke(width = 3.dp.toPx())
                        )
                    }
                } else {
                    drawCircle(color = accent.copy(alpha = .18f), radius = radius,
                        center = centre, style = Stroke(width = 3.dp.toPx()))
                    // A settled arc, pulsing gently rather than travelling.
                    drawArc(
                        color = accent.copy(alpha = .55f + sweep * .45f),
                        startAngle = -90f, sweepAngle = 360f, useCenter = false,
                        topLeft = Offset(centre.x - radius, centre.y - radius),
                        size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
                        style = Stroke(width = 3.dp.toPx())
                    )
                }
            }
            Surface(shape = AppDesign.Pill, color = container, contentColor = onContainer) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    androidx.compose.material3.Icon(AppIcons.Radio, null, Modifier.size(22.dp))
                    Text(if (publicRoute) "Relaying" else "Sent",
                        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/**
 * Disclosure row with a rotating arrow.
 *
 * For text that is worth having but not worth the vertical space by default. A page
 * of stacked explanation boxes is unreadable under stress; one line you can open is
 * not. The arrow rotates rather than swapping between two icons, so the control does
 * not jump as it opens, and under reduced motion it snaps instead.
 */
@Composable internal fun ExpandableSection(
    title: String,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    container: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(modifier.fillMaxWidth(), shape = AppDesign.Card, color = container) {
        ExpandableRow(title, reducedMotion, accent = accent,
            padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp), content = content)
    }
}

/**
 * The same disclosure without a surface of its own, for use inside an existing card.
 *
 * Keeps an explanation attached to the control it explains instead of putting a
 * second box underneath it.
 */
@Composable internal fun ExpandableRow(
    title: String,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    padding: PaddingValues = PaddingValues(vertical = 10.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(
        if (expanded) 180f else 0f,
        tween(if (reducedMotion) 0 else 180), label = "Disclosure arrow"
    )
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(padding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold)
            androidx.compose.material3.Icon(
                Icons.Default.KeyboardArrowDown,
                if (expanded) "Collapse" else "Expand",
                Modifier.size(24.dp).rotate(rotation), tint = accent
            )
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
        }
    }
}
