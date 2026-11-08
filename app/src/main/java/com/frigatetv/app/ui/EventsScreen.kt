package com.frigatetv.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.frigatetv.app.AppState
import com.frigatetv.app.data.FrigateApi
import com.frigatetv.app.data.FrigateEvent
import com.frigatetv.app.data.ServerProfile
import com.frigatetv.app.data.displayName
import com.frigatetv.app.data.orderedCameras
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.compose.runtime.saveable.rememberSaveable

private enum class LabelFilter(val title: String) {
    ALL("All"),
    PERSON("People"),
    VEHICLE("Vehicles"),
    OTHER("Other"),
}

private enum class TimeRange(val title: String, val seconds: Long) {
    DAY("Last 24 h", 86_400L),
    WEEK("7 days", 604_800L),
    MONTH("30 days", 2_592_000L),
}

private val VEHICLE_LABELS = setOf("car", "truck", "motorcycle", "bus", "bicycle")
private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, HH:mm:ss")
private val api = FrigateApi()

private fun LabelFilter.matches(label: String): Boolean = when (this) {
    LabelFilter.ALL -> true
    LabelFilter.PERSON -> label == "person"
    LabelFilter.VEHICLE -> label in VEHICLE_LABELS
    LabelFilter.OTHER -> label != "person" && label !in VEHICLE_LABELS
}

fun formatEventTime(epochSeconds: Double): String =
    TIME_FORMAT.format(Instant.ofEpochMilli((epochSeconds * 1000).toLong()).atZone(ZoneId.systemDefault()))

@Composable
fun EventsScreen(state: AppState) {
    val profile = state.active ?: return
    val cameras = orderedCameras(state.cameras, state.prefs.cameraOrder).filter { it.enabled }

    var label by rememberSaveable { mutableStateOf(LabelFilter.ALL) }
    var range by rememberSaveable { mutableStateOf(TimeRange.DAY) }
    var cameraId by rememberSaveable { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<FrigateEvent?>(null) }

    val result by produceState<Result<List<FrigateEvent>>?>(null, profile, range, cameraId) {
        val now = System.currentTimeMillis() / 1000.0
        value = runCatching {
            api.events(profile, limit = 200, after = now - range.seconds, camera = cameraId)
        }
    }

    val event = selected
    if (event != null) {
        BackHandler { selected = null }
        EventDetail(profile, event, muted = state.prefs.muted)
        return
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp)) {
        FilterRow("Type") {
            LabelFilter.entries.forEach { f ->
                FocusButton(f.title, { label = f }, selected = label == f)
            }
        }
        FilterRow("Camera") {
            FocusButton("All", { cameraId = null }, selected = cameraId == null)
            cameras.forEach { cam ->
                FocusButton(displayName(cam.id), { cameraId = cam.id }, selected = cameraId == cam.id)
            }
        }
        FilterRow("Range") {
            TimeRange.entries.forEach { r ->
                FocusButton(r.title, { range = r }, selected = range == r)
            }
        }
        Spacer12()

        val loaded = result
        val visible = loaded?.getOrNull()?.filter { label.matches(it.label) }.orEmpty()
        when {
            loaded == null -> CenteredMessage("Loading events…")
            loaded.isFailure -> CenteredMessage("Could not load events: ${loaded.exceptionOrNull()?.message}")
            visible.isEmpty() -> CenteredMessage("No events for this filter.")
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 280.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(visible, key = { it.id }) { e ->
                    EventCard(profile, e, onOpen = { selected = e })
                }
            }
        }
    }
}

@Composable
private fun Spacer12() {
    Box(Modifier.height(12.dp))
}

@Composable
private fun FilterRow(title: String, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, color = TextMuted, fontSize = 16.sp, modifier = Modifier.padding(end = 8.dp))
        content()
    }
}

@Composable
private fun EventCard(profile: ServerProfile, event: FrigateEvent, onOpen: () -> Unit) {
    FocusSurface(
        onClick = onOpen,
        background = Panel,
        modifier = Modifier.fillMaxWidth(),
    ) { _ ->
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
                if (event.hasSnapshot) {
                    RemoteImage(api.eventThumbnailUrl(profile, event.id), refreshMs = null, modifier = Modifier.fillMaxSize())
                } else {
                    Text("No preview", color = TextMuted, fontSize = 16.sp, modifier = Modifier.align(Alignment.Center))
                }
            }
            Column(Modifier.padding(12.dp)) {
                Text(
                    text = event.label.replaceFirstChar { it.titlecase() } + (event.subLabel?.let { " · $it" } ?: ""),
                    color = TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(displayName(event.camera), color = TextMuted, fontSize = 16.sp)
                Text(formatEventTime(event.startTime), color = TextMuted, fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun EventDetail(profile: ServerProfile, event: FrigateEvent, muted: Boolean) {
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black),
        ) {
            if (event.hasClip) {
                StreamPlayer(
                    url = api.eventClipUrl(profile, event.id),
                    rtsp = false,
                    audible = !muted,
                    networkEpoch = 0,
                    onState = {},
                    controls = true,
                    retry = false,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (event.hasSnapshot) {
                RemoteImage(api.eventSnapshotUrl(profile, event.id), refreshMs = null, modifier = Modifier.fillMaxSize())
                Text(
                    "No recording available for this event.",
                    color = TextPrimary,
                    fontSize = 18.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).background(Color(0xB3000000)).padding(12.dp),
                )
            } else {
                Text("No clip or snapshot available.", color = TextMuted, fontSize = 20.sp, modifier = Modifier.align(Alignment.Center))
            }
        }
        Spacer12()
        Text(
            text = "${event.label.replaceFirstChar { it.titlecase() }} · ${displayName(event.camera)} · ${formatEventTime(event.startTime)}",
            color = TextPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Press BACK to return to the list.",
            color = TextMuted,
            fontSize = 16.sp,
        )
    }
}
