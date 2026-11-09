package com.frigatetv.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.frigatetv.app.player.StreamState

private val PillShape = RoundedCornerShape(percent = 50)
private val BadgeShape = RoundedCornerShape(Space.xxs)
private val KeyCapShape = RoundedCornerShape(Space.xxs)
private val BadgeBg = Ink.copy(alpha = 0.6f)

fun StreamState.color(): Color = when (this) {
    StreamState.Live -> LiveGreen
    StreamState.Connecting, StreamState.Buffering -> Warn
    StreamState.Offline -> Danger
}

/** Compact status label on a semantic color (LIVE, OFFLINE, MOTION). Fully rounded ends. */
@Composable
fun StatusPill(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(color, PillShape)
            .padding(horizontal = Space.xs, vertical = Space.xxs),
    ) {
        Text(text, color = Ink, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

/** Compact status: a colored dot and a label on a translucent chip. Static, so it costs nothing per frame. */
@Composable
fun StreamBadge(label: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(BadgeBg, BadgeShape)
            .padding(horizontal = Space.xs, vertical = Space.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(Space.xs).background(color, CircleShape))
        Spacer(Modifier.width(Space.xxs + 2.dp))
        Text(label, color = TextPrimary, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

/** A remote key drawn as a key cap, followed by what it does. */
@Composable
fun KeyHint(keyLabel: String, action: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = keyLabel,
            color = TextPrimary,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier
                .background(PanelHi, KeyCapShape)
                .padding(horizontal = Space.xs, vertical = Space.xxs / 2),
        )
        Spacer(Modifier.width(Space.xs))
        Text(action, color = TextSecondary, style = MaterialTheme.typography.labelMedium)
    }
}

/** Section title with an optional trailing action. Titles are specific nouns, not "Settings". */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, style = MaterialTheme.typography.titleLarge)
            if (subtitle != null) {
                Spacer(Modifier.height(Space.xxs))
                Text(subtitle, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(Space.md))
            trailing()
        }
    }
}

/** Flat panel for grouping related controls. Padding is [Space.md]; children use [Radius.md]. */
@Composable
fun SurfaceCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(Radius.lg))
            .background(Panel)
            .padding(Space.md),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
        content = content,
    )
}

/** Subtle dark overlay used to dim content behind text. */
fun Modifier.scrim(alpha: Float = 0.55f): Modifier = drawBehind {
    drawRect(Color.Black.copy(alpha = alpha))
}

/** True on Android TV / Google TV, where remote-key hints apply; false on touch devices. */
@Composable
fun isTelevision(): Boolean {
    val uiMode = androidx.compose.ui.platform.LocalConfiguration.current.uiMode
    return uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK ==
        android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
}
