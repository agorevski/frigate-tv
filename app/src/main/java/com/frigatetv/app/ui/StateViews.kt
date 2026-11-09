package com.frigatetv.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Three pulsing dots plus a label. Shown while data loads so the screen never looks frozen. */
@Composable
fun LoadingState(label: String, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "loading")
    Column(
        modifier.fillMaxSize().padding(Space.lg),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            repeat(3) { index ->
                val alpha by transition.animateFloat(
                    initialValue = 0.25f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(Motion.PulseMs, delayMillis = index * 150),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "dot$index",
                )
                Box(Modifier.size(10.dp).graphicsLayer { this.alpha = alpha }.background(Accent, CircleShape))
            }
        }
        Spacer(Modifier.height(Space.sm))
        Text(label, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Status dot that pulses while [active], e.g. while a connection is being established.
 * When inactive no animation runs, so idle tiles do not request a frame every vsync.
 */
@Composable
fun PulsingDot(color: Color, active: Boolean, modifier: Modifier = Modifier) {
    if (active) {
        val alpha by rememberInfiniteTransition(label = "dot").animateFloat(
            initialValue = 1f,
            targetValue = 0.3f,
            animationSpec = infiniteRepeatable(tween(Motion.PulseMs), RepeatMode.Reverse),
            label = "dotAlpha",
        )
        Box(modifier.size(10.dp).graphicsLayer { this.alpha = alpha }.background(color, CircleShape))
    } else {
        Box(modifier.size(10.dp).background(color, CircleShape))
    }
}

private val NoticeStripeWidth = 4.dp

/** Inline notice that slides in and out, marked by an accent stripe on its leading edge. Pass null to hide it. */
@Composable
fun NoticeBar(text: String?, modifier: Modifier = Modifier) {
    // Keep the last message during the exit animation so the bar does not blank out while closing.
    var shown by remember { mutableStateOf("") }
    if (text != null) shown = text
    AnimatedVisibility(
        visible = text != null,
        modifier = modifier,
        enter = fadeIn(tween(Motion.EnterMs)) + expandVertically(tween(Motion.EnterMs)),
        exit = fadeOut(tween(Motion.FocusMs)) + shrinkVertically(tween(Motion.FocusMs)),
    ) {
        Text(
            shown,
            color = TextPrimary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .fillMaxWidth()
                .background(PanelHi)
                .drawBehind {
                    drawRect(Accent, size = Size(NoticeStripeWidth.toPx(), size.height))
                }
                .padding(horizontal = Space.lg, vertical = Space.xs),
        )
    }
}

/**
 * Centered message for empty or failed lists. Gives the reason and, when possible, the next step;
 * pass [action] (e.g. a [FocusButton] "Retry") to make that step one click away.
 */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.fillMaxSize().padding(Space.lg),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(Space.xxl).background(PanelHi, CircleShape), contentAlignment = Alignment.Center) {
            Box(Modifier.size(Space.sm).background(TextSecondary, CircleShape))
        }
        Spacer(Modifier.height(Space.md))
        Text(title, color = TextPrimary, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (message != null) {
            Spacer(Modifier.height(Space.xs))
            Text(
                message,
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 560.dp),
            )
        }
        if (action != null) {
            Spacer(Modifier.height(Space.lg))
            action()
        }
    }
}

/** Plain centered text for states with no action, such as "No enabled cameras". */
@Composable
fun CenteredMessage(text: String) {
    Column(
        Modifier.fillMaxSize().padding(Space.lg),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, color = TextSecondary, style = MaterialTheme.typography.titleLarge)
    }
}
