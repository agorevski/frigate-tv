package com.frigatetv.app.ui.events

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.frigatetv.app.data.FrigateRepository
import com.frigatetv.app.ui.FocusSurface
import com.frigatetv.app.ui.Ink
import com.frigatetv.app.ui.LiveGreen
import com.frigatetv.app.ui.NumericStyle
import com.frigatetv.app.ui.Panel
import com.frigatetv.app.ui.Radius
import com.frigatetv.app.ui.RemoteImage
import com.frigatetv.app.ui.Space
import com.frigatetv.app.ui.TextPrimary
import com.frigatetv.app.ui.TextSecondary

private val CardShape = RoundedCornerShape(Radius.lg)
internal val BadgeShape = RoundedCornerShape(Radius.sm)
internal val BadgeBg = Ink.copy(alpha = 0.75f)

/** One event: thumbnail, title, camera and time. Focus and touch both open the detail view. */
@Composable
internal fun EventCard(
    repo: FrigateRepository,
    ui: EventUi,
    onOpen: () -> Unit,
    focusRequester: FocusRequester?,
) {
    val thumbUrl = remember(repo, ui.id) {
        if (ui.event.hasSnapshot) repo.eventThumbnailUrl(ui.id) else null
    }
    FocusSurface(
        onClick = onOpen,
        background = Panel,
        shape = CardShape,
        focusRequester = focusRequester,
        modifier = Modifier.fillMaxWidth(),
    ) { _ ->
        // Text order matters to UI tests: title, camera, then time.
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Ink)) {
                if (thumbUrl != null) {
                    // Frigate's thumbnail.jpg is a small crop, cheap to fetch and decode for a grid.
                    RemoteImage(thumbUrl, refreshMs = null, modifier = Modifier.fillMaxSize())
                } else {
                    Text(
                        "No preview",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
            Column(Modifier.padding(horizontal = Space.sm, vertical = Space.xs)) {
                Text(
                    text = ui.title,
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    ui.camera,
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        ui.time,
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyMedium.merge(NumericStyle),
                        maxLines = 1,
                    )
                    if (ui.relative != null) {
                        Text(
                            " · ${ui.relative}",
                            color = TextSecondary.copy(alpha = 0.75f),
                            style = MaterialTheme.typography.bodyMedium.merge(NumericStyle),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        // Overlays on the thumbnail, composed last so they draw on top.
        Box(Modifier.matchParentSize().padding(Space.xs)) {
            if (ui.score != null) Badge(ui.score, Modifier.align(Alignment.TopStart))
            Badge(
                ui.duration,
                Modifier.align(Alignment.TopEnd),
                dot = if (ui.ongoing) LiveGreen else null,
            )
        }
    }
}

/** Small dark chip drawn over a thumbnail. */
@Composable
private fun Badge(text: String, modifier: Modifier = Modifier, dot: Color? = null) {
    Row(
        modifier.clip(BadgeShape).background(BadgeBg).padding(horizontal = Space.xs, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) {
            Box(Modifier.size(8.dp).background(dot, CircleShape))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = TextPrimary, style = MaterialTheme.typography.labelMedium.merge(NumericStyle))
    }
}
