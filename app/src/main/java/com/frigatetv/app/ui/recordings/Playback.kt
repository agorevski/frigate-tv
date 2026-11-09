package com.frigatetv.app.ui.recordings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.frigatetv.app.data.displayName
import com.frigatetv.app.player.StreamPlayer
import com.frigatetv.app.ui.Accent
import com.frigatetv.app.ui.Panel
import com.frigatetv.app.ui.Radius
import com.frigatetv.app.ui.Space
import com.frigatetv.app.ui.StatusPill
import com.frigatetv.app.ui.TextPrimary
import com.frigatetv.app.ui.TextSecondary
import com.frigatetv.app.ui.isTelevision
import com.frigatetv.app.ui.scrim

/** Recorded video from [start] for one camera, with a REC label and a hint for leaving. */
@Composable
internal fun PlaybackView(url: String, cameraId: String, start: Double, dayLabel: String, audible: Boolean) {
    val isTv = isTelevision()
    Column(Modifier.fillMaxSize().padding(horizontal = Space.lg, vertical = Space.md)) {
        // Video sits in a Panel frame; the inner corner radius is the outer radius minus the frame padding.
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.lg))
                .background(Panel)
                .padding(Space.xs),
        ) {
            StreamPlayer(
                url = url,
                rtsp = false,
                audible = audible,
                networkEpoch = 0,
                slot = PLAYBACK_SLOT,
                onState = {},
                controls = true,
                retry = false,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(Color.Black),
            )
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(Space.sm)
                    .clip(RoundedCornerShape(Radius.sm))
                    .scrim(0.6f)
                    .padding(horizontal = Space.sm, vertical = Space.xxs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                StatusPill("REC", Accent)
                Text(displayName(cameraId), color = TextPrimary, style = MaterialTheme.typography.titleMedium)
                Text(dayLabel, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                buildAnnotatedString {
                    append("Playing ${displayName(cameraId)} from ")
                    withStyle(NUMERIC_SPAN) { append(formatClock(start)) }
                    append(if (isTv) ". Press BACK to return to the timeline." else ". Back returns to the timeline.")
                },
                color = TextPrimary,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            if (isTv) {
                Spacer(Modifier.width(Space.md))
                Text(
                    "OK  pause  ·  ◀ ▶  seek",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/** Decoder slot of the recording playback player. */
private const val PLAYBACK_SLOT = "playback"
